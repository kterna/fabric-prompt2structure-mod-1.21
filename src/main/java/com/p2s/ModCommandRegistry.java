package com.p2s;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;

public final class ModCommandRegistry {
    private ModCommandRegistry() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("p2sreload")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> {
                                    ModConfig.reload();
                                    ctx.getSource().sendSuccess(() -> P2SI18n.tr("command.p2s.config.reloaded"), false);
                                    return 1;
                                })
                )
        );
    }
}
