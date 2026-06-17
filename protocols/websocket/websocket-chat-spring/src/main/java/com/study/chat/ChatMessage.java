package com.study.chat;

public class ChatMessage {

    public enum Type { CHAT, JOIN, LEAVE }

    private Type type;
    private String sender;
    private String content;

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
