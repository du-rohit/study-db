package com.study.websocket;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

// Maps this class to the ws://localhost:8080/ws/echo path
@ServerEndpoint("/echo")
public class EchoEndpoint {

    // Called once when a client connects
    @OnOpen
    public void onOpen(Session session) {
        System.out.println("[OPEN]  Client connected  | session=" + session.getId());
    }

    // Called each time a text message arrives; the return value is sent back to the client
    @OnMessage
    public String onMessage(String message, Session session) {
        System.out.println("[MSG]   Received: " + message + " | session=" + session.getId());
        return "Echo: " + message;
    }

    // Called once when the client disconnects (cleanly or not)
    @OnClose
    public void onClose(Session session, CloseReason reason) {
        System.out.println("[CLOSE] Client disconnected | session=" + session.getId()
                + " | reason=" + reason.getReasonPhrase());
    }

    // Called if an unhandled error occurs on this session
    @OnError
    public void onError(Session session, Throwable error) {
        System.err.println("[ERROR] session=" + session.getId() + " | " + error.getMessage());
    }
}
