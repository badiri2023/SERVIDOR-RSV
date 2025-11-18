package com.server;

import org.java_websocket.WebSocket;
import org.json.JSONArray;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre de clients connectats amb noms personalitzats.
 *
 * Manté dos mapes bidireccionals:
 * - WebSocket a nom de client
 * - Nom de client a WebSocket
 *
 * El sistema de noms automàtics (seedNames i pool) ha estat desactivat.
 * Ara tots els clients han d'enviar un nom personalitzat amb el missatge "NICKNAME:<nom>".
 */
final class ClientRegistry {

    /** Mapa de sockets a noms de client. */
    private final Map<WebSocket, String> bySocket = new ConcurrentHashMap<>();

    /** Mapa de noms de client a sockets. */
    private final Map<String, WebSocket> byName = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    // ──────── Sistema de noms automàtics desactivat ────────
    /*
    private final Queue<String> pool = new ConcurrentLinkedQueue<>();
    private final List<String> seedNames;

    ClientRegistry(List<String> seedNames) {
        this.seedNames = seedNames;
        resetPool();
    }

    private synchronized void resetPool() {
        pool.clear();
        pool.addAll(seedNames);
    }

    private String takeOrRecycle() {
        String name = pool.poll();
        if (name == null) {
            resetPool();
            name = pool.poll();
        }
        return name;
    }

    private void giveBack(String name) {
        if (name != null) {
            pool.offer(name);
        }
    }

    String add(WebSocket socket) {
        String name = takeOrRecycle();
        bySocket.put(socket, name);
        byName.put(name, socket);
        return name;
    }
    */
    // ───────────────────────────────────────────────────────

    /**
     * Elimina un client del registre.
     *
     * @param socket socket del client a eliminar
     * @return el nom que estava assignat, o null si no existia
     */
    String remove(WebSocket socket) {
        String name = bySocket.remove(socket);
        if (name != null) {
            byName.remove(name);
            // giveBack(name); // Desactivat
        }
        return name;
    }

    /**
     * Obté el socket associat a un nom de client.
     */
    WebSocket socketByName(String name) {
        return byName.get(name);
    }

    /**
     * Obté el nom associat a un socket.
     */
    String nameBySocket(WebSocket socket) {
        return bySocket.get(socket);
    }

    /**
     * Retorna la llista actual de noms de clients connectats en format JSONArray.
     */
    JSONArray currentNames() {
        JSONArray arr = new JSONArray();
        for (String n : byName.keySet()) {
            arr.put(n);
        }
        return arr;
    }

    /**
     * Neteja el registre per a un socket desconnectat.
     */
    String cleanupDisconnected(WebSocket socket) {
        return remove(socket);
    }

    /**
     * Registra un nom personalitzat si no està en ús.
     */
public boolean registerNickname(String nickname, WebSocket socket) {
    System.out.println("Registro exitoso: " + nickname + " desde " + socket.getRemoteSocketAddress());

    if (nickname == null || nickname.isBlank()) return false;
    if (nickname.length() > 20) return false;
    if (!nickname.matches("[a-zA-Z0-9_\\-]+")) return false;

    synchronized (lock) {
        // Comprobar si el socket ya está registrado
        if (bySocket.containsKey(socket)) {
            System.out.println("Fallo: Socket ya registrado.");
            return false;
        } 
        // Comprobar si el nombre ya está en uso
        if (byName.containsKey(nickname)) {
            System.out.println("Fallo: Nickname duplicado -> " + nickname);
            return false;
        }
        bySocket.put(socket, nickname);
        byName.put(nickname, socket);

        System.out.println("Registro exitoso: " + nickname + " desde " + socket.getRemoteSocketAddress());
        return true;
    }
}

    /**
     * Retorna una còpia immutable de l'estat actual del mapa socket a nom.
     */
    Map<WebSocket, String> snapshot() {
        return Map.copyOf(bySocket);
    }
}