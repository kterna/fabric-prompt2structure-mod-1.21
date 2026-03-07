package com.p2s;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class P2SI18n {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private P2SI18n() {
    }

    public static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static Component literalOrEmpty(String text) {
        return text == null || text.isBlank() ? Component.empty() : Component.literal(text);
    }

    public static Component resolve(String key, JsonElement args, String fallback) {
        if (key != null && !key.isBlank()) {
            return Component.translatable(key, translationArgs(args));
        }
        return literalOrEmpty(fallback);
    }

    public static Component resolve(String key, String argsJson, String fallback) {
        JsonElement parsed = null;
        if (argsJson != null && !argsJson.isBlank()) {
            try {
                parsed = JsonParser.parseString(argsJson);
            } catch (Exception ignored) {
                parsed = null;
            }
        }
        return resolve(key, parsed, fallback);
    }

    public static Component resolvePayload(JsonObject payload, String keyField, String argsField, String fallbackField) {
        if (payload == null) {
            return Component.empty();
        }
        String key = getString(payload, keyField);
        JsonElement args = payload.has(argsField) ? payload.get(argsField) : null;
        String fallback = getString(payload, fallbackField);
        return resolve(key, args, fallback);
    }

    public static Object[] translationArgs(JsonElement args) {
        if (args == null || args.isJsonNull()) {
            return new Object[0];
        }
        List<Object> values = new ArrayList<>();
        if (args.isJsonArray()) {
            JsonArray array = args.getAsJsonArray();
            for (JsonElement element : array) {
                values.add(translationArg(element));
            }
        } else {
            values.add(translationArg(args));
        }
        return values.toArray();
    }

    private static Object translationArg(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        return element.toString();
    }

    public static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String normalizeRole(String role) {
        String lower = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("you") || lower.contains("user")) {
            return ROLE_USER;
        }
        if (lower.contains("ai") || lower.contains("assistant")) {
            return ROLE_ASSISTANT;
        }
        return lower;
    }

    public static boolean isUserRole(String role) {
        return ROLE_USER.equals(normalizeRole(role));
    }

    public static boolean isAssistantRole(String role) {
        return ROLE_ASSISTANT.equals(normalizeRole(role));
    }

    public static Component roleComponent(String role) {
        String normalized = normalizeRole(role);
        return switch (normalized) {
            case ROLE_USER -> tr("role.p2s.user");
            case ROLE_ASSISTANT -> tr("role.p2s.assistant");
            default -> literalOrEmpty(role);
        };
    }

    public static String rolePrefix(String role) {
        Component label = roleComponent(role);
        String text = label.getString();
        return text.isBlank() ? "" : text + ": ";
    }

    public static Component statusComponent(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "idle" -> tr("status.p2s.idle");
            case "awaiting_confirm" -> tr("status.p2s.awaiting_confirm");
            case "committed" -> tr("status.p2s.committed");
            case "cancelled" -> tr("status.p2s.cancelled");
            case "error" -> tr("status.p2s.error");
            case "pending" -> tr("status.p2s.pending");
            case "in_progress" -> tr("status.p2s.in_progress");
            case "done" -> tr("status.p2s.done");
            case "blocked" -> tr("status.p2s.blocked");
            default -> literalOrEmpty(status);
        };
    }

    public static Component rollbackModeComponent(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "session_only" -> tr("status.p2s.rollback_mode.chat");
            case "workspace_and_session" -> tr("status.p2s.rollback_mode.all");
            default -> literalOrEmpty(mode);
        };
    }

    public static Component checkpointLabelComponent(String label) {
        if (label == null || label.isBlank()) {
            return Component.empty();
        }
        String value = label.trim();
        if (value.equals("manual")) {
            return tr("screen.p2s.chat.checkpoint.label.manual");
        }
        if (value.startsWith("manual-")) {
            return tr("screen.p2s.chat.checkpoint.label.manual_numbered", value.substring("manual-".length()));
        }
        if (value.startsWith("apply:")) {
            return tr("screen.p2s.chat.checkpoint.label.apply", value.substring("apply:".length()));
        }
        if (value.startsWith("undo:")) {
            return tr("screen.p2s.chat.checkpoint.label.undo", value.substring("undo:".length()));
        }
        if (value.startsWith("redo:")) {
            return tr("screen.p2s.chat.checkpoint.label.redo", value.substring("redo:".length()));
        }
        if (value.startsWith("rollback:")) {
            return tr("screen.p2s.chat.checkpoint.label.rollback", value.substring("rollback:".length()));
        }
        return Component.literal(value);
    }

    public static Component riskComponent(String risk) {
        String normalized = risk == null ? "" : risk.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "high" -> tr("risk.p2s.high");
            case "medium" -> tr("risk.p2s.medium");
            case "low" -> tr("risk.p2s.low");
            default -> literalOrEmpty(risk);
        };
    }
}
