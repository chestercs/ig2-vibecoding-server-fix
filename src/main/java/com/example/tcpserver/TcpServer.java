package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class TcpServer {
    private static final int PORT = 1611;
    private static final int RATE_LIMIT_MS = 50;
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, String>> clientParams = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> lobbyConnections = new ConcurrentHashMap<>();
    private int clientIdCounter = 0;

    public static void main(String[] args) throws IOException {
        new TcpServer().start();
    }

    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("TCP server listening on port " + PORT);

        ScheduledExecutorService tickScheduler = Executors.newSingleThreadScheduledExecutor();
        tickScheduler.scheduleAtFixedRate(() -> {
            for (ClientHandler sender : clients.values()) {
                for (Integer targetId : lobbyConnections.getOrDefault(sender.clientId, Collections.emptySet())) {
                    if (targetId.equals(sender.clientId)) continue;
                    ClientHandler target = clients.get(targetId);
                    if (target != null) {
                        String msg = "fc:" + sender.clientId + ",fp:0,tp:0|.";
                        target.sendString(msg);
                    }
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

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
        private final Map<Integer, Long> lastSentToClient = new ConcurrentHashMap<>();
        private final Object writeLock = new Object();
        private volatile boolean running = true;

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
            if (message.startsWith("pong:")) return;

            Map<String, String> params = parseMessage(message);
            String cmd = params.get("cmd");

            switch (cmd) {
                case "send" -> handleSend(params, message);
                case "query" -> handleQuery(params.getOrDefault("str", ""));
                case "info" -> clientParams.put(clientId, decodeBase64Name(params));
                case "connect" -> handleConnect(params);
                case "disconnect" -> handleDisconnect(params);
            }
        }

        private void handleSend(Map<String, String> params, String originalMsg) {
            String fp = params.get("fp");
            String tp = params.get("tp");
            String data = originalMsg.contains("|") ? originalMsg.substring(originalMsg.indexOf('|') + 1) : "";
            String msg = "fc:" + clientId + ",fp:" + fp + ",tp:" + tp + "|" + data;
            String tcStr = params.get("tc");
            sendString("ack:ok");

            Runnable broadcast = () -> {
                long now = System.currentTimeMillis();
                if (tcStr == null || tcStr.equals("0")) {
                    for (int targetId : lobbyConnections.getOrDefault(clientId, Collections.emptySet())) {
                        if (targetId == clientId) continue;
                        if (rateLimitPassed(targetId, now)) {
                            ClientHandler target = clients.get(targetId);
                            if (target != null) target.sendString(msg);
                        }
                    }
                } else {
                    int targetId = Integer.parseInt(tcStr);
                    if (targetId != clientId && rateLimitPassed(targetId, now)) {
                        ClientHandler target = clients.get(targetId);
                        if (target != null) target.sendString(msg);
                    }
                }
            };

            Executors.newSingleThreadScheduledExecutor().schedule(broadcast, 10, TimeUnit.MILLISECONDS);
        }

        private void handleQuery(String search) {
            List<String> results = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, String>> entry : clientParams.entrySet()) {
                Map<String, String> gp = entry.getValue();
                String name = gp.getOrDefault("nam", "").toLowerCase();
                if (name.contains(search.toLowerCase())) {
                    Map<String, String> copy = new HashMap<>(gp);
                    copy.put("id", entry.getKey().toString());
                    copy.put("sti", String.valueOf(name.indexOf(search.toLowerCase())));
                    copy.put("nam", Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)));
                    results.add(flatten(copy));
                    if (results.size() >= 100) break;
                }
            }
            sendString("gamelist:" + String.join("|", results));
        }

        private void handleConnect(Map<String, String> params) {
            int targetId = Integer.parseInt(params.getOrDefault("tc", "0"));
            if (targetId == 0) {
                sendString("ack:error");
                return;
            }
            ClientHandler target = clients.get(targetId);
            if (target != null) {
                lobbyConnections.get(clientId).add(targetId);
                lobbyConnections.get(targetId).add(clientId);
                target.sendString("fc:" + clientId + ",fp:" + params.get("fp") + ",tp:" + params.get("tp") + "|!connect!");
                sendString("ack:ok");
            } else {
                sendString("ack:error");
            }
        }

        private void handleDisconnect(Map<String, String> params) {
            int peerId = Integer.parseInt(params.getOrDefault("fc", "0"));
            lobbyConnections.getOrDefault(clientId, new HashSet<>()).remove(peerId);
            lobbyConnections.getOrDefault(peerId, new HashSet<>()).remove(clientId);
            ClientHandler peer = clients.get(peerId);
            if (peer != null) {
                peer.sendString("disconnected:" + clientId);
            }
            sendString("ack:ok");
        }

        private boolean rateLimitPassed(int targetId, long now) {
            long last = lastSentToClient.getOrDefault(targetId, 0L);
            if (now - last >= RATE_LIMIT_MS) {
                lastSentToClient.put(targetId, now);
                return true;
            }
            return false;
        }

        private Map<String, String> parseMessage(String msg) {
            Map<String, String> params = new HashMap<>();
            String[] sections = msg.split("\\|", 2);
            String[] entries = sections[0].split(",");
            for (String entry : entries) {
                String[] kv = entry.split(":", 2);
                if (kv.length == 2) params.put(kv[0], kv[1]);
            }
            if (sections.length == 2) params.put("data", sections[1]);
            return params;
        }

        private Map<String, String> decodeBase64Name(Map<String, String> params) {
            if (params.containsKey("nam")) {
                try {
                    String decoded = new String(Base64.getDecoder().decode(params.get("nam")), StandardCharsets.UTF_8);
                    params.put("nam", decoded);
                } catch (IllegalArgumentException ignored) {}
            }
            return params;
        }

        private String flatten(Map<String, String> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                parts.add(entry.getKey() + ":" + entry.getValue());
            }
            return String.join(",", parts);
        }

        private void sendString(String msg) {
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

        private void shutdown() {
            running = false;
            clients.remove(clientId);
            lobbyConnections.remove(clientId);
            try {
                socket.close();
            } catch (IOException ignored) {}
            System.out.println("Client disconnected (id: " + clientId + ")");
        }
    }
}