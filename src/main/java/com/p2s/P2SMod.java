package com.p2s;

import com.p2s.network.P2SNetworkPayloads;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2SMod implements ModInitializer {
	public static final String MOD_ID = "prompt2structure";

	/** Enable verbose debug logging. When false, the JIT eliminates all debug code paths (zero overhead). */
	public static final boolean DEBUG = false;

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		P2SNetworkPayloads.register();
		ModCommandRegistry.register();
		ServerNetworkHandler.register();
		LOGGER.info("Prompt-to-Structure module loaded. {}", ModConfig.describeConfigSource());
		LOGGER.info("Using API URL: {}, model: {}, timeout: {}s, prompt: {}, toolCall: {}", ModConfig.API_URL, ModConfig.MODEL, ModConfig.HTTP_TIMEOUT_SECONDS, ModConfig.activePromptName(), ModConfig.USE_TOOL_CALL);
	}
}
