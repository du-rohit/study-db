package com.study.chat;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
public class WebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Spring publishes this event automatically when any WebSocket session closes
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        // Session attributes are stored inside the message headers, not on the event directly
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String username = (String) accessor.getSessionAttributes().get("username");
        if (username == null) return;

        ChatMessage leaveMessage = new ChatMessage();
        leaveMessage.setType(ChatMessage.Type.LEAVE);
        leaveMessage.setSender(username);
        leaveMessage.setContent(username + " left the chat");

        // Programmatic send — no @SendTo annotation needed
        messagingTemplate.convertAndSend("/topic/chat", leaveMessage);
    }
}
