package fr.landel.myproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.landel.myproxy.utils.Logger;

/**
 * Created from http://stackoverflow.com/q/16351413/1266906.
 * 
 * TODO correct HTTP response end TODO correct HTTPS latency
 */
public class Server extends Thread {

	private static final long START = System.currentTimeMillis();

	private static final Logger LOGGER = new Logger(Server.class);

	public static final boolean PROXY_ENABLED = false;
	public static final String PROXY_HOST = "proxy.gicm.net";
	public static final int PROXY_PORT = 3128;
	public static final String PROXY_USER = "e8772";
	public static final char[] PROXY_PASSWORD = "gla22052".toCharArray();

	// TODO create an authenticator pool by remote proxy host
	public static final Authenticator AUTHENTICATOR = new Authenticator() {
		public PasswordAuthentication getPasswordAuthentication() {
			return new PasswordAuthentication(PROXY_USER, PROXY_PASSWORD);
		}
	};

	public static final String AGENT_NAME = "MyProxy";
	public static final String AGENT_VERSION = "1.0";

	private static final int SERVER_PORT = 3129;

	public static final int SERVER_TIMEOUT = 30_000;

	private static final int MAX_THREADS = 50;
	public static final ExecutorService EXECUTORS_HANDLER = Executors.newFixedThreadPool(MAX_THREADS);
	public static final ExecutorService EXECUTORS_FORWARD = Executors.newFixedThreadPool(MAX_THREADS);

	public static final ConcurrentMap<String, SocketAddress> CONNECTED_SOCKET_ADDRESSES = new ConcurrentHashMap<>();

	public static void main(String[] args) {
		(new Server()).run();
	}

	public Server() {
		super("Server Thread");
	}

	@Override
	public void run() {
		LOGGER.info("Start server");

		// System.setProperty("http.maxRedirects", "3");
		System.setProperty("http.agent", AGENT_NAME + '/' + AGENT_VERSION);

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
						+ GROUP_QUERY + ">[^\\s]*))\\sHTTP\\/(?<" + GROUP_VERSION + ">1\\.[01])",
				Pattern.CASE_INSENSITIVE);

		private final Socket clientSocket;
		private boolean previousWasR = false;

		public Handler(Socket clientSocket) throws SocketException {
			this.clientSocket = clientSocket;
			this.clientSocket.setSoTimeout(SERVER_TIMEOUT);
		}

		@Override
		public void run() {
			final long start = System.currentTimeMillis();
			final UUID uuid = UUID.randomUUID();

			try {
				final String request = readLine(clientSocket);

				LOGGER.info("Request: {}, UUID: {}", request, uuid);

				String address = PROXY_HOST.toLowerCase() + ':' + PROXY_PORT;
				SocketAddress proxyAddess = CONNECTED_SOCKET_ADDRESSES.get(address);
				if (proxyAddess == null) {
					CONNECTED_SOCKET_ADDRESSES.put(address,
							proxyAddess = new InetSocketAddress(PROXY_HOST, PROXY_PORT));
				}

				final Proxy proxy = new Proxy(Proxy.Type.HTTP, proxyAddess);

				Matcher matcher;
				if ((matcher = CONNECT_PATTERN.matcher(request)).matches()) {

					final String host = matcher.group(GROUP_HOST);
					final int port = Integer.parseInt(matcher.group(GROUP_PORT));
					final String version = matcher.group(GROUP_VERSION);

					new HttpsRequestHandler(uuid, clientSocket, proxy, AUTHENTICATOR, previousWasR).handle(host, port,
							version);

				} else if ((matcher = METHOD_PATTERN.matcher(request)).matches()) {

					final String url = matcher.group(GROUP_URL);
					final String version = matcher.group(GROUP_VERSION);

					new HttpRequestHandler(uuid, clientSocket, proxy, AUTHENTICATOR).handle(url, version);

				} else {

					new ErrorRequestHandler(clientSocket).writeHeader("1.0", HttpStatus.BAD_REQUEST);

					LOGGER.error("Send HTTP code: 400 Bad Request, UUID: {}", uuid);
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
				return byteArrayOutputStream.toString(StandardCharsets.UTF_8);
			}
		}
	}
}