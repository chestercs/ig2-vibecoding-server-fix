
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TcpServer {

    private static final int PORT = 1611;
    private static final int PING_INTERVAL_MS = 10000;
    private static final int PING_TIMEOUT_MS = 15000;
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private int clientIdCounter = 0;

    public static void main(String[] args) throws IOException {
        new TcpServer().start();
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("TCP server listening on port " + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            int clientId = ++clientIdCounter;
            ClientHandler handler = new ClientHandler(clientSocket, clientId);
            clients.put(clientId, handler);
            new Thread(handler).start();
            System.out.println("Connection from " + clientSocket.getRemoteSocketAddress() + " (id: " + clientId + ")");
        }
    }

    private class ClientHandler implements Runnable {

        private final Socket socket;
        private final int clientId;
        private final DataInputStream input;
        private final DataOutputStream output;
        private volatile boolean running = true;
        private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private volatile int pingId = 0;
        private volatile long lastPongTime;

        ClientHandler(Socket socket, int clientId) throws IOException {
            this.socket = socket;
            this.clientId = clientId;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
            this.lastPongTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            scheduler.scheduleAtFixedRate(this::sendPing, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::checkTimeout, PING_TIMEOUT_MS, PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            try {
                while (running) {
                    int size = Integer.reverseBytes(input.readInt());
                    byte[] data = new byte[size];
                    input.readFully(data);
                    handleMessage(new String(data, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                System.out.println("Connection lost (" + socket.getRemoteSocketAddress() + " id:" + clientId + ")");
            } finally {
                shutdown();
            }
        }

        private void sendPing() {
            sendMessage("ping:" + (++pingId));
        }

        private void checkTimeout() {
            if (System.currentTimeMillis() - lastPongTime > PING_TIMEOUT_MS) {
                System.out.println("Connection timed out: " + clientId);
                shutdown();
            }
        }

        private void handleMessage(String message) {
            if (message.startsWith("pong:")) {
                lastPongTime = System.currentTimeMillis();
            }
            // Additional message handling here
            sendMessage("ack:ok");
        }

        private void sendMessage(String message) {
            try {
                byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
                output.writeInt(Integer.reverseBytes(msgBytes.length));
                output.write(msgBytes);
                output.flush();
            } catch (IOException e) {
                shutdown();
            }
        }

        private void shutdown() {
            running = false;
            scheduler.shutdown();
            clients.remove(clientId);
            try {
                socket.close();
            } catch (IOException ignored) {}
            System.out.println("Connection closed (id:" + clientId + ")");
        }
    }
}
