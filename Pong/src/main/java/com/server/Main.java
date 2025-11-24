package com.server;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import org.java_websocket.WebSocket;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Servidor WebSocket para Pong (Autoritativo).
 * Gestiona el lobby (retos) y lanza GameSessions para las partidas.
 * También acepta comandos de admin por consola.
 */
public class Main extends WebSocketServer {

    public static final int DEFAULT_PORT = 3000;

    // JSON keys
    private static final String K_TYPE = "type";
    private static final String K_MESSAGE = "message";
    private static final String K_TTL = "ttl_ms";
    private static final String K_NAME = "name";
    private static final String K_B64 = "b64";

    // message types
    private static final String T_CLIENTS = "clients";
    private static final String T_TEXT = "text";
    private static final String T_IMAGE = "image";

    // Extensions permeses
    private static final Set<String> ALLOWED_EXTS = Set.of("png", "jpg", "jpeg");

    // Ajuda
    private static final String HELP_TEXT = """
            ── Ajuda de comandes ───────────────────────────────────────────────
            /help → Mostra aquesta ajuda.
            /text <missatge>
                    → Envia un missatge de text als clients.
                    Exemple: /text Hola a tothom!
            /image <spec>
                    → Envia una imatge PNG/JPG/JPEG (no s'accepta .b64).
                      • /image classpath:ietilogo.png
                      • /image ./src/main/resources/ietilogo.png
            /list → Mostra la llista d'identificadors de clients connectats.
            /quit → Atura el servidor.
            ────────────────────────────────────────────────────────────────────
            """;

    private final ClientRegistry clients;
    private final CountDownLatch quitLatch;

    // --- logica juego ---
    private final Map<WebSocket, GameSession> activeGames;
    private volatile WebSocket raspberryPiSocket = null;

    // --- logger ---
    private final DatabaseLogger logger = DatabaseLogger.getInstance();

    public Main(InetSocketAddress address, CountDownLatch quitLatch) {
        super(address);
        this.clients = new ClientRegistry();
        this.quitLatch = quitLatch;
        this.activeGames = new ConcurrentHashMap<>();
    }

    // Helpers
    private static JSONObject msg(String type) {
        return new JSONObject().put(K_TYPE, type);
    }

    public void sendSafe(WebSocket to, String payload) {
        if (to == null) return;
        try {
            to.send(payload);
        } catch (WebsocketNotConnectedException e) {
            String name = clients.cleanupDisconnected(to);
            System.out.println("Client desconnectat durant send: " + name);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void broadcastAll(String payload) {
        for (Map.Entry<WebSocket, String> e : clients.snapshot().entrySet()) {
            sendSafe(e.getKey(), payload);
        }
    }

    private void sendClientsListToAll() {
        JSONArray list = clients.currentNames();
        for (Map.Entry<WebSocket, String> e : clients.snapshot().entrySet()) {
            JSONObject rst = msg(T_CLIENTS)
                    .put("id", e.getValue())
                    .put("list", list);
            sendSafe(e.getKey(), rst.toString());
        }
    }

    // WebSocketServer overrides
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("🔌 Nueva conexion desde: " + conn.getRemoteSocketAddress());
        // --- logger ---
        logger.log("CONNECTION_OPEN", "Intento de conexion desde: " + conn.getRemoteSocketAddress());
        // ---
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String name = clients.remove(conn);
        if (name == null) {
            System.out.println("Client desconnectat (no registrat): " + conn.getRemoteSocketAddress());
            // --- ¡LOG AÑADIDO! ---
            logger.log("DISCONNECT", "Cliente no registrado desconectado: " + conn.getRemoteSocketAddress());
            // ---
            return;
        }

        // --- ¡LOG AÑADIDO! ---
        logger.log("DISCONNECT", "Cliente desconectado: " + name);
        // ---

        // --- LÓGICA DE JUEGO ---
        GameSession game = activeGames.remove(conn);
        if (game != null) {
            System.out.println("Jugador " + name + " ha abandonado una partida.");

            // --- ¡LOG AÑADIDO! ---
            logger.log("GAME_ABANDON", "Jugador " + name + " ha abandonado la partida.");
            // ---

            game.stopGame(conn); 

            WebSocket otherPlayer = game.getOtherPlayer(conn);
            if (otherPlayer != null) {
                activeGames.remove(otherPlayer);
                System.out.println("Jugador removido de activeGames: " + clients.nameBySocket(otherPlayer));
            }
            
            System.out.println("Partida finalizada por desconexión de: " + name);
        }
        
        if (conn == raspberryPiSocket) {
            System.out.println("Raspberry Pi desconectada.");
            raspberryPiSocket = null;
        }
        // --- FIN LÓGICA DE JUEGO ---

        System.out.println("Client desconnectat: " + name);
        sendClientsListToAll();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            // --- 1. Lógica de Registro (Nickname) ---
            if (message.startsWith("NICKNAME:")) {
                String nickname = message.substring("NICKNAME:".length()).trim();
                boolean ok = clients.registerNickname(nickname, conn);
                if (ok) {
                    conn.send("ACCEPTED");
                    sendClientsListToAll();
                    System.out.println("Client registrat amb nom: " + nickname);

                    // --- ¡LOG AÑADIDO! ---
                    String ip = conn.getRemoteSocketAddress().toString();
                    logger.log("CONNECTION", "Cliente registrado: " + nickname + " (IP: " + ip + ")");
                    // ---

                    if (nickname.equalsIgnoreCase("Pantalla") || nickname.equalsIgnoreCase("RaspberryPi") || nickname.equalsIgnoreCase("Pantalla_Matrix")) {
                        System.out.println("¡Raspberry Pi detectada y registrada!");
                        raspberryPiSocket = conn;
                    }

                } else {
                    conn.send("REJECTED"); 
                    conn.close();
                    System.out.println("Intent fallit: nom duplicat → " + nickname);
                }
                return;
            }

            // --- 2. Validar si está Registrado ---
            String senderName = clients.nameBySocket(conn);
            if (senderName == null) {
                conn.send("REJECTED:No registrat");
                conn.close();
                System.out.println("Client sense nom intentant enviar: " + message);
                return;
            }

            System.out.println("Mensaje recibido de " + senderName + ": " + message);

            // --- 3. Lógica "En Partida" ---
            GameSession game = activeGames.get(conn);
            if (game != null) {
                System.out.println("🎮 Mensaje en partida de " + senderName + ": " + message);

                if (message.trim().startsWith("{")) {
                    JSONObject msg = new JSONObject(message);
                    String type = msg.optString("type", "");
                    
                    if (type.equals("move")) {
                        double y_pos = msg.optDouble("y_pos", 0.5); 
                        System.out.println("MOVIMIENTO RECIBIDO Y PROCESADO de " + senderName + ": " + y_pos);
                        game.processMove(conn, y_pos);
                    } else {
                        System.out.println("Otro mensaje JSON en partida - Tipo: " + type);
                    }
                } else {
                    System.out.println("Mensaje de texto en partida: " + message);
                }
                return; 
            }

            // --- 4. Lógica de "Lobby" (si no está en partida) ---
            JSONObject msg = new JSONObject(message);
            String type = msg.optString("type", "");

            if (type.equals("config_request")) {
                try (InputStream is = Thread.currentThread()
                        .getContextClassLoader()
                        .getResourceAsStream("data/config.txt")) {
                    if (is == null) {
                        System.out.println("No s'ha trobat config.txt dins resources/data");
                        return;
                    }
                    String content = new String(is.readAllBytes());
                    sendSafe(conn, content);
                    System.out.println("Respost config_request amb: " + content);
                } catch (Exception e) {
                    System.out.println("Error llegint config.txt: " + e.getMessage());
                }
            } 
            
            else if (type.equals("challenge")) {
                String targetName = msg.optString("to", "");
                String fromName = msg.optString("from", senderName); 
                
                WebSocket targetSocket = clients.socketByName(targetName);
                
                System.out.println("PROCESANDO CHALLENGE: De " + fromName + " para " + targetName);
                
                if (targetSocket != null) {
                    JSONObject challengeMsg = new JSONObject()
                            .put("type", "challenge_received")
                            .put("from", fromName);
                    
                    sendSafe(targetSocket, challengeMsg.toString());
                    System.out.println("Challenge enviado a " + targetName);
                } else {
                    System.out.println("Target no encontrado: " + targetName);
                    JSONObject errorMsg = new JSONObject()
                            .put("type", "error")
                            .put("message", "Jugador no encontrado: " + targetName);
                    sendSafe(conn, errorMsg.toString());
                }
            }
            
            else if (type.equals("challenge_response")) {
                String targetName = msg.optString("to", ""); 
                boolean accepted = msg.optBoolean("accepted", false);
                WebSocket targetSocket = clients.socketByName(targetName);

                if (targetSocket != null) {
                    if (accepted) {
                        System.out.println(senderName + " ACEPTÓ el reto de " + targetName);

                        // --- logg ---
                        logger.log("GAME_START", "Partida iniciada: " + senderName + " vs " + targetName);
                        // ---

                        GameSession newGame = new GameSession(conn, senderName, targetSocket, targetName, this);

                        activeGames.put(conn, newGame);
                        activeGames.put(targetSocket, newGame);
                        
                        if (raspberryPiSocket != null) {
                            System.out.println("Añadiendo Pi como espectadora...");
                            newGame.addSpectator(raspberryPiSocket, "Pantalla");
                        }

                        newGame.start();
                        
                    } else {
                        JSONObject declinedMsg = new JSONObject()
                                .put("type", "challenge_declined")
                                .put("from", senderName); 
                        sendSafe(targetSocket, declinedMsg.toString());
                        System.out.println(senderName + " RECHAZÓ el reto de " + targetName);
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error processant missatge: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("Servidor WebSocket engegat al port: " + getPort());
        setConnectionLostTimeout(100);
        System.out.println(HELP_TEXT);
        Thread repl = new Thread(this::replWithHistory, "stdin-broadcast-loop");
        repl.setDaemon(true); 
        repl.start();
    }

    // ─────────────────────── Consola JLine (historial + edició) ───────────────────────
    private void replWithHistory() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).dumb(false).build();
            Parser parser = new DefaultParser();
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(parser)
                    .build();

            final int TTL_MS = 5000;

            while (true) {
                String line;
                try {
                    line = reader.readLine("> ");
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    safeStopServer();
                    quitLatch.countDown();
                    break;
                }

                if (line == null) continue;
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("/quit")) {
                    System.out.println("Aturant servidor…");
                    safeStopServer();
                    quitLatch.countDown();
                    break;
                }

                if (line.equalsIgnoreCase("/help")) {
                    System.out.println(HELP_TEXT);
                    continue;
                }

                if (line.equalsIgnoreCase("/list")) {
                    System.out.println("Connectats: " + clients.currentNames());
                    System.out.println("En partida: " + activeGames.values().stream().distinct().count() + " partides");
                    continue;
                }

                if (line.startsWith("/text ")) {
                    String text = line.substring(6).trim();
                    if (text.isEmpty()) {
                        System.out.println("Ús: /text <missatge>");
                        continue;
                    }
                    JSONObject payload = msg(T_TEXT)
                            .put(K_MESSAGE, text)
                            .put(K_TTL, TTL_MS);
                    broadcastAll(payload.toString());
                    continue;
                }

                if (line.startsWith("/image ")) {
                    String spec = line.substring(7).trim();
                    if (spec.isEmpty()) {
                        System.out.println("Ús: /image <spec>  (exemple: /image classpath:ietilogo.png)");
                        continue;
                    }
                    try {
                        ImageLoadResult img = loadImageBase64(spec);
                        if (img == null) {
                            System.out.println("No s'ha pogut carregar (o extensió no permesa): " + spec);
                            continue;
                        }
                        JSONObject payload = msg(T_IMAGE)
                                .put(K_NAME, img.displayName)
                                .put(K_B64, img.base64)
                                .put(K_TTL, 5000);
                        broadcastAll(payload.toString());
                    } catch (Exception e) {
                        System.out.println("Error llegint imatge: " + e.getMessage());
                    }
                    continue;
                }

                System.out.println("Ordre desconeguda. Escriu /help per veure l'ajuda.");
            }
        } catch (Exception e) {
            System.out.println("stdin loop ended: " + e.getMessage());
        }
    }

    private void safeStopServer() {
        try {
            stop(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("Avis: stop() ha llençat: " + e.getMessage());
        }
    }

    // ───────────────────────────── Helpers d’imatge ─────────────────────────────
    private static class ImageLoadResult {
        final String displayName;
        final String base64;
        ImageLoadResult(String name, String b64) { this.displayName = name; this.base64 = b64; }
    }

    private static ImageLoadResult loadImageBase64(String spec) throws Exception {
        String lower = spec.toLowerCase(Locale.ROOT);
        if (lower.startsWith("classpath:")) {
            String resPath = spec.substring("classpath:".length());
            if (resPath.startsWith("/")) resPath = resPath.substring(1);
            if (!isAllowedExt(resPath)) return null;

            try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resPath)) {
                if (is == null) return null;
                byte[] data = is.readAllBytes();
                String b64 = Base64.getEncoder().encodeToString(data);
                String name = deriveDisplayName(resPath);
                return new ImageLoadResult(name, b64);
            }
        } else {
            File f = new File(spec);
            if (!f.exists() || !f.isFile()) return null;
            if (!isAllowedExt(f.getName())) return null;

            byte[] data = Files.readAllBytes(f.toPath());
            String b64 = Base64.getEncoder().encodeToString(data);
            String name = f.getName();
            return new ImageLoadResult(name, b64);
        }
    }

    private static boolean isAllowedExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTS.contains(ext);
    }

    private static String deriveDisplayName(String pathish) {
        int idx = pathish.lastIndexOf('/');
        return (idx >= 0) ? pathish.substring(idx + 1) : pathish;
    }

    // ───────────────────────────── Lifecycle util ─────────────────────────────
    private static void registerShutdownHook(Main server, CountDownLatch quitLatch) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Aturant servidor (shutdown hook)...");
            try { server.stop(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {}
            quitLatch.countDown();
            System.out.println("Servidor aturat.");
        }));
    }

    public static void main(String[] args) {
        CountDownLatch quitLatch = new CountDownLatch(1);
        Main server = new Main(new InetSocketAddress(DEFAULT_PORT), quitLatch);
        server.start();
        registerShutdownHook(server, quitLatch);

        System.out.println("Servidor WebSocket en execució al port " + DEFAULT_PORT + ". Prem /quit o Ctrl+D per sortir.");
        try {
            quitLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // --- cerrar logger---
        System.out.println("Cerrando el logger...");
        server.logger.close(); 
        System.out.println("Sortint…");
    }
}
