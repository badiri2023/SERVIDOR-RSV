package com.project.server;

import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Servidor WebSocket: només broadcast.
 * Ordes per consola (amb historial i edició de línia):
 *   /help
 *   /text <missatge>
 *   /image <spec>   on <spec> és:
 *       - un path de fitxer (PNG/JPG/GIF...) → s'encoda a Base64
 *       - un path .b64 → es llegeix la cadena Base64
 *       - "classpath:<res>" → es carrega des de src/main/resources (p.ex. classpath:ietilogo.png)
 *   /list
 *   /quit
 *
 * Tipus de missatges cap al client:
 *
 * {
 *  "type": "text",
 *  "message": "Hola món!",
 *  "ttl_ms": 5000
 * }
 *
 * {
 *  "type": "image",
 *  "name": "ietilogo.png",
 *  "b64": "<cadena Base64 molt llarga>",
 *  "ttl_ms": 5000
 * }
 */

public class Main extends WebSocketServer {

    public static final int DEFAULT_PORT = 3000;

    //private static final List<String> CHARACTER_NAMES = Arrays.asList("Mario", "Luigi", "Peach");

    // JSON keys
    private static final String K_TYPE = "type";
    private static final String K_MESSAGE = "message";
    private static final String K_TTL = "ttl_ms";
    private static final String K_NAME = "name";
    private static final String K_B64  = "b64";

    // message types
    private static final String T_CLIENTS = "clients";
    private static final String T_TEXT  = "text";
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

    public Main(InetSocketAddress address, CountDownLatch quitLatch) {
        super(address);
        this.clients = new ClientRegistry();
        this.quitLatch = quitLatch;
    }

    // Helpers
    private static JSONObject msg(String type) { return new JSONObject().put(K_TYPE, type); }

    private void sendSafe(WebSocket to, String payload) {
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
        System.out.println("🔌 Nueva conexión desde: " + conn.getRemoteSocketAddress());
    }



    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String name = clients.remove(conn);
        System.out.println("Client desconnectat: " + name);
        sendClientsListToAll();
    }
@Override
public void onMessage(WebSocket conn, String message) {
    try {
        if (message.startsWith("NICKNAME:")) {
            String nickname = message.substring("NICKNAME:".length()).trim();
            boolean ok = clients.registerNickname(nickname, conn);
            if (ok) {
                conn.send("ACCEPTED");
                sendClientsListToAll();
                System.out.println("Client registrat amb nom: " + nickname);

                JSONObject hola = new JSONObject()
                    .put("type", "text")
                    .put("message", "Hola " + nickname)
                    .put("ttl_ms", 5000);
                broadcastAll(hola.toString());
            } else {
                conn.send("REJECTED");
                conn.close(); // ❗ Cierra si el nombre está duplicado
                System.out.println("Intent fallit: nom duplicat → " + nickname);
            }
            return;
        }
        // Si el cliente aún no está registrado, ignoramos o cerramos
        if (clients.nameBySocket(conn) == null) {
            conn.send("REJECTED:No registrat");
            conn.close(); // ❗ Cierra si no ha enviado nickname
            System.out.println("Client sense nom intentant enviar: " + message);
            return;
        }

        // Después: procesar mensajes JSON
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
        // Mostra la mateixa ajuda que /help
        System.out.println(HELP_TEXT);
        Thread repl = new Thread(this::replWithHistory, "stdin-broadcast-loop");
        repl.setDaemon(true); // no impedeix la sortida si tot s'ha parat
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
                    // Ctrl+C al REPL: ignorem i continuem
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D: atura i senyalitza sortida
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
            // 1s de timeout per tancar netament
            stop(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Si falla, forcem una sortida més endavant via quitLatch
            System.out.println("Avis: stop() ha llençat: " + e.getMessage());
        }
    }

    // ───────────────────────────── Helpers d’imatge ─────────────────────────────
    private static class ImageLoadResult {
        final String displayName;
        final String base64;
        ImageLoadResult(String name, String b64) { this.displayName = name; this.base64 = b64; }
    }

    /** Retorna Base64 d'una imatge (PNG/JPG/JPEG) via path o classpath:. No accepta .b64 */
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
        System.out.println("Sortint…");
        // Si vols ser extra explícit:
        // System.exit(0);
    }
}