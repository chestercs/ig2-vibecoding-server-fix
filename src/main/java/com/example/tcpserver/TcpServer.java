package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Imperium Galactica II – matchmaking-szerver (Java 23)
 * 1-az-1-ben a hivatalos Node.js logikára portolva, kiegészítve:
 *   • üres lobbyk kiszűrése
 *   • szellemlobbyk eltávolítása kilépéskor
 *   • reconnect lehetőség megőrzése
 *   • ping / pong timeout-kezelés
 */
public final class TcpServer {

    /* konfiguráció */
    private static final int PORT             = 1611;
    private static final int PING_INTERVAL_MS = 10_000;
    private static final int PING_TIMEOUT_MS  = 15_000;

    /* globális állapot */
    private final Map<Integer, ClientHandler> clients  = new ConcurrentHashMap<>();
    private final AtomicInteger               idGen    = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        new TcpServer().start();
    }

    /* fő accept-ciklus */
    private void start() throws IOException {
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("TCP szerver elindult a " + PORT + "-es porton.");
        while (true) {
            Socket sock = ss.accept();
            sock.setTcpNoDelay(true);
            int id = idGen.incrementAndGet();
            ClientHandler ch = new ClientHandler(sock, id);
            clients.put(id, ch);
            new Thread(ch, "cli-" + id).start();
            System.out.println("Kapcsolat: " + sock.getRemoteSocketAddress() + " (id:" + id + ")");
        }
    }

    /* ═══════════════════ klienskezelő ═══════════════════ */
    private final class ClientHandler implements Runnable {

        final Socket sock;
        final int    id;
        final DataInputStream  in;
        final DataOutputStream out;

        /** az adott klienshez kapcsolódó peer-ID-k (host-vendég viszony) */
        final Map<Integer, Integer> connected = new ConcurrentHashMap<>();
        /** lobby / játék metaadatai (pl. nam) */
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
            /* timeout watchdog */
            Executors.newSingleThreadScheduledExecutor()
                    .schedule(() -> { if (lastPong != pingCnt) shutdown(); },
                            PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override public void run() {
            try {
                while (running) {
                    int len = Integer.reverseBytes(in.readInt());
                    byte[] buf = in.readNBytes(len);
                    process(new String(buf, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) { }
            finally { shutdown(); }
        }

        /* üzenet-feldolgozás */
        private void process(String msg) {
            if (msg.startsWith("pong:")) {
                lastPong = Integer.parseInt(msg.substring(5));
                return;
            }
            Map<String, String> p = parse(msg);
            switch (p.getOrDefault("cmd", "")) {
                case "send"       -> handleSend(p);
                case "info"       -> handleInfo(p);
                case "query"      -> handleQuery(p.getOrDefault("str", ""));
                case "connect"    -> handleConnect(p);
                case "disconnect" -> handleDisconnect(p);
            }
        }

        /* -------- Node.js parancsok portja -------- */

        /** játék közbeni adat */
        private void handleSend(Map<String, String> p) {
            if (!p.containsKey("data")) return;
            String pkt = "fc:" + id
                    + ",fp:" + p.get("fp")
                    + ",tp:" + p.get("tp")
                    + "|"   + p.get("data");

            if ("0".equals(p.get("tc"))) {
                clients.values().forEach(c -> { if (c.id != id) c.send(pkt); });
                send("ack:ok");
            } else {
                ClientHandler trg = clients.get(Integer.parseInt(p.get("tc")));
                if (trg != null) { trg.send(pkt); send("ack:ok"); }
                else             { send("ack:error"); }
            }
        }

        /** lobby metaadat (név, faj) */
        private void handleInfo(Map<String, String> p) {
            gameParams = new HashMap<>();
            p.forEach((k, v) -> { if (!"cmd".equals(k)) gameParams.put(k, v); });
            if (gameParams.containsKey("nam"))
                gameParams.put("nam",
                        new String(Base64.getDecoder().decode(gameParams.get("nam")),
                                StandardCharsets.UTF_8));
        }

        /** lobby lekérdezés */
        private void handleQuery(String search) {
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

            send("gamelist:" + String.join("|", list));
        }

        /** vendég csatlakozás a hosthoz */
        private void handleConnect(Map<String, String> p) {
            int tc = Integer.parseInt(p.get("tc"));
            ClientHandler trg = clients.get(tc);
            if (trg != null) {
                connected.put(tc, 1);
                trg.connected.put(id, 1);
                trg.send("fc:" + id
                        + ",fp:" + p.get("fp")
                        + ",tp:" + p.get("tp")
                        + "|!connect!");
                send("ack:ok");
            } else send("ack:error");
        }

        /** kapcsolat bontása */
        private void handleDisconnect(Map<String, String> p) {
            int fc = Integer.parseInt(p.getOrDefault("fc", "0"));

            if (fc == 0) {
                // teljes lekapcsolódás (host tényleg kilép)
                connected.keySet().forEach(k -> {
                    ClientHandler trg = clients.get(k);
                    if (trg != null) {
                        trg.connected.remove(id);
                        trg.send("disconnected:" + id);
                    }
                });
                connected.clear();

                // Itt TÖRÖLD a gameParams-ot, csak ilyenkor!
                gameParams = null;
                send("ack:ok");
                return;
            }

            // részleges bontás (guest lép ki)
            ClientHandler trg = clients.get(fc);
            if (trg != null) {
                connected.remove(fc);
                trg.connected.remove(id);
                trg.send("disconnected:" + id);
            }
            send("ack:ok");
        }


        /* ---- util ---- */

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
            try {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                out.writeInt(Integer.reverseBytes(b.length));
                out.write(b);
                out.flush();
            } catch (IOException ignored) { }
        }

        /* kapcsolat teljes lezárása */
        private void shutdown() {
            running = false;

            /* értesítjük az összes peer-t */
            connected.keySet().forEach(pid -> {
                ClientHandler p = clients.get(pid);
                if (p != null) {
                    p.connected.remove(id);
                    p.send("disconnected:" + id);
                }
            });

            clients.remove(id);
            try { sock.close(); } catch (IOException ignored) { }
            System.out.println("Kapcsolat bontva (id:" + id + ")");
        }
    }
}
