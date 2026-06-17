package com.study.chat;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Messages sent to /topic/... are handled by the in-memory broker (broadcast to subscribers)
        registry.enableSimpleBroker("/topic");

        // Messages sent to /app/... are routed to @MessageMapping methods in controllers
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The URL clients connect to for the initial WebSocket handshake
        // withSockJS() adds a fallback for browsers that don't support WebSocket
        registry.addEndpoint("/ws-chat").withSockJS();
    }
}
