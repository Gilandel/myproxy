package fr.landel.myproxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import fr.landel.myproxy.utils.CastUtils;
import fr.landel.myproxy.utils.Logger;

/**
 * The Proxy creates a Server Socket which will wait for connections on the
 * specified port. Once a connection arrives and a socket is accepted, the Proxy
 * creates a RequestHandler object on a new thread and passes the socket to it
 * to be handled. This allows the Proxy to continue accept further connections
 * while others are being handled.
 * 
 * The Proxy class is also responsible for providing the dynamic management of
 * the proxy through the console and is run on a separate thread in order to not
 * interrupt the acceptance of socket connections. This allows the administrator
 * to dynamically block web sites in real time.
 * 
 * The Proxy server is also responsible for maintaining cached copies of the any
 * websites that are requested by clients and this includes the HTML markup,
 * images, css and js files associated with each webpage.
 * 
 * Upon closing the proxy server, the HashMaps which hold cached items and
 * blocked sites are serialized and written to a file and are loaded back in
 * when the proxy is started once more, meaning that cached and blocked sites
 * are maintained.
 *
 */
public class Proxy1 implements Runnable {

	private static final Logger LOG = new Logger(Proxy1.class);

	// Main method for the program
	public static void main(String[] args) {
		long start = System.currentTimeMillis();

		// Create an instance of Proxy and begin listening for connections
		Proxy1 myProxy = new Proxy1(3129);

		LOG.info("Started in {}", getTime(System.currentTimeMillis() - start));

		myProxy.listen();
	}

	private ServerSocket serverSocket;

	/**
	 * Semaphore for Proxy and Console Management System.
	 */
	private volatile boolean running = true;

	static File outputDir = new File("target");
	static File cacheDir = new File(outputDir, "cached");
	static File cachedSites = new File(outputDir, "cachedSites.txt");
	static File blockedSitesTxtFile = new File(outputDir, "blockedSites.txt");

	/**
	 * Data structure for constant order lookup of cache items. Key: URL of
	 * page/image requested. Value: File in storage associated with this key.
	 */
	static Map<String, File> cache;

	/**
	 * Data structure for constant order lookup of blocked sites. Key: URL of
	 * page/image requested. Value: URL of page/image requested.
	 */
	static Map<String, String> blockedSites;

	/**
	 * ArrayList of threads that are currently running and servicing requests. This
	 * list is required in order to join all threads on closing of server
	 */
	static List<Thread> servicingThreads;

	/**
	 * Create the Proxy Server
	 * 
	 * @param port Port number to run proxy server from.
	 */
	public Proxy1(int port) {

		// Load in hash map containing previously cached sites and blocked Sites
		cache = new HashMap<>();
		blockedSites = new HashMap<>();

		// Create array list to hold servicing threads
		servicingThreads = new ArrayList<>();

		// Start dynamic manager on a separate thread.
		new Thread(this).start(); // Starts overridden run() method at bottom

		try {
			if (!cacheDir.isDirectory()) {
				cacheDir.mkdirs();
			}

			// Load in cached sites from file
			if (!cachedSites.exists()) {
				LOG.info("No cached sites found - creating new file");
				cachedSites.createNewFile();

			} else if (cachedSites.length() > 0) {
				try (ObjectInputStream objectInputStream = new ObjectInputStream(new FileInputStream(cachedSites))) {
					cache = CastUtils.cast(objectInputStream.readObject());
				}
			}

			// Load in blocked sites from file
			if (!blockedSitesTxtFile.exists()) {
				LOG.info("No blocked sites found - creating new file");
				blockedSitesTxtFile.createNewFile();

			} else if (blockedSitesTxtFile.length() > 0) {
				try (ObjectInputStream objectInputStream = new ObjectInputStream(
						new FileInputStream(blockedSitesTxtFile))) {
					blockedSites = CastUtils.cast(objectInputStream.readObject());
				}
			}
		} catch (IOException e) {
			LOG.error("Error loading previously cached sites file");
			e.printStackTrace();

		} catch (ClassNotFoundException e) {
			LOG.error("Class not found loading in preivously cached sites file");
			e.printStackTrace();
		}

		try {
			// Create the Server Socket for the Proxy
			serverSocket = new ServerSocket(port);

			// Set the timeout
			// serverSocket.setSoTimeout(100000); // debug
			LOG.info("Waiting for client on port {}...", serverSocket.getLocalPort());
			running = true;
		}

		// Catch exceptions associated with opening socket
		catch (SocketException se) {
			LOG.error("Socket Exception when connecting to client");
			se.printStackTrace();

		} catch (SocketTimeoutException ste) {
			LOG.error("Timeout occured while connecting to client");

		} catch (IOException io) {
			LOG.error("IO exception when connecting to client");
		}
	}

	/**
	 * Listens to port and accepts new socket connections. Creates a new thread to
	 * handle the request and passes it the socket connection and continues
	 * listening.
	 */
	public void listen() {

		while (running) {
			try {
				// serverSocket.accept() Blocks until a connection is made
				Socket socket = serverSocket.accept();

				// Create new Thread and pass it Runnable RequestHandler
				Thread thread = new Thread(new RequestHandler(socket));

				// Key a reference to each thread so they can be joined later if necessary
				servicingThreads.add(thread);

				thread.start();
			} catch (SocketException e) {
				// Socket exception is triggered by management system to shut down the proxy
				LOG.error("Server closed");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Saves the blocked and cached sites to a file so they can be re loaded at a
	 * later time. Also joins all of the RequestHandler threads currently servicing
	 * requests.
	 */
	private void closeServer() {
		LOG.info("\nClosing Server...");
		running = false;
		try {
			try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(new FileOutputStream(cachedSites))) {
				objectOutputStream.writeObject(cache);
				LOG.info("Cached Sites written");
			}

			try (ObjectOutputStream objectOutputStream2 = new ObjectOutputStream(
					new FileOutputStream(blockedSitesTxtFile))) {
				objectOutputStream2.writeObject(blockedSites);
				LOG.info("Blocked Site list saved");
			}

			try {
				// Close all servicing threads
				for (Thread thread : servicingThreads) {
					if (thread.isAlive()) {
						System.out.print("Waiting on " + thread.getId() + " to close..");

						thread.join();

						LOG.info("Closed");
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		} catch (IOException e) {
			LOG.error("Error saving cache/blocked sites");
			e.printStackTrace();
		}

		// Close Server Socket
		try {
			LOG.info("Terminating Connection");
			serverSocket.close();

		} catch (IOException e) {
			LOG.error("Exception closing proxy's server socket");
			e.printStackTrace();
		}

	}

	/**
	 * Looks for File in cache
	 * 
	 * @param url of requested file
	 * @return File if file is cached, null otherwise
	 */
	public static File getCachedPage(String url) {
		return cache.get(url);
	}

	/**
	 * Adds a new page to the cache
	 * 
	 * @param urlString   URL of webpage to cache
	 * @param fileToCache File Object pointing to File put in cache
	 */
	public static void addCachedPage(String urlString, File fileToCache) {
		cache.put(urlString, fileToCache);
	}

	/**
	 * Check if a URL is blocked by the proxy
	 * 
	 * @param url URL to check
	 * @return true if URL is blocked, false otherwise
	 */
	public static boolean isBlocked(String url) {
		if (blockedSites.get(url) != null) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Creates a management interface which can dynamically update the proxy
	 * configurations blocked : Lists currently blocked sites cached : Lists
	 * currently cached sites close : Closes the proxy server * : Adds * to the list
	 * of blocked sites
	 */
	@Override
	public void run() {
		Scanner scanner = new Scanner(System.in);

		String command;
		while (running) {
			LOG.info(
					"Enter new site to block, or type \"blocked\" to see blocked sites, \"cached\" to see cached sites, or \"close\" to close server.");
			command = scanner.nextLine();
			if ("blocked".equalsIgnoreCase(command)) {
				LOG.info("\nCurrently Blocked Sites");
				for (String key : blockedSites.keySet()) {
					LOG.info(key);
				}
				LOG.info("");
			}

			else if ("cached".equalsIgnoreCase(command)) {
				LOG.info("\nCurrently Cached Sites");
				for (String key : cache.keySet()) {
					LOG.info(key);
				}
				LOG.info("");
			}

			else if (command.equals("close")) {
				running = false;
				closeServer();
			}

			else {
				blockedSites.put(command, command);
				LOG.info("\n{} blocked successfully \n", command);
			}
		}
		scanner.close();
	}

	public static String getTime(final long time) {
		double diff;

		long hours = Math.round(Math.floor(time / 3_600_000.0));
		long minutes = Math.round(Math.floor((diff = (time - hours * 3_600_000.0)) / 60_000.0));
		long seconds = Math.round(Math.floor((diff = (diff - minutes * 60_000.0)) / 1_000.0));
		long millis = Math.round(diff - seconds * 1_000.0);

		final StringBuilder builder = new StringBuilder();
		if (hours > 0) {
			builder.append(hours).append(":") //
					.append(minutes).append(":") //
					.append(seconds).append(".") //
					.append(millis).append("ms");
		} else if (minutes > 0) {
			builder.append(minutes).append(":") //
					.append(seconds).append(".") //
					.append(millis).append("ms");
		} else if (seconds > 0) {
			builder.append(seconds).append(".") //
					.append(millis).append("ms");
		} else {
			builder.append(millis).append("ms");
		}
		return builder.toString();
	}

}