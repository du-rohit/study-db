package com.study.chat;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    // Client sends to /app/chat.send → this method handles it → return value broadcast to /topic/chat
    @MessageMapping("/chat.send")
    @SendTo("/topic/chat")
    public ChatMessage sendMessage(@Payload ChatMessage message) {
        return message;
    }

    // Client sends to /app/chat.join → stores username in WebSocket session attributes
    @MessageMapping("/chat.join")
    @SendTo("/topic/chat")
    public ChatMessage join(@Payload ChatMessage message, SimpMessageHeaderAccessor headerAccessor) {
        // Store username so we can retrieve it when the user disconnects
        headerAccessor.getSessionAttributes().put("username", message.getSender());
        message.setType(ChatMessage.Type.JOIN);
        return message;
    }
}
