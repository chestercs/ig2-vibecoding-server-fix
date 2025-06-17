
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Main {

    private static final int PORT = 1611;
    private static final int TIMEOUT_MS = 15000;
    private static final int PING_INTERVAL_MS = 5000;
    private static final int MAX_MESSAGE_RATE_PER_SECOND = 10;

    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private int clientIdCounter = 1;

    public static void main(String[] args) throws IOException {
        new Main().start();
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("TCP server listening on port 2" + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            int clientId = clientIdCounter++;
            ClientHandler handler = new ClientHandler(clientSocket, clientId);
            clients.put(clientId, handler);
            new Thread(handler).start();
            System.out.println("Connection from " + clientSocket.getRemoteSocketAddress() + " (id: " + clientId + ")");
        }
    }

    private class Lobby {
        String lobbyId;
        int hostClientId;

        Lobby(String lobbyId, int hostClientId) {
            this.lobbyId = lobbyId;
            this.hostClientId = hostClientId;
        }
    }

    private class ClientHandler implements Runnable {

        private final Socket socket;
        private final int clientId;
        private final BufferedReader reader;
        private final PrintWriter writer;
        private volatile boolean running = true;
        private volatile long lastPongTime = System.currentTimeMillis();
        private volatile int messageCount = 0;
        private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();

        ClientHandler(Socket socket, int clientId) throws IOException {
            this.socket = socket;
            this.clientId = clientId;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new PrintWriter(socket.getOutputStream(), true);
        }

        @Override
        public void run() {
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

            scheduler.scheduleAtFixedRate(this::sendPing, 0, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::processQueue, 0, 100, TimeUnit.MILLISECONDS);

            try {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    handleMessage(line.trim());
                }
            } catch (IOException e) {
                // Ignore, probably client disconnected
            } finally {
                shutdown();
                scheduler.shutdown();
            }
        }

        private void sendPing() {
            long now = System.currentTimeMillis();
            if (now - lastPongTime > TIMEOUT_MS) {
                System.out.println("Connection timed out: " + clientId);
                shutdown();
                return;
            }
            sendMessage("ping");
        }

        private void processQueue() {
            for (int i = 0; i < MAX_MESSAGE_RATE_PER_SECOND / 10; i++) {
                String msg = messageQueue.poll();
                if (msg != null) {
                    writer.println(msg);
                } else {
                    break;
                }
            }
        }

        private void handleMessage(String message) {
            if (message.isEmpty()) return;

            if (message.equals("pong")) {
                lastPongTime = System.currentTimeMillis();
                return;
            }

            if (message.startsWith("cmd:find")) {
                broadcastLobbies();
                return;
            }

            if (message.startsWith("cmd:host|")) {
                String[] parts = message.split("\\|");
                if (parts.length > 1) {
                    String lobbyId = parts[1];
                    lobbies.put(lobbyId, new Lobby(lobbyId, clientId));
                }
                return;
            }

            // Simulate response if needed
            if (message.startsWith("cmd:connect|")) {
                String[] parts = message.split("\\|");
                if (parts.length > 1) {
                    String lobbyId = parts[1];
                    if (lobbies.containsKey(lobbyId)) {
                        sendMessage("connected:" + lobbyId);
                    } else {
                        sendMessage("error:lobby_not_found");
                    }
                }
                return;
            }
        }

        private void broadcastLobbies() {
            StringBuilder sb = new StringBuilder("lobbies:");
            for (Lobby lobby : lobbies.values()) {
                sb.append(lobby.lobbyId).append(",");
            }
            String lobbyList = sb.toString();
            if (lobbyList.endsWith(",")) {
                lobbyList = lobbyList.substring(0, lobbyList.length() - 1);
            }
            sendMessage(lobbyList);
        }

        private void sendMessage(String message) {
            messageQueue.offer(message);
        }

        private void shutdown() {
            running = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            clients.remove(clientId);
            lobbies.values().removeIf(lobby -> lobby.hostClientId == clientId);
            System.out.println("Connection closed (" + socket.getRemoteSocketAddress() + " id:" + clientId + ")");
        }
    }
}