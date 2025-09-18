package DETAI1;


public class AdminMessageHandler {
    private static ChatAdminGUI gui = null;

    public static void setGUI(ChatAdminGUI g) {
        gui = g;
    }

    public static void receiveFromClient(String nick, String msg) {
        if (gui != null) gui.onClientMessage(nick, msg);
    }

    public static void clientJoined(String nick) {
        if (gui != null) gui.addClientToList(nick);
    }

    public static void clientLeft(String nick) {
        if (gui != null) gui.removeClientFromList(nick);
    }
}