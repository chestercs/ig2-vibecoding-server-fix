package com.example.tcpserver.deepresearched;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class TcpServerProdNio {

    private static final int PORT = 1611;
    private static final int PING_INTERVAL_MS = 10_000;
    private static final int PING_TIMEOUT_MS = 15_000;

    // ClientID → Client
    private final Map<Integer, Client> clients = new ConcurrentHashMap<>();
    private final Map<SocketChannel, Client> channelToClient = new HashMap<>();
    private int nextId = 1;

    public static void main(String[] args) throws Exception {
        new TcpServerProdNio().start();
    }

    private void start() throws Exception {
        Selector selector = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(PORT));
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("TCP NIO szerver elindult a " + PORT + "-es porton.");

        long lastPingCheck = System.currentTimeMillis();

        while (true) {
            long now = System.currentTimeMillis();

            // Kliens ping check (időzített, nem thread)
            if (now - lastPingCheck > 500) {
                for (Client c : clients.values()) {
                    if (now - c.lastPingSent > PING_INTERVAL_MS) {
                        c.pingCnt++;
                        c.send("ping:" + c.pingCnt);
                        c.lastPingSent = now;
                        c.lastPingTimeoutCheck = now;
                    } else if (c.pingCnt > c.lastPong && now - c.lastPingTimeoutCheck > PING_TIMEOUT_MS) {
                        System.out.println("PING TIMEOUT: id=" + c.id);
                        c.close();
                    }
                }
                lastPingCheck = now;
            }

            selector.select(10); // Non-block, hogy mindig van idő pinget nézni

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();

                try {
                    if (key.isAcceptable()) {
                        SocketChannel ch = server.accept();
                        ch.configureBlocking(false);
                        ch.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                        int id = nextId++;
                        Client cli = new Client(ch, id);
                        clients.put(id, cli);
                        channelToClient.put(ch, cli);
                        cli.sendId();
                        System.out.println("Kapcsolat: " + ch.getRemoteAddress() + " (id:" + id + ") [" + System.currentTimeMillis() + "]");
                    } else if (key.isReadable()) {
                        Client c = channelToClient.get((SocketChannel) key.channel());
                        if (c != null && c.running) {
                            if (!c.readPackets()) {
                                c.close();
                            }
                        }
                    } else if (key.isWritable()) {
                        Client c = channelToClient.get((SocketChannel) key.channel());
                        if (c != null && c.running) {
                            c.flush();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    Client c = channelToClient.get((SocketChannel) key.channel());
                    if (c != null) c.close();
                }
            }
        }
    }

    private final class Client {
        final SocketChannel ch;
        final int id;
        final ByteBuffer readLen = ByteBuffer.allocate(4);
        ByteBuffer readMsg = null;
        final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();

        final Map<Integer, Integer> connected = new ConcurrentHashMap<>();
        Map<String, String> gameParams = null;

        volatile boolean running = true;
        int pingCnt = 0;
        int lastPong = 0;
        long lastPingSent = 0;
        long lastPingTimeoutCheck = 0;

        Client(SocketChannel ch, int id) {
            this.ch = ch;
            this.id = id;
        }

        void sendId() {
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(Integer.reverseBytes(id));
            buf.flip();
            writeQueue.add(buf);
            flush();
            System.out.println("[" + id + "][SEND-ID][" + System.currentTimeMillis() + "]");
        }

        void send(String msg) {
            long sendTime = System.nanoTime();
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(4 + b.length);
            buf.putInt(Integer.reverseBytes(b.length));
            buf.put(b);
            buf.flip();
            writeQueue.add(buf);
            flush();
            System.out.println("[" + id + "][SEND][" + sendTime + "ns] " + msg);
        }

        // Selector OP_WRITE esetén próbál flusholni
        void flush() {
            try {
                while (!writeQueue.isEmpty()) {
                    ByteBuffer buf = writeQueue.peek();
                    ch.write(buf);
                    if (buf.hasRemaining()) break;
                    writeQueue.poll();
                }
            } catch (IOException e) {
                close();
            }
        }

        // Packet framing: 4 byte len, utána ennyi byte UTF-8
        boolean readPackets() {
            try {
                while (true) {
                    if (readMsg == null) {
                        int n = ch.read(readLen);
                        if (n == -1) return false;
                        if (readLen.hasRemaining()) return true;
                        readLen.flip();
                        int len = Integer.reverseBytes(readLen.getInt());
                        readMsg = ByteBuffer.allocate(len);
                        readLen.clear();
                    }
                    int n = ch.read(readMsg);
                    if (n == -1) return false;
                    if (readMsg.hasRemaining()) return true;
                    long receiveTime = System.nanoTime();
                    String msg = new String(readMsg.array(), StandardCharsets.UTF_8);
                    System.out.println("[" + id + "][RECV][" + receiveTime + "ns] " + msg);
                    process(msg, receiveTime);
                    readMsg = null;
                }
            } catch (IOException e) {
                return false;
            }
        }

        // szerver logika, pont mint az eredeti
        private void process(String msg, long receiveTime) {
            if (msg.startsWith("pong:")) {
                lastPong = Integer.parseInt(msg.substring(5));
                return;
            }
            Map<String, String> p = parse(msg);
            switch (p.getOrDefault("cmd", "")) {
                case "send"       -> handleSend(p, receiveTime);
                case "info"       -> handleInfo(p);
                case "query"      -> handleQuery(p.getOrDefault("str", ""));
                case "connect"    -> handleConnect(p);
                case "disconnect" -> handleDisconnect(p);
            }
        }

        private void handleSend(Map<String, String> p, long receiveTime) {
            if (!p.containsKey("data")) return;
            String pkt = "fc:" + id
                    + ",fp:" + p.get("fp")
                    + ",tp:" + p.get("tp")
                    + "|"   + p.get("data");

            if ("0".equals(p.get("tc"))) {
                clients.values().forEach(c -> {
                    if (c.id != id) c.send(pkt);
                });
                send("ack:ok");
            } else {
                Client trg = clients.get(Integer.parseInt(p.get("tc")));
                if (trg != null) {
                    trg.send(pkt);
                    send("ack:ok");
                } else {
                    send("ack:error");
                }
            }
        }

        private void handleInfo(Map<String, String> p) {
            if (p.containsKey("nam") && !p.get("nam").isBlank()) {
                gameParams = new HashMap<>();
                p.forEach((k, v) -> {
                    if (!"cmd".equals(k))
                        gameParams.put(k, k.equals("nam")
                                ? new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8)
                                : v);
                });
            }
        }

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

        private void handleConnect(Map<String, String> p) {
            int tc = Integer.parseInt(p.get("tc"));
            Client trg = clients.get(tc);
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

        private void handleDisconnect(Map<String, String> p) {
            int fc = Integer.parseInt(p.getOrDefault("fc", "0"));

            if (fc == 0) {
                connected.keySet().forEach(pid -> {
                    Client c = clients.get(pid);
                    if (c != null) c.connected.remove(id);
                });
                connected.clear();
                gameParams = null;
                send("ack:ok");
                return;
            }
            Client peer = clients.get(fc);
            if (peer != null) {
                connected.remove(fc);
                peer.connected.remove(id);
                peer.send("disconnected:" + id);
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

        void close() {
            running = false;
            try { ch.close(); } catch (Exception ignored) {}
            clients.remove(id);
            channelToClient.remove(ch);
            connected.keySet().forEach(pid -> {
                Client p = clients.get(pid);
                if (p != null) {
                    p.connected.remove(id);
                    p.send("disconnected:" + id);
                }
            });
            System.out.println("Kapcsolat bontva (id:" + id + ") [" + System.currentTimeMillis() + "]");
        }
    }
}
