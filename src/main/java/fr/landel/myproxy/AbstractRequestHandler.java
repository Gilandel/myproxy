package fr.landel.myproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

import fr.landel.myproxy.utils.Logger;

public abstract class AbstractRequestHandler {

	private static final Logger LOGGER = new Logger(AbstractRequestHandler.class);

	private static final int BUFFER_SIZE = 20_480;

	protected boolean previousWasR;

	protected void forwardData(final InputStream inputStream, final OutputStream outputStream, final UUID uuid)
			throws IOException {

		byte[] buffer = new byte[BUFFER_SIZE];
		int read;
		long length = 0;
		while ((read = inputStream.read(buffer)) > -1) {
			length += read;
			outputStream.write(buffer, 0, read);
		}

		if (inputStream.available() < 1) {
			outputStream.flush();
		}

		LOGGER.info("Read data length: {}, UUID: {}", length, uuid);
	}

	protected void forwardData(final Socket inputSocket, final Socket outputSocket, final String version, final UUID uuid) {

		long startForwarding = System.currentTimeMillis();

		try {
			final InputStream inputStream = inputSocket.getInputStream();
			final OutputStream outputStream = outputSocket.getOutputStream();
			
			try {
				forwardData(inputStream, outputStream, uuid);

			} catch (SocketTimeoutException e) {
				LOGGER.error(e, "Cannot forward data, UUID: {}", uuid);

				writeHeader(new OutputStreamWriter(outputStream), version, HttpStatus.GATEWAY_TIMEOUT);
				
			} catch (IOException e) {
				LOGGER.error(e, "Cannot forward data, UUID: {}", uuid);

				writeHeader(new OutputStreamWriter(outputStream), version, HttpStatus.BAD_REQUEST);
				
			} finally {
				if (!outputSocket.isOutputShutdown()) {
					outputSocket.shutdownOutput();
				}
				if (!inputSocket.isInputShutdown()) {
					inputSocket.shutdownInput();
				}
			}
		} catch (IOException e1) {
			LOGGER.error(e1, "Cannot forward data, UUID: {}", uuid);
		}

		LOGGER.info("Forwarded data: {} ms, UUID: {}", System.currentTimeMillis() - startForwarding, uuid);
	}
	
	protected void writeHeader(final Socket socket, final String version, final HttpStatus httpCode)
			throws IOException {
		
		writeHeader(socket.getOutputStream(), version, httpCode);
	}
	
	protected void writeHeader(final OutputStream outputStream, final String version, final HttpStatus httpCode)
			throws IOException {
		
		writeHeader(new OutputStreamWriter(outputStream), version, httpCode);
	}

	protected void writeHeader(final Writer outputStreamWriter, final String version, final HttpStatus httpCode)
			throws IOException {

		outputStreamWriter.write("HTTP/");
		outputStreamWriter.write(version);
		outputStreamWriter.write(' ');
		outputStreamWriter.write(httpCode.value());
		outputStreamWriter.write(' ');
		outputStreamWriter.write(httpCode.getReasonPhrase());
		outputStreamWriter.write("\r\nProxy-agent: ");
		outputStreamWriter.write(Server.AGENT_NAME);
		outputStreamWriter.write("/");
		outputStreamWriter.write(Server.AGENT_VERSION);
		outputStreamWriter.write("\r\n\r\n");

		outputStreamWriter.flush();
	}

	protected String readLine(final Socket socket) throws IOException {
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
			return byteArrayOutputStream.toString();
		}
	}
}
