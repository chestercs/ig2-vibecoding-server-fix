package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Stable TCP lobby‑ és matchmaking‑szerver Imperium Galactica II‑höz.
 *
 * ──────────────────────────────────────────────────────────────
 * Áttekintés
 *  • Azonnali ack:ok a kliensnek (dead‑lock megszüntetése).
 *  • 10 ms‑cel késleltetett broadcast, hogy ne áraszszuk el a klienst.
 *  • Per‑cél rate‑limit 50 ms (max. 20 pkt/s per peer).
 *  • Üres vagy hibás lobby‑név sosem kerül a listába.
 *  • Stabil connect / disconnect / reconnect kezelése.
 *
 *  (A korábbi 50 ms‑es tick‑szinkron ciklust eltávolítottuk, mert lobby‑fagyást okozott.)
 * ──────────────────────────────────────────────────────────────
 */
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

    //─────────────────────────────────────────────────  SERVER LOOP
    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("TCP server listening on port " + PORT);

        // ───── Tick‑echo: 50 ms‑enként minden aktív peernek küldünk egy üres "fc" csomagot,
        // hogy a kliens ne érezze úgy, hogy egyedül maradt és ne dobja le a kapcsolatot a játék indításakor.
        ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (ClientHandler sender : clients.values()) {
                for (Integer targetId : lobbyConnections.getOrDefault(sender.clientId, Set.of())) {
                    if (targetId.equals(sender.clientId)) continue;
                    ClientHandler target = clients.get(targetId);
                    if (target != null) {
                        String hb = "fc:" + sender.clientId + ",fp:0,tp:0|" + now;
                        target.send(hb);
                    }
                }
            }
        }, 1000, 50, TimeUnit.MILLISECONDS);

        while (true) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            int clientId = ++clientIdCounter;
            ClientHandler handler = new ClientHandler(socket, clientId);
            clients.put(clientId, handler);
            lobbyConnections.put(clientId, ConcurrentHashMap.newKeySet());
            new Thread(handler).start();
            System.out.println("New connection from " + socket.getRemoteSocketAddress() + " (id:" + clientId + ")");
        }
    }

    //───────────────────────────────────────────────  CLIENT HANDLER
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final int clientId;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final Map<Integer, Long> lastSent = new ConcurrentHashMap<>();
        private final Object writeLock = new Object();
        private volatile boolean running = true;
        private final ScheduledExecutorService delayedSender = Executors.newSingleThreadScheduledExecutor();

        ClientHandler(Socket s, int id) throws IOException {
            socket = s; clientId = id;
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            sendClientId();
        }

        private void sendClientId() throws IOException {
            synchronized (writeLock) {
                out.writeInt(Integer.reverseBytes(clientId));
                out.flush();
            }
        }

        @Override public void run() {
            try {
                while (running) {
                    int size = Integer.reverseBytes(in.readInt());
                    byte[] buf = new byte[size];
                    in.readFully(buf);
                    process(new String(buf, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                System.out.println("Connection error (id:" + clientId + "): " + e.getMessage());
            } finally { shutdown(); }
        }

        //─────────────────────────────────────────  MESSAGE DISPATCH
        private void process(String msg) {
            if (msg.startsWith("pong:")) return; // ping‑pong kezelése máshol nem kell

            Map<String,String> p = parse(msg);
            String cmd = p.get("cmd");
            if (cmd == null) return;

            switch (cmd) {
                case "send"      -> handleSend(p, msg);
                case "query"     -> handleQuery(p.getOrDefault("str", ""));
                case "info"      -> handleInfo(p);
                case "connect"   -> handleConnect(p);
                case "disconnect"-> handleDisconnect(p);
            }
        }

        //────────────────────────────────────  cmd:info  (lobby meta)
        private void handleInfo(Map<String,String> p) {
            Map<String,String> decoded = decodeName(p);
            if (decoded.getOrDefault("nam", "").isBlank()) return; // üres név → ignoráljuk
            clientParams.put(clientId, decoded);
        }

        //────────────────────────────────────────────  cmd:query
        private void handleQuery(String search) {
            String s = search.toLowerCase();
            List<String> res = new ArrayList<>();
            for (var e : clientParams.entrySet()) {
                String name = e.getValue().getOrDefault("nam", "");
                int pos = name.toLowerCase().indexOf(s);
                if (pos >= 0) {
                    Map<String,String> copy = new HashMap<>(e.getValue());
                    copy.put("id", String.valueOf(e.getKey()));
                    copy.put("sti", String.valueOf(pos));
                    copy.put("nam", Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)));
                    res.add(flat(copy));
                    if (res.size() >= 100) break;
                }
            }
            send("gamelist:" + String.join("|", res));
        }

        //────────────────────────────────────────────  cmd:connect
        private void handleConnect(Map<String,String> p) {
            int targetId = Integer.parseInt(p.getOrDefault("tc", "0"));
            if (targetId == 0 || targetId == clientId) { send("ack:error"); return; }
            ClientHandler target = clients.get(targetId);
            if (target == null) { send("ack:error"); return; }
            lobbyConnections.get(clientId).add(targetId);
            lobbyConnections.get(targetId).add(clientId);
            target.send("fc:"+clientId+",fp:"+p.get("fp")+",tp:"+p.get("tp")+"|!connect!");
            send("ack:ok");
        }

        //───────────────────────────────────────────  cmd:disconnect
        private void handleDisconnect(Map<String,String> p) {
            int peer = Integer.parseInt(p.getOrDefault("fc","0"));

            // biztonságosan távolítjuk el a kapcsolatot anélkül, hogy Immutable set‑hez nyúlnánk
            Set<Integer> mySet   = lobbyConnections.get(clientId);
            if (mySet != null)   mySet.remove(peer);

            Set<Integer> peerSet = lobbyConnections.get(peer);
            if (peerSet != null) peerSet.remove(clientId);

            ClientHandler ch = clients.get(peer);
            if (ch != null) ch.send("disconnected:"+clientId);
            send("ack:ok");
        }

        //──────────────────────────────────────────────  cmd:send
        private void handleSend(Map<String,String> p, String original) {
            String fp = p.get("fp"), tp = p.get("tp");
            String data = original.contains("|") ? original.substring(original.indexOf('|')+1) : "";
            String packet = "fc:"+clientId+",fp:"+fp+",tp:"+tp+"|"+data;
            String tc = p.get("tc");
            send("ack:ok"); // azonnali ack

            Runnable task = () -> {
                long now = System.currentTimeMillis();
                if (tc == null || "0".equals(tc)) {
                    for (int t : lobbyConnections.getOrDefault(clientId, Set.of())) {
                        if (t == clientId) continue;
                        if (rateLimit(t, now)) clients.get(t).send(packet);
                    }
                } else {
                    int t = Integer.parseInt(tc);
                    if (t != clientId && rateLimit(t, now)) {
                        ClientHandler trg = clients.get(t);
                        if (trg != null) trg.send(packet);
                    }
                }
            };
            delayedSender.schedule(task, 10, TimeUnit.MILLISECONDS);
        }

        //──────────────────────────────────────────────────── HELPERS
        private boolean rateLimit(int target, long now) {
            long last = lastSent.getOrDefault(target, 0L);
            if (now - last >= RATE_LIMIT_MS) { lastSent.put(target, now); return true; }
            return false;
        }

        private Map<String,String> parse(String msg) {
            Map<String,String> m = new HashMap<>();
            String[] sec = msg.split("\\|",2);
            for (String e : sec[0].split(",")) {
                String[] kv = e.split(":",2);
                if (kv.length==2) m.put(kv[0], kv[1]);
            }
            if (sec.length==2) m.put("data", sec[1]);
            return m;
        }

        private Map<String,String> decodeName(Map<String,String> m) {
            if (m.containsKey("nam")) {
                try {
                    String d = new String(Base64.getDecoder().decode(m.get("nam")), StandardCharsets.UTF_8);
                    m.put("nam", d);
                } catch (IllegalArgumentException ignored) {}
            }
            return m;
        }

        private String flat(Map<String,String> m) {
            return m.entrySet().stream().map(e -> e.getKey()+":"+e.getValue()).
                    collect(Collectors.joining(","));
        }

        private void send(String msg) {
            try {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                synchronized (writeLock) {
                    out.writeInt(Integer.reverseBytes(b.length));
                    out.write(b); out.flush();
                }
            } catch (IOException e) { shutdown(); }
        }

        private void shutdown() {
            running = false;
            clients.remove(clientId);
            lobbyConnections.remove(clientId);
            try { socket.close(); } catch (IOException ignored){}
            System.out.println("Client disconnected (id:"+clientId+")");
        }
    }
}
