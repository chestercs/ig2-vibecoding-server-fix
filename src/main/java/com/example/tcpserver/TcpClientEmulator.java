package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class TcpClientEmulator {

    private static final String HOST = "127.0.0.1";
    private static final int PORT   = 1611;
    private static int clientId     = -1;
    private static boolean isHost = false;
    private static final Set<Integer> peerIds = Collections.synchronizedSet(new HashSet<>());

    // lobbySize == slot count == választható fajok száma
    private static int lobbySize = 7; // default 7 faj (0-6 slot)

    // Slot → PlayerState (minden slot egy faj!)
    private static final Map<Integer, PlayerState> slots = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    // query parancs imitálásához: csak egy lobby-t tudunk, az önmagunkat írjuk ki
    private static String lobbyName = "noname";

    static class PlayerState {
        int slotIndex;    // faj = slot index!
        int peerId;
        String playerName;
        boolean ready;

        PlayerState(int slotIndex, int peerId, String playerName) {
            this.slotIndex = slotIndex;
            this.peerId = peerId;
            this.playerName = playerName;
            this.ready = false;
        }

        String toIg2SlotPacket() {
            // Egyszerűsített: packetType(1B) slot(1B) ready(1B) name (null term)
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(8); // packetType
            bos.write(slotIndex);
            bos.write(ready ? 1 : 0);
            byte[] nameBytes = playerName.getBytes(StandardCharsets.UTF_8);
            bos.write(nameBytes, 0, Math.min(nameBytes.length, 24));
            bos.write(0); // null-terminate
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        }
        String stateString() {
            return "Slot#" + slotIndex + " (" + fajNev(slotIndex) + ") " +
                    (peerId == clientId ? "[Te] " : "") +
                    (playerName != null ? playerName : "") + " | " + (ready ? "READY" : "NOT READY");
        }
    }

    public static void main(String[] args) throws Exception {
        Socket sock = new Socket(HOST, PORT);
        sock.setTcpNoDelay(true);

        DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
        Scanner sc = new Scanner(System.in);

        clientId = Integer.reverseBytes(in.readInt());
        System.out.println(ts() + " [EMU] Kapott kliens ID: " + clientId);

        printHelp();

        Thread serverReader = new Thread(() -> {
            try {
                while (true) {
                    int len = Integer.reverseBytes(in.readInt());
                    byte[] buf = in.readNBytes(len);
                    String msg = new String(buf, StandardCharsets.UTF_8);

                    if (msg.startsWith("ping:")) {
                        sendPacket(out, "pong:" + msg.substring(5));
                        continue;
                    }

                    int fc = extractPeerId(msg);
                    if (fc > 0 && fc != clientId) peerIds.add(fc);

                    // slot state packet dekódolás
                    if (msg.matches(".*\\|[A-Za-z0-9+/=]{10,}$")) {
                        String[] spl = msg.split("\\|", 2);
                        if (spl.length == 2) {
                            String base64 = spl[1];
                            String decoded = decodeIg2SlotPacket(base64);
                            System.out.println(ts() + " [SLOT STATE] " + decoded);
                        }
                    }
                    if (msg.matches(".*\\|[A-Za-z0-9+/=]+$")) {
                        String[] spl = msg.split("\\|", 2);
                        if (spl.length == 2) {
                            String base64 = spl[1];
                            String decoded = decodeIg2ChatPacket(base64);
                            if (decoded != null && !decoded.isBlank()) {
                                System.out.println(ts() + " [CHAT] " + decoded);
                            }
                        }
                    }
                    // gamelist parancs kezelés
                    if (msg.startsWith("gamelist:")) {
                        String raw = msg.substring(9);
                        System.out.println(ts() + " [SZERVER] --- LOBBYK LISTÁJA ---");
                        if (raw.isBlank()) System.out.println("Nincs aktív lobby.");
                        else for (String part : raw.split("\\|")) System.out.println("  " + part);
                        System.out.println(ts() + " [SZERVER] --- vége ---");
                        continue;
                    }

                    System.out.println(ts() + " [SZERVER] " + msg);
                }
            } catch (IOException e) {
                System.out.println(ts() + " [EMU] Kapcsolat lezárva.");
            }
        });
        serverReader.setDaemon(true);
        serverReader.start();

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
                    isHost = true;
                    lobbyName = parts[1];
                    synchronized (LOCK) {
                        slots.clear();
                        // A host mindig a 0. slotban van (ember faj)
                        PlayerState me = new PlayerState(0, clientId, parts[1]);
                        slots.put(0, me);
                    }
                    String nameBase64 = Base64.getEncoder().encodeToString(parts[1].getBytes(StandardCharsets.UTF_8));
                    sendPacket(out, "cmd:info,nam:" + nameBase64 + ",nfr:" + lobbySize + ",lng:1,plf:5");
                    broadcastLobbyState(out);
                    System.out.println(ts() + " [EMU] Lobby létrehozva, neved: " + parts[1]);
                }
                case "size" -> {
                    if (!isHost) { System.out.println("Csak host állíthatja a slot számot."); continue; }
                    int size = Integer.parseInt(parts[1]);
                    if (size < 1) size = 1;
                    if (size > 12) size = 12;
                    synchronized (LOCK) {
                        lobbySize = size;
                        int finalSize = size;
                        slots.keySet().removeIf(k -> k >= finalSize);
                    }
                    sendPacket(out, "cmd:info,nfr:" + lobbySize + ",plf:5");
                    broadcastLobbyState(out);
                    System.out.println(ts() + " [EMU] Lobby slot szám: " + lobbySize);
                }
                case "race" -> {
                    int wantedSlot = Integer.parseInt(parts[1]);
                    if (wantedSlot < 0 || wantedSlot >= lobbySize) {
                        System.out.println("Nincs ilyen faj/slot!");
                        continue;
                    }
                    synchronized (LOCK) {
                        // Ha már ül ott valaki, kidobjuk (egyszerűen eltávolítjuk)
                        slots.entrySet().removeIf(e -> e.getValue().peerId == clientId); // előző slotból kiszáll
                        PlayerState old = slots.get(wantedSlot);
                        if (old != null && old.peerId != clientId) {
                            System.out.println("A kiválasztott slot/faj foglalt, előbb szabadítsd fel!");
                            continue;
                        }
                        // új slotba beülök
                        PlayerState me = new PlayerState(wantedSlot, clientId, myName());
                        slots.put(wantedSlot, me);
                    }
                    broadcastLobbyState(out);
                }
                case "ready" -> {
                    synchronized (LOCK) {
                        for (PlayerState ps : slots.values()) {
                            if (ps.peerId == clientId) ps.ready = true;
                        }
                    }
                    broadcastLobbyState(out);
                }
                case "unready" -> {
                    synchronized (LOCK) {
                        for (PlayerState ps : slots.values()) {
                            if (ps.peerId == clientId) ps.ready = false;
                        }
                    }
                    broadcastLobbyState(out);
                }
                case "connect" -> {
                    if (parts.length < 2) { System.out.println("Használat: connect <hostId>"); continue; }
                    int hostId = Integer.parseInt(parts[1].trim());
                    peerIds.add(hostId);
                    isHost = false;
                    int slot = findFirstFreeSlot();
                    if (slot == -1) { System.out.println("Nincs szabad faj/slot!"); continue; }
                    synchronized (LOCK) {
                        PlayerState me = new PlayerState(slot, clientId, myName());
                        slots.put(slot, me);
                    }
                    sendPacket(out, "cmd:connect,fp:105200,tc:" + hostId + ",tp:5200");
                    broadcastLobbyState(out);
                    System.out.println(ts() + " [EMU] Csatlakoztál a lobbyhoz, slot: " + slot + " (" + fajNev(slot) + ")");
                }
                case "send" -> {
                    if (parts.length < 2) { System.out.println("Használat: send <szöveg>"); continue; }
                    for (int pid : peerIds) {
                        if (pid != clientId) {
                            String msg = "cmd:send,fp:1234,tc:" + pid + ",tp:0|" + encodeIg2Chat(parts[1]);
                            sendPacket(out, msg);
                        }
                    }
                }
                case "start" -> {
                    if (!isHost) { System.out.println("Csak host indíthat játékot!"); continue; }
                    for (int pid : peerIds) {
                        if (pid != clientId) {
                            String msg = "cmd:send,fp:1000,tc:" + pid + ",tp:1000|GAMESTART";
                            sendPacket(out, msg);
                            System.out.println(ts() + " [EMU] GAMESTART peerId: " + pid);
                        }
                    }
                }
                case "state" -> {
                    synchronized (LOCK) {
                        for (int slot = 0; slot < lobbySize; ++slot) {
                            PlayerState ps = slots.get(slot);
                            if (ps == null)
                                System.out.println("Slot#" + slot + " (" + fajNev(slot) + ") -- üres --");
                            else
                                System.out.println(ps.stateString());
                        }
                    }
                }
                case "kick" -> {
                    int kickId = Integer.parseInt(parts[1]);
                    synchronized (LOCK) {
                        slots.values().removeIf(ps -> ps.peerId == kickId);
                    }
                    broadcastLobbyState(out);
                    System.out.println("Peer " + kickId + " kidobva a lobbyból.");
                }
                case "query" -> {
                    // Egyetlen lobby létezik, azt írjuk ki!
                    String encodedName = Base64.getEncoder().encodeToString(lobbyName.getBytes(StandardCharsets.UTF_8));
                    String lobbyInfo = "id:1,nam:" + encodedName + ",nfr:" + lobbySize + ",lng:1";
                    sendPacket(out, "gamelist:" + lobbyInfo);
                }
                case "help" -> printHelp();
                default -> System.out.println("Ismeretlen parancs. Írd be: help");
            }
        }
        System.out.println(ts() + " [EMU] Lezárom a kapcsolatot.");
        sock.close();
    }

    static int findFirstFreeSlot() {
        for (int i = 0; i < lobbySize; ++i)
            if (!slots.containsKey(i))
                return i;
        return -1;
    }

    static void broadcastLobbyState(DataOutputStream out) {
        synchronized (LOCK) {
            for (int slot = 0; slot < lobbySize; ++slot) {
                PlayerState ps = slots.get(slot);
                if (ps == null) {
                    // üres slot: üres packet
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bos.write(8); // packetType
                    bos.write(slot); // slotIndex
                    bos.write(0);    // not ready
                    bos.write(0);    // null-terminated üres név
                    String emptyPkt = Base64.getEncoder().encodeToString(bos.toByteArray());
                    for (int pid : peerIds)
                        sendPacket(out, "cmd:send,fp:5200,tc:" + pid + ",tp:105200|" + emptyPkt);
                    sendPacket(out, "cmd:send,fp:5200,tc:" + clientId + ",tp:105200|" + emptyPkt);
                } else {
                    String pkt = ps.toIg2SlotPacket();
                    for (int pid : peerIds)
                        sendPacket(out, "cmd:send,fp:5200,tc:" + pid + ",tp:105200|" + pkt);
                    sendPacket(out, "cmd:send,fp:5200,tc:" + clientId + ",tp:105200|" + pkt);
                }
            }
        }
    }

    static String myName() {
        PlayerState ps = null;
        synchronized (LOCK) {
            for (PlayerState p : slots.values())
                if (p.peerId == clientId) ps = p;
        }
        if (ps != null) return ps.playerName;
        return "player" + clientId;
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

    static String ts() { return "[" + System.currentTimeMillis() + "]"; }

    static void printHelp() {
        System.out.println("""
                Parancsok:
                  createlobby <név>  – Lobby létrehozás (te leszel az ember sloton)
                  connect <hostId>   – Lobbyhoz csatlakozás (bármelyik slotba beülsz)
                  size <n>           – Lobby slot/faj szám (host-only)
                  race <slot>        – Faj/slot váltás (pl. race 2 = antari)
                  ready / unready    – Készen állsz/Nem vagy kész
                  start              – Játék indítása (host-only)
                  query              – Lobbyk listázása
                  state              – Aktuális lobby state kiírása
                  kick <peerId>      – Peer kidobása
                  send <szöveg>      – Chat minden peernek
                  help               – Súgó
                  exit               – Kilépés
                """);
    }

    static int extractPeerId(String msg) {
        int idx = msg.indexOf("fc:");
        if (idx < 0) return -1;
        int start = idx + 3, end = start;
        while (end < msg.length() && Character.isDigit(msg.charAt(end))) end++;
        try { return Integer.parseInt(msg.substring(start, end)); }
        catch (Exception e) { return -1; }
    }

    // IG2 lobby slot packet dekódolás
    static String decodeIg2SlotPacket(String base64) {
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            if (data.length < 4) return "[érvénytelen slot packet]";
            int slot = data[1];
            boolean ready = data[2] != 0;
            int nameStart = 3, nameEnd = nameStart;
            while (nameEnd < data.length && data[nameEnd] != 0) nameEnd++;
            String name = new String(Arrays.copyOfRange(data, nameStart, nameEnd), StandardCharsets.UTF_8);
            return "Slot#" + slot + " Név:" + name + " Faj:" + fajNev(slot) + " " + (ready ? "READY" : "NOT READY");
        } catch (Exception e) {
            return "[decode error: " + e + "]";
        }
    }

    static String decodeIg2ChatPacket(String base64) {
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            if (data.length < 9) return "";
            int packetType = data[0] & 0xFF;
            if (packetType == 1) {
                int szovegStart = 8;
                int end = szovegStart;
                while (end < data.length && data[end] != 0) end++;
                String szoveg = new String(Arrays.copyOfRange(data, szovegStart, end), StandardCharsets.UTF_8);
                return szoveg;
            }
            return "[IG2_PACKET] type=" + packetType + " base64=" + base64;
        } catch (Exception e) {
            return "[decode error: " + e + "]";
        }
    }

    static String encodeIg2Chat(String szoveg) {
        byte[] base = new byte[8 + szoveg.length() + 1];
        base[0] = 0x01;
        base[1] = 0x00;
        base[2] = 0x06; base[3] = 0x00;
        base[4] = 0x00; base[5] = 0x00; base[6] = 0x00; base[7] = 0x00;
        byte[] sz = szoveg.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(sz, 0, base, 8, sz.length);
        base[8 + sz.length] = 0;
        return Base64.getEncoder().encodeToString(base);
    }

    // faj neve slot index alapján
    static String fajNev(int idx) {
        return switch (idx) {
            case 0 -> "Ember";
            case 1 -> "Antari";
            case 2 -> "Gast";
            case 3 -> "Sual";      // ... kiegészíthető
            case 4 -> "Thoraq";
            case 5 -> "Zergon";
            case 6 -> "Muzul";
            default -> "Ismeretlen";
        };
    }
}
