package fr.landel.myproxy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.UUID;

import fr.landel.myproxy.utils.Logger;

public class HttpRequestHandler extends AbstractRequestHandler {

	private static final Logger LOGGER = new Logger(HttpRequestHandler.class);

	private final long start;
	private final UUID uuid;
	private final Socket clientSocket;
	private final Proxy proxy;
	private final Authenticator authenticator;

	public HttpRequestHandler(final UUID uuid, final Socket clientSocket, final Proxy proxy,
			final Authenticator authenticator) throws IOException {

		this.start = System.currentTimeMillis();
		this.uuid = uuid;
		this.clientSocket = clientSocket;
		this.clientSocket.setSoTimeout(Server.SERVER_TIMEOUT);
		this.proxy = proxy;
		this.authenticator = authenticator;
	}

	public void handle(final String url, final String version) throws IOException {

		LOGGER.info("Init HTTP, url: {}, uuid: {}", url, uuid);

		new BufferedReader(new InputStreamReader(clientSocket.getInputStream())).readLine();

		final URL remoteURL = new URL(url);

		final URLConnection connection;
		if (Server.PROXY_ENABLED) {
			connection = remoteURL.openConnection(proxy);
			if (connection instanceof HttpURLConnection) {
				final HttpURLConnection conn = (HttpURLConnection) connection;
				conn.setAuthenticator(this.authenticator);
			}
		} else {
			connection = remoteURL.openConnection();
		}

		connection.setUseCaches(false);
		connection.setDoOutput(true);

		OutputStream outputStream;

		try (BufferedWriter clientOutput = new BufferedWriter(
				new OutputStreamWriter(outputStream = clientSocket.getOutputStream()))) {

			// Create Buffered Reader from remote Server
			try (InputStream inputStream = connection.getInputStream()) {

				writeHeader(clientOutput, version, HttpStatus.OK);

				// Read from input stream between proxy and remote server
				forwardData(inputStream, outputStream, uuid);
				
			} catch (IOException e) {
				LOGGER.error(e, "HTTP request handled: {} ms, uuid: {}", System.currentTimeMillis() - start, uuid);

				writeHeader(clientOutput, version, HttpStatus.BAD_REQUEST);

				clientOutput.write(e.getMessage());
			}

			// Ensure all data is sent by this point
			clientOutput.flush();

		} finally {
			if (!clientSocket.isClosed()) {
				clientSocket.close();
			}

			LOGGER.info("HTTP request handled: {} ms, uuid: {}", System.currentTimeMillis() - start, uuid);
		}
	}
}
