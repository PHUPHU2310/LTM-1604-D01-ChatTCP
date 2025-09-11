package DETAI1;

import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static Set<ObjectOutputStream> clientWriters = new HashSet<>();

    public static void main(String[] args) throws Exception {
        System.out.println(" running...");
        ServerSocket serverSocket = new ServerSocket(12345);

        while (true) {
            Socket socket = serverSocket.accept();
            System.out.println(" connect.... " + socket);
            new ClientHandler(socket).start();
        }
    }

    private static class ClientHandler extends Thread {
        private Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                out.flush(); // 🔑 bắt buộc flush ngay sau khi tạo
                in = new ObjectInputStream(socket.getInputStream());

                synchronized (clientWriters) {
                    clientWriters.add(out);
                }

                Message msg;
                while ((msg = (Message) in.readObject()) != null) {
                    System.out.println("e: " + msg);

                    synchronized (clientWriters) {
                        for (ObjectOutputStream writer : clientWriters) {
                            writer.writeObject(msg);
                            writer.flush();
                        }
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("e");
            } finally {
                if (out != null) {
                    clientWriters.remove(out);
                }
                try {
                    socket.close();
                } catch (IOException e) {
                }
            }
        }
    }
}
