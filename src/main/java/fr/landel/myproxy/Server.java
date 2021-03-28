package fr.landel.myproxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.landel.myproxy.utils.Logger;
import fr.landel.myproxy.utils.StringUtils;

/**
 * Created from http://stackoverflow.com/q/16351413/1266906.
 * 
 * TODO correct HTTP response end
 * TODO correct HTTPS latency
 */
public class Server extends Thread {

	private static final long START = System.currentTimeMillis();

	private static final Logger LOGGER = new Logger(Server.class);

	private static final String PROXY_HOST = "proxy.host.net";
	private static final int PROXY_PORT = 3128;
	private static final String PROXY_USER = "user";
	private static final char[] PROXY_PASSWORD = "password".toCharArray();

	private static final String AGENT_NAME = "MyProxy";
	private static final String AGENT_VERSION = "1.0";

	private static final int SERVER_PORT = 3129;

	private static final int MAX_THREADS = 200;
	private static final ExecutorService EXECUTORS_HANDLER = Executors.newFixedThreadPool(MAX_THREADS);
	private static final ExecutorService EXECUTORS_FORWARD = Executors.newFixedThreadPool(MAX_THREADS);

	public static void main(String[] args) {
		(new Server()).run();
	}

	public Server() {
		super("Server Thread");
	}

	@Override
	public void run() {
		LOGGER.info("Start server");

		try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {

			LOGGER.info("Proxy created in {} ms on port: {}", System.currentTimeMillis() - START, SERVER_PORT);

			Socket socket;
			try {
				while ((socket = serverSocket.accept()) != null) {
					EXECUTORS_HANDLER.submit(new Handler(socket));
				}
			} catch (IOException e) {
				LOGGER.error(e, "Cannot create socket handler");
			}
		} catch (IOException e) {
			LOGGER.error(e, "Cannot create server socket on port {}", SERVER_PORT);
			return;
		}
	}

	public static class Handler extends Thread {

		public static final String GROUP_SCHEME = "scheme";
		public static final String GROUP_METHOD = "method";
		public static final String GROUP_HOST = "host";
		public static final String GROUP_PORT = "port";
		public static final String GROUP_VERSION = "version";
		public static final String GROUP_URL = "url";
		public static final String GROUP_QUERY = "query";

		public static final Pattern CONNECT_PATTERN = Pattern.compile("CONNECT (?<" + GROUP_HOST + ">[^:]+):(?<"
				+ GROUP_PORT + ">[0-9]+) HTTP\\/(?<" + GROUP_VERSION + ">1\\.[01])", Pattern.CASE_INSENSITIVE);

		public static final Pattern METHOD_PATTERN = Pattern.compile(
				"(?<" + GROUP_METHOD + ">GET|POST|PUT|HEAD|TRACE|OPTIONS) (?<" + GROUP_URL + ">(?<" + GROUP_SCHEME
						+ ">(?:ht|f)tps?):\\/\\/(?<" + GROUP_HOST + ">[^:]+)(?::(?<" + GROUP_PORT + ">[0-9]+))?(?<"
						+ GROUP_QUERY + ">[^\s]*)) HTTP\\/(?<" + GROUP_VERSION + ">1\\.[01])",
				Pattern.CASE_INSENSITIVE);

		private static final Authenticator AUTHENTICATOR = new Authenticator() {
			public PasswordAuthentication getPasswordAuthentication() {
				return (new PasswordAuthentication(PROXY_USER, PROXY_PASSWORD));
			}
		};

		private final Socket clientSocket;
		private boolean previousWasR = false;

		public Handler(Socket clientSocket) throws SocketException {
			this.clientSocket = clientSocket;
			this.clientSocket.setSoTimeout(30_000);
		}

		@Override
		public void run() {
			final long start = System.currentTimeMillis();
			final UUID uuid = UUID.randomUUID();

			try {
				final String request = readLine(clientSocket);

				LOGGER.info("Request: {}, UUID: {}", request, uuid);

				final Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));

				Matcher matcher;
				if ((matcher = CONNECT_PATTERN.matcher(request)).matches()) {

					final String host = matcher.group(GROUP_HOST);
					final int port = Integer.parseInt(matcher.group(GROUP_PORT));
					final String version = matcher.group(GROUP_VERSION);

					String header;
					do {
						header = readLine(clientSocket);
					} while (StringUtils.isNotEmpty(header));

					try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream(),
							StandardCharsets.ISO_8859_1)) {

						final Socket proxySocket;
						try {
							Authenticator.setDefault(AUTHENTICATOR);

							proxySocket = new Socket(proxy);

							proxySocket.connect(new InetSocketAddress(host, port));

							LOGGER.info("Proxy connected: {}, UUID: {}", proxySocket, uuid);

						} catch (IOException | NumberFormatException e) {
							LOGGER.error(e, "Cannot connect to {} on port {}, UUID: {}", host, port, uuid);

							outputStreamWriter.write("HTTP/");
							outputStreamWriter.write(version);
							outputStreamWriter.write(" 502 Bad Gateway\r\nProxy-agent: ");
							outputStreamWriter.write(AGENT_NAME);
							outputStreamWriter.write("/");
							outputStreamWriter.write(AGENT_VERSION);
							outputStreamWriter.write("\r\n\r\n");
							outputStreamWriter.flush();

							LOGGER.info("Send HTTP code: 502, Bad Gateway, UUID: {}", uuid);

							return;
						}

						try {
							outputStreamWriter.write("HTTP/");
							outputStreamWriter.write(version);
							outputStreamWriter.write(" 200 Connection established\r\nProxy-agent: ");
							outputStreamWriter.write(AGENT_NAME);
							outputStreamWriter.write("/");
							outputStreamWriter.write(AGENT_VERSION);
							outputStreamWriter.write("\r\n\r\n");
							outputStreamWriter.flush();

							LOGGER.info("Send HTTP code: 200, Connection established, UUID: {}", uuid);

							final Future<?> remoteToClient = EXECUTORS_FORWARD
									.submit(() -> forwardData(proxySocket, clientSocket, uuid));

							try {
								if (previousWasR) {
									int read = clientSocket.getInputStream().read();
									if (read != -1) {
										if (read != '\n') {
											proxySocket.getOutputStream().write(read);
										}
										forwardData(clientSocket, proxySocket, uuid);
									} else {
										if (!proxySocket.isOutputShutdown()) {
											proxySocket.shutdownOutput();
										}
										if (!clientSocket.isInputShutdown()) {
											clientSocket.shutdownInput();
										}
									}
								} else {
									forwardData(clientSocket, proxySocket, uuid);
								}
							} finally {
								try {
									remoteToClient.get();
								} catch (InterruptedException | ExecutionException e) {
									LOGGER.error(e, "Cannot join threads, UUID: {}", uuid);
								}
							}
						} finally {
							proxySocket.close();
						}
					}
				} else if ((matcher = METHOD_PATTERN.matcher(request)).matches()) {

					try (final BufferedOutputStream proxyToClientBw = new BufferedOutputStream(
							clientSocket.getOutputStream())) {

						// final String method = matcher.group(GROUP_METHOD);
						final String url = matcher.group(GROUP_URL);
						// final String scheme = matcher.group(GROUP_SCHEME);
						// final String host = matcher.group(GROUP_HOST);
						// final int port;
						// final String matcherPort = matcher.group(GROUP_PORT);
						// if (StringUtils.isNotEmpty(matcherPort)) {
						// port = Integer.parseInt(matcherPort);
						// } else {
						// port = 80;
						// }
						// final String query = matcher.group(GROUP_QUERY);
						final String version = matcher.group(GROUP_VERSION);

						URL remoteURL = new URL(url);
						URLConnection proxyToServerCon = remoteURL.openConnection(proxy);

						if (proxyToServerCon instanceof HttpURLConnection) {
							HttpURLConnection conn = (HttpURLConnection) proxyToServerCon;
							conn.setAuthenticator(AUTHENTICATOR);

						} else {
							StringBuilder header = new StringBuilder();
							header.append("HTTP/");
							header.append(version);
							header.append(" 400 Bad Request\r\nProxy-agent: ");
							header.append(AGENT_NAME);
							header.append("/");
							header.append(AGENT_VERSION);
							header.append("\r\n\r\n");

							proxyToClientBw.write(header.toString().getBytes());
							proxyToClientBw.flush();

							LOGGER.info("Send HTTP code: 400 Bad Request, UUID: {}", uuid);

							return;
						}

						proxyToServerCon.setUseCaches(false);
						proxyToServerCon.setDoOutput(true);

						// Create Buffered Reader from remote Server
						try (BufferedInputStream proxyToServerBuf = new BufferedInputStream(
								proxyToServerCon.getInputStream())) {

							// Send success code to client
							StringBuilder header = new StringBuilder();
							header.append("HTTP/");
							header.append(version);
							header.append(" 200 OK\r\nProxy-agent: ");
							header.append(AGENT_NAME);
							header.append("/");
							header.append(AGENT_VERSION);
							header.append("\r\n\r\n");

							proxyToClientBw.write(header.toString().getBytes());

							LOGGER.info("Send HTTP code: 200 OK, UUID: {}", uuid);

							byte[] buffer = new byte[20480];
							int read;
							long length = 0;
							while ((read = proxyToServerBuf.read(buffer)) > -1) {
								length += read;
								proxyToClientBw.write(buffer, 0, read);
								if (proxyToServerBuf.available() < 1) {
									proxyToClientBw.flush();
								}
							}

							LOGGER.info("Read data, length: {}, UUID: {}", length, uuid);
						}
					}
				} else {
					try (final BufferedWriter proxyToClientBw = new BufferedWriter(
							new OutputStreamWriter(clientSocket.getOutputStream()))) {

						proxyToClientBw.write("HTTP/1.1 400 Bad Request\r\nProxy-agent: ");
						proxyToClientBw.write(AGENT_NAME);
						proxyToClientBw.write("/");
						proxyToClientBw.write(AGENT_VERSION);
						proxyToClientBw.write("\r\n\r\n");
						proxyToClientBw.flush();

						LOGGER.info("Send HTTP code: 400 Bad Request, UUID: {}", uuid);
					}
				}
			} catch (IOException e) {
				LOGGER.info(e, "Cannot handle request, UUID: {}", uuid);
			} finally {
				try {
					clientSocket.close();
				} catch (IOException e) {
					LOGGER.info(e, "Cannot close client, UUID: {}", uuid);
				}

				LOGGER.info("Request handled in {} ms, UUID: {}", System.currentTimeMillis() - start, uuid);
			}
		}

		private static void forwardData(final Socket inputSocket, final Socket outputSocket, final UUID uuid) {
			try {
				final InputStream inputStream = inputSocket.getInputStream();

				try {
					final OutputStream outputStream = outputSocket.getOutputStream();

					try {
						byte[] buffer = new byte[20480];
						int read;
						long length = 0;
						while ((read = inputStream.read(buffer)) > -1) {
							length += read;
							outputStream.write(buffer, 0, read);
							if (inputStream.available() < 1) {
								outputStream.flush();
							}
						}

						LOGGER.info("Read data length: {}, UUID: {}", length, uuid);

					} finally {
						if (!outputSocket.isOutputShutdown()) {
							outputSocket.shutdownOutput();
						}
					}
				} finally {
					if (!inputSocket.isInputShutdown()) {
						inputSocket.shutdownInput();
					}
				}
			} catch (IOException e) {
				LOGGER.info(e, "Cannot forward data, UUID: {}", uuid);
			}
		}

		private String readLine(final Socket socket) throws IOException {
			try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
				int next;
				readerLoop: while ((next = socket.getInputStream().read()) != -1) {
					if (previousWasR && next == '\n') {
						previousWasR = false;
						continue;
					}
					previousWasR = false;
					switch (next) {
					case '\r':
						previousWasR = true;
						break readerLoop;
					case '\n':
						break readerLoop;
					default:
						byteArrayOutputStream.write(next);
						break;
					}
				}
				return byteArrayOutputStream.toString(StandardCharsets.ISO_8859_1);
			}
		}
	}
}