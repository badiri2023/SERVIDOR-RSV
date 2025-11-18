package com.server;

import org.java_websocket.WebSocket;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameSession implements Runnable {

    // --- Constantes del Juego ---
    private static final int WINNING_SCORE = 5; // Gana el primero que llega a 5
    private static final int LOADING_SCREEN_MS = 3000; // 3 seg de carga
    private static final int PRE_COUNTDOWN_MS = 2000; // 2 seg para leer quién saca

    private final WebSocket p1;
    private final String p1_name;
    private final WebSocket p2;
    private final String p2_name;
    private final Map<WebSocket, String> spectators = new ConcurrentHashMap<>();

    // Estado del juego
    private volatile double p1_y = 0.5, p2_y = 0.5;
    private volatile double ball_x = 0.5, ball_y = 0.5;
    private volatile double ball_vx = 0.01, ball_vy = 0.005;
    private volatile int score1 = 0, score2 = 0;

    private volatile boolean running = true;
    private final Thread gameThread;
    private final Main server;

    public GameSession(WebSocket p1, String p1_name, WebSocket p2, String p2_name, Main server) {
        this.p1 = p1; // p1 és qui va acceptar el repte (JUGADOR 1 - ESQUERRA)
        this.p1_name = p1_name;
        this.p2 = p2; // p2 és qui va reptar originalment (JUGADOR 2 - DRETA)
        this.p2_name = p2_name;
        this.server = server;
        this.gameThread = new Thread(this);
    }

    public void start() {
        this.gameThread.start();
    }

    /**
     * El Game Loop principal - AJUSTADO A TU FLUJO
     */
    @Override
    public void run() {
        try {
            // 1. VISTA DE CARGA (Vista 3)
            JSONObject startMsg = new JSONObject()
                    .put("type", "game_start")
                    .put("opponent", p2_name)
                    .put("role", "p1"); // p1 (el que aceptó) es jugador 1
            server.sendSafe(p1, startMsg.toString());

            startMsg.put("opponent", p1_name)
                    .put("role", "p2"); // p2 (el que retó) es jugador 2
            server.sendSafe(p2, startMsg.toString());

            // 2. ANIMACIÓN "CHOOSING STARTER"
            JSONObject choosingMsg = new JSONObject().put("type", "choosing_starter");
            broadcast(choosingMsg.toString());

            // Esperamos los 3 segundos de la pantalla de carga / animación
            Thread.sleep(LOADING_SCREEN_MS);

            // 3. ANUNCIAR QUIÉN SACA
            JSONObject announceMsg = new JSONObject()
                    .put("type", "text")
                    .put("message", "Starts Player\n" + p2_name) // Formato vertical para la Pi
                    .put("ttl_ms", PRE_COUNTDOWN_MS); // Mostrar 2 segundos
            broadcast(announceMsg.toString());

            // Esperar 2 segundos para que se lea el mensaje
            Thread.sleep(PRE_COUNTDOWN_MS);

            // 4. CUENTA ATRÁS (en todos los dispositivos)
            for (int i = 3; i > 0; i--) {
                JSONObject countdownMsg = new JSONObject()
                        .put("type", "countdown")
                        .put("value", i);
                broadcast(countdownMsg.toString());
                Thread.sleep(1000); // Espera 1 segundo
            }

            // 5. ¡GO!
            JSONObject goMsg = new JSONObject()
                    .put("type", "countdown")
                    .put("value", "GO!");
            broadcast(goMsg.toString());
            Thread.sleep(500); // Pausa en "GO!"

            // 6. POSICIÓN INICIAL
            // Enviamos un único estado inicial ANTES del bucle
            // para que todos pinten la posición 0-0.
            broadcast(createGameStateJSON().toString()); // <--- ¡CORREGIDO!

            // 7. BUCLE DE JUEGO
            while (running) {
                updatePhysics();
                broadcast(createGameStateJSON().toString()); // <--- ¡CORREGIDO!
                Thread.sleep(16); // ~60 FPS
            }

        } catch (InterruptedException e) {
            System.out.println("Game loop interrumpido.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("Error en GameSession: " + e.getMessage());
        } finally {
            System.out.println("Partida finalizada.");
        }
    }

    /** Crea el JSON de estado del juego */
    private JSONObject createGameStateJSON() {
        return new JSONObject()
                .put("type", "game_state")
                .put("p1_y", p1_y)
                .put("p2_y", p2_y)
                .put("ball_x", ball_x)
                .put("ball_y", ball_y)
                .put("score1", score1)
                .put("score2", score2);
    }
    
    /** El cliente (Móvil/Desktop) llama a esto con su nueva posición */
    public void updatePaddle(WebSocket player, double y_pos) {
        double clamped_y = Math.max(0.0, Math.min(1.0, y_pos));
        if (player == p1) this.p1_y = clamped_y;
        else if (player == p2) this.p2_y = clamped_y;
    }

    /** Detiene el juego si un jugador se desconecta */
    public void stopGame(WebSocket disconnectedPlayer) {
        if (!running) return; // Ya se ha parado
        this.running = false;
        this.gameThread.interrupt();

        // El jugador que queda es el ganador por abandono
        WebSocket remainingPlayer = (disconnectedPlayer == p1) ? p2 : p1;
        JSONObject endMsg = new JSONObject()
                .put("type", "game_over")
                .put("reason", "Oponente desconectado");
        
        // Se lo enviamos al jugador que queda
        server.sendSafe(remainingPlayer, endMsg.toString());
    }

    /** Detiene el juego porque alguien ha ganado */
    private void endGame(String winnerName) {
        if (!running) return; // Ya se ha parado
        this.running = false;
        this.gameThread.interrupt();

        JSONObject endMsg = new JSONObject()
                .put("type", "game_over")
                .put("winner", winnerName);
        
        // Se lo enviamos a TODOS (jugadores y Pi)
        broadcast(endMsg.toString());
    }

    public WebSocket getOtherPlayer(WebSocket player) {
        return (player == p1) ? p2 : p1;
    }
    
    public void addSpectator(WebSocket spec, String name) {
        spectators.put(spec, name);
    }
    
    private void broadcast(String message) {
        server.sendSafe(p1, message);
        server.sendSafe(p2, message);
        for (WebSocket spec : spectators.keySet()) {
            server.sendSafe(spec, message);
        }
    }

    /**
     * Lógica de física de Pong - MODIFICADA CON CONDICIÓN DE VICTORIA
     */
    private void updatePhysics() {
        // Mover pelota
        ball_x += ball_vx;
        ball_y += ball_vy;

        // Colisión con paredes (arriba/abajo)
        if (ball_y < 0) { ball_y = 0; ball_vy = -ball_vy; }
        if (ball_y > 1) { ball_y = 1; ball_vy = -ball_vy; }

        // Colisión con pala 1 (izquierda, p1)
        if (ball_x < 0.05) {
            if (ball_y > p1_y - 0.1 && ball_y < p1_y + 0.1) {
                ball_x = 0.05;
                ball_vx = -ball_vx;
            } else {
                // Punto para P2
                score2++;
                resetBall(false); // Saca P2
            }
        }

        // Colisión con pala 2 (derecha, p2)
        if (ball_x > 0.95) {
            if (ball_y > p2_y - 0.1 && ball_y < p2_y + 0.1) {
                ball_x = 0.95;
                ball_vx = -ball_vx;
            } else {
                // Punto para P1
                score1++;
                resetBall(true); // Saca P1
            }
        }
    }

    private void resetBall(boolean p1Scored) {
        // --- ¡NUEVO! Comprobar victoria ANTES de resetear ---
        if (score1 >= WINNING_SCORE) {
            endGame(p1_name);
            return;
        }
        if (score2 >= WINNING_SCORE) {
            endGame(p2_name);
            return;
        }
        
        // Si no hay victoria, resetea la pelota
        ball_x = 0.5;
        ball_y = 0.5;
        // La pelota va hacia el jugador que NO anotó
        ball_vx = p1Scored ? -0.01 : 0.01;
        ball_vy = (Math.random() > 0.5) ? 0.005 : -0.005;
    }
}