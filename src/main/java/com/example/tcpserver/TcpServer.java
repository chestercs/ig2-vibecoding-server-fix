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
    private static final int RATE_LIMIT_MS = 50;

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
        private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private volatile int lastPingId = 0;
        private volatile long lastPongTime = System.currentTimeMillis();
        private long lastBroadcastTime = 0;

        ClientHandler(Socket socket, int clientId) throws IOException {
            this.socket = socket;
            this.clientId = clientId;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
            sendClientId();
        }

        private void sendClientId() throws IOException {
            output.writeInt(Integer.reverseBytes(clientId));
            output.flush();
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

        private void handleMessage(String message) throws IOException {
            if (message.startsWith("pong:")) {
                lastPongTime = System.currentTimeMillis();
                return;
            }

            Map<String, String> params = parseMessage(message);
            String cmd = params.get("cmd");

            switch (cmd) {
                case "send":
                    handleSend(params, message.substring(message.indexOf('|') + 1));
                    break;
                case "query":
                    handleQuery(params);
                    break;
                case "info":
                    handleInfo(params);
                    sendAck();
                    break;
                case "connect":
                    handleConnect(params);
                    break;
                case "start":
                    broadcastMessage(clientId, message);
                    break;
                default:
                    sendAck();
                    break;
            }
        }

        private void handleSend(Map<String, String> params, String data) throws IOException {
            sendAck();
            broadcastMessage(clientId, "fc:" + clientId + ",fp:" + params.get("fp") + ",tp:" + params.get("tp") + "|" + data);
        }

        private void broadcastMessage(int senderId, String msg) {
            lobbyConnections.get(senderId).forEach(targetId -> {
                ClientHandler target = clients.get(targetId);
                if (target != null) target.sendMessage(msg);
            });
        }

        private void handleQuery(Map<String, String> params) throws IOException {
            StringBuilder gamelist = new StringBuilder("gamelist:");

            clients.forEach((id, handler) -> {
                Map<String, String> gp = clientParams.get(id);
                if (gp != null && gp.containsKey("nam")) {
                    gamelist.append("id:").append(id).append(",nam:").append(gp.get("nam")).append("|");
                }
            });

            if (gamelist.charAt(gamelist.length() - 1) == '|') gamelist.setLength(gamelist.length() - 1);
            sendMessage(gamelist.toString());
        }

        private void handleInfo(Map<String, String> params) {
            clientParams.put(clientId, params);
        }

        private void handleConnect(Map<String, String> params) {
            int hostId = Integer.parseInt(params.get("tc"));
            lobbyConnections.get(hostId).add(clientId);
            lobbyConnections.get(clientId).add(hostId);
            ClientHandler host = clients.get(hostId);
            if (host != null) host.sendMessage("fc:" + clientId + "|!connect!");
            sendAck();
        }

        private void sendPing() {
            sendMessage("ping:" + (++lastPingId));
        }

        private void checkTimeout() {
            if (System.currentTimeMillis() - lastPongTime > PING_TIMEOUT_MS) shutdown();
        }

        private void sendAck() {
            sendMessage("ack:ok");
        }

        private void sendMessage(String msg) {
            try {
                byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
                output.writeInt(Integer.reverseBytes(msgBytes.length));
                output.write(msgBytes);
                output.flush();
            } catch (IOException e) {
                shutdown();
            }
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

        private void shutdown() {
            running = false;
            clients.remove(clientId);
            lobbyConnections.remove(clientId);
            scheduler.shutdown();
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("Client disconnected (id: " + clientId + ")");
        }
    }
}
