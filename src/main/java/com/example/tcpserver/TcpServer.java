package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Imperium Galactica II – stabil TCP lobby- és játék-szerver (Java 23).
 *
 * Főbb jellemzők
 *  • Azonnali ack:ok a kliensnek   → megszünteti a dead-lockot.
 *  • 10 ms-cel késleltetett broadcast   → nem árasztjuk el a klienst.
 *  • Peer-enként 50 ms rate-limit (≈20 pkt/s)   → stabil sávszél-használat.
 *  • Üres/hibás lobby-név kiszűrése   → nincs „névtelen” lobby-bejegyzés.
 *  • Lobby-tagok kölcsönös info-szinkronja (név, faj)   → „computer” név eltűnik.
 *  • Tick-echo (50 ms) csak heartbeat-re   → kliens nem érzi magát „egyedül”.
 */
public final class TcpServer {

    /* konfiguráció */
    private static final int PORT           = 1611;
    private static final int RATE_LIMIT_MS  = 50;   // peer-enkénti küldési limit
    private static final int HEARTBEAT_MS   = 50;   // tick-echo intervallum

    /* globális állapot */
    private final Map<Integer, ClientHandler>                clients          = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, String>>          clientParams     = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>>                 lobbyConnections = new ConcurrentHashMap<>();
    private int clientIdCounter = 0;

    /* ─────────────────────────────────────────  belépési pont  */
    public static void main(String[] args) {
        try {
            new TcpServer().start();
        } catch (IOException ex) {
            System.err.println("Szerver nem indítható: " + ex.getMessage());
        }
    }

    /* ─────────────────────────────────────────  fő accept-ciklus  */
    public void start() throws IOException {

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("TCP szerver elindult a " + PORT + "-es porton.");

        /* 50 ms-enként heartbeat minden peer-párnak  */
        ScheduledExecutorService tick = Executors.newSingleThreadScheduledExecutor();
        tick.scheduleAtFixedRate(this::sendHeartbeats,
                1000, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

        while (true) {                         // végtelen accept-ciklus
            Socket sock = serverSocket.accept();
            sock.setTcpNoDelay(true);

            int id = ++clientIdCounter;
            ClientHandler ch = new ClientHandler(sock, id);
            clients.put(id, ch);
            lobbyConnections.put(id, ConcurrentHashMap.newKeySet());

            new Thread(ch, "cli-" + id).start();
            System.out.println("Új kapcsolat: " + sock.getRemoteSocketAddress() + " (id:" + id + ")");
        }
    }

    /* ─────────────────────────────────  HEARTBEAT minden peernek  */
    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        for (ClientHandler sender : clients.values()) {
            Set<Integer> targets = lobbyConnections.getOrDefault(sender.clientId, Set.of());
            for (Integer tid : targets) {
                if (tid.equals(sender.clientId)) continue;
                ClientHandler trg = clients.get(tid);
                if (trg != null) {
                    String hb = "fc:" + sender.clientId + ",fp:0,tp:0|" + now;
                    trg.send(hb);
                }
            }
        }
    }

    /* ════════════════════════════════  BELSŐ OSZTÁLY  ════════════════════════════════ */
    private final class ClientHandler implements Runnable {

        private final Socket socket;
        private final int    clientId;
        private final DataInputStream  in;
        private final DataOutputStream out;

        private final Map<Integer, Long> lastSent = new ConcurrentHashMap<>();
        private final Object writeLock = new Object();
        private final ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor();
        private volatile boolean running = true;

        /* konstruktor */
        ClientHandler(Socket s, int id) throws IOException {
            socket   = s;
            clientId = id;
            in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            sendClientId();
        }

        private void sendClientId() throws IOException {
            synchronized (writeLock) {
                out.writeInt(Integer.reverseBytes(clientId));
                out.flush();
            }
        }

        /* fő olvasó ciklus */
        @Override public void run() {
            try {
                while (running) {
                    int len = Integer.reverseBytes(in.readInt());
                    byte[] buf = in.readNBytes(len);
                    process(new String(buf, StandardCharsets.UTF_8));
                }
            } catch (IOException ex) {
                System.out.println("Kapcsolathiba (id:" + clientId + "): " + ex.getMessage());
            } finally { shutdown(); }
        }

        /* ─── üzenetek feldolgozása */
        private void process(String msg) {
            if (msg.startsWith("pong:")) return;            // ping-pongra most nem reagálunk külön

            Map<String,String> p = parse(msg);
            String cmd = p.get("cmd");
            if (cmd == null) return;

            switch (cmd) {
                case "send"       -> handleSend(p, msg);
                case "query"      -> handleQuery(p.getOrDefault("str", ""));
                case "info"       -> handleInfo(p);
                case "connect"    -> handleConnect(p);
                case "disconnect" -> handleDisconnect(p);
                default           -> { /* ismeretlen parancs – ignoráljuk */ }
            }
        }

        /* info – lobby meta (név, faj) */
        private void handleInfo(Map<String,String> p) {
            Map<String,String> inf = decodeName(p);
            if (inf.getOrDefault("nam", "").isBlank()) return;
            clientParams.put(clientId, inf);

            // broadcast minden lobby-tag felé
            String pkt = "info," + flat(inf);
            lobbyConnections.getOrDefault(clientId, Set.of()).forEach(tid -> {
                if (tid != clientId) {
                    ClientHandler trg = clients.get(tid);
                    if (trg != null) trg.send(pkt);
                }
            });
        }

        /* lobby listázás */
        private void handleQuery(String search) {
            String s = search.toLowerCase();
            List<String> list = new ArrayList<>();
            for (var e : clientParams.entrySet()) {
                String name = e.getValue().getOrDefault("nam", "");
                int pos = name.toLowerCase().indexOf(s);
                if (pos >= 0) {
                    Map<String,String> c = new HashMap<>(e.getValue());
                    c.put("id", String.valueOf(e.getKey()));
                    c.put("sti", String.valueOf(pos));
                    c.put("nam", Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)));
                    list.add(flat(c));
                    if (list.size() == 100) break;
                }
            }
            send("gamelist:" + String.join("|", list));
        }

        /* kliens -> host csatlakozás */
        private void handleConnect(Map<String,String> p) {
            int hostId = Integer.parseInt(p.getOrDefault("tc", "0"));
            if (hostId == 0 || hostId == clientId) { send("ack:error"); return; }

            ClientHandler host = clients.get(hostId);
            if (host == null) { send("ack:error"); return; }

            lobbyConnections.get(clientId).add(hostId);
            lobbyConnections.get(hostId).add(clientId);

            // értesítjük a hostot
            host.send("fc:"+clientId+",fp:"+p.get("fp")+",tp:"+p.get("tp")+"|!connect!");

            // host infó -> kliens
            Optional.ofNullable(clientParams.get(hostId)).ifPresent(info ->
                    send("info," + flat(info)));

            // új kliens infó -> host
            Optional.ofNullable(clientParams.get(clientId)).ifPresent(info ->
                    host.send("info," + flat(info)));

            send("ack:ok");
        }

        /* kapcsolat bontása */
        private void handleDisconnect(Map<String,String> p) {
            int peer = Integer.parseInt(p.getOrDefault("fc", "0"));

            lobbyConnections.getOrDefault(clientId, Set.of()).remove(peer);
            lobbyConnections.getOrDefault(peer, Set.of()).remove(clientId);

            ClientHandler ch = clients.get(peer);
            if (ch != null) ch.send("disconnected:" + clientId);

            send("ack:ok");
        }

        /* játék közbeni adat (parancs) */
        private void handleSend(Map<String,String> p, String raw) {
            String fp = p.get("fp"), tp = p.get("tp");
            String data = raw.contains("|") ? raw.substring(raw.indexOf('|')+1) : "";
            String packet = "fc:"+clientId+",fp:"+fp+",tp:"+tp+"|"+data;
            String tc = p.get("tc");
            send("ack:ok");                                 // azonnali ack a kliensnek

            Runnable task = () -> {
                long now = System.currentTimeMillis();
                if (tc == null || "0".equals(tc)) {
                    for (int tgt : lobbyConnections.getOrDefault(clientId, Set.of())) {
                        if (tgt == clientId) continue;
                        if (rateLimit(tgt, now)) clients.get(tgt).send(packet);
                    }
                } else {
                    int tgt = Integer.parseInt(tc);
                    if (tgt != clientId && rateLimit(tgt, now)) {
                        Optional.ofNullable(clients.get(tgt)).ifPresent(ch -> ch.send(packet));
                    }
                }
            };
            delayed.schedule(task, 10, TimeUnit.MILLISECONDS);
        }

        /* ───── segédek ───── */
        private boolean rateLimit(int tgt, long now) {
            long last = lastSent.getOrDefault(tgt, 0L);
            if (now - last >= RATE_LIMIT_MS) { lastSent.put(tgt, now); return true; }
            return false;
        }

        private Map<String,String> parse(String msg) {
            Map<String,String> m = new HashMap<>();
            String[] parts = msg.split("\\|", 2);
            for (String part : parts[0].split(",")) {
                String[] kv = part.split(":", 2);
                if (kv.length == 2) m.put(kv[0], kv[1]);
            }
            if (parts.length == 2) m.put("data", parts[1]);
            return m;
        }

        private Map<String,String> decodeName(Map<String,String> m) {
            if (m.containsKey("nam")) {
                try {
                    String d = new String(Base64.getDecoder().decode(m.get("nam")),
                            StandardCharsets.UTF_8);
                    m.put("nam", d);
                } catch (IllegalArgumentException ignored) {}
            }
            return m;
        }

        private String flat(Map<String,String> m) {
            return m.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(","));
        }

        private void send(String msg) {
            try {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                synchronized (writeLock) {
                    out.writeInt(Integer.reverseBytes(b.length));
                    out.write(b);
                    out.flush();
                }
            } catch (IOException ex) { shutdown(); }
        }

        private void shutdown() {
            running = false;
            clients.remove(clientId);
            lobbyConnections.remove(clientId);
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("Kliense kapcsolat bontva (id:" + clientId + ")");
        }
    }
}
