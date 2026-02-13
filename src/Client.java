import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Client implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(Client.class.getName());
    private static final int BUFFER_SIZE = 8192;
    private static final int SOCKET_TIMEOUT = 30000;
    private static final String FINISH_TOKEN = "finish";
    private static final String CLIENT_DIRECTORY = "Client";
    private static final Path CLIENT_PATH = Paths.get(CLIENT_DIRECTORY);

    private final String host;
    private final int port;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Scanner scanner;
    private final ExecutorService virtualThreadExecutor;

    private volatile boolean connected;
    private volatile boolean running;

    public Client(String host, int port) throws IOException {
        this.host = validateHost(host);
        this.port = validatePort(port);
        this.scanner = new Scanner(System.in);
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            this.socket = createSocket();
            this.input = createInputStream();
            this.output = createOutputStream();
            this.connected = true;
            this.running = true;

            createClientDirectory();
            LOGGER.info(() -> String.format("Connected to server %s:%d", host, port));

        } catch (IOException e) {
            cleanup();
            throw new IOException("Failed to connect to server: " + e.getMessage(), e);
        }
    }

    private String validateHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        return host.trim();
    }

    private int validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }

    private Socket createSocket() throws IOException {
        Socket socket = new Socket(host, port);
        configureSocket(socket);
        return socket;
    }

    private void configureSocket(Socket socket) throws IOException {
        socket.setSoTimeout(SOCKET_TIMEOUT);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(BUFFER_SIZE * 8);
        socket.setSendBufferSize(BUFFER_SIZE * 8);
    }

    private DataInputStream createInputStream() throws IOException {
        return new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
        );
    }

    private DataOutputStream createOutputStream() throws IOException {
        return new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
        );
    }

    private void createClientDirectory() throws IOException {
        if (Files.notExists(CLIENT_PATH)) {
            Files.createDirectories(CLIENT_PATH);
            LOGGER.info(() -> "Created client directory: " + CLIENT_DIRECTORY);
        }
    }

    /**
     * Starts the client menu interface.
     */
    public void start() {
        LOGGER.info("Starting client interface");

        try {
            showMenu();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error in client menu", e);
        } finally {
            close();
        }
    }

    private void showMenu() {
        String menu = """
                ╔════════════════════════════════╗
                ║           * MENU *             ║
                ╠════════════════════════════════╣
                ║  1. Download File              ║
                ║  2. Upload File                ║
                ║  3. List Remote Files          ║
                ║  4. Exit                       ║
                ╚════════════════════════════════╝
                """;

        while (running && connected) {
            try {
                System.out.println(menu);
                System.out.print("Enter choice (1-4): ");

                String choice = scanner.nextLine().trim();

                if (choice.isEmpty()) {
                    System.out.println("Please enter a valid choice.");
                    continue;
                }

                // Send command to server
                output.writeUTF(choice);
                output.flush();

                // Process command
                switch (choice) {
                    case "1" -> downloadFile();
                    case "2" -> uploadFile();
                    case "3" -> listFiles();
                    case "4" -> {
                        System.out.println("Goodbye!");
                        running = false;
                    }
                    default -> System.out.println("❌ Invalid choice. Please enter 1-4.");
                }

            } catch (SocketTimeoutException e) {
                System.out.println("⚠️  Connection timeout. Reconnecting...");
                handleDisconnect();
            } catch (EOFException e) {
                System.out.println("⚠️  Server closed the connection.");
                handleDisconnect();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "IO error in menu", e);
                System.out.println("⚠️  Connection error: " + e.getMessage());
                handleDisconnect();
            }
        }
    }

    private void handleDisconnect() {
        connected = false;
        running = false;
    }

    private void downloadFile() throws IOException {
        System.out.print("📁 Enter filename to download: ");
        String fileName = scanner.nextLine().trim();

        if (fileName.isEmpty()) {
            System.out.println("❌ Filename cannot be empty.");
            return;
        }

        // Sanitize filename
        fileName = sanitizeFileName(fileName);
        Path filePath = CLIENT_PATH.resolve(fileName);

        output.writeUTF(fileName);
        output.flush();

        String response = input.readUTF();

        if ("READY".equals(response)) {
            System.out.println("⬇️  Downloading " + fileName + "...");

            try {
                long bytesTransferred = receiveFile(filePath);
                System.out.printf("✅ File downloaded successfully: %s (%d bytes)%n",
                        fileName, bytesTransferred);

                // Wait for final status
                String status = input.readUTF();
                if (status.startsWith("SUCCESS")) {
                    System.out.println("📊 " + status);
                }

            } catch (IOException e) {
                System.out.println("❌ Download failed: " + e.getMessage());
                Files.deleteIfExists(filePath);
                throw e;
            }

        } else if ("EXISTS".equals(response)) {
            System.out.println("⚠️  File already exists on server.");
            System.out.print("Do you want to overwrite? (yes/no): ");
            String overwrite = scanner.nextLine().trim().toLowerCase();

            output.writeUTF(overwrite.equals("yes") ? "YES" : "NO");
            output.flush();

            if (overwrite.equals("yes")) {
                downloadFile(); // Retry with overwrite
            } else {
                System.out.println("❌ Download cancelled.");
            }

        } else {
            System.out.println("❌ Server response: " + response);
        }
    }

    private long receiveFile(Path filePath) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytes = 0;
            int bytesRead;

            Instant start = Instant.now();

            while ((bytesRead = input.read(buffer)) != -1) {
                // Check for finish token
                if (bytesRead >= FINISH_TOKEN.length()) {
                    String chunk = new String(buffer, 0, bytesRead);
                    int finishIndex = chunk.indexOf(FINISH_TOKEN);
                    if (finishIndex >= 0) {
                        if (finishIndex > 0) {
                            fileOut.write(buffer, 0, finishIndex);
                            totalBytes += finishIndex;
                        }
                        break;
                    }
                }

                fileOut.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            Duration duration = Duration.between(start, Instant.now());
            double speed = totalBytes / (1024.0 * Math.max(1, duration.toMillis() / 1000.0));

            long FTB = totalBytes;
            LOGGER.info(() -> String.format("Downloaded %s: %d bytes in %d ms (%.1f KB/s)",
                    filePath.getFileName(), FTB, duration.toMillis(), speed));

            return totalBytes;
        }
    }

    private void uploadFile() throws IOException {
        System.out.print("📂 Enter file path to upload: ");
        String filePath = scanner.nextLine().trim();

        if (filePath.isEmpty()) {
            System.out.println("❌ File path cannot be empty.");
            return;
        }

        Path sourcePath = Paths.get(filePath);

        if (!Files.exists(sourcePath)) {
            System.out.println("❌ File does not exist: " + filePath);
            return;
        }

        if (!Files.isRegularFile(sourcePath)) {
            System.out.println("❌ Not a regular file: " + filePath);
            return;
        }

        String fileName = sourcePath.getFileName().toString();
        output.writeUTF(fileName);
        output.flush();

        String response = input.readUTF();

        if ("READY".equals(response)) {
            System.out.println("⬆️  Uploading " + fileName + "...");

            try {
                long bytesTransferred = sendFile(sourcePath);
                System.out.printf("✅ File uploaded successfully: %s (%d bytes)%n",
                        fileName, bytesTransferred);

                String status = input.readUTF();
                if (status.startsWith("SUCCESS")) {
                    System.out.println("📊 " + status);
                }

            } catch (IOException e) {
                System.out.println("❌ Upload failed: " + e.getMessage());
                throw e;
            }

        } else if ("EXISTS".equals(response)) {
            System.out.println("⚠️  File already exists on server.");
            System.out.print("Do you want to overwrite? (yes/no): ");
            String overwrite = scanner.nextLine().trim().toLowerCase();

            output.writeUTF(overwrite.equals("yes") ? "YES" : "NO");
            output.flush();

            if (overwrite.equals("yes")) {
                uploadFile(); // Retry with overwrite
            } else {
                System.out.println("❌ Upload cancelled.");
            }

        } else {
            System.out.println("❌ Server response: " + response);
        }
    }

    private long sendFile(Path filePath) throws IOException {
        try (InputStream fileIn = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytes = 0;
            int bytesRead;

            Instant start = Instant.now();

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                output.flush();
                totalBytes += bytesRead;
            }

            output.write(FINISH_TOKEN.getBytes());
            output.flush();

            Duration duration = Duration.between(start, Instant.now());
            double speed = totalBytes / (1024.0 * Math.max(1, duration.toMillis() / 1000.0));

            long FTB = totalBytes;
            LOGGER.info(() -> String.format("Uploaded %s: %d bytes in %d ms (%.1f KB/s)",
                    filePath.getFileName(), FTB, duration.toMillis(), speed));

            return totalBytes;
        }
    }

    private void listFiles() throws IOException {
        System.out.println("\n📋 Requesting file list from server...");

        String fileList = input.readUTF();

        System.out.println("\n" + "=".repeat(50));
        System.out.println(fileList);
        System.out.println("=".repeat(50));
    }

    private String sanitizeFileName(String fileName) {
        // Remove path separators and normalize
        return Paths.get(fileName).getFileName().toString()
                .replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    @Override
    public void close() {
        LOGGER.info("Closing client connection");
        running = false;
        connected = false;

        closeQuietly(scanner);
        closeQuietly(output);
        closeQuietly(input);
        closeQuietly(socket);

        shutdownExecutor();

        LOGGER.info("Client closed");
    }

    private void shutdownExecutor() {
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
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

    private void cleanup() {
        closeQuietly(output);
        closeQuietly(input);
        closeQuietly(socket);
        closeQuietly(scanner);
    }

    // ==================== Main Method ====================

    static void main() {
        configureLogging();

        System.out.println("🌐 File Transfer Client");
        System.out.println("=".repeat(50));

        try (Scanner scanner = new Scanner(System.in)) {
            // Get server host
            System.out.print("Server host [localhost]: ");
            String host = scanner.nextLine().trim();
            if (host.isEmpty()) {
                host = "localhost";
            }

            // Get server port
            System.out.print("Server port: ");
            while (!scanner.hasNextInt()) {
                System.out.println("Invalid port. Please enter a number between 1 and 65535.");
                scanner.next();
                System.out.print("Server port: ");
            }
            int port = scanner.nextInt();
            scanner.nextLine(); // consume newline

            try (Client client = new Client(host, port)) {

                // Add shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("\n👋 Shutting down client...");
                    client.close();
                }));

                // Start client interface
                client.start();

            } catch (IOException e) {
                System.err.println("❌ Failed to connect to server: " + e.getMessage());
                System.exit(1);
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unexpected error", e);
            System.exit(1);
        }

        System.out.println("\n👋 Client terminated.");
    }

    private static void configureLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$-6s %5$s%6$s%n");
    }
}