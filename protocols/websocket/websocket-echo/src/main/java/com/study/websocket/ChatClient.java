package com.study.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Scanner;
import java.util.concurrent.CompletionStage;

public class ChatClient {

    public static void main(String[] args) throws Exception {
        String username = args.length > 0 ? args[0] : "Anonymous";

        HttpClient httpClient = HttpClient.newHttpClient();

        WebSocket ws = httpClient
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:8080/ws/chat"), new WebSocket.Listener() {

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        System.out.println("Connected as [" + username + "]. Type a message or /quit to exit.");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        System.out.println(data);
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        System.out.println("Disconnected.");
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        System.err.println("Error: " + error.getMessage());
                    }
                })
                .join();

        // Main thread reads stdin — WebSocket callbacks run on their own threads in the background
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if ("/quit".equals(line)) break;
            ws.sendText(username + ": " + line, true);
        }

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }
}
