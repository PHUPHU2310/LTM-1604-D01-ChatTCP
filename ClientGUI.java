package DETAI1;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;

public class ClientGUI extends JFrame {
    private JTextArea chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Socket socket;
    private String name;

    public ClientGUI() {
        // ---- Giao diện ----
        setTitle("NOBODY");
        setSize(400, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);

        inputField = new JTextField();
        sendButton = new JButton("Send");

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(inputField, BorderLayout.CENTER);
        panel.add(sendButton, BorderLayout.EAST);

        add(scrollPane, BorderLayout.CENTER);
        add(panel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        
        connectToServer();
    }

    private void connectToServer() {
        try {
            socket = new Socket("localhost", 12345);
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());

            name = JOptionPane.showInputDialog(this, "Nhập tên nhé:");
            if (name == null || name.trim().isEmpty()) {
                name = "Người dùng";
            }

            // Thread nhận tin nhắn
            new Thread(() -> {
                try {
                    Message msg;
                    while ((msg = (Message) in.readObject()) != null) {
                        chatArea.append(msg.toString() + "\n");
                    }
                } catch (Exception e) {
                    chatArea.append("2310");
                }
            }).start();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không thể kết nối server!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }
    }

    private void sendMessage() {
        try {
            String text = inputField.getText().trim();
            if (!text.isEmpty()) {
                Message msg = new Message(name, text);
                out.writeObject(msg);
                out.flush();
                inputField.setText("");
            }
        } catch (IOException e) {
            chatArea.append("error");
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ClientGUI client = new ClientGUI();
            client.setVisible(true);
        });
    }
}
