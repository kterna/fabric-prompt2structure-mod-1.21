package com.p2s;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ModCommandRegistry {
    private ModCommandRegistry() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var p2sCommand = Commands.literal("p2s")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("y", IntegerArgumentType.integer())
                                    .then(Commands.argument("z", IntegerArgumentType.integer())
                                            .then(Commands.argument("prompt", StringArgumentType.greedyString())
                                                    .executes(ModCommandRegistry::runCommand)))));
            dispatcher.register(p2sCommand);

            dispatcher.register(
                    Commands.literal("p2sreload")
                            .requires(source -> source.hasPermission(2))
                            .executes(ctx -> {
                                ModConfig.reload();
                                ctx.getSource().sendSuccess(() -> Component.literal("P2S config reloaded"), false);
                                return 1;
                            })
            );

            dispatcher.register(
                    Commands.literal("p2slist")
                            .requires(source -> source.hasPermission(2))
                            .then(Commands.argument("limit", IntegerArgumentType.integer(1, 50))
                                    .executes(ctx -> list(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "limit"))))
                            .executes(ctx -> list(ctx.getSource(), 10))
            );

            dispatcher.register(
                    Commands.literal("p2sload")
                            .requires(source -> source.hasPermission(2))
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .then(Commands.argument("x", IntegerArgumentType.integer())
                                            .then(Commands.argument("y", IntegerArgumentType.integer())
                                                    .then(Commands.argument("z", IntegerArgumentType.integer())
                                                            .executes(ModCommandRegistry::loadSaved)))))
            );

            dispatcher.register(
                    Commands.literal("p2sdelete")
                            .requires(source -> source.hasPermission(2))
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "name");
                                        boolean ok = ScriptStorage.delete(name);
                                        if (ok) {
                                            ctx.getSource().sendSuccess(() -> Component.literal("Deleted saved script: " + name), false);
                                        } else {
                                            ctx.getSource().sendFailure(Component.literal("No saved script: " + name));
                                        }
                                        return ok ? 1 : 0;
                                    }))
            );
        });
    }

    private static int runCommand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        String prompt = StringArgumentType.getString(context, "prompt");

        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        BlockPos origin = new BlockPos(x, y, z);

        source.sendSuccess(() -> Component.literal("Requesting structure from AI..."), false);

        LLMService.requestStructure(prompt).thenAccept(result -> {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                try {
                    String savedName = ScriptStorage.save(prompt, result.rawContent(), result.fullMessage(), null);
                    StructureBuilder.build(world, origin, result.script());
                    source.sendSuccess(() -> Component.literal("Build completed (saved as " + savedName + ")"), false);
                } catch (Exception e) {
                    source.sendFailure(Component.literal("Build failed: " + e.getMessage()));
                    P2SMod.LOGGER.error("Build failed", e);
                }
            });
        }).exceptionally(ex -> {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                source.sendFailure(Component.literal("Request or parse failed: " + ex.getMessage()));
                P2SMod.LOGGER.error("LLM generation failed", ex);
            });
            return null;
        });

        return 1;
    }

    private static int list(CommandSourceStack source, int limit) {
        var entries = ScriptStorage.list(limit);
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No saved scripts"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Saved scripts (latest " + entries.size() + "):"), false);
        entries.forEach(e -> source.sendSuccess(
                () -> Component.literal(String.format("%s | %s | %s",
                        e.name,
                        java.time.Instant.ofEpochMilli(e.timestamp).toString(),
                        e.prompt == null ? "" : e.prompt)),
                false));
        return entries.size();
    }

    private static int loadSaved(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "name");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        ScriptStorage.Entry entry = ScriptStorage.load(name);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("No saved script: " + name));
            return 0;
        }
        StructureBuilder.VbsScript script;
        try {
            script = StructureBuilder.parse(entry.content);
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Saved script invalid: " + e.getMessage()));
            return 0;
        }

        ServerLevel world = ctx.getSource().getLevel();
        BlockPos origin = new BlockPos(x, y, z);
        StructureBuilder.build(world, origin, script);
        ctx.getSource().sendSuccess(() -> Component.literal("Built saved script: " + name), false);
        return 1;
    }
}
