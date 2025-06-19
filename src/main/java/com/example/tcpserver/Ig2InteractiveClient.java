package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Ig2InteractiveClient {

    // ===== CONFIG =====
    private static final String HOST = "127.0.0.1";
    private static final int PORT   = 1611;
    private static int clientId     = -1;

    public static void main(String[] args) throws Exception {
        Socket sock = new Socket(HOST, PORT);
        sock.setTcpNoDelay(true);

        DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
        Scanner sc = new Scanner(System.in);

        // Szerver clientId fogadása
        clientId = Integer.reverseBytes(in.readInt());
        System.out.println(ts() + " [EMU] Kapott kliens ID: " + clientId);

        // PeerID lista (későbbi listázáshoz)
        Set<Integer> peerIds = Collections.synchronizedSet(new HashSet<>());

        // Szerver figyelő szál
        Thread serverReader = new Thread(() -> {
            try {
                while (true) {
                    int len = Integer.reverseBytes(in.readInt());
                    byte[] buf = in.readNBytes(len);
                    String msg = new String(buf, StandardCharsets.UTF_8);

                    // Ping feldolgozás – nem írjuk ki, csak automatikusan válaszolunk
                    if (msg.startsWith("ping:")) {
                        //sendPacket(out, "pong:" + msg.substring(5));
                        continue; // Kommentáltad, ezért már nem logoljuk
                    }

                    // Query (gamelist) dekódolása
                    if (msg.startsWith("gamelist:")) {
                        String raw = msg.substring(9);
                        System.out.println(ts() + " [SZERVER] --- LOBBYK LISTÁJA ---");
                        if (raw.isBlank()) {
                            System.out.println("Nincs aktív lobby.");
                        } else {
                            for (String part : raw.split("\\|")) {
                                Map<String, String> m = parseLobbyPart(part);
                                String nev = m.get("nam") != null
                                        ? new String(Base64.getDecoder().decode(m.get("nam")), StandardCharsets.UTF_8)
                                        : "(nincs név)";
                                String id = m.getOrDefault("id", "?");
                                String nfr = m.getOrDefault("nfr", "?");
                                String lng = m.getOrDefault("lng", "?");
                                System.out.printf("  [id:%s] Név: \"%s\", slot/faj: %s, lang: %s%n", id, nev, nfr, lng);
                            }
                        }
                        System.out.println(ts() + " [SZERVER] --- vége ---");
                        continue;
                    }

                    // Egyébként minden választ logolunk
                    System.out.println(ts() + " [SZERVER] " + msg);

                    // Csatlakozás után figyeljük a "fc:" mezőt, amiből peerId-t kinyerjük
                    if (msg.startsWith("fc:")) {
                        String[] parts = msg.split(",");
                        for (String p : parts) {
                            if (p.startsWith("fc:")) {
                                try { peerIds.add(Integer.parseInt(p.substring(3))); } catch (Exception ignore) {}
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println(ts() + " [EMU] Kapcsolat lezárva.");
            }
        });
        serverReader.setDaemon(true);
        serverReader.start();

        // Parancsmenü
        printHelp();

        while (true) {
            System.out.print("> ");
            String line = sc.nextLine().trim();
            if (line.equalsIgnoreCase("exit")) break;
            if (line.isBlank()) continue;

            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();

            switch (cmd) {
                case "createlobby" -> {
                    if (parts.length < 2) {
                        System.out.println("Használat: createlobby <név>");
                        continue;
                    }
                    String nameBase64 = Base64.getEncoder().encodeToString(parts[1].getBytes(StandardCharsets.UTF_8));
                    String msg = "cmd:info,nam:" + nameBase64 + ",nfr:5,lng:1,plf:5";
                    sendPacket(out, msg);
                }
                case "query" -> sendPacket(out, "cmd:query,plf:5,str:");
                case "connect" -> {
                    if (parts.length < 2) {
                        System.out.println("Használat: connect <hostId>");
                        continue;
                    }
                    String msg = "cmd:connect,fp:105200,tc:" + parts[1].trim() + ",tp:5200";
                    sendPacket(out, msg);
                }
                case "send" -> {
                    if (parts.length < 2) {
                        System.out.println("Használat: send <szöveg>");
                        continue;
                    }
                    // Broadcast minden peernek
                    String msg = "cmd:send,fp:1234,tc:0,tp:0|" + parts[1];
                    sendPacket(out, msg);
                }
                case "sendto" -> {
                    // sendto <peerId> <szöveg>
                    String[] args2 = parts.length > 1 ? parts[1].split("\\s+", 2) : new String[0];
                    if (args2.length < 2) {
                        System.out.println("Használat: sendto <peerId> <szöveg>");
                        continue;
                    }
                    String msg = "cmd:send,fp:1234,tc:" + args2[0] + ",tp:0|" + args2[1];
                    sendPacket(out, msg);
                }
                case "startgame" -> {
                    // Minden aktív peerId-nak elküldi a "GAMESTART"-ot
                    if (peerIds.isEmpty()) {
                        System.out.println("Előbb connectelj valakihez, vagy nézd meg a peerId-kat (listpeers)!");
                        continue;
                    }
                    for (int pid : peerIds) {
                        String msg = "cmd:send,fp:1000,tc:" + pid + ",tp:1000|GAMESTART";
                        sendPacket(out, msg);
                        System.out.println(ts() + " [EMU] GAMESTART küldve peerId: " + pid);
                    }
                }
                case "disconnect" -> sendPacket(out, "cmd:disconnect,fc:0");
                case "listpeers" -> {
                    // Lényegében a query, de most csak peerId-kat listázunk ki
                    System.out.println(ts() + " [EMU] Saját clientId: " + clientId);
                    if (peerIds.isEmpty()) System.out.println("Nincs aktív peerId kapcsolat.");
                    else System.out.println("Aktív peerId-k: " + peerIds);
                }
                case "help" -> printHelp();
                default -> System.out.println("Ismeretlen parancs. Írd be: help");
            }
        }

        System.out.println(ts() + " [EMU] Lezárom a kapcsolatot.");
        sock.close();
    }

    static void sendPacket(DataOutputStream out, String msg) {
        try {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            out.writeInt(Integer.reverseBytes(b.length));
            out.write(b);
            out.flush();
            System.out.println(ts() + " [KÜLDVE] " + msg);
        } catch (IOException e) {
            System.out.println(ts() + " [EMU] Hiba a küldéskor: " + e);
        }
    }

    static String ts() {
        return "[" + System.currentTimeMillis() + "]";
    }

    static void printHelp() {
        System.out.println("""
                Parancsok:
                  createlobby <név>         – Lobby létrehozás/frissítés (játékos szám: 5)
                  query                    – Lobbyk listázása (szépen kiírja!)
                  connect <hostId>         – Csatlakozás lobbyhoz (host id-t a query-nél látod)
                  send <szöveg>            – Chat/parancs küldése (broadcast, mindenkihez – IG2-ben ritka)
                  sendto <peerId> <szöveg> – Chat/parancs küldése CÉLZOTT peer-nek
                  startgame                – (Host) Játék indítása: minden peerId-nak küld GAMESTART-ot
                  disconnect               – Kapcsolat bontása
                  listpeers                – Aktív peerId-k, saját clientId kiírása
                  exit                     – Kilépés
                  help                     – Súgó
                """);
    }

    // Segédfüggvény a query/gamelist dekódolásához
    static Map<String, String> parseLobbyPart(String s) {
        Map<String, String> m = new HashMap<>();
        for (String e : s.split(",")) {
            String[] kv = e.split(":", 2);
            if (kv.length == 2) m.put(kv[0], kv[1]);
        }
        return m;
    }
}
