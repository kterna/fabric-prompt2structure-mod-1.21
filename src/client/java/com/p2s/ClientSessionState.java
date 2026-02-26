package com.p2s;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ClientSessionState {
    private static boolean active = false;
    private static String sessionId = "";
    private static int turnCount = 0;
    private static int partCount = 0;
    private static int totalBlocks = 0;
    private static String partsSummary = "";
    private static String structureSummary = "";
    private static String status = "";
    private static final List<ChatMessage> messages = new ArrayList<>();

    private ClientSessionState() {
    }

    public static void onSessionSync(boolean activeFlag, String id, int turns, int parts, int blocks, String summary, String structure) {
        active = activeFlag;
        sessionId = id == null ? "" : id;
        turnCount = turns;
        partCount = parts;
        totalBlocks = blocks;
        partsSummary = summary == null ? "" : summary;
        structureSummary = structure == null ? "" : structure;
        if (!activeFlag) {
            status = "";
            messages.clear();
        }
    }

    public static void onChatResponse(String text, boolean hasStructure, String newStatus) {
        if (text != null && !text.isBlank()) {
            addMessage("AI", text.trim());
        }
        if (newStatus != null) {
            status = newStatus;
        }
    }

    public static void onBuildProgress(String phase, String currentPart, int progress, int blocksPlaced) {
        StringBuilder sb = new StringBuilder();
        if (phase != null && !phase.isBlank()) {
            sb.append(phase);
        }
        if (currentPart != null && !currentPart.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(currentPart);
        }
        if (progress >= 0) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(progress).append("%");
        }
        status = sb.toString();
    }

    public static void addUserMessage(String text) {
        if (text != null && !text.isBlank()) {
            addMessage("You", text.trim());
        }
    }

    private static void addMessage(String role, String text) {
        messages.add(new ChatMessage(role, text));
        if (messages.size() > 200) {
            messages.remove(0);
        }
    }

    public static List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public static boolean isActive() {
        return active;
    }

    public static String getSessionId() {
        return sessionId;
    }

    public static int getTurnCount() {
        return turnCount;
    }

    public static int getPartCount() {
        return partCount;
    }

    public static int getTotalBlocks() {
        return totalBlocks;
    }

    public static String getPartsSummary() {
        return partsSummary;
    }

    public static String getStructureSummary() {
        return structureSummary;
    }

    public static String getStatus() {
        return status;
    }

    public static void setStatus(String value) {
        status = value == null ? "" : value;
    }

    public record ChatMessage(String role, String text) {
    }
}
