package fr.landel.myproxy;

import java.io.IOException;
import java.net.Socket;

public class ErrorRequestHandler extends AbstractRequestHandler {

	private final Socket clientSocket;

	public ErrorRequestHandler( final Socket clientSocket) throws IOException {

		this.clientSocket = clientSocket;
		this.clientSocket.setSoTimeout(Server.SERVER_TIMEOUT);
	}
	
	protected void writeHeader(String version, HttpStatus httpCode) throws IOException {
		super.writeHeader(this.clientSocket, version, httpCode);
	}
}
