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
    private static String runtimeState = "";
    private static String revision = "";
    private static boolean hasPendingPatch = false;
    private static String pendingSummary = "";
    private static String pendingRisk = "";
    private static int pendingChangedBlocks = 0;
    private static String previewSummary = "";
    private static String previewDetail = "";
    private static String previewRisk = "";
    private static int previewChangedBlocks = 0;
    private static final List<ChatMessage> messages = new ArrayList<>();

    private ClientSessionState() {
    }

    public static void onSessionSync(
            boolean activeFlag,
            String id,
            int turns,
            int parts,
            int blocks,
            String summary,
            String structure,
            String runtime,
            String rev,
            boolean pending,
            String pendingPatchSummary,
            String risk,
            int changed
    ) {
        active = activeFlag;
        sessionId = id == null ? "" : id;
        turnCount = turns;
        partCount = parts;
        totalBlocks = blocks;
        partsSummary = summary == null ? "" : summary;
        structureSummary = structure == null ? "" : structure;
        runtimeState = runtime == null ? "" : runtime;
        revision = rev == null ? "" : rev;
        hasPendingPatch = pending;
        pendingSummary = pendingPatchSummary == null ? "" : pendingPatchSummary;
        pendingRisk = risk == null ? "" : risk;
        pendingChangedBlocks = Math.max(0, changed);
        if (!pending) {
            clearPreview();
        }
        if (!activeFlag) {
            status = "";
            messages.clear();
            clearPreview();
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

    public static void onPatchPreview(boolean hasPreview, String summary, String detail, int changedBlocks, String riskLevel) {
        if (!hasPreview) {
            clearPreview();
            return;
        }
        previewSummary = summary == null ? "" : summary;
        previewDetail = detail == null ? "" : detail;
        previewRisk = riskLevel == null ? "" : riskLevel;
        previewChangedBlocks = Math.max(0, changedBlocks);
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

    public static String getRuntimeState() {
        return runtimeState;
    }

    public static String getRevision() {
        return revision;
    }

    public static boolean hasPendingPatch() {
        return hasPendingPatch;
    }

    public static String getPendingSummary() {
        return pendingSummary;
    }

    public static String getPendingRisk() {
        return pendingRisk;
    }

    public static int getPendingChangedBlocks() {
        return pendingChangedBlocks;
    }

    public static String getPreviewSummary() {
        return previewSummary;
    }

    public static String getPreviewDetail() {
        return previewDetail;
    }

    public static String getPreviewRisk() {
        return previewRisk;
    }

    public static int getPreviewChangedBlocks() {
        return previewChangedBlocks;
    }

    private static void clearPreview() {
        previewSummary = "";
        previewDetail = "";
        previewRisk = "";
        previewChangedBlocks = 0;
    }

    public record ChatMessage(String role, String text) {
    }
}
