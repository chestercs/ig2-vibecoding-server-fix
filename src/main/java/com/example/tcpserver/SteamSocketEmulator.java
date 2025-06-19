package com.example.tcpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class SteamSocketEmulator {

    private static final int PORT = 27015; // Klasszikus Steam Socket port
    private static final int HEARTBEAT_INTERVAL_MS = 100;

    private final ConcurrentHashMap<SocketAddress, ClientSession> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        new SteamSocketEmulator().start();
    }

    public void start() throws IOException {
        DatagramSocket socket = new DatagramSocket(PORT);
        socket.setSoTimeout(0);
        System.out.println("[SteamEmu] UDP socket listening on port " + PORT);

        ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            for (ClientSession session : clients.values()) {
                session.sendHeartbeat(socket);
            }
        }, 1000, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        byte[] buffer = new byte[1024];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            handlePacket(socket, packet);
        }
    }

    private void handlePacket(DatagramSocket socket, DatagramPacket packet) {
        String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        SocketAddress clientAddr = packet.getSocketAddress();

        clients.computeIfAbsent(clientAddr, addr -> new ClientSession(addr)).updateLastSeen();

        if (msg.startsWith("latency_ping")) {
            String reply = "latency_pong";
            byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
            DatagramPacket replyPacket = new DatagramPacket(replyData, replyData.length, packet.getSocketAddress());
            try {
                socket.send(replyPacket);
            } catch (IOException e) {
                System.out.println("[SteamEmu] Failed to send latency_pong to " + clientAddr);
            }
        }
    }

    private static class ClientSession {
        private final SocketAddress address;
        private volatile long lastSeen = System.currentTimeMillis();

        ClientSession(SocketAddress address) {
            this.address = address;
        }

        void updateLastSeen() {
            lastSeen = System.currentTimeMillis();
        }

        void sendHeartbeat(DatagramSocket socket) {
            long now = System.currentTimeMillis();
            if (now - lastSeen > 15000) return; // inactive client
            String packet = "steam_sync:" + now;
            byte[] data = packet.getBytes(StandardCharsets.UTF_8);
            try {
                socket.send(new DatagramPacket(data, data.length, address));
            } catch (IOException e) {
                System.out.println("[SteamEmu] Failed to send heartbeat to " + address);
            }
        }
    }
}
