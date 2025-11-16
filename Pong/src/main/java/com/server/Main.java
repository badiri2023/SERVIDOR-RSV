package com.server;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.exceptions.WebsocketNotConnectedException;

import org.json.JSONObject;
import org.json.JSONArray;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class Main extends WebSocketServer {

    public static final int DEFAULT_PORT = 443;

    // JSON Keys
    private static final String K_TYPE = "type";
    private static final String K_MESSAGE = "message";
    private static final String K_USERNAME = "userName";
    private static final String K_PLAYERS = "players";
    private static final String K_OPPONENT = "opponent";
    private static final String K_FROM = "from";
    private static final String K_ACCEPTED = "accepted";
    private static final String K_TO = "to";

    // Message Types (Client -> Server)
    private static final String T_USER_INFO = "userInfo";
    private static final String T_GET_PLAYERS = "getPlayers";
    private static final String T_CLIENT_INVITE = "clientInvite";
    private static final String T_INVITATION_RESPONSE = "invitationResponse";
    
    // Message Types (Server -> Client)
    private static final String T_WELCOME = "welcome";
    private static final String T_PLAYERS_LIST = "playersList";
    private static final String T_CLIENT_INVITE_RECEIVED = "clientInvite";
    private static final String T_INVITATION_RESPONSE_RECEIVED = "invitationResponse";
    private static final String T_GAME_START = "gameStart";
    private static final String T_ERROR = "error";

    private final Map<WebSocket, String> clients = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> usernames = new ConcurrentHashMap<>();
    private final Map<String, PendingInvitation> pendingInvitations = new ConcurrentHashMap<>();
    private final Set<String> usedNames = ConcurrentHashMap.newKeySet();

    public Main(InetSocketAddress address) {
        super(address);
    }

    /**
     * Envíar mensaje al cliente conectado
     */
    private void sendSafe(WebSocket to, String payload) {
        if (to == null || !to.isOpen()) return;
        try {
            to.send(payload);
        } catch (WebsocketNotConnectedException e) {
            String username = clients.remove(to);
            if (username != null) {
                usernames.remove(username);
                usedNames.remove(username);
                System.out.println("Client disconnected during send: " + username);
                broadcastPlayersList();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("New connection: " + conn.getRemoteSocketAddress());
        
        // ✅ ENVIAR MENSAJE DE BIENVENIDA AL CLIENTE
        JSONObject welcomeMessage = new JSONObject();
        welcomeMessage.put(K_TYPE, T_WELCOME);
        welcomeMessage.put(K_MESSAGE, "¡Hola! Te has conectado al servidor PONG correctamente.");
        
        sendSafe(conn, welcomeMessage.toString());
        System.out.println("Welcome message sent to new client");
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = clients.remove(conn);
        if (username != null) {
            usernames.remove(username);
            usedNames.remove(username);
            System.out.println("Client disconnected: " + username);
            
            cleanupPendingInvitations(username);
            broadcastPlayersList();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("Message received: " + message);
        
        JSONObject obj;
        try {
            obj = new JSONObject(message);
        } catch (Exception ex) {
            System.err.println("Invalid JSON received");
            sendError(conn, "Invalid JSON format");
            return;
        }

        String type = obj.optString(K_TYPE, "");
        
        switch (type) {
            case T_USER_INFO:
                handleUserInfo(conn, obj);
                break;
            case T_GET_PLAYERS:
                handleGetPlayers(conn);
                break;
            case T_CLIENT_INVITE:
                handleClientInvite(conn, obj);
                break;
            case T_INVITATION_RESPONSE:
                handleInvitationResponse(conn, obj);
                break;
            default:
                System.out.println("Unknown message type: " + type);
                sendError(conn, "Unknown message type: " + type);
                break;
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
        if (conn != null) {
            String username = clients.remove(conn);
            if (username != null) {
                usernames.remove(username);
                usedNames.remove(username);
                System.out.println("Client error - removed: " + username);
                broadcastPlayersList();
            }
        }
    }

    @Override
    public void onStart() {
        System.out.println("🎯 PONG WebSocket server started on port: " + getPort());
        setConnectionLostTimeout(100);
    }

    // ========== MANEJADORES DE MENSAJES ==========

    private void handleUserInfo(WebSocket conn, JSONObject obj) {
        String userName = obj.optString(K_USERNAME, "").trim();
        
        if (userName.isEmpty()) {
            sendError(conn, "Nombre de usuario no válido");
            return;
        }
        
        if (usedNames.contains(userName)) {
            sendError(conn, "Nombre de usuario ya en uso: " + userName);
            return;
        }
        
        String oldUsername = clients.get(conn);
        if (oldUsername != null) {
            usernames.remove(oldUsername);
            usedNames.remove(oldUsername);
        }
        
        clients.put(conn, userName);
        usernames.put(userName, conn);
        usedNames.add(userName);
        
        System.out.println("✅ Client registered: " + userName);
        
        // ✅ ENVIAR CONFIRMACIÓN DE REGISTRO
        JSONObject response = new JSONObject();
        response.put(K_TYPE, "userRegistered");
        response.put(K_USERNAME, userName);
        response.put(K_MESSAGE, "Usuario registrado correctamente: " + userName);
        
        sendSafe(conn, response.toString());
        broadcastPlayersList();
    }

    private void handleGetPlayers(WebSocket conn) {
        sendPlayersList(conn);
    }

    private void handleClientInvite(WebSocket conn, JSONObject obj) {
        String fromPlayer = obj.optString(K_FROM, "");
        String opponentName = obj.optString(K_OPPONENT, "");
        
        String currentUser = clients.get(conn);
        if (currentUser == null || !currentUser.equals(fromPlayer)) {
            sendError(conn, "No estás registrado correctamente");
            return;
        }
        
        WebSocket opponentSocket = usernames.get(opponentName);
        if (opponentSocket == null) {
            sendError(conn, "Jugador no encontrado: " + opponentName);
            return;
        }
        
        if (opponentName.equals(fromPlayer)) {
            sendError(conn, "No puedes invitarte a ti mismo");
            return;
        }
        
        String invitationId = UUID.randomUUID().toString();
        PendingInvitation invitation = new PendingInvitation(
            invitationId, fromPlayer, opponentName, System.currentTimeMillis()
        );
        pendingInvitations.put(invitationId, invitation);
        
        // ✅ ENVIAR INVITACIÓN CON MENSAJE
        JSONObject inviteMessage = new JSONObject();
        inviteMessage.put(K_TYPE, T_CLIENT_INVITE_RECEIVED);
        inviteMessage.put(K_FROM, fromPlayer);
        inviteMessage.put("invitationId", invitationId);
        inviteMessage.put(K_MESSAGE, fromPlayer + " te ha invitado a jugar PONG!");
        
        sendSafe(opponentSocket, inviteMessage.toString());
        
        System.out.println("🎯 Invitation sent: " + fromPlayer + " -> " + opponentName);
        
        JSONObject confirmation = new JSONObject();
        confirmation.put(K_TYPE, "invitationSent");
        confirmation.put(K_OPPONENT, opponentName);
        confirmation.put(K_MESSAGE, "Invitación enviada a " + opponentName);
        
        sendSafe(conn, confirmation.toString());
    }

    private void handleInvitationResponse(WebSocket conn, JSONObject obj) {
        String invitationId = obj.optString("invitationId", "");
        boolean accepted = obj.optBoolean(K_ACCEPTED, false);
        String responder = obj.optString(K_FROM, "");
        String toPlayer = obj.optString(K_TO, "");
        
        PendingInvitation invitation = pendingInvitations.get(invitationId);
        if (invitation == null) {
            sendError(conn, "Invitación no encontrada o expirada");
            return;
        }
        
        if (!invitation.getToPlayer().equals(responder)) {
            sendError(conn, "No eres el destinatario de esta invitación");
            return;
        }
        
        WebSocket fromSocket = usernames.get(invitation.getFromPlayer());
        WebSocket toSocket = usernames.get(responder);
        
        if (fromSocket == null || toSocket == null) {
            sendError(conn, "Uno de los jugadores no está disponible");
            pendingInvitations.remove(invitationId);
            return;
        }
        
        JSONObject response = new JSONObject();
        response.put(K_TYPE, T_INVITATION_RESPONSE_RECEIVED);
        response.put(K_FROM, responder);
        response.put(K_ACCEPTED, accepted);
        response.put("invitationId", invitationId);
        
        String messageText = accepted ? 
            responder + " ha aceptado tu invitación de PONG!" : 
            responder + " ha rechazado tu invitación de PONG";
        response.put(K_MESSAGE, messageText);
        
        sendSafe(fromSocket, response.toString());
        
        if (accepted) {
            startGameSession(invitation.getFromPlayer(), responder, invitationId);
        } else {
            System.out.println("❌ Invitation rejected: " + invitationId);
        }
        
        pendingInvitations.remove(invitationId);
    }

    // ========== MÉTODOS AUXILIARES ==========

    private void sendPlayersList(WebSocket conn) {
        JSONObject response = new JSONObject();
        response.put(K_TYPE, T_PLAYERS_LIST);
        
        JSONArray players = new JSONArray();
        String currentUser = clients.get(conn);
        
        for (String username : usernames.keySet()) {
            if (!username.equals(currentUser)) {
                players.put(username);
            }
        }
        
        response.put(K_PLAYERS, players);
        response.put(K_MESSAGE, "Lista de jugadores actualizada");
        
        sendSafe(conn, response.toString());
    }

    private void broadcastPlayersList() {
        JSONObject message = new JSONObject();
        message.put(K_TYPE, T_PLAYERS_LIST);
        
        JSONArray allPlayers = new JSONArray();
        for (String username : usernames.keySet()) {
            allPlayers.put(username);
        }
        
        message.put(K_PLAYERS, allPlayers);
        message.put(K_MESSAGE, "Lista de jugadores actualizada");
        
        String messageStr = message.toString();
        for (WebSocket conn : clients.keySet()) {
            sendSafe(conn, messageStr);
        }
        
        System.out.println("📊 Players list updated: " + allPlayers);
    }

    private void startGameSession(String player1, String player2, String invitationId) {
        String gameId = UUID.randomUUID().toString();
        
        JSONObject gameStart = new JSONObject();
        gameStart.put(K_TYPE, T_GAME_START);
        gameStart.put("gameId", gameId);
        gameStart.put("player1", player1);
        gameStart.put("player2", player2);
        gameStart.put(K_MESSAGE, "¡Partida iniciada! " + player1 + " vs " + player2);
        
        WebSocket socket1 = usernames.get(player1);
        WebSocket socket2 = usernames.get(player2);
        
        if (socket1 != null) sendSafe(socket1, gameStart.toString());
        if (socket2 != null) sendSafe(socket2, gameStart.toString());
        
        System.out.println("🎮 Game started: " + player1 + " vs " + player2);
    }

    private void cleanupPendingInvitations(String playerName) {
        pendingInvitations.entrySet().removeIf(entry -> 
            entry.getValue().getFromPlayer().equals(playerName) || 
            entry.getValue().getToPlayer().equals(playerName)
        );
    }

    private void sendError(WebSocket conn, String errorMessage) {
        JSONObject error = new JSONObject();
        error.put(K_TYPE, T_ERROR);
        error.put(K_MESSAGE, errorMessage);
        sendSafe(conn, error.toString());
    }

    // ========== MÉTODOS ESTÁTICOS ==========

    private static void registerShutdownHook(Main server) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping server...");
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
            System.out.println("Server stopped.");
        }));
    }

    private static void awaitForever() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port, using default: " + DEFAULT_PORT);
            }
        }
        
        Main server = new Main(new InetSocketAddress(port));
        registerShutdownHook(server);
        server.start();
        System.out.println("🎯 PONG Server running on port " + port + ". Press Ctrl+C to stop.");
        System.out.println("📍 Server URL: wss://matrixplay5.ieti.site:" + port);
        awaitForever();
    }
}