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
    
    // ✅ NUEVO: Velocidades de la pelota
    private static final double BALL_SPEED = 0.00;
    private static final double PADDLE_HEIGHT_NORMALIZED = 0.2; // 20% del campo

    private final WebSocket p1;
    private final String p1_name;
    private final WebSocket p2;
    private final String p2_name;
    private final Map<WebSocket, String> spectators = new ConcurrentHashMap<>();

    // Estado del juego
    private volatile double p1_y = 0.5, p2_y = 0.5;
    private volatile double ball_x = 0.5, ball_y = 0.5;
  
    private volatile double ball_vx = BALL_SPEED, ball_vy = 0.0;
    private volatile int score1 = 0, score2 = 0;

    private volatile boolean running = true;
    private final Thread gameThread;
    private final Main server;

    // Contador de frames para logs
    private int frameCount = 0;

    private String lastGameState = "";

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
     * Procesa mensajes de movimiento de los jugadores
     */
    public void processMove(WebSocket player, double y_pos) {
        if (!running) {
            System.out.println("❌ Movimiento ignorado - Juego no activo");
            return;
        }
        
        double clamped_y = Math.max(0.0, Math.min(1.0, y_pos));
        String playerName = (player == p1) ? p1_name : p2_name;
        
        System.out.println("MOVIMIENTO PROCESADO - Jugador: " + playerName + " | Y: " + clamped_y);
        
        updatePaddle(player, clamped_y);
        
        broadcast(createGameStateJSON().toString());
        System.out.println("Estado enviado inmediatamente después de movimiento");
    }

    /**
     * El Game Loop principal - AJUSTADO A TU FLUJO
     */
    @Override
    public void run() {
        try {
            System.out.println("🎮 INICIANDO GAME SESSION: " + p1_name + " vs " + p2_name);

            // 1. VISTA DE CARGA (Vista 3)
            JSONObject startMsg = new JSONObject()
                    .put("type", "game_start")
                    .put("opponent", p2_name)
                    .put("role", "p1"); // p1 (el que aceptó) es jugador 1
            server.sendSafe(p1, startMsg.toString());
            System.out.println("✅ game_start enviado a P1: " + p1_name);

            startMsg.put("opponent", p1_name)
                    .put("role", "p2"); // p2 (el que retó) es jugador 2
            server.sendSafe(p2, startMsg.toString());
            System.out.println("✅ game_start enviado a P2: " + p2_name);

            // 2. ANIMACIÓN "CHOOSING STARTER"
            JSONObject choosingMsg = new JSONObject().put("type", "choosing_starter");
            broadcast(choosingMsg.toString());
            System.out.println("✅ choosing_starter enviado");

            // Esperamos los 3 segundos de la pantalla de carga / animación
            Thread.sleep(LOADING_SCREEN_MS);

            // 3. ANUNCIAR QUIÉN SACA
            JSONObject announceMsg = new JSONObject()
                    .put("type", "text")
                    .put("message", "Starts Player\n" + p2_name) // Formato vertical para la Pi
                    .put("ttl_ms", PRE_COUNTDOWN_MS); // Mostrar 2 segundos
            broadcast(announceMsg.toString());
            System.out.println("✅ starter announcement enviado: " + p2_name);

            // Esperar 2 segundos para que se lea el mensaje
            Thread.sleep(PRE_COUNTDOWN_MS);

            // 4. CUENTA ATRÁS (en todos los dispositivos)
            for (int i = 3; i > 0; i--) {
                JSONObject countdownMsg = new JSONObject()
                        .put("type", "countdown")
                        .put("value", i);
                broadcast(countdownMsg.toString());
                System.out.println("⏰ Countdown: " + i);
                Thread.sleep(1000); // Espera 1 segundo
            }

            // 5. ¡GO!
            JSONObject goMsg = new JSONObject()
                    .put("type", "countdown")
                    .put("value", "GO!");
            broadcast(goMsg.toString());
            System.out.println("🎯 GO! enviado");
            Thread.sleep(500); // Pausa en "GO!"

            // 6. POSICIÓN INICIAL
            // Enviamos un único estado inicial ANTES del bucle
            // para que todos pinten la posición 0-0.
            broadcast(createGameStateJSON().toString());
            System.out.println("✅ Estado inicial enviado");

            // 7. BUCLE DE JUEGO
            System.out.println("INICIANDO BUCLE DE JUEGO PRINCIPAL");
            while (running) {
                frameCount++;
                
                updatePhysics();

                String currentState = createGameStateJSON().toString();
                if (!currentState.equals(lastGameState)) {
                    broadcast(currentState);
                    lastGameState = currentState;
                    
                    // Log ocasional de cambios
                    if (frameCount % 60 == 0) {
                        System.out.println("Estado cambiado - Frame: " + frameCount);
                    }
                }
                
                Thread.sleep(16); // ~60 FPS
            }

        } catch (InterruptedException e) {
            System.out.println("❌ Game loop interrumpido.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("💥 Error en GameSession: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("🏁 Partida finalizada. Total frames: " + frameCount);
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
        
        if (player == p1) {
            double oldY = this.p1_y;
            this.p1_y = clamped_y;
            System.out.println("✅ PALA P1 ACTUALIZADA - " + p1_name + 
                             " - De: " + String.format("%.3f", oldY) + " a: " + String.format("%.3f", this.p1_y));
        } else if (player == p2) {
            double oldY = this.p2_y;
            this.p2_y = clamped_y;
            System.out.println("✅ PALA P2 ACTUALIZADA - " + p2_name + 
                             " - De: " + String.format("%.3f", oldY) + " a: " + String.format("%.3f", this.p2_y));
        } else {
            System.out.println("❌ JUGADOR NO RECONOCIDO en updatePaddle");
        }
    }

    /** Detiene el juego si un jugador se desconecta */
    public void stopGame(WebSocket disconnectedPlayer) {
        if (!running) return;
        
        String disconnectedName = (disconnectedPlayer == p1) ? p1_name : p2_name;
        System.out.println("Deteniendo juego - Desconectado: " + disconnectedName);
        
        this.running = false;
        this.gameThread.interrupt();

        // Enviar mensaje específico de desconexión al jugador que queda
        WebSocket remainingPlayer = (disconnectedPlayer == p1) ? p2 : p1;
        
        JSONObject disconnectMsg = new JSONObject()
                .put("type", "player_disconnected")
                .put("disconnected_player", disconnectedName)
                .put("message", "El oponente se ha desconectado");
        
        server.sendSafe(remainingPlayer, disconnectMsg.toString());
        System.out.println("Mensaje de desconexión enviado a: " + 
                        ((remainingPlayer == p1) ? p1_name : p2_name));
    }

    /** Detiene el juego porque alguien ha ganado */
    private void endGame(String winnerName) {
        if (!running) return; // Ya se ha parado
        
        System.out.println("🏆 Fin del juego - Ganador: " + winnerName);
        this.running = false;
        this.gameThread.interrupt();

        JSONObject endMsg = new JSONObject()
                .put("type", "game_over")
                .put("winner", winnerName)
                .put("score1", score1)
                .put("score2", score2);
        
        // Se lo enviamos a TODOS (jugadores y Pi)
        broadcast(endMsg.toString());
        System.out.println("✅ Game_over broadcast enviado");
    }

    public WebSocket getOtherPlayer(WebSocket player) {
        if (player == p1) return p2;
        if (player == p2) return p1;
        return null;
    }
    
    public void addSpectator(WebSocket spec, String name) {
        spectators.put(spec, name);
        System.out.println("👀 Espectador añadido: " + name);
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
        // ✅ MOVER PELOTA (ahora sí tiene velocidad)
        ball_x += ball_vx;
        ball_y += ball_vy;

        // Log ocasional de física (cada ~1 segundo)
        if (frameCount % 60 == 0) { // Cada segundo aprox (60 frames)
            System.out.println("⚡ Física - Ball: " + String.format("%.3f", ball_x) + "," + String.format("%.3f", ball_y) + 
                              " | Vel: " + String.format("%.5f", ball_vx) + "," + String.format("%.5f", ball_vy));
        }

        // Colisión con paredes (arriba/abajo)
        if (ball_y < 0) { 
            ball_y = 0; 
            ball_vy = Math.abs(ball_vy); // Rebote hacia abajo
            System.out.println("🔨 Rebote techo");
        }
        if (ball_y > 1) { 
            ball_y = 1; 
            ball_vy = -Math.abs(ball_vy); // Rebote hacia arriba
            System.out.println("🔨 Rebote suelo");
        }

        // ✅ CORREGIDO: Colisión con pala 1 (izquierda, p1)
        if (ball_x < 0.05 && ball_vx < 0) { // Solo si se mueve hacia la izquierda
            if (ball_y >= p1_y - PADDLE_HEIGHT_NORMALIZED/2 && 
                ball_y <= p1_y + PADDLE_HEIGHT_NORMALIZED/2) {
                
                // Rebote con ángulo según dónde golpee la pala
                double hitPos = (ball_y - p1_y) / (PADDLE_HEIGHT_NORMALIZED/2);
                ball_vy = hitPos * BALL_SPEED * 0.8; // Ángulo vertical
                ball_vx = Math.abs(ball_vx) * 1.05; // Aumentar velocidad + invertir dirección
                
                ball_x = 0.05; // Corregir posición
                System.out.println("🔨 Rebote P1: " + p1_name + " | Ángulo: " + String.format("%.3f", hitPos));
            } else if (ball_x < 0) {
                // Punto para P2
                score2++;
                System.out.println("🎯 PUNTO para P2: " + p2_name + " - Score: " + score1 + "-" + score2);
                resetBall(false);
                return; // Salir para evitar procesamiento extra
            }
        }

        // ✅ CORREGIDO: Colisión con pala 2 (derecha, p2)
        if (ball_x > 0.95 && ball_vx > 0) { // Solo si se mueve hacia la derecha
            if (ball_y >= p2_y - PADDLE_HEIGHT_NORMALIZED/2 && 
                ball_y <= p2_y + PADDLE_HEIGHT_NORMALIZED/2) {
                
                // Rebote con ángulo según dónde golpee la pala
                double hitPos = (ball_y - p2_y) / (PADDLE_HEIGHT_NORMALIZED/2);
                ball_vy = hitPos * BALL_SPEED * 0.8; // Ángulo vertical
                ball_vx = -Math.abs(ball_vx) * 1.05; // Aumentar velocidad + invertir dirección
                
                ball_x = 0.95; // Corregir posición
                System.out.println("🔨 Rebote P2: " + p2_name + " | Ángulo: " + String.format("%.3f", hitPos));
            } else if (ball_x > 1) {
                // Punto para P1
                score1++;
                System.out.println("🎯 PUNTO para P1: " + p1_name + " - Score: " + score1 + "-" + score2);
                resetBall(true);
                return; // Salir para evitar procesamiento extra
            }
        }
    }

    private void resetBall(boolean p1Scored) {
        // Comprobar victoria ANTES de resetear
        if (score1 >= WINNING_SCORE) {
            endGame(p1_name);
            return;
        }
        if (score2 >= WINNING_SCORE) {
            endGame(p2_name);
            return;
        }
        
        System.out.println("🔄 Reseteando pelota - Anotó: " + (p1Scored ? p1_name : p2_name));
        
        // Posición central
        ball_x = 0.5;
        ball_y = 0.5;
        
        // ✅ DIRECCIÓN INICIAL: hacia el jugador que NO anotó
        if (p1Scored) {
            ball_vx = -BALL_SPEED; // Hacia P2 (derecha a izquierda)
        } else {
            ball_vx = BALL_SPEED;  // Hacia P1 (izquierda a derecha)
        }
        
        // Pequeño ángulo vertical aleatorio
        ball_vy = (Math.random() - 0.5) * BALL_SPEED * 0.5;
        
        System.out.println("✅ Pelota resetada - Velocidad: " + 
                         String.format("%.5f", ball_vx) + "," + String.format("%.5f", ball_vy));
        
        // ✅ ENVIAR ESTADO INMEDIATAMENTE después del reset
        broadcast(createGameStateJSON().toString());
    }
}