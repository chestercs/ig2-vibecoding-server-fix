package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TcpServer {

    private static final int PORT = 1611;
    private static final int PING_INTERVAL_MS = 10000;
    private static final int PING_TIMEOUT_MS = 15000;

    private final ConcurrentHashMap<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Map<String, String>> clientParams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<Integer>> lobbyConnections = new ConcurrentHashMap<>();

    private int clientIdCounter = 0;

    public static void main(String[] args) throws IOException {
        new TcpServer().start();
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("TCP server listening on port " + PORT);

        while (true) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            int clientId = ++clientIdCounter;
            ClientHandler handler = new ClientHandler(socket, clientId);
            clients.put(clientId, handler);
            lobbyConnections.put(clientId, ConcurrentHashMap.newKeySet());
            new Thread(handler).start();
            System.out.println("New connection from " + socket.getRemoteSocketAddress() + " (id: " + clientId + ")");
        }
    }

    private class ClientHandler implements Runnable {

        private final Socket socket;
        private final int clientId;
        private final DataInputStream input;
        private final DataOutputStream output;
        private volatile boolean running = true;
        private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        private volatile int lastPingId = 0;
        private volatile long lastPongTime = System.currentTimeMillis();

        private final Object writeLock = new Object();

        ClientHandler(Socket socket, int clientId) throws IOException {
            this.socket = socket;
            this.clientId = clientId;
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            sendClientId();
        }

        private void sendClientId() throws IOException {
            synchronized (writeLock) {
                output.writeInt(Integer.reverseBytes(clientId));
                output.flush();
            }
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
                    String message = new String(data, StandardCharsets.UTF_8);
                    handleMessage(message);
                }
            } catch (IOException e) {
                System.out.println("Connection error (id: " + clientId + "): " + e.getMessage());
            } finally {
                shutdown();
            }
        }

        private void handleMessage(String message) {
            if (message.startsWith("pong:")) {
                lastPongTime = System.currentTimeMillis();
                return;
            }

            Map<String, String> params = parseMessage(message);
            String cmd = params.get("cmd");

            switch (cmd) {
                case "send":
                    handleSend(params, message);
                    sendAck();
                    break;
                case "query":
                    handleQuery();
                    break;
                case "info":
                    if (params.containsKey("nam") && !params.get("nam").trim().isEmpty()) {
                        clientParams.put(clientId, params);
                        syncInfoToLobby("info," + formatParams(params));
                    }
                    sendAck();
                    break;
                case "connect":
                    handleConnect(params);
                    break;
                case "start":
                    broadcastLobbyMessage(message);
                    sendAck();
                    break;
                default:
                    sendAck();
                    break;
            }
        }

        private void handleSend(Map<String, String> params, String originalMsg) {
            String fp = params.get("fp");
            String tp = params.get("tp");
            String data = originalMsg.contains("|") ? originalMsg.substring(originalMsg.indexOf('|') + 1) : "";
            String msg = "fc:" + clientId + ",fp:" + fp + ",tp:" + tp + "|" + data;

            String tcStr = params.get("tc");
            if (tcStr == null || tcStr.equals("0")) {
                Set<Integer> sent = new HashSet<>();
                for (int targetId : lobbyConnections.getOrDefault(clientId, Collections.emptySet())) {
                    if (sent.add(targetId)) {
                        ClientHandler target = clients.get(targetId);
                        if (target != null) target.sendInstant(msg);
                    }
                }
            } else {
                int targetId = Integer.parseInt(tcStr);
                ClientHandler target = clients.get(targetId);
                if (target != null) target.sendInstant(msg);
            }
        }

        private void broadcastLobbyMessage(String msg) {
            Set<Integer> sent = new HashSet<>();
            for (int targetId : lobbyConnections.getOrDefault(clientId, Collections.emptySet())) {
                if (sent.add(targetId)) {
                    ClientHandler target = clients.get(targetId);
                    if (target != null) target.sendInstant(msg);
                }
            }
        }

        private void syncInfoToLobby(String msg) {
            for (int targetId : lobbyConnections.getOrDefault(clientId, Collections.emptySet())) {
                ClientHandler target = clients.get(targetId);
                if (target != null) target.sendInstant(msg);
            }
        }

        private void handleQuery() {
            StringBuilder gamelist = new StringBuilder("gamelist:");
            clients.forEach((id, handler) -> {
                Map<String, String> gp = clientParams.get(id);
                if (gp != null && gp.containsKey("nam") && !gp.get("nam").trim().isEmpty()) {
                    gamelist.append("id:").append(id).append(",nam:").append(gp.get("nam")).append("|");
                }
            });
            if (gamelist.length() > 9 && gamelist.charAt(gamelist.length() - 1) == '|') {
                gamelist.setLength(gamelist.length() - 1);
            }
            sendInstant(gamelist.toString());
        }

        private void handleConnect(Map<String, String> params) {
            int hostId = Integer.parseInt(params.get("tc"));
            lobbyConnections.get(clientId).add(hostId);
            lobbyConnections.get(hostId).add(clientId);

            ClientHandler host = clients.get(hostId);
            if (host != null) {
                host.sendInstant("fc:" + clientId + ",fp:" + params.get("fp") + ",tp:" + params.get("tp") + "|!connect!");
            }
            sendAck();
        }

        private void sendAck() {
            sendInstant("ack:ok");
        }

        private void sendInstant(String msg) {
            try {
                byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
                synchronized (writeLock) {
                    output.writeInt(Integer.reverseBytes(msgBytes.length));
                    output.write(msgBytes);
                    output.flush();
                }
            } catch (IOException e) {
                shutdown();
            }
        }

        private void sendPing() {
            sendInstant("ping:" + (++lastPingId));
        }

        private void checkTimeout() {
            if (System.currentTimeMillis() - lastPongTime > PING_TIMEOUT_MS) shutdown();
        }

        private Map<String, String> parseMessage(String msg) {
            Map<String, String> params = new HashMap<>();
            String[] parts = msg.split("[|,]");
            for (String part : parts) {
                String[] kv = part.split(":", 2);
                if (kv.length == 2) params.put(kv[0], kv[1]);
            }
            return params;
        }

        private String formatParams(Map<String, String> params) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (count++ > 0) sb.append(",");
                sb.append(entry.getKey()).append(":").append(entry.getValue());
            }
            return sb.toString();
        }

        private void shutdown() {
            running = false;
            clients.remove(clientId);
            lobbyConnections.remove(clientId);
            scheduler.shutdown();
            try {
                socket.close();
            } catch (IOException ignored) {}
            System.out.println("Client disconnected (id: " + clientId + ")");
        }
    }
}