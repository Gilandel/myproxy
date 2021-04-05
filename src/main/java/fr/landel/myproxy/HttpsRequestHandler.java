package fr.landel.myproxy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import fr.landel.myproxy.utils.Logger;
import fr.landel.myproxy.utils.StringUtils;

public class HttpsRequestHandler extends AbstractRequestHandler {

	private static final Logger LOGGER = new Logger(HttpsRequestHandler.class);

	private final long start;
	private final UUID uuid;
	private final Socket clientSocket;
	private final Proxy proxy;
	private final Authenticator authenticator;

	public HttpsRequestHandler(final UUID uuid, final Socket clientSocket, final Proxy proxy,
			final Authenticator authenticator, final boolean previousWasR) throws SocketException {

		this.start = System.currentTimeMillis();
		this.uuid = uuid;
		this.clientSocket = clientSocket;
		this.clientSocket.setSoTimeout(Server.SERVER_TIMEOUT);
		this.proxy = proxy;
		this.authenticator = authenticator;

		this.previousWasR = previousWasR;
	}

	public void handle(final String host, final int port, final String version) throws IOException {

		LOGGER.info("Init HTTPS, host: {}, port: {}, uuid: {}", host, port, uuid);

		String header;
		do {
			header = readLine(clientSocket);
		} while (StringUtils.isNotEmpty(header));

		final Socket proxySocket;
		try {
			if (Server.PROXY_ENABLED) {
				Authenticator.setDefault(authenticator);

				proxySocket = new Socket(proxy);
			} else {
				Authenticator.setDefault(null);

				proxySocket = new Socket();
			}

			proxySocket.setPerformancePreferences(2, 1, 0);

			LOGGER.info(
					"KeepAlive: {}, OOBInline: {}, ReuseAddress: {}, SoLinger: {}, SoTimeout: {}, TCPNoDelay: {}, SendBufferSize: {}, ReceiveBufferSize: {}, LocalAddress: {}, LocalPort: {}, LocalSocketAddress: {}, RemoteSocketAddress: {}",
					clientSocket.getKeepAlive(), clientSocket.getOOBInline(), clientSocket.getReuseAddress(),
					clientSocket.getSoLinger(), clientSocket.getSoTimeout(), clientSocket.getTcpNoDelay(),
					clientSocket.getSendBufferSize(), clientSocket.getReceiveBufferSize(),
					clientSocket.getLocalAddress(), clientSocket.getLocalPort(), clientSocket.getLocalSocketAddress(),
					clientSocket.getRemoteSocketAddress());

			proxySocket.setKeepAlive(clientSocket.getKeepAlive());
			proxySocket.setOOBInline(clientSocket.getOOBInline());
			proxySocket.setReuseAddress(clientSocket.getReuseAddress());
			proxySocket.setSoLinger(clientSocket.getSoLinger() != -1, clientSocket.getSoLinger());
			proxySocket.setSoTimeout(clientSocket.getSoTimeout());
			proxySocket.setTcpNoDelay(clientSocket.getTcpNoDelay());
			proxySocket.setSendBufferSize(clientSocket.getSendBufferSize());
			proxySocket.setReceiveBufferSize(clientSocket.getReceiveBufferSize());

			final String address = host.toLowerCase() + ':' + port;
			SocketAddress remoteAddess = Server.CONNECTED_SOCKET_ADDRESSES.get(address);
			if (remoteAddess == null) {
				Server.CONNECTED_SOCKET_ADDRESSES.put(address, remoteAddess = new InetSocketAddress(host, port));
			}

			proxySocket.connect(remoteAddess, Server.SERVER_TIMEOUT);

			LOGGER.info("Proxy connected: {}, UUID: {}", proxySocket, uuid);

		} catch (IOException | NumberFormatException e) {
			LOGGER.error(e, "Cannot connect to {} on port {}, UUID: {}", host, port, uuid);

			try (BufferedWriter clientOutput = new BufferedWriter(
					new OutputStreamWriter(clientSocket.getOutputStream()))) {

				writeHeader(clientOutput, version, HttpStatus.BAD_GATEWAY);
			}

			LOGGER.error("Send HTTP code: 502, Bad Gateway, handled in {} ms, UUID: {}",
					System.currentTimeMillis() - start, uuid);

			return;
		}

		try (BufferedWriter clientOutput = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {

			writeHeader(clientOutput, version, HttpStatus.CONNECTION_ESTABLISHED);

			LOGGER.info("Send HTTP code: 200, Connection established, UUID: {}", uuid);

			final Future<?> remoteToClient = Server.EXECUTORS_FORWARD
					.submit(() -> forwardData(proxySocket, clientSocket, version, uuid));

			try {
				// if data already read
				if (previousWasR) {
					// read next byte
					int read = clientSocket.getInputStream().read();
					// if end not reached, forward left data
					if (read != -1) {
						if (read != '\n') {
							proxySocket.getOutputStream().write(read);
						}
						forwardData(clientSocket, proxySocket, version, uuid);
					}
					// else shutdown sockets
					else {
						if (!proxySocket.isOutputShutdown()) {
							proxySocket.shutdownOutput();
						}
						if (!clientSocket.isInputShutdown()) {
							clientSocket.shutdownInput();
						}
					}
				}
				// if not read
				else {
					forwardData(clientSocket, proxySocket, version, uuid);
				}
			} catch (SocketTimeoutException e) {
				writeHeader(clientOutput, version, HttpStatus.GATEWAY_TIMEOUT);

			} finally {
				try {
					remoteToClient.get();
				} catch (InterruptedException | ExecutionException e) {
					LOGGER.error(e, "Cannot join threads, UUID: {}", uuid);
				}
			}
		} finally {
			if (!proxySocket.isOutputShutdown()) {
				proxySocket.shutdownOutput();
			}
			if (!clientSocket.isInputShutdown()) {
				clientSocket.shutdownInput();
			}
		}

		LOGGER.info("Send HTTP code: 200, handled in {} ms, UUID: {}", System.currentTimeMillis() - start, uuid);
	}
}