package com.p2s;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

import static net.minecraft.commands.Commands.literal;

public final class P2SDebugCommands {
    private P2SDebugCommands() {
    }

    public static void register() {
        if (!P2SMod.DEBUG) {
            return;
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("p2sdebug")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .then(literal("parse_selection").executes(P2SDebugCommands::parseSelection))
        ));
    }

    private static int parseSelection(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }

        SelectionManager.Selection selection = SelectionManager.get(player.getUUID());
        if (selection == null || !selection.isComplete()) {
            source.sendFailure(Component.literal("Selection is incomplete. Set both points before running /p2sdebug parse_selection."));
            return 0;
        }

        WorldStructureParser.ParseDebugReport report;
        try {
            report = WorldStructureParser.parseSelection(player.serverLevel(), player.getGameProfile().getName(), selection);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }

        Path dumpPath;
        try {
            dumpPath = WorldParseDebugReportWriter.write(report);
            WorldParseDebugReportWriter.logSummary(report, dumpPath);
        } catch (Exception e) {
            P2SMod.LOGGER.warn("Failed to write world parse debug report: {}", e.getMessage(), e);
            source.sendFailure(Component.literal("Failed to write debug report: " + e.getMessage()));
            return 0;
        }

        String summary = "P2S debug parse complete: nonAir=" + report.nonAirBlocks()
                + ", specialUnits=" + report.specialUnits().size()
                + ", dump=" + dumpPath;
        source.sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }
}
