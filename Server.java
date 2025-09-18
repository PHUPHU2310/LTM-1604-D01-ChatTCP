package DETAI1;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

/**
 * Improved Server:
 * - multi-client using thread pool
 * - keepalive / timeout handling
 * - send text (FROM|sender|text)
 * - send small files as base64 (FILE|sender|filename|base64)
 * - send large files as stream (FILESTREAM|sender|filename|size) then raw bytes
 * - kick, broadcast, sendToClient
 *
 * Place in package com.mycompany.chatgpt
 */
public class Server {
    public static volatile int PORT = 9000;
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static ServerSocket serverSocket;

    // maximum file size (bytes) to encode as base64 (compatibility with older client)
    private static final int MAX_BASE64_SIZE_BYTES = 200 * 1024; // 200 KB

    // client inactivity timeout (seconds) - if no read for this time, drop
    private static final int CLIENT_TIMEOUT_SECONDS = 300; // 5 minutes

    // start server in background; returns true if started
    public static synchronized boolean startServer(int port) {
        if (serverSocket != null && !serverSocket.isClosed()) return true;
        try {
            PORT = port;
            serverSocket = new ServerSocket(PORT);
            pool.execute(() -> acceptLoop());
            // schedule periodic cleanup of dead clients (safety)
            scheduler.scheduleAtFixedRate(Server::cleanupDeadClients, 60, 60, TimeUnit.SECONDS);
            System.out.println("SERVER: running on port " + PORT);
            return true;
        } catch (IOException ex) {
            System.err.println("SERVER: failed to start: " + ex.getMessage());
            return false;
        }
    }

    private static void acceptLoop() {
        try {
            while (!serverSocket.isClosed()) {
                Socket sock = serverSocket.accept();
                sock.setSoTimeout(0); // disable socket-level read timeout; we use lastActive timestamp
                ClientHandler handler = new ClientHandler(sock);
                pool.execute(handler);
            }
        } catch (IOException ex) {
            if (serverSocket != null && serverSocket.isClosed()) {
                System.out.println("SERVER: accept loop terminated (server closed).");
            } else {
                ex.printStackTrace();
            }
        }
    }

    public static synchronized void stopServer() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        // close clients
        for (ClientHandler h : clients.values()) h.closeQuiet();
        clients.clear();
        scheduler.shutdownNow();
        pool.shutdownNow();
        System.out.println("SERVER: stopped.");
    }

    public static Set<String> getClientNames() {
        return new HashSet<>(clients.keySet());
    }

    // send text to a client
    public static void sendToClient(String nick, String sender, String text) {
        ClientHandler h = clients.get(nick);
        if (h != null) h.enqueueText("FROM|" + sender + "|" + text);
    }

    // broadcast text
    public static void broadcast(String sender, String text) {
        String msg = "FROM|" + sender + "|" + text;
        for (ClientHandler h : clients.values()) h.enqueueText(msg);
    }

    // send file to client: choose base64 small or stream large
    public static void sendFileToClient(String nick, String sender, File f) {
        ClientHandler h = clients.get(nick);
        if (h == null) return;
        sendFileInternal(h, sender, f);
    }

    // broadcast file
    public static void broadcastFile(String sender, File f) {
        for (ClientHandler h : clients.values()) {
            sendFileInternal(h, sender, f);
        }
    }

    private static void sendFileInternal(ClientHandler h, String sender, File f) {
        try {
            long size = f.length();
            if (size <= MAX_BASE64_SIZE_BYTES) {
                // encode base64 and send single-line header (compat)
                byte[] bytes = Files.readAllBytes(f.toPath());
                String b64 = Base64.getEncoder().encodeToString(bytes);
                String header = "FILE|" + sender + "|" + f.getName() + "|" + b64;
                h.enqueueText(header);
            } else {
                // stream mode: send header indicating file stream follows, then raw bytes
                // header format: FILESTREAM|sender|filename|size
                String header = "FILESTREAM|" + sender + "|" + f.getName() + "|" + size;
                h.enqueueStreamFile(header, f);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    // kick client
    public static void kickClient(String nick, String reason) {
        ClientHandler h = clients.get(nick);
        if (h != null) {
            h.enqueueText("FROM|server|You have been kicked. Reason: " + reason);
            h.closeQuiet();
            clients.remove(nick);
            AdminMessageHandler.clientLeft(nick);
            System.out.println("SERVER: kicked " + nick);
        }
    }

    // register client
    private static void register(String nick, ClientHandler h) {
        clients.put(nick, h);
        AdminMessageHandler.clientJoined(nick);
    }

    // unregister client
    private static void unregister(String nick) {
        clients.remove(nick);
        AdminMessageHandler.clientLeft(nick);
    }

    private static void cleanupDeadClients() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ClientHandler> e : clients.entrySet()) {
            ClientHandler h = e.getValue();
            if ((now - h.getLastActive()) > CLIENT_TIMEOUT_SECONDS * 1000L) {
                toRemove.add(e.getKey());
            }
        }
        for (String nick : toRemove) {
            ClientHandler h = clients.get(nick);
            if (h != null) {
                System.out.println("SERVER: removing inactive client " + nick);
                h.closeQuiet();
                unregister(nick);
            }
        }
    }

    // ================= ClientHandler =================
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final BlockingQueue<Object> sendQueue = new LinkedBlockingQueue<>();
        private volatile long lastActive = System.currentTimeMillis();
        private volatile boolean running = true;

        private PrintWriter textOut; // for sending text lines (may also use DataOutputStream)
        private OutputStream rawOut;
        private BufferedReader reader;
        private String nick;
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

        ClientHandler(Socket s) {
            this.socket = s;
        }

        long getLastActive() { return lastActive; }

        public void run() {
            try {
                InputStream inStream = socket.getInputStream();
                rawOut = socket.getOutputStream();
                reader = new BufferedReader(new InputStreamReader(inStream, "UTF-8"));
                textOut = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);

                // handshake: request nick
                textOut.println("REQUEST_NICK");
                String line = reader.readLine();
                if (line == null) { closeQuiet(); return; }
                nick = line.trim();
                if (nick.isEmpty()) nick = "Guest-" + new Random().nextInt(1000);

                // ensure unique
                synchronized (clients) {
                    if (clients.containsKey(nick)) {
                        String base = nick;
                        int i = 1;
                        while (clients.containsKey(base + "_" + i)) i++;
                        nick = base + "_" + i;
                        textOut.println("NICK_ASSIGNED|" + nick);
                    } else {
                        textOut.println("NICK_ACCEPTED|" + nick);
                    }
                }

                register(nick, this);
                System.out.println(time() + " SERVER: " + nick + " connected from " + socket.getRemoteSocketAddress());

                // start a sender thread to flush sendQueue (so reading is not blocked)
                Thread senderThread = new Thread(this::senderLoop, "sender-" + nick);
                senderThread.setDaemon(true);
                senderThread.start();

                // read loop
                while (running) {
                    String msg = reader.readLine();
                    if (msg == null) break;
                    lastActive = System.currentTimeMillis();
                    msg = msg.trim();
                    if (msg.isEmpty()) continue;
                    if ("/quit".equalsIgnoreCase(msg)) break;

                    // Handle simple control commands from client if needed (e.g., client->client)
                    // For now all client messages forwarded to admin GUI
                    AdminMessageHandler.receiveFromClient(nick, msg);
                }

            } catch (SocketException sx) {
                System.out.println("SERVER: socket error for " + nick + " : " + sx.getMessage());
            } catch (IOException ex) {
                System.out.println("SERVER: io error for " + nick + " : " + ex.getMessage());
            } finally {
                running = false;
                closeQuiet();
                unregister(nick);
                System.out.println(time() + " SERVER: " + nick + " disconnected.");
            }
        }

        // enqueue a text line (String) to send
        void enqueueText(String line) {
            if (!running) return;
            sendQueue.offer(line);
        }

        // enqueue a file stream: first header (String), then File object -> senderLoop will do streaming
        void enqueueStreamFile(String header, File f) {
            if (!running) return;
            sendQueue.offer(header);
            sendQueue.offer(f);
        }

        // sender loop consumes queue and writes to socket
        private void senderLoop() {
            try {
                while (running && !socket.isClosed()) {
                    Object obj = sendQueue.take(); // blocking
                    if (obj instanceof String) {
                        // send text header or normal message
                        String line = (String) obj;
                        try {
                            textOut.println(line); // send as line terminated
                        } catch (Exception ex) {
                            System.err.println("SEND error to " + nick + " : " + ex.getMessage());
                        }
                    } else if (obj instanceof File) {
                        File f = (File) obj;
                        // Write raw bytes directly AFTER the previous header (which should be FILESTREAM|...)
                        try (FileInputStream fis = new FileInputStream(f)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            // Important: flush textual output first
                            textOut.flush();
                            // Now write raw bytes to rawOut
                            while ((read = fis.read(buffer)) != -1) {
                                rawOut.write(buffer, 0, read);
                            }
                            rawOut.flush();
                        } catch (IOException ex) {
                            System.err.println("STREAM send error to " + nick + " : " + ex.getMessage());
                        }
                    } else {
                        // ignore unknown object
                    }
                }
            } catch (InterruptedException ie) {
                // thread interrupted: exit
            } catch (Exception ex) {
                System.err.println("Sender loop error for " + nick + " : " + ex.getMessage());
            }
        }

        void closeQuiet() {
            running = false;
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (textOut != null) textOut.close(); } catch (Exception ignored) {}
            try { if (rawOut != null) rawOut.close(); } catch (Exception ignored) {}
            try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
        }

        String time() {
            return "[" + sdf.format(new Date()) + "]";
        }
    }
}
