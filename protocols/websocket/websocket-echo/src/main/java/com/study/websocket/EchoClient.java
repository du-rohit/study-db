package com.study.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class EchoClient {

    public static void main(String[] args) throws Exception {
        // Latch so main thread waits until we receive the echo reply before exiting
        CountDownLatch replyReceived = new CountDownLatch(1);

        HttpClient httpClient = HttpClient.newHttpClient();

        WebSocket webSocket = httpClient
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:8080/ws/echo"), new WebSocket.Listener() {

                    @Override
                    public void onOpen(WebSocket ws) {
                        System.out.println("[CLIENT] Connected to server");
                        ws.sendText("Hello WebSocket!", true); // true = this is the complete message
                        ws.request(1); // ask to receive 1 message
                    }

                    // last=true means this chunk is the end of the message (messages can arrive in parts)
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        System.out.println("[CLIENT] Server replied: " + data);
                        replyReceived.countDown();
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        System.out.println("[CLIENT] Connection closed | code=" + statusCode + " reason=" + reason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.err.println("[CLIENT] Error: " + error.getMessage());
                        replyReceived.countDown(); // unblock main thread on error too
                    }
                })
                .join(); // blocks until the WebSocket handshake completes

        replyReceived.await(); // wait until we get the echo back

        // Initiate a clean close handshake (sends a WebSocket CLOSE frame)
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
