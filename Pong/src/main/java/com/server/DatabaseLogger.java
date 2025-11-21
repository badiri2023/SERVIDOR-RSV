package com.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory; 
import java.util.concurrent.TimeUnit;    

/**
 * Singleton per gestionar el log a la base de dades SQLite.
 * Totes les escriptures són asíncrones per no bloquejar el servidor.
 */
public class DatabaseLogger {

    private static DatabaseLogger instance;
    private Connection connection;

    // --- ESTA ES LA PARTE QUE MODIFICASTE (CORRECTA) ---
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true); // <-- ¡LÍNEA MÁGICA!
            t.setName("DatabaseLoggerThread");
            return t;
        }
    });

    private static final String DB_FILE = "server_logs.db"; 

    // Singleton pattern
    public static synchronized DatabaseLogger getInstance() {
        if (instance == null) {
            instance = new DatabaseLogger();
            instance.connect();
        }
        return instance;
    }

    // Connecta i crea la taula si no existeix
    private void connect() {
        try {
            // Aquesta línia ara funciona perquè DB_FILE existeix
            String url = "jdbc:sqlite:" + DB_FILE;
            connection = DriverManager.getConnection(url);
            
            String sql = "CREATE TABLE IF NOT EXISTS logs ("
                       + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                       + " timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,"
                       + " event_type TEXT NOT NULL,"
                       + " details TEXT"
                       + ");";
            
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(sql);
            }
            log("SERVER", "Base de datos iniciada.");

        } catch (SQLException e) {
            System.err.println("Error fatal al conectar con SQLite: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Envia una tasca de log a la cua (asíncron).
     */
    public void log(String eventType, String details) {
        executor.submit(() -> {
            String sql = "INSERT INTO logs(event_type, details) VALUES(?, ?)";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, eventType);
                pstmt.setString(2, details);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                System.err.println("Error al escribir en el log: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Error inesperado en el logger: " + e.getMessage());
            }
        });
    }

    /**
     * Tanca la connexió a la BBDD i el pool de threads.
     */
    public void close() {
        log("SERVER", "Cerrando base de datos.");
        executor.shutdown();
        try {
            // Espera un màxim de 5 segons
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { // <-- Ara fem servir l'import
                System.err.println("El logger no ha pogut tancar a temps.");
                executor.shutdownNow();
            }
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt(); // Restaura l'estat d'interrupció
        }
    }
}