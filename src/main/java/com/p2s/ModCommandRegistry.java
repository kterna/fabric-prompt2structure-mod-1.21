package com.p2s;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

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
            p2sCommand.then(Commands.literal("select")
                    .then(Commands.literal("pos1")
                            .then(Commands.argument("x", IntegerArgumentType.integer())
                                    .then(Commands.argument("y", IntegerArgumentType.integer())
                                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                                    .executes(ModCommandRegistry::selectPos1)))))
                    .then(Commands.literal("pos2")
                            .then(Commands.argument("x", IntegerArgumentType.integer())
                                    .then(Commands.argument("y", IntegerArgumentType.integer())
                                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                                    .executes(ModCommandRegistry::selectPos2)))))
                    .then(Commands.literal("clear").executes(ModCommandRegistry::selectClear))
                    .then(Commands.literal("show").executes(ModCommandRegistry::selectShow)));

            p2sCommand.then(Commands.literal("session")
                    .then(Commands.literal("start").executes(ctx -> {
                        SessionManager.startSession(ctx.getSource().getPlayerOrException(), "");
                        return 1;
                    }))
                    .then(Commands.literal("end").executes(ctx -> {
                        SessionManager.endSession(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                    .then(Commands.literal("undo").executes(ctx -> {
                        SessionManager.undo(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                    .then(Commands.literal("redo").executes(ctx -> {
                        SessionManager.redo(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                    .then(Commands.literal("apply").executes(ctx -> {
                        SessionManager.handleSessionAction(ctx.getSource().getPlayerOrException(), "apply", "");
                        return 1;
                    }))
                    .then(Commands.literal("discard").executes(ctx -> {
                        SessionManager.handleSessionAction(ctx.getSource().getPlayerOrException(), "discard", "");
                        return 1;
                    }))
                    .then(Commands.literal("save")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> {
                                        SessionManager.save(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name"));
                                        return 1;
                                    }))
                            .executes(ctx -> {
                                SessionManager.save(ctx.getSource().getPlayerOrException(), null);
                                return 1;
                            }))
            );

            p2sCommand.then(Commands.literal("chat")
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                SessionManager.handleChatMessage(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "message"));
                                return 1;
                            })));

            p2sCommand.then(Commands.literal("gen")
                    .then(Commands.argument("prompt", StringArgumentType.greedyString())
                            .executes(ctx -> generateWithSelection(ctx.getSource(), StringArgumentType.getString(ctx, "prompt")))));
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

            dispatcher.register(
                    Commands.literal("p2sprompt")
                            .requires(source -> source.hasPermission(2))
                            .then(Commands.literal("list").executes(ctx -> listPrompts(ctx.getSource())))
                            .then(Commands.literal("set")
                                    .then(Commands.argument("name", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                return SharedSuggestionProvider.suggest(ModConfig.promptMap().keySet(), builder);
                                            })
                                            .executes(ctx -> setPrompt(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                            .executes(ctx -> showCurrentPrompt(ctx.getSource()))
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
                    if (result.script() == null) {
                        source.sendFailure(Component.literal("AI response did not include a structure"));
                        return;
                    }
                    String savedName = ScriptStorage.saveV2(prompt, result.script(), result.fullMessage(), null);
                    StructureBuilder.buildV2(world, origin, result.script());
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
        StructureBuilder.VbsScriptV2 script = entry.toScriptV2();
        if (script == null) {
            ctx.getSource().sendFailure(Component.literal("Saved script invalid or empty"));
            return 0;
        }

        ServerLevel world = ctx.getSource().getLevel();
        BlockPos origin = new BlockPos(x, y, z);
        StructureBuilder.buildV2(world, origin, script);
        ctx.getSource().sendSuccess(() -> Component.literal("Built saved script: " + name), false);
        return 1;
    }

    private static int selectPos1(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        SelectionManager.setPos1(player, new BlockPos(x, y, z));
        context.getSource().sendSuccess(() -> Component.literal("Pos1 set: " + x + "," + y + "," + z), false);
        return 1;
    }

    private static int selectPos2(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        SelectionManager.setPos2(player, new BlockPos(x, y, z));
        context.getSource().sendSuccess(() -> Component.literal("Pos2 set: " + x + "," + y + "," + z), false);
        return 1;
    }

    private static int selectClear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SelectionManager.clear(player);
        context.getSource().sendSuccess(() -> Component.literal("Selection cleared"), false);
        return 1;
    }

    private static int selectShow(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SelectionManager.Selection sel = SelectionManager.get(player.getUUID());
        if (sel == null || (!sel.hasPos1() && !sel.hasPos2())) {
            context.getSource().sendSuccess(() -> Component.literal("No selection"), false);
            return 0;
        }
        String p1 = sel.pos1() == null ? "-" : sel.pos1().getX() + "," + sel.pos1().getY() + "," + sel.pos1().getZ();
        String p2 = sel.pos2() == null ? "-" : sel.pos2().getX() + "," + sel.pos2().getY() + "," + sel.pos2().getZ();
        context.getSource().sendSuccess(() -> Component.literal("Pos1: " + p1), false);
        context.getSource().sendSuccess(() -> Component.literal("Pos2: " + p2), false);
        if (sel.isComplete()) {
            Vec3i size = sel.size();
            context.getSource().sendSuccess(() -> Component.literal("Size: " + size.getX() + "x" + size.getY() + "x" + size.getZ()), false);
        }
        return 1;
    }

    private static int generateWithSelection(CommandSourceStack source, String prompt) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        SelectionManager.Selection sel = SelectionManager.get(player.getUUID());
        BlockPos origin = player.blockPosition();
        Vec3i size = null;
        if (sel != null && sel.isComplete()) {
            origin = sel.min();
            size = sel.size();
        }
        final BlockPos originFinal = origin;
        String systemPrompt = ModConfig.currentSystemPrompt();
        if (size != null) {
            systemPrompt = systemPrompt + "\n\n" + SessionManager.buildAreaConstraint(size);
        }

        List<JsonObject> messages = new ArrayList<>();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        source.sendSuccess(() -> Component.literal("Requesting structure from AI..."), false);

        LLMService.requestWithHistory(messages).thenAccept(result -> {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                if (result.script() == null) {
                    source.sendFailure(Component.literal("AI response did not include a structure"));
                    return;
                }
                try {
                    String savedName = ScriptStorage.saveV2(prompt, result.script(), result.rawAssistantMessage().toString(), null);
                    StructureBuilder.buildV2(source.getLevel(), originFinal, result.script());
                    source.sendSuccess(() -> Component.literal("Build completed (saved as " + savedName + ")"), false);
                } catch (Exception e) {
                    source.sendFailure(Component.literal("Build failed: " + e.getMessage()));
                    P2SMod.LOGGER.error("Build failed", e);
                }
            });
        }).exceptionally(ex -> {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                source.sendFailure(Component.literal("Request failed: " + ex.getMessage()));
                P2SMod.LOGGER.error("LLM generation failed", ex);
            });
            return null;
        });

        return 1;
    }

    private static int listPrompts(CommandSourceStack source) {
        var prompts = ModConfig.promptMap();
        if (prompts.isEmpty()) {
            source.sendFailure(Component.literal("No prompts configured"));
            return 0;
        }
        String current = ModConfig.activePromptName();
        source.sendSuccess(() -> Component.literal("Available prompts (current: " + current + "):"), false);
        prompts.keySet().forEach(name -> source.sendSuccess(
                () -> Component.literal((name.equals(current) ? "* " : "  ") + name),
                false));
        return prompts.size();
    }

    private static int setPrompt(CommandSourceStack source, String name) {
        boolean ok = ModConfig.setActivePrompt(name, true);
        if (!ok) {
            source.sendFailure(Component.literal("Prompt not found: " + name));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Active prompt set to: " + name), false);
        return 1;
    }

    private static int showCurrentPrompt(CommandSourceStack source) {
        String current = ModConfig.activePromptName();
        source.sendSuccess(() -> Component.literal("Current prompt: " + current), false);
        return 1;
    }
}
