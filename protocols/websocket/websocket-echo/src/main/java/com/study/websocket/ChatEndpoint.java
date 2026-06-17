package com.study.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@ServerEndpoint("/chat")
public class ChatEndpoint {

    // Static: shared across ALL instances. Tyrus creates one ChatEndpoint per connected client,
    // so without static, each instance would only see its own session.
    private static final Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());

    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        System.out.println("[CHAT] Connected: " + shortId(session) + " | total=" + sessions.size());
        broadcast(shortId(session) + " joined the chat");
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        System.out.println("[CHAT] " + shortId(session) + ": " + message);
        broadcast(shortId(session) + ": " + message);
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        sessions.remove(session);
        System.out.println("[CHAT] Disconnected: " + shortId(session) + " | total=" + sessions.size());
        broadcast(shortId(session) + " left the chat");
    }

    @OnError
    public void onError(Session session, Throwable error) {
        sessions.remove(session);
        System.err.println("[CHAT] Error on " + shortId(session) + ": " + error.getMessage());
    }

    private void broadcast(String message) {
        // Iterate a snapshot to avoid ConcurrentModificationException if a session closes mid-loop
        for (Session s : Set.copyOf(sessions)) {
            if (s.isOpen()) {
                // getAsyncRemote() sends without blocking this thread
                s.getAsyncRemote().sendText(message);
            }
        }
    }

    private String shortId(Session session) {
        // Full session ID is a long UUID — first 8 chars is enough to identify a client
        return session.getId().substring(0, 8);
    }
}
