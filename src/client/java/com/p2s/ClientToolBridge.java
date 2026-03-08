package com.p2s;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientToolBridge {
    private static final Gson GSON = new Gson();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final Map<String, CompletableFuture<JsonObject>> PENDING = new ConcurrentHashMap<>();

    private ClientToolBridge() {
    }

    public static CompletableFuture<JsonObject> call(String toolName, JsonObject args) {
        String requestId = Long.toString(NEXT_ID.incrementAndGet(), 36);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING.put(requestId, future);

        if (!ClientServerBridge.canUseToolBridge()) {
            future.completeExceptionally(ClientServerBridge.missingServerException());
            PENDING.remove(requestId);
            return future;
        }

        String argumentsJson = args == null ? "{}" : GSON.toJson(args);
        Minecraft mc = Minecraft.getInstance();
        Runnable sendTask = () -> ClientPlayNetworking.send(new com.p2s.network.C2SToolBridgePayload(requestId, toolName == null ? "" : toolName, argumentsJson));
        if (mc != null) {
            mc.execute(sendTask);
        } else {
            sendTask.run();
        }

        future.orTimeout(20, TimeUnit.SECONDS).whenComplete((ignored, ex) -> PENDING.remove(requestId));
        return future;
    }

    public static void onToolResponse(String requestId, boolean ok, String responseJson, String error) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        CompletableFuture<JsonObject> future = PENDING.remove(requestId);
        if (future == null) {
            return;
        }

        if (!ok) {
            future.completeExceptionally(new IllegalStateException(error == null ? "Tool bridge failed" : error));
            return;
        }

        try {
            JsonObject payload = JsonParser.parseString(responseJson == null || responseJson.isBlank() ? "{}" : responseJson).getAsJsonObject();
            future.complete(payload);
        } catch (Exception e) {
            future.completeExceptionally(new IllegalStateException("Invalid tool bridge response: " + e.getMessage(), e));
        }
    }
}
