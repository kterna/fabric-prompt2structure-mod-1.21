package com.p2s;

import net.minecraft.resources.ResourceLocation;

public final class P2SNetworkConstants {
    public static final ResourceLocation C2S_SET_SELECTION_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "c2s_set_selection");
    public static final ResourceLocation C2S_SESSION_ACTION_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "c2s_session_action");
    public static final ResourceLocation C2S_TOOL_BRIDGE_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "c2s_tool_bridge");

    public static final ResourceLocation S2C_SELECTION_SYNC_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "s2c_selection_sync");
    public static final ResourceLocation S2C_CHAT_RESPONSE_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "s2c_chat_response");
    public static final ResourceLocation S2C_SESSION_SYNC_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "s2c_session_sync");
    public static final ResourceLocation S2C_BUILD_PROGRESS_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "s2c_build_progress");
    public static final ResourceLocation S2C_PATCH_PREVIEW_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "s2c_patch_preview");
    public static final ResourceLocation S2C_TOOL_BRIDGE_ID = ResourceLocation.fromNamespaceAndPath(P2SMod.MOD_ID, "s2c_tool_bridge");

    private P2SNetworkConstants() {
    }
}
