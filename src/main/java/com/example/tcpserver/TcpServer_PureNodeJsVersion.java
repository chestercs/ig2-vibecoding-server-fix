package com.example.tcpserver;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Imperium Galactica II matchmaking‑szerver – Java 23, 1‑az‑1‑ben a hivatalos Node.js logikára
 * fordítva, extra "lagcsökkentő" trükkök nélkül (stabil referenciaváltozat).
 */
public final class TcpServer_PureNodeJsVersion {
    /* Állandók */
    private static final int PORT = 1611;
    private static final int PING_INTERVAL_MS  = 10_000;
    private static final int PING_TIMEOUT_MS   = 15_000;

    /* Szerver‑oldali állapot */
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger();

    /* ─────────────────────────────────────────────  belépési pont  */
    public static void main(String[] args) throws IOException { new TcpServer_PureNodeJsVersion().start(); }

    /* ─────────────────────────────────────────────  fő ciklus  */
    private void start() throws IOException {
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("TCP szerver elindult a " + PORT + "‑es porton.");
        while (true) {
            Socket s = ss.accept();
            s.setTcpNoDelay(true);
            int id = idGen.incrementAndGet();
            ClientHandler ch = new ClientHandler(s, id);
            clients.put(id, ch);
            new Thread(ch, "cli-"+id).start();
            System.out.println("Kapcsolat " + s.getRemoteSocketAddress() + " (id:"+id+")");
        }
    }

    /* ═════════════════════  BELSO OSZTÁLY  ═════════════════════ */
    private final class ClientHandler implements Runnable {
        final Socket sock; final int id;
        final DataInputStream in; final DataOutputStream out;
        final Map<Integer,Integer> connected = new ConcurrentHashMap<>();
        Map<String,String> gameParams = null;
        volatile boolean running = true;
        int pingCounter = 0; int lastPong = 0;

        ClientHandler(Socket s,int id) throws IOException {
            this.sock=s; this.id=id;
            in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            sendId(); startPingLoop();
        }
        private void sendId() throws IOException {
            out.writeInt(Integer.reverseBytes(id));
            out.flush();
        }
        private void startPingLoop(){
            Executors.newSingleThreadScheduledExecutor()
                    .scheduleAtFixedRate(this::sendPing, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
        private void sendPing(){ if(!running) return; send("ping:"+(++pingCounter));
            Executors.newSingleThreadScheduledExecutor().schedule(()->{ if(lastPong!=pingCounter) shutdown(); }, PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        @Override public void run(){ try{
            while(running){ int sz=Integer.reverseBytes(in.readInt()); byte[] buf=in.readNBytes(sz);
                process(new String(buf,StandardCharsets.UTF_8)); }
        }catch(IOException e){/*ignor*/} finally{shutdown();}}

        /* ── feldolgozás */
        private void process(String msg){
            if(msg.startsWith("pong:")){ lastPong=Integer.parseInt(msg.substring(5)); return; }
            Map<String,String> p=parse(msg);
            switch(p.getOrDefault("cmd","")){
                case "send"      -> handleSend(p);
                case "info"      -> handleInfo(p);
                case "query"     -> handleQuery(p.getOrDefault("str",""));
                case "connect"   -> handleConnect(p);
                case "disconnect"-> handleDisconnect(p);
            }
        }

        /* --- parancsok implementációja (1‑az‑1‑ben a Node.js‑ből) --- */
        private void handleSend(Map<String,String> p){
            if(!p.containsKey("data")) return;
            String data = "fc:"+id+",fp:"+p.get("fp")+",tp:"+p.get("tp")+"|"+p.get("data");
            if("0".equals(p.get("tc"))){
                clients.values().forEach(c->{ if(c.id!=id) c.send(data); });
                send("ack:ok");
            }else{
                ClientHandler trg=clients.get(Integer.parseInt(p.get("tc")));
                if(trg!=null){ trg.send(data); send("ack:ok"); }
                else send("ack:error");
            }
        }
        private void handleInfo(Map<String,String> p){ gameParams=new HashMap<>(); p.forEach((k,v)->{ if(!"cmd".equals(k)) gameParams.put(k,v);}); if(gameParams.containsKey("nam"))
            gameParams.put("nam",new String(Base64.getDecoder().decode(gameParams.get("nam")),StandardCharsets.UTF_8)); }
        private void handleQuery(String search){
            final String query = search.toLowerCase();
            List<String> list = new ArrayList<>();
            clients.values().stream()
                    .filter(c -> c.gameParams != null && c.gameParams.containsKey("nam"))
                    .filter(c -> c.gameParams.get("nam").toLowerCase().contains(query))
                    .limit(100)
                    .forEach(c -> {
                        Map<String,String> gp = new HashMap<>(c.gameParams);
                        gp.put("id", String.valueOf(c.id));
                        gp.put("sti", String.valueOf(c.gameParams.get("nam").toLowerCase().indexOf(query)));
                        gp.put("nam", Base64.getEncoder().encodeToString(c.gameParams.get("nam").getBytes(StandardCharsets.UTF_8)));
                        list.add(flat(gp));
                    });
            send("gamelist:" + String.join("|", list));
        }
            private void handleConnect(Map<String,String> p){ int tc=Integer.parseInt(p.get("tc")); ClientHandler trg=clients.get(tc);
                if(trg!=null){ connected.put(tc,1); trg.connected.put(id,1);
                    trg.send("fc:"+id+",fp:"+p.get("fp")+",tp:"+p.get("tp")+"|!connect!"); send("ack:ok"); } else send("ack:error"); }
            private void handleDisconnect(Map<String,String> p){ int fc=Integer.parseInt(p.getOrDefault("fc","0"));
                connected.keySet().stream().filter(k->fc==0||k==fc).forEach(k->{ ClientHandler trg=clients.get(k); if(trg!=null){ trg.connected.remove(id); trg.send("disconnected:"+id); } });
                if(fc==0) gameParams=null; send("ack:ok"); }

            /* --- util --- */
            private Map<String,String> parse(String s){ Map<String,String>m=new HashMap<>(); int i=s.indexOf('|'); String head=i>=0?s.substring(0,i):s; for(String part:head.split(",")){ var kv=part.split(":",2); if(kv.length==2) m.put(kv[0],kv[1]); }
                if(i>=0) m.put("data",s.substring(i+1)); return m; }
            private String flat(Map<String,String> m){ return m.entrySet().stream().map(e->e.getKey()+":"+e.getValue()).collect(Collectors.joining(",")); }
            private synchronized void send(String msg){ try{ byte[] b=msg.getBytes(StandardCharsets.UTF_8); out.writeInt(Integer.reverseBytes(b.length)); out.write(b); out.flush(); }catch(IOException ignored){} }
            private void shutdown(){ running=false; clients.remove(id); try{ sock.close(); }catch(IOException ignored){} System.out.println("Kapcsolat bontva (id:"+id+")"); }
        }
    }
