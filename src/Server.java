import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Server implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_BACKLOG = 50;

    private final int port;
    private final int backlog;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService virtualThreadExecutor;

    private ServerSocket serverSocket;
    private Thread acceptorThread;
    private Instant startTime;
    private volatile boolean shutdownHookRegistered = false;

    /**
     * Creates a new server instance with default backlog.
     *
     * @param port the port to listen on
     */
    public Server(int port) {
        this(port, DEFAULT_BACKLOG);
    }

    /**
     * Creates a new server instance with custom backlog.
     *
     * @param port    the port to listen on
     * @param backlog the maximum queue length for incoming connections
     */
    public Server(int port, int backlog) {
        validatePort(port);
        validateBacklog(backlog);

        this.port = port;
        this.backlog = backlog;
        // Java 25: Virtual threads executor for lightweight concurrency
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    private void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
    }

    private void validateBacklog(int backlog) {
        if (backlog < 1) {
            throw new IllegalArgumentException("Backlog must be positive");
        }
    }

    /**
     * Starts the server and begins accepting connections.
     *
     * @throws IOException           if the server socket cannot be created
     * @throws IllegalStateException if the server is already running
     */
    public synchronized void start() throws IOException {
        if (isRunning()) {
            throw new IllegalStateException("Server is already running on port " + port);
        }

        try {
            this.serverSocket = createServerSocket();
            this.startTime = Instant.now();
            this.running.set(true);

            // Register shutdown hook only once
            if (!shutdownHookRegistered) {
                registerShutdownHook();
                shutdownHookRegistered = true;
            }

            // Use virtual thread for acceptor to minimize overhead
            this.acceptorThread = Thread.startVirtualThread(this::acceptConnections);

            LOGGER.info(() -> String.format("Server started on port %d (backlog: %d)", port, backlog));
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start server on port " + port, e);
            cleanup();
            throw e;
        }
    }

    private ServerSocket createServerSocket() throws IOException {
        return new ServerSocket(port, backlog);
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual()
                .name("shutdown-hook-" + port)
                .unstarted(() -> {
                    if (isRunning()) {
                        LOGGER.info("Shutdown hook triggered, stopping server...");
                        stop();
                    }
                }));
    }

    private void acceptConnections() {
        LOGGER.info(() -> "Acceptor thread started for port " + port);

        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket clientSocket = serverSocket.accept();
                handleIncomingConnection(clientSocket);
            } catch (SocketException e) {
                // Normal shutdown or socket closed
                if (isRunning()) {
                    LOGGER.log(Level.WARNING, "Socket exception occurred", e);
                }
                break;
            } catch (IOException e) {
                if (isRunning()) {
                    LOGGER.log(Level.SEVERE, "Error accepting client connection", e);
                }
                break;
            }
        }

        LOGGER.info(() -> "Acceptor thread stopped for port " + port);
    }

    private void handleIncomingConnection(Socket clientSocket) {
        try {
            // Set socket options for better performance
            configureClientSocket(clientSocket);

            // Log incoming connection with client info
            LOGGER.info(() -> String.format("New connection from %s:%d",
                    clientSocket.getInetAddress().getHostAddress(),
                    clientSocket.getPort()));

            // Java 25: Submit handler as virtual task
            virtualThreadExecutor.submit(() -> {
                try {
                    RequestServerHandler handler = new RequestServerHandler(clientSocket);
                    handler.run();
                    // Handler will close its own resources in run() method
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error handling client connection", e);
                    // Ensure socket is closed if handler construction fails
                    closeQuietly(clientSocket);
                }
            });

        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to configure client socket", e);
            closeQuietly(clientSocket);
        }
    }

    private void configureClientSocket(Socket socket) throws IOException {
        // Enable TCP keep-alive for better connection management
        socket.setKeepAlive(true);

        // Disable Nagle's algorithm for lower latency
        socket.setTcpNoDelay(true);

        // Set socket timeout for responsiveness
        socket.setSoTimeout(30000); // 30 seconds

        // Set receive and send buffer sizes for better performance
        socket.setReceiveBufferSize(65536); // 64KB
        socket.setSendBufferSize(65536); // 64KB
    }

    /**
     * Stops the server gracefully.
     */
    public void stop() {
        stop(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * Stops the server with a specific timeout.
     *
     * @param timeout the maximum time to wait for graceful shutdown
     */
    public void stop(Duration timeout) {
        if (!running.compareAndSet(true, false)) {
            LOGGER.fine("Server is already stopped");
            return;
        }

        LOGGER.info(() -> String.format("Stopping server on port %d...", port));

        try {
            // Stop accepting new connections
            closeQuietly(serverSocket);
            serverSocket = null;

            // Interrupt acceptor thread
            if (acceptorThread != null && acceptorThread.isAlive()) {
                acceptorThread.interrupt();
                try {
                    acceptorThread.join(timeout.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warning("Interrupted while waiting for acceptor thread");
                }
            }

            // Shutdown executor service gracefully
            shutdownExecutor(timeout);

            long uptime = Duration.between(startTime, Instant.now()).getSeconds();
            LOGGER.info(() -> String.format("Server stopped. Uptime: %d seconds", uptime));

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during server shutdown", e);
        } finally {
            cleanup();
        }
    }

    private void shutdownExecutor(Duration timeout) throws InterruptedException {
        virtualThreadExecutor.shutdown();
        if (!virtualThreadExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            LOGGER.warning("Executor service did not terminate in time, forcing shutdown");
            virtualThreadExecutor.shutdownNow();
        }
    }

    private void cleanup() {
        closeQuietly(serverSocket);
        serverSocket = null;
        acceptorThread = null;
        startTime = null;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINEST, "Error closing resource", e);
            }
        }
    }

    /**
     * Checks if the server is currently running.
     *
     * @return true if the server is accepting connections
     */
    public boolean isRunning() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    @Override
    public void close() {
        stop();
    }

    // ==================== Main Method ====================

    /**
     * Interactive server launcher with improved user experience.
     */
    static void main() {
        // Configure logging for better console output
        configureLogging();

        try (Scanner scanner = new Scanner(System.in)) {
            IO.print("Enter port number (1-65535): ");

            while (!scanner.hasNextInt()) {
                IO.println("Invalid input. Please enter a valid port number.");
                scanner.next(); // consume invalid input
                IO.print("Enter port number (1-65535): ");
            }

            int port = scanner.nextInt();

            try (Server server = new Server(port)) {
                // Add shutdown hook for clean termination
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    IO.println("\nShutting down server...");
                    server.stop();
                }));

                server.start();

                // Keep the main thread alive until interrupted
                Thread.currentThread().join();

            } catch (IOException e) {
                IO.println("Failed to start server: " + e.getMessage());
                System.exit(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                IO.println("Server interrupted");
            }
        }
    }

    private static void configureLogging() {
        // Simple console logging configuration
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$-6s [%2$s] %5$s%6$s%n");
    }
}