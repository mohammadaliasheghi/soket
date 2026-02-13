import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RequestServerHandler implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(RequestServerHandler.class.getName());
    private static final String BASE_DIRECTORY = "Data";
    private static final int BUFFER_SIZE = 8192; // Optimized buffer size
    private static final String FINISH_TOKEN = "finish";
    private static final Path BASE_PATH = Paths.get(BASE_DIRECTORY);

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    // Connection timeout in milliseconds
    private static final int SOCKET_TIMEOUT = 30000;

    public RequestServerHandler(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setSoTimeout(SOCKET_TIMEOUT);

        // Use try-with-resources pattern with auto-closable streams
        this.input = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
        );
        this.output = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
        );

        // Ensure base directory exists
        createBaseDirectory();
    }

    private static void createBaseDirectory() throws IOException {
        if (Files.notExists(BASE_PATH)) {
            Files.createDirectories(BASE_PATH);
            LOGGER.info("Created base directory: " + BASE_DIRECTORY);
        }
    }

    @Override
    public void run() {
        LOGGER.info(() -> "Received connection from: " + socket.getRemoteSocketAddress());

        // Use try-with-resources for automatic cleanup
        try (socket; input; output) {
            processRequests();
        } catch (EOFException e) {
            LOGGER.fine("Client closed connection normally");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error handling client request", e);
            sendErrorResponse("Internal server error: " + e.getMessage());
        } finally {
            LOGGER.info(() -> "Connection closed: " + socket.getRemoteSocketAddress());
        }
    }

    private void processRequests() throws IOException {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String request = input.readUTF();
                handleRequest(request);
            } catch (EOFException e) {
                // Client closed connection normally
                break;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error reading request", e);
                break;
            }
        }
    }

    private void handleRequest(String request) throws IOException {
        // Using Java 25 pattern matching for switch
        switch (request) {
            case "1" -> upload();
            case "2" -> download();
            case "3" -> listFiles();
            case "4" -> {
                LOGGER.info("Client requested disconnection");
                throw new EOFException("Client initiated disconnect");
            }
            default -> sendInvalidRequestResponse(request);
        }
    }

    private void sendInvalidRequestResponse(String request) throws IOException {
        String message = "Invalid request: '" + request + "'. Valid requests: 1(Upload), 2(Download), 3(List), 4(Exit)";
        output.writeUTF(message);
        output.flush();
        LOGGER.warning(() -> "Invalid request from client: " + request);
    }

    private void sendErrorResponse(String error) {
        try {
            output.writeUTF("ERROR: " + error);
            output.flush();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to send error response", e);
        }
    }

    public void upload() throws IOException {
        String fileName = input.readUTF();
        Path filePath = validateAndSanitizePath(fileName);

        try {
            if (Files.exists(filePath)) {
                output.writeUTF("EXISTS");
                output.flush();

                // Check if client wants to overwrite
                String response = input.readUTF();
                if (!"YES".equalsIgnoreCase(response)) {
                    output.writeUTF("Upload cancelled by user");
                    output.flush();
                    return;
                }
            }

            output.writeUTF("READY");
            output.flush();

            // Use NIO for efficient file transfer
            long bytesTransferred = receiveFile(filePath);
            LOGGER.info(() -> String.format("Uploaded file: %s (%d bytes)", fileName, bytesTransferred));

            output.writeUTF("SUCCESS: File uploaded successfully (" + bytesTransferred + " bytes)");
            output.flush();

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error uploading file: " + fileName, e);
            output.writeUTF("ERROR: Upload failed - " + e.getMessage());
            output.flush();
            throw e;
        }
    }

    public void download() throws IOException {
        String fileName = input.readUTF();
        Path filePath = BASE_PATH.resolve(fileName).normalize();

        // Security check: prevent directory traversal
        if (!filePath.startsWith(BASE_PATH)) {
            output.writeUTF("ERROR: Invalid file path");
            output.flush();
            LOGGER.warning(() -> "Attempted directory traversal: " + fileName);
            return;
        }

        if (Files.notExists(filePath) || !Files.isRegularFile(filePath)) {
            output.writeUTF("ERROR: File not found - " + fileName);
            output.flush();
            LOGGER.warning(() -> "Requested non-existent file: " + fileName);
            return;
        }

        try {
            output.writeUTF("READY");
            output.flush();

            long bytesTransferred = sendFile(filePath);
            LOGGER.info(() -> String.format("Downloaded file: %s (%d bytes)", fileName, bytesTransferred));

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error downloading file: " + fileName, e);
            output.writeUTF("ERROR: Download failed - " + e.getMessage());
            output.flush();
            throw e;
        }
    }

    private long receiveFile(Path filePath) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytes = 0;
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                // Check for finish token
                if (bytesRead >= FINISH_TOKEN.length()) {
                    String chunk = new String(buffer, 0, bytesRead);
                    if (chunk.contains(FINISH_TOKEN)) {
                        // Write data before finish token
                        int finishIndex = chunk.indexOf(FINISH_TOKEN);
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

            return totalBytes;
        }
    }

    private long sendFile(Path filePath) throws IOException {
        try (InputStream fileIn = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalBytes = 0;
            int bytesRead;

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            output.write(FINISH_TOKEN.getBytes());
            output.flush();
            return totalBytes;
        }
    }

    public void listFiles() throws IOException {
        try (var filesStream = Files.list(BASE_PATH)) {
            var fileList = filesStream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .toList();

            StringBuilder sb = new StringBuilder();
            sb.append("Files in ").append(BASE_DIRECTORY).append(":\n");

            if (fileList.isEmpty()) {
                sb.append("(No files found)");
            } else {
                for (int i = 0; i < fileList.size(); i++) {
                    sb.append(String.format("%d. %s%n", i + 1, fileList.get(i)));
                }
                sb.append(String.format("%nTotal: %d file(s)", fileList.size()));
            }

            output.writeUTF(sb.toString());
            output.flush();

            LOGGER.fine(() -> "Listed " + fileList.size() + " files for client");
        }
    }

    private Path validateAndSanitizePath(String fileName) throws IOException {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IOException("Invalid file name");
        }

        // Remove any path separators and normalize
        String cleanName = Paths.get(fileName).getFileName().toString();
        Path filePath = BASE_PATH.resolve(cleanName).normalize();

        // Security check
        if (!filePath.startsWith(BASE_PATH)) {
            throw new IOException("Invalid file path");
        }

        return filePath;
    }
}