package com.example.tcpserver;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class TcpServer {

    private static final int PORT = 1611;
    private static final int PING_INTERVAL_MS = 10_000;
    private static final int PING_TIMEOUT_MS  = 15_000;

    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        new TcpServer().start();
    }

    private void start() throws IOException {
        ServerSocket ss = new ServerSocket(PORT);
        log("TCP szerver elindult a " + PORT + "-es porton.");
        while (true) {
            Socket sock = ss.accept();
            sock.setTcpNoDelay(true);
            int id = idGen.incrementAndGet();
            ClientHandler ch = new ClientHandler(sock, id);
            clients.put(id, ch);
            new Thread(ch, "cli-" + id).start();
            log("Kapcsolat: " + sock.getRemoteSocketAddress() + " (id:" + id + ")");
        }
    }

    private void log(String msg) {
        System.out.printf("[%d] %s\n", System.currentTimeMillis(), msg);
    }

    private final class ClientHandler implements Runnable {

        final Socket sock;
        final int id;
        final DataInputStream in;
        final DataOutputStream out;

        final Map<Integer, Integer> connected = new ConcurrentHashMap<>();
        Map<String, String> gameParams = null;

        volatile boolean running = true;
        int pingCnt  = 0;
        int lastPong = 0;

        ClientHandler(Socket s, int id) throws IOException {
            this.sock = s;
            this.id   = id;
            in  = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            sendId();
            startPingLoop();
        }

        private void sendId() throws IOException {
            out.writeInt(Integer.reverseBytes(id));
            out.flush();
            log("[" + id + "] Kiküldve a kliens-ID.");
        }

        private void startPingLoop() {
            Executors.newSingleThreadScheduledExecutor()
                    .scheduleAtFixedRate(this::ping,
                            PING_INTERVAL_MS,
                            PING_INTERVAL_MS,
                            TimeUnit.MILLISECONDS);
        }

        private void ping() {
            if (!running) return;
            send("ping:" + (++pingCnt));
            log("[" + id + "] Ping elküldve, pingCnt=" + pingCnt);
            Executors.newSingleThreadScheduledExecutor()
                    .schedule(() -> {
                        if (lastPong != pingCnt) {
                            log("[" + id + "] PING TIMEOUT (" + lastPong + "!=" + pingCnt + ")");
                            shutdown();
                        }
                    }, PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override public void run() {
            try {
                while (running) {
                    int len = Integer.reverseBytes(in.readInt());
                    long recvStart = System.nanoTime();
                    byte[] buf = in.readNBytes(len);
                    String msg = new String(buf, StandardCharsets.UTF_8);
                    long recvDone = System.nanoTime();
                    log("[" + id + "] BEJÖVŐ (" + len + "b), " +
                            "olvasás: " + (recvDone - recvStart)/1_000_000 + "ms: " + msg);
                    process(msg);
                }
            } catch (IOException e) {
                log("[" + id + "] IOHIBA: " + e);
            }
            finally { shutdown(); }
        }

        private void process(String msg) {
            long procStart = System.nanoTime();
            if (msg.startsWith("pong:")) {
                lastPong = Integer.parseInt(msg.substring(5));
                log("[" + id + "] PONG fogadva: " + lastPong);
                return;
            }
            Map<String, String> p = parse(msg);
            String cmd = p.getOrDefault("cmd", "");
            log("[" + id + "] PARANCS feldolgozás: cmd=" + cmd + " | " + p);
            switch (cmd) {
                case "send"       -> handleSend(p);
                case "info"       -> handleInfo(p);
                case "query"      -> handleQuery(p.getOrDefault("str", ""));
                case "connect"    -> handleConnect(p);
                case "disconnect" -> handleDisconnect(p);
                default -> log("[" + id + "] Ismeretlen parancs vagy nem parancs: " + msg);
            }
            long procDone = System.nanoTime();
            log("[" + id + "] PARANCS feldolgozás vége, idő: " + (procDone - procStart)/1_000_000 + "ms");
        }

        private void handleSend(Map<String, String> p) {
            long sendStart = System.nanoTime();
            if (!p.containsKey("data")) return;
            String pkt = "fc:" + id
                    + ",fp:" + p.get("fp")
                    + ",tp:" + p.get("tp")
                    + "|"   + p.get("data");

            if ("0".equals(p.get("tc"))) {
                clients.values().forEach(c -> {
                    if (c.id != id) {
                        log("[" + id + "] Broadcastolom SEND-et a " + c.id + "-nek, tartalom: " + pkt);
                        c.send(pkt);
                    }
                });
                send("ack:ok");
            } else {
                int tc = Integer.parseInt(p.get("tc"));
                ClientHandler trg = clients.get(tc);
                if (trg != null) {
                    log("[" + id + "] SEND célzottan " + tc + "-nek: " + pkt);
                    trg.send(pkt);
                    send("ack:ok");
                }
                else {
                    log("[" + id + "] SEND error, cél kliens nem él: " + tc);
                    send("ack:error");
                }
            }
            long sendDone = System.nanoTime();
            log("[" + id + "] handleSend teljes idő: " + (sendDone - sendStart)/1_000_000 + "ms");
        }

        private void handleInfo(Map<String, String> p) {
            log("[" + id + "] INFO fogadva: " + p);
            if (p.containsKey("nam") && !p.get("nam").isBlank()) {
                gameParams = new HashMap<>();
                p.forEach((k, v) -> {
                    if (!"cmd".equals(k))
                        gameParams.put(k, k.equals("nam")
                                ? new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8)
                                : v);
                });
                log("[" + id + "] INFO lobby metaadat frissítve: " + gameParams);
            }
        }

        private void handleQuery(String search) {
            log("[" + id + "] QUERY keresés: '" + search + "'");
            String q = search.toLowerCase();
            List<String> list = new ArrayList<>();
            clients.values().stream()
                    .filter(c -> c.gameParams != null
                            && c.gameParams.containsKey("nam")
                            && !c.gameParams.get("nam").isBlank())
                    .filter(c -> c.gameParams.get("nam").toLowerCase().contains(q))
                    .limit(100)
                    .forEach(c -> {
                        Map<String, String> gp = new HashMap<>(c.gameParams);
                        gp.put("id",  String.valueOf(c.id));
                        gp.put("sti", String.valueOf(
                                c.gameParams.get("nam").toLowerCase().indexOf(q)));
                        gp.put("nam", Base64.getEncoder().encodeToString(
                                c.gameParams.get("nam").getBytes(StandardCharsets.UTF_8)));
                        list.add(flat(gp));
                    });

            log("[" + id + "] QUERY találatok: " + list.size());
            send("gamelist:" + String.join("|", list));
        }

        private void handleConnect(Map<String, String> p) {
            int tc = Integer.parseInt(p.get("tc"));
            ClientHandler trg = clients.get(tc);
            if (trg != null) {
                log("[" + id + "] CONNECT → host: " + tc);
                connected.put(tc, 1);
                trg.connected.put(id, 1);
                trg.send("fc:" + id
                        + ",fp:" + p.get("fp")
                        + ",tp:" + p.get("tp")
                        + "|!connect!");
                send("ack:ok");
            } else {
                log("[" + id + "] CONNECT error, nincs host: " + tc);
                send("ack:error");
            }
        }

        private void handleDisconnect(Map<String, String> p) {
            int fc = Integer.parseInt(p.getOrDefault("fc", "0"));

            if (fc == 0) {
                connected.keySet().forEach(pid -> {
                    ClientHandler c = clients.get(pid);
                    if (c != null) c.connected.remove(id);
                });
                connected.clear();
                gameParams = null;
                log("[" + id + "] TELJES KILÉPÉS, lobby törölve");
                send("ack:ok");
                return;
            }

            ClientHandler peer = clients.get(fc);
            if (peer != null) {
                connected.remove(fc);
                peer.connected.remove(id);
                peer.send("disconnected:" + id);
                log("[" + id + "] DISCONNECT részleges, peer=" + fc);
            }
            send("ack:ok");
        }

        private Map<String, String> parse(String s) {
            Map<String, String> m = new HashMap<>();
            int i = s.indexOf('|');
            String head = (i >= 0) ? s.substring(0, i) : s;
            for (String part : head.split(",")) {
                String[] kv = part.split(":", 2);
                if (kv.length == 2) m.put(kv[0], kv[1]);
            }
            if (i >= 0) m.put("data", s.substring(i + 1));
            return m;
        }

        private String flat(Map<String, String> m) {
            return m.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(","));
        }

        private synchronized void send(String msg) {
            long start = System.nanoTime();
            try {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                out.writeInt(Integer.reverseBytes(b.length));
                out.write(b);
                out.flush();
                long dur = (System.nanoTime() - start) / 1_000_000;
                log("[" + id + "] KÜLDÉS (" + msg.substring(0, Math.min(msg.length(), 80)) + ") " +
                        "ideje: " + dur + "ms");
            } catch (IOException e) {
                log("[" + id + "] KÜLDÉSI HIBA: " + e);
                shutdown();
            }
        }

        private void shutdown() {
            running = false;
            connected.keySet().forEach(pid -> {
                ClientHandler p = clients.get(pid);
                if (p != null) {
                    p.connected.remove(id);
                    p.send("disconnected:" + id);
                }
            });
            clients.remove(id);
            try { sock.close(); } catch (IOException ignored) { }
            log("Kapcsolat bontva (id:" + id + ")");
        }
    }
}
