package DETAI1;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

/**
 * Client GUI: connect to server, exchange messages, receive files.
 * Protocol:
 * - server: REQUEST_NICK
 * - client sends: nickname
 * - server ack: NICK_ACCEPTED|nick or NICK_ASSIGNED|nick
 * - server sends text: FROM|sender|text
 * - server sends file: FILE|sender|filename|base64
 */
public class ClientGUI extends JFrame {
    private final JTextArea chatArea = new JTextArea();
    private final JTextField nickField = new JTextField("user", 12);
    private final JButton connectBtn = new JButton("Connect");
    private final JTextField inputField = new JTextField(30);
    private final JButton sendBtn = new JButton("Send");

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean connected = false;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    public ClientGUI() {
        super("Chat Client");
        setSize(650, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initLayout();

        connectBtn.addActionListener(e -> connect());
        sendBtn.addActionListener(e -> send());
        inputField.addActionListener(e -> send());
    }

    private void initLayout() {
        JPanel top = new JPanel();
        top.add(new JLabel("Nick:"));
        top.add(nickField);
        top.add(connectBtn);

        chatArea.setEditable(false);
        JScrollPane sc = new JScrollPane(chatArea);

        JPanel bottom = new JPanel();
        bottom.add(inputField);
        bottom.add(sendBtn);

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(sc, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
    }

    private void connect() {
        if (connected) return;
        try {
            socket = new Socket("127.0.0.1", Server.PORT);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            // start reader thread
            new Thread(this::readLoop).start();

            connected = true;
            connectBtn.setEnabled(false);
            sendBtn.setEnabled(true);
            chatArea.append("[" + sdf.format(new Date()) + "] Connected to server\n");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot connect: " + ex.getMessage());
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if ("REQUEST_NICK".equals(line)) {
                    String nick = nickField.getText().trim();
                    if (nick.isEmpty()) nick = "Guest";
                    out.println(nick);
                } else if (line.startsWith("NICK_ASSIGNED|") || line.startsWith("NICK_ACCEPTED|")) {
                    String[] p = line.split("\\|", 2);
                    if (p.length == 2) {
                        final String assignedNick = p[1];
                        SwingUtilities.invokeLater(() -> nickField.setText(assignedNick));
                    }
                    chatArea.append("[Server] Nick confirmed: " + p[1] + "\n");
                } else if (line.startsWith("FROM|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3) {
                        String sender = parts[1];
                        String text = parts[2];
                        chatArea.append("[" + sdf.format(new Date()) + "] " + sender + ": " + text + "\n");
                        if ("admin".equalsIgnoreCase(sender)) {
                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(this, "New message from admin: " + text);
                            });
                        }
                    }
                } else if (line.startsWith("FILE|")) {
                    String[] p = line.split("\\|", 4);
                    if (p.length == 4) {
                        handleIncomingFile(p[1], p[2], p[3]);
                    }
                } else {
                    chatArea.append("[Server] " + line + "\n");
                }
            }
        } catch (IOException ex) {
        chatArea.append("Lost connection to server\n");
        } finally {
        closeQuiet();
        }
    }


    private void handleIncomingFile(String sender, String filename, String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            // ask user where to save
            SwingUtilities.invokeLater(() -> {
                int r = JOptionPane.showConfirmDialog(this, sender + " sent file " + filename + ". Save?", "File received", JOptionPane.YES_NO_OPTION);
                if (r == JOptionPane.YES_OPTION) {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setSelectedFile(new File(filename));
                    int ch = chooser.showSaveDialog(this);
                    if (ch == JFileChooser.APPROVE_OPTION) {
                        File outFile = chooser.getSelectedFile();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(bytes);
                            chatArea.append("[" + sdf.format(new Date()) + "] Received file saved: " + outFile.getAbsolutePath() + "\n");
                        } catch (IOException ex) {
                            chatArea.append("Error saving file: " + ex.getMessage() + "\n");
                        }
                    }
                } else {
                    chatArea.append("Declined file: " + filename + "\n");
                }
            });
        } catch (Exception ex) {
            chatArea.append("Error decoding file: " + ex.getMessage() + "\n");
        }
    }

    private void send() {
        if (!connected) return;
        String txt = inputField.getText().trim();
        if (txt.isEmpty()) return;
        out.println(txt);
        inputField.setText("");
    }

    private void closeQuiet() {
        connected = false;
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            connectBtn.setEnabled(true);
            sendBtn.setEnabled(false);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ClientGUI c = new ClientGUI();
            c.setVisible(true);
        });
    }
}
