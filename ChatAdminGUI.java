package DETAI1;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Admin GUI + starts server.
 * Left: client list with search.
 * Right: tabbed private chats + log area.
 * Bottom: input, send, notify toggle, pre-announce list, file send, kick.
 */
public class ChatAdminGUI extends JFrame {
    private final DefaultListModel<String> masterModel = new DefaultListModel<>();
    private final DefaultListModel<String> filteredModel = new DefaultListModel<>();
    private final JList<String> clientList = new JList<>(filteredModel);
    private final JTextField searchField = new JTextField(12);

    private final JTabbedPane tabbed = new JTabbedPane();
    private final JTextArea logArea = new JTextArea();
    private final JTextField inputField = new JTextField(30);
    private final JButton sendBtn = new JButton("Send");
    private final JToggleButton notifyBtn = new JToggleButton("NotifyAll");
    private final JButton preAnnBtn = new JButton("Pre-announce");
    private final JButton sendFileBtn = new JButton("Send File");
    private final JButton kickBtn = new JButton("Kick");
    private final JButton startBtn = new JButton("Start Server");
    private final JTextField portField = new JTextField("9000",5);

    private final Map<String, JTextArea> chatMap = new HashMap<>();
    private final List<String> preAnnList = new ArrayList<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    public ChatAdminGUI() {
        super("Admin Chat Manager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 650);
        setLocationRelativeTo(null);
        initLayout();
        AdminMessageHandler.setGUI(this);

        // events
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                String nick = clientList.getSelectedValue();
                if (nick != null) openTab(nick);
            }
        });

        sendBtn.addActionListener(e -> sendFromAdmin());
        inputField.addActionListener(e -> sendFromAdmin());

        preAnnBtn.addActionListener(e -> addPreAnnounce());
        sendFileBtn.addActionListener(e -> sendFile());
        kickBtn.addActionListener(e -> kickSelected());
        notifyBtn.setToolTipText("If selected, messages go to all clients");

        searchField.addKeyListener(new KeyAdapter() {
            public void keyReleased(KeyEvent e) { filterList(); }
        });

        startBtn.addActionListener(e -> startServerAction());
    }

    private void initLayout() {
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(400, getHeight()));
        JPanel leftTop = new JPanel();
        leftTop.add(new JLabel("Search:"));
        leftTop.add(searchField);
        leftTop.add(new JLabel("Port:"));
        leftTop.add(portField);
        leftTop.add(startBtn);
        leftPanel.add(leftTop, BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(clientList);
        leftPanel.add(listScroll, BorderLayout.CENTER);

        JPanel rightTop = new JPanel(new BorderLayout());
        tabbed.setPreferredSize(new Dimension(750, 350));
        JScrollPane logScroll = new JScrollPane(logArea);
        logArea.setEditable(false);
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabbed, logScroll);
        rightSplit.setResizeWeight(0.65);
        rightTop.add(rightSplit, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.add(inputField);
        bottom.add(sendBtn);
        bottom.add(notifyBtn);
        bottom.add(preAnnBtn);
        bottom.add(sendFileBtn);
        bottom.add(kickBtn);

        getContentPane().add(leftPanel, BorderLayout.WEST);
        getContentPane().add(rightTop, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
    }

    private void startServerAction() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid port");
            return;
        }
        boolean ok = Server.startServer(port);
        if (!ok) {
            JOptionPane.showMessageDialog(this, "Cannot start server on port " + port + ". Maybe in use.");
        } else {
            appendLog("Server started on port " + port);
            startBtn.setEnabled(false);
            portField.setEnabled(false);
        }
    }

    private void filterList() {
        String q = searchField.getText().trim().toLowerCase();
        filteredModel.clear();
        for (int i = 0; i < masterModel.size(); i++) {
            String nick = masterModel.get(i);
            if (nick.toLowerCase().contains(q)) filteredModel.addElement(nick);
        }
    }

    // admin actions
    private void sendFromAdmin() {
        String txt = inputField.getText().trim();
        if (txt.isEmpty()) return;

        String timestamped = "[" + sdf.format(new Date()) + "] " + txt;
        if (notifyBtn.isSelected()) {
            Server.broadcast("admin", timestamped);
            appendLog("[Admin->All] " + timestamped);
            // also show in admin logs
        } else {
            String nick = clientList.getSelectedValue();
            if (nick == null) {
                JOptionPane.showMessageDialog(this, "Select a client or enable NotifyAll");
                return;
            }
            Server.sendToClient(nick, "admin", timestamped);
            appendToTab(nick, "[Admin->You] " + timestamped);
            appendLog("[Admin->" + nick + "] " + timestamped);
        }
        inputField.setText("");
    }

    private void addPreAnnounce() {
        String txt = JOptionPane.showInputDialog(this, "Enter pre-announcement text:");
        if (txt != null && !txt.trim().isEmpty()) {
            preAnnList.add(txt.trim());
            appendLog("[PreAnn saved] " + txt.trim());
            // show option to send all pre announces
            int r = JOptionPane.showConfirmDialog(this, "Send all saved pre-announcements now?", "Send PreAnn", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) {
                for (String p : preAnnList) {
                    Server.broadcast("admin", "[" + sdf.format(new Date()) + "] " + p);
                    appendLog("[Admin PreAnn->All] " + p);
                }
                preAnnList.clear();
            }
        }
    }

    private void sendFile() {
        JFileChooser chooser = new JFileChooser();
        int res = chooser.showOpenDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        if (!f.exists()) return;
        if (notifyBtn.isSelected()) {
            Server.broadcastFile("admin", f);
            appendLog("[Admin File->All] " + f.getName());
        } else {
            String nick = clientList.getSelectedValue();
            if (nick == null) { JOptionPane.showMessageDialog(this, "Select a client"); return; }
            Server.sendFileToClient(nick, "admin", f);
            appendToTab(nick, "[Admin sent file] " + f.getName());
            appendLog("[Admin File->" + nick + "] " + f.getName());
        }
    }

    private void kickSelected() {
        String nick = clientList.getSelectedValue();
        if (nick == null) { JOptionPane.showMessageDialog(this, "Select a client to kick"); return; }
        String reason = JOptionPane.showInputDialog(this, "Reason for kick (optional):");
        if (reason == null) reason = "no reason";
        Server.kickClient(nick, reason);
        appendLog("[Admin] kicked " + nick + " Reason: " + reason);
    }

    // GUI helpers
    public void addClientToList(String nick) {
        SwingUtilities.invokeLater(() -> {
            if (!masterModel.contains(nick)) {
                masterModel.addElement(nick);
                filterList();
            }
            appendLog("[System] " + nick + " connected");
        });
    }

    public void removeClientFromList(String nick) {
        SwingUtilities.invokeLater(() -> {
            masterModel.removeElement(nick);
            filterList();
            // close tab if exists
            int idx = indexOfTab(nick);
            if (idx >= 0) tabbed.remove(idx);
            chatMap.remove(nick);
            appendLog("[System] " + nick + " disconnected");
        });
    }

    // when client sends message
    public void onClientMessage(String nick, String msg) {
        SwingUtilities.invokeLater(() -> {
            if (!masterModel.contains(nick)) {
                masterModel.addElement(nick);
                filterList();
            }
            openTab(nick);
            appendToTab(nick, "[" + nick + "] " + msg);
            appendLog("[" + nick + "->Admin] " + msg);
        });
    }

    private void openTab(String nick) {
        int idx = indexOfTab(nick);
        if (idx >= 0) { tabbed.setSelectedIndex(idx); return; }
        JTextArea ta = new JTextArea();
        ta.setEditable(false);
        JScrollPane sc = new JScrollPane(ta);
        tabbed.addTab(nick, sc);
        chatMap.put(nick, ta);
        tabbed.setSelectedComponent(sc);
    }

    private int indexOfTab(String nick) {
        for (int i = 0; i < tabbed.getTabCount(); i++) {
            if (tabbed.getTitleAt(i).equals(nick)) return i;
        }
        return -1;
    }

    private void appendToTab(String nick, String text) {
        JTextArea ta = chatMap.get(nick);
        if (ta == null) { openTab(nick); ta = chatMap.get(nick); }
        ta.append("[" + sdf.format(new Date()) + "] " + text + "\n");
    }

    private void appendLog(String s) {
        logArea.append("[" + sdf.format(new Date()) + "] " + s + "\n");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ChatAdminGUI gui = new ChatAdminGUI();
            gui.setVisible(true);
        });
    }
}
