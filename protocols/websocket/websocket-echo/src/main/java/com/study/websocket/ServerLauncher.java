package com.study.websocket;

import org.glassfish.tyrus.server.Server;

public class ServerLauncher {

    public static void main(String[] args) throws Exception {
        // Args: host, port, root path, server properties, endpoint classes to register
        Server server = new Server("localhost", 8080, "/ws", null, EchoEndpoint.class, ChatEndpoint.class);

        server.start();
        System.out.println("Server started  →  ws://localhost:8080/ws/echo");
        System.out.println("Press ENTER to stop...");
        System.in.read();
        server.stop();
        System.out.println("Server stopped.");
    }
}
