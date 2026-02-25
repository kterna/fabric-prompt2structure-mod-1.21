# P2S Mod v2 - 设计文档（客户端+服务端架构）

> 基于当前代码库 (commit aae175d) 的四项重大需求升级设计。
> 架构升级：从纯服务端 mod → 客户端+服务端 mod。

---

## 目录

1. [现状分析](#1-现状分析)
2. [架构升级：Server-only → Client+Server](#2-架构升级server-only--clientserver)
3. [需求一：区域选择与可视化框架](#3-需求一区域选择与可视化框架)
4. [需求二：Session 会话制 — 持续对话修改 Structure](#4-需求二session-会话制--持续对话修改-structure)
5. [需求三：Tool Call 生成模式](#5-需求三tool-call-生成模式)
6. [需求四：增强 JSON 格式 — 多 JSON 分区生成与优先级](#6-需求四增强-json-格式--多-json-分区生成与优先级)
7. [文件变更总览](#7-文件变更总览)
8. [实施顺序与依赖关系](#8-实施顺序与依赖关系)

---

## 1. 现状分析

### 当前架构

```
玩家命令 /p2s x y z "prompt"
    │
    ▼
ModCommandRegistry.runCommand()
    │
    ▼
LLMService.requestStructure(prompt)   ← 单轮 messages: [system, user]
    │                                     response_format: json_object
    ▼
parseResponse() → 从 choices[0].message.content 提取 JSON
    │
    ▼
StructureBuilder.parse(json) → VbsScript { palette, structure: [Layer{actions}] }
    │
    ▼
StructureBuilder.build(world, origin, script) → setBlockAndUpdate()
```

### 当前局限

| 方面 | 现状 | 问题 |
|------|------|------|
| 架构 | `environment: "server"`，纯服务端 | 无法做客户端渲染、GUI、按键绑定 |
| 区域 | 用户手动输入 x y z 坐标 | 无可视化预览，无法直观选定区域 |
| 对话 | 每次 `/p2s` 都是全新单轮对话 | 无法迭代修改："把门改大一点"无法实现 |
| 交互 | 所有操作都通过命令 | 对话体验差，没有 GUI |
| 生成方式 | 直接从 response content 提取 JSON | 不可靠，依赖 `response_format: json_object` 和 markdown 清理 |
| JSON 格式 | 单一 VbsScript，所有部分混在一起 | 无法分区生成，大型建筑容易超出 token 限制或互相覆盖 |

---

## 2. 架构升级：Server-only → Client+Server

### 2.1 为什么需要客户端

| 能力 | Server-only | Client+Server |
|------|-------------|---------------|
| 选区线框渲染 | 服务端粒子（模糊、有延迟、需持续发包） | 客户端 GL 线框渲染（精确、流畅、零网络开销） |
| 对话交互 | `/p2s chat "msg"` 命令（打字慢、不直观） | 专用 Chat GUI Screen（输入框 + 消息列表） |
| 按键绑定 | 无 | 按键打开聊天窗口、切换选区模式 |
| 状态显示 | 聊天栏消息 | HUD 叠加层（当前 session、选区尺寸等） |
| 建筑预览 | 无 | 未来可扩展：半透明方块预览 |

### 2.2 总体架构

```
┌─────────────── CLIENT ───────────────┐     ┌─────────────── SERVER ───────────────┐
│                                      │     │                                      │
│  P2SModClient                        │     │  P2SMod                              │
│  ├── SelectionRenderer (GL 渲染)     │     │  ├── ModCommandRegistry (命令)       │
│  ├── ChatScreen (GUI 界面)           │     │  ├── SessionManager (会话管理)       │
│  ├── SelectionManager.Client         │     │  ├── SelectionManager.Server         │
│  ├── KeyBindings (按键绑定)          │     │  ├── LLMService (AI 请求)            │
│  └── HudOverlay (状态 HUD)           │     │  ├── StructureBuilder (方块放置)     │
│                                      │     │  └── ScriptStorage (持久化)          │
│              ▲                        │     │              ▲                        │
│              │                        │     │              │                        │
│      ┌───────┴────────┐              │     │      ┌───────┴────────┐              │
│      │ ClientPlayNetworkHandler      │     │      │ ServerPlayNetworkHandler      │
│      └───────┬────────┘              │     │      └───────┬────────┘              │
│              │                        │     │              │                        │
└──────────────┼────────────────────────┘     └──────────────┼────────────────────────┘
               │         Custom Packets                      │
               └─────────────────────────────────────────────┘
```

### 2.3 配置变更

**fabric.mod.json**
```diff
- "environment": "server",
+ "environment": "*",
  "entrypoints": {
    "main": ["com.p2s.P2SMod"],
+   "client": ["com.p2s.P2SModClient"]
  },
  "mixins": [
-   "p2s.mixins.json"
+   "p2s.mixins.json",
+   "p2s.client.mixins.json"
  ],
```

**build.gradle** — loom 的 `splitEnvironmentSourceSets()` 已经启用，只需补上 client sourceSet：
```diff
  loom {
    splitEnvironmentSourceSets()
    mods {
      "prompt2structure" {
        sourceSet sourceSets.main
+       sourceSet sourceSets.client
      }
    }
  }
```

### 2.4 网络协议：自定义 Packet 设计

使用 Fabric Networking API v1 (`net.fabricmc.fabric.api.networking.v1`)。

#### Packet 一览

| ID (ResourceLocation) | 方向 | 用途 |
|-------|------|------|
| `p2s:c2s_set_selection` | C→S | 客户端通知服务端选区变更 |
| `p2s:c2s_chat_message` | C→S | 客户端发送聊天消息 |
| `p2s:c2s_session_action` | C→S | 客户端请求 session 操作 (start/end/undo) |
| `p2s:s2c_selection_sync` | S→C | 服务端同步选区状态给客户端 |
| `p2s:s2c_chat_response` | S→C | 服务端推送 AI 回复给客户端 |
| `p2s:s2c_session_sync` | S→C | 服务端同步 session 状态（活跃/轮次/结构摘要） |
| `p2s:s2c_build_progress` | S→C | 服务端通知构建进度/完成 |

#### Packet 数据结构

```java
// ===== C→S Packets (客户端发往服务端) =====

// 选区设置
public record C2SSetSelectionPacket(
    int pointIndex,   // 0=pos1, 1=pos2, -1=clear
    BlockPos pos       // 方块坐标 (clear 时忽略)
) {}

// 聊天消息
public record C2SChatMessagePacket(
    String message     // 用户输入的消息文本
) {}

// Session 操作
public record C2SSessionActionPacket(
    String action,     // "start" | "end" | "undo" | "save"
    String payload     // start: 可选 origin 坐标 JSON; save: 可选 name; 其他为空
) {}

// ===== S→C Packets (服务端发往客户端) =====

// 选区同步
public record S2CSelectionSyncPacket(
    boolean hasPos1, BlockPos pos1,
    boolean hasPos2, BlockPos pos2
) {}

// AI 聊天回复
public record S2CChatResponsePacket(
    String assistantText,  // AI 的文字回复
    boolean hasStructure,  // 是否包含结构变更
    String status          // "thinking" | "building" | "done" | "error"
) {}

// Session 状态同步
public record S2CSessionSyncPacket(
    boolean active,         // 是否有活跃 session
    String sessionId,
    int turnCount,          // 对话轮次
    int partCount,          // 当前结构 part 数量
    int totalBlocks,        // 总方块数
    String partsSummary     // 例: "foundation, walls, roof"
) {}

// 构建进度
public record S2CBuildProgressPacket(
    String phase,           // "clearing" | "building" | "done"
    String currentPart,     // 当前正在构建的 part name
    int progress,           // 0-100
    int blocksPlaced
) {}
```

#### 注册方式

```java
// 服务端 (P2SMod.java)
public static final ResourceLocation C2S_SET_SELECTION = ResourceLocation.fromNamespaceAndPath("p2s", "c2s_set_selection");
public static final ResourceLocation C2S_CHAT_MESSAGE  = ResourceLocation.fromNamespaceAndPath("p2s", "c2s_chat_message");
public static final ResourceLocation C2S_SESSION_ACTION = ResourceLocation.fromNamespaceAndPath("p2s", "c2s_session_action");

ServerPlayNetworking.registerGlobalReceiver(C2S_SET_SELECTION, (server, player, handler, buf, responseSender) -> {
    int pointIndex = buf.readVarInt();
    BlockPos pos = buf.readBlockPos();
    server.execute(() -> {
        SelectionManager.handleClientSelection(player, pointIndex, pos);
        // 回发 S2CSelectionSyncPacket 确认
    });
});

// 客户端 (P2SModClient.java)
public static final ResourceLocation S2C_SELECTION_SYNC = ResourceLocation.fromNamespaceAndPath("p2s", "s2c_selection_sync");
public static final ResourceLocation S2C_CHAT_RESPONSE  = ResourceLocation.fromNamespaceAndPath("p2s", "s2c_chat_response");
public static final ResourceLocation S2C_SESSION_SYNC   = ResourceLocation.fromNamespaceAndPath("p2s", "s2c_session_sync");
public static final ResourceLocation S2C_BUILD_PROGRESS = ResourceLocation.fromNamespaceAndPath("p2s", "s2c_build_progress");

ClientPlayNetworking.registerGlobalReceiver(S2C_CHAT_RESPONSE, (client, handler, buf, responseSender) -> {
    String text = buf.readUtf();
    boolean hasStructure = buf.readBoolean();
    String status = buf.readUtf();
    client.execute(() -> {
        ChatScreen.onAIResponse(text, hasStructure, status);
    });
});
```

### 2.5 服务端仍可独立运行

即使客户端没安装此 mod：
- 所有命令 (`/p2s`, `/p2s session start`, `/p2s chat`) 仍可通过服务端命令操作
- 没有客户端 = 没有 GUI / 没有线框渲染 / 没有按键绑定，但核心功能正常
- 服务端在发送 S2C packet 前检查客户端是否注册了对应 channel

```java
// 安全发包
public static void sendToClient(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
    if (ServerPlayNetworking.canSend(player, channel)) {
        ServerPlayNetworking.send(player, channel, buf);
    }
}
```

---

## 3. 需求一：区域选择与可视化框架

### 3.1 目标

- 玩家可以在游戏中手动选取一个长方体区域（两个对角点）
- 选取后以 GL 线框方式实时可视化显示所选区域（客户端渲染，精确流畅）
- 后续生成命令可引用该区域，而非手动输入坐标

### 3.2 交互设计

```
快捷键 (默认 R)           ← 切换选区模式 on/off
  选区模式下：
    左键点击方块 → 设置 pos1
    右键点击方块 → 设置 pos2
    聊天栏提示 "Pos1: (x, y, z)" / "Pos2: (x, y, z)"
    HUD 显示: "Selection Mode | 12x8x15 | Pos1: ... Pos2: ..."

快捷键 (默认 P)           ← 打开 Chat GUI（见需求二）

/p2s select pos1 <x> <y> <z>   ← 命令方式手动设置 pos1（向后兼容）
/p2s select pos2 <x> <y> <z>   ← 命令方式手动设置 pos2
/p2s select clear               ← 清除选区
/p2s select show                ← 显示选区信息
```

### 3.3 数据模型 — SelectionManager

选区数据在服务端和客户端各存一份，通过 packet 同步。

```java
// 新文件: src/main/java/com/p2s/SelectionManager.java (共享逻辑)
public class SelectionManager {
    // 服务端: 每个玩家的权威选区状态
    private static final Map<UUID, Selection> serverSelections = new ConcurrentHashMap<>();

    public record Selection(BlockPos pos1, BlockPos pos2) {
        public BlockPos min() {
            return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
            );
        }
        public BlockPos max() {
            return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
            );
        }
        public Vec3i size() {
            return new Vec3i(
                max().getX() - min().getX() + 1,
                max().getY() - min().getY() + 1,
                max().getZ() - min().getZ() + 1
            );
        }
        public boolean isComplete() { return pos1 != null && pos2 != null; }
    }

    // 服务端方法
    public static void setPos1(UUID player, BlockPos pos) { /* 更新 + sync to client */ }
    public static void setPos2(UUID player, BlockPos pos) { /* 更新 + sync to client */ }
    public static Selection get(UUID player) { return serverSelections.get(player); }
    public static void clear(UUID player) { /* 清除 + sync to client */ }

    // 被 C2S packet handler 调用
    public static void handleClientSelection(ServerPlayer player, int point, BlockPos pos) { /* ... */ }
}
```

```java
// 新文件: src/client/java/com/p2s/ClientSelectionManager.java (客户端本地状态)
public class ClientSelectionManager {
    // 客户端本地镜像（由 S2C packet 更新）
    private static @Nullable BlockPos pos1;
    private static @Nullable BlockPos pos2;
    private static boolean selectMode = false;

    public static void onSyncFromServer(BlockPos p1, BlockPos p2) {
        pos1 = p1; pos2 = p2;
    }

    public static boolean isSelectMode() { return selectMode; }
    public static void toggleSelectMode() { selectMode = !selectMode; }

    // 客户端点击方块 → 发 C2S packet → 服务端处理 → 回发 S2C 确认
    public static void onLeftClick(BlockPos pos) {
        if (!selectMode) return;
        sendC2SSetSelection(0, pos);  // 0 = pos1
    }
    public static void onRightClick(BlockPos pos) {
        if (!selectMode) return;
        sendC2SSetSelection(1, pos);  // 1 = pos2
    }

    public static @Nullable BlockPos getPos1() { return pos1; }
    public static @Nullable BlockPos getPos2() { return pos2; }
}
```

### 3.4 客户端渲染 — GL 线框

```java
// 新文件: src/client/java/com/p2s/SelectionRenderer.java
public class SelectionRenderer {

    /**
     * 注册到 WorldRenderEvents.AFTER_TRANSLUCENT
     * 在世界渲染后绘制半透明线框
     */
    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            BlockPos p1 = ClientSelectionManager.getPos1();
            BlockPos p2 = ClientSelectionManager.getPos2();
            if (p1 == null && p2 == null) return;

            MatrixStack matrices = context.matrixStack();
            Camera camera = context.camera();
            Vec3d camPos = camera.getPos();

            matrices.push();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);

            VertexConsumerProvider consumers = context.consumers();
            // 使用 RenderLayer.getLines() 或自定义 RenderLayer

            if (p1 != null && p2 != null) {
                // 完整选区：绿色线框
                drawBox(matrices, consumers,
                    Math.min(p1.getX(), p2.getX()),
                    Math.min(p1.getY(), p2.getY()),
                    Math.min(p1.getZ(), p2.getZ()),
                    Math.max(p1.getX(), p2.getX()) + 1,
                    Math.max(p1.getY(), p2.getY()) + 1,
                    Math.max(p1.getZ(), p2.getZ()) + 1,
                    0.0f, 1.0f, 0.0f, 0.8f  // 绿色, alpha=0.8
                );
            } else {
                // 单点：黄色小框
                BlockPos p = (p1 != null) ? p1 : p2;
                drawBox(matrices, consumers,
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                    1.0f, 1.0f, 0.0f, 0.8f  // 黄色
                );
            }

            matrices.pop();
        });
    }

    /**
     * 绘制 AABB 的 12 条棱边
     */
    private static void drawBox(MatrixStack matrices, VertexConsumerProvider consumers,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 float r, float g, float b, float a) {
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());
        Matrix4f model = matrices.peek().getPositionMatrix();
        Matrix3f normal = matrices.peek().getNormalMatrix();

        // 底面 4 条
        line(lines, model, normal, x1,y1,z1, x2,y1,z1, r,g,b,a);
        line(lines, model, normal, x2,y1,z1, x2,y1,z2, r,g,b,a);
        line(lines, model, normal, x2,y1,z2, x1,y1,z2, r,g,b,a);
        line(lines, model, normal, x1,y1,z2, x1,y1,z1, r,g,b,a);
        // 顶面 4 条
        line(lines, model, normal, x1,y2,z1, x2,y2,z1, r,g,b,a);
        line(lines, model, normal, x2,y2,z1, x2,y2,z2, r,g,b,a);
        line(lines, model, normal, x2,y2,z2, x1,y2,z2, r,g,b,a);
        line(lines, model, normal, x1,y2,z2, x1,y2,z1, r,g,b,a);
        // 竖直 4 条
        line(lines, model, normal, x1,y1,z1, x1,y2,z1, r,g,b,a);
        line(lines, model, normal, x2,y1,z1, x2,y2,z1, r,g,b,a);
        line(lines, model, normal, x2,y1,z2, x2,y2,z2, r,g,b,a);
        line(lines, model, normal, x1,y1,z2, x1,y2,z2, r,g,b,a);
    }
}
```

### 3.5 选区模式交互 — 客户端事件拦截

```java
// 在 P2SModClient.onInitializeClient() 注册
// 左键拦截：AttackBlockCallback (Fabric API)
AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
    if (world.isClientSide() && ClientSelectionManager.isSelectMode()) {
        ClientSelectionManager.onLeftClick(pos);
        return ActionResult.SUCCESS; // 阻止方块破坏
    }
    return ActionResult.PASS;
});

// 右键拦截：UseBlockCallback
UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
    if (world.isClientSide() && ClientSelectionManager.isSelectMode()) {
        ClientSelectionManager.onRightClick(hitResult.getBlockPos());
        return ActionResult.SUCCESS;
    }
    return ActionResult.PASS;
});
```

### 3.6 按键绑定

```java
// 新文件: src/client/java/com/p2s/ModKeyBindings.java
public class ModKeyBindings {
    public static final KeyBinding SELECT_MODE = KeyBindingHelper.registerKeyBinding(
        new KeyBinding("key.p2s.select_mode", InputUtil.Type.KEYSYM,
                       GLFW.GLFW_KEY_R, "category.p2s")
    );

    public static final KeyBinding OPEN_CHAT = KeyBindingHelper.registerKeyBinding(
        new KeyBinding("key.p2s.open_chat", InputUtil.Type.KEYSYM,
                       GLFW.GLFW_KEY_P, "category.p2s")
    );

    public static void registerTickHandler() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (SELECT_MODE.wasPressed()) {
                ClientSelectionManager.toggleSelectMode();
                // 显示 actionbar 消息: "Selection mode: ON/OFF"
            }
            while (OPEN_CHAT.wasPressed()) {
                client.setScreen(new P2SChatScreen());
            }
        });
    }
}
```

### 3.7 HUD 叠加 — 选区状态

```java
// 新文件: src/client/java/com/p2s/HudOverlay.java
public class HudOverlay {
    public static void register() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return;

            int y = 4;

            // 选区模式指示
            if (ClientSelectionManager.isSelectMode()) {
                drawContext.drawText(client.textRenderer,
                    "§a[Selection Mode]", 4, y, 0x55FF55, true);
                y += 12;
            }

            // 选区尺寸
            BlockPos p1 = ClientSelectionManager.getPos1();
            BlockPos p2 = ClientSelectionManager.getPos2();
            if (p1 != null && p2 != null) {
                int sx = Math.abs(p2.getX() - p1.getX()) + 1;
                int sy = Math.abs(p2.getY() - p1.getY()) + 1;
                int sz = Math.abs(p2.getZ() - p1.getZ()) + 1;
                drawContext.drawText(client.textRenderer,
                    String.format("§7Selection: %dx%dx%d", sx, sy, sz),
                    4, y, 0xAAAAAA, true);
                y += 12;
            }

            // Session 状态
            if (ClientSessionState.isActive()) {
                drawContext.drawText(client.textRenderer,
                    String.format("§b[Session] Turn %d | %d parts",
                        ClientSessionState.getTurnCount(),
                        ClientSessionState.getPartCount()),
                    4, y, 0x55FFFF, true);
            }
        });
    }
}
```

### 3.8 与生成命令集成

```
/p2s gen <prompt>     ← 使用已选区域的 min 点作为 origin
                         区域尺寸信息自动注入到 system prompt

/p2s <x> <y> <z> <prompt>   ← 保留向后兼容
```

注入到 system prompt 的信息：
```
Build area constraint: The structure must fit within a {sizeX}x{sizeY}x{sizeZ} region.
Coordinates are relative to (0,0,0), max bounds are ({sizeX-1}, {sizeY-1}, {sizeZ-1}).
```

---

## 4. 需求二：Session 会话制 — 持续对话修改 Structure

### 4.1 目标

- 用户可以开启一个 session，与 AI 进行多轮对话
- 每轮对话中 AI 可以看到之前的完整聊天记录 + 当前结构状态
- 用户可以说"把门改大一点"、"加一个阳台"等自然语言来迭代修改
- **通过客户端 Chat GUI 进行对话，体验接近 ChatGPT**

### 4.2 Chat GUI Screen 设计

```java
// 新文件: src/client/java/com/p2s/P2SChatScreen.java
public class P2SChatScreen extends Screen {

    // 布局（屏幕右侧 40% 宽度的半透明面板，不阻挡游戏视角）
    // ┌────────────────────────────────────────┬──────────────────────┐
    // │                                        │  ┌──── Chat ──────┐ │
    // │                                        │  │ AI: 好的...     │ │
    // │          Minecraft 游戏画面             │  │ You: 加个阳台   │ │
    // │         (仍可看到建筑变化)              │  │ AI: 已添加...   │ │
    // │                                        │  │ [Building...]   │ │
    // │                                        │  ├────────────────┤ │
    // │                                        │  │ [输入框____] [>]│ │
    // │                                        │  └────────────────┘ │
    // └────────────────────────────────────────┴──────────────────────┘

    private final List<ChatEntry> messages = new ArrayList<>();
    private TextFieldWidget inputField;
    private double scrollOffset = 0;

    record ChatEntry(String role, String text, long timestamp) {
        boolean isUser() { return "user".equals(role); }
        boolean isSystem() { return "status".equals(role); }
    }

    @Override
    public void init() {
        int panelWidth = this.width * 2 / 5;
        int panelX = this.width - panelWidth;

        // 底部输入框
        inputField = new TextFieldWidget(textRenderer,
            panelX + 4, this.height - 24, panelWidth - 32, 16,
            Text.literal("Message..."));
        inputField.setMaxLength(512);
        addDrawableChild(inputField);

        // 发送按钮
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), btn -> sendMessage())
            .dimensions(this.width - 24, this.height - 24, 20, 16)
            .build());
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        messages.add(new ChatEntry("user", text, System.currentTimeMillis()));
        messages.add(new ChatEntry("status", "Thinking...", System.currentTimeMillis()));
        inputField.setText("");

        // 发送 C2S packet
        ClientPlayNetworking.send(P2SMod.C2S_CHAT_MESSAGE,
            PacketByteBufs.create().writeUtf(text));
    }

    // 由 S2C_CHAT_RESPONSE handler 调用
    public static void onAIResponse(String text, boolean hasStructure, String status) {
        // 移除 "Thinking..." 状态消息
        // 添加 AI 回复
        // 如果 hasStructure → 添加 "[Structure updated]" 状态
        // 如果 status == "building" → 添加 "[Building...]" 状态
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 右侧半透明面板背景
        int panelX = this.width * 3 / 5;
        context.fill(panelX, 0, this.width, this.height, 0xAA000000);

        // 渲染消息列表（支持滚动）
        int y = this.height - 40;
        for (int i = messages.size() - 1; i >= 0 && y > 4; i--) {
            ChatEntry entry = messages.get(i);
            int color = entry.isUser() ? 0xFFFFFF : entry.isSystem() ? 0xAAAA00 : 0x55FF55;
            String prefix = entry.isUser() ? "§f[You] " : entry.isSystem() ? "§e" : "§a[AI] ";
            // 自动换行绘制
            y -= renderWrappedText(context, prefix + entry.text, panelX + 4, y, this.width - panelX - 8, color);
            y -= 4; // 消息间距
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false; // 不暂停游戏，可以看到建筑实时变化
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && inputField.isFocused()) {
            sendMessage();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
```

### 4.3 Session 数据模型 (服务端)

```java
// 新文件: src/main/java/com/p2s/SessionManager.java
public class SessionManager {
    private static final Map<UUID, Session> activeSessions = new ConcurrentHashMap<>();

    public static class Session {
        private final String id;
        private final UUID playerId;
        private final long createdAt;
        private BlockPos origin;
        private @Nullable SelectionManager.Selection selection;

        // 对话历史 — 存储完整的 OpenAI messages 数组
        // 包括 system, user, assistant (含 tool_calls), tool messages
        private final List<JsonObject> messageHistory = new ArrayList<>();

        // 结构版本管理
        private @Nullable StructureBuilder.VbsScriptV2 currentScript;
        private final List<StructureBuilder.VbsScriptV2> scriptHistory = new ArrayList<>();
        private @Nullable BlockState[][][] originalTerrain; // 原始地形快照

        public void pushScript(StructureBuilder.VbsScriptV2 newScript) {
            if (currentScript != null) {
                scriptHistory.add(deepCopy(currentScript));
            }
            currentScript = newScript;
        }

        public @Nullable StructureBuilder.VbsScriptV2 undo() {
            if (scriptHistory.isEmpty()) return null;
            currentScript = scriptHistory.remove(scriptHistory.size() - 1);
            return currentScript;
        }
    }

    public static Session start(ServerPlayer player) {
        Session session = new Session(player);
        // 初始化 system message
        session.addSystemMessage(buildSystemPrompt(player));
        // 如果有选区 → 记录 origin + 保存原始地形快照
        SelectionManager.Selection sel = SelectionManager.get(player.getUUID());
        if (sel != null && sel.isComplete()) {
            session.selection = sel;
            session.origin = sel.min();
            session.captureOriginalTerrain(player.serverLevel());
        } else {
            session.origin = player.blockPosition();
        }
        activeSessions.put(player.getUUID(), session);
        syncSessionState(player); // S2C packet
        return session;
    }

    public static void end(ServerPlayer player) {
        activeSessions.remove(player.getUUID());
        syncSessionState(player);
    }

    public static @Nullable Session get(UUID playerId) {
        return activeSessions.get(playerId);
    }

    /** 处理聊天消息 — 核心流程 */
    public static void handleChat(ServerPlayer player, String userMessage) {
        Session session = activeSessions.get(player.getUUID());
        if (session == null) {
            player.sendSystemMessage(Component.literal("§cNo active session. Use /p2s session start"));
            return;
        }

        // 1. 追加 user message
        session.addUserMessage(userMessage);

        // 2. 通知客户端 "thinking"
        sendChatResponse(player, "", false, "thinking");

        // 3. 异步调用 LLM
        LLMService.requestWithHistory(session.messageHistory).thenAccept(result -> {
            player.server.execute(() -> {
                // 4. 追加 assistant message (含 tool_calls) 到 history
                session.addRawMessage(result.rawAssistantMessage());

                if (result.script() != null) {
                    // 5. 追加 tool result message
                    session.addToolResult(result.toolCallId(), buildToolResultText(result));

                    // 6. 合并结构
                    VbsScriptV2 merged = StructureBuilder.mergeScripts(
                        session.currentScript, result.script());
                    session.pushScript(merged);

                    // 7. 通知客户端 "building"
                    sendChatResponse(player, result.textContent(), true, "building");

                    // 8. 全量重建
                    rebuildStructure(player.serverLevel(), session);

                    // 9. 通知客户端 "done"
                    sendChatResponse(player, "", false, "done");
                } else {
                    // AI 只回复了文字，没有 tool call
                    sendChatResponse(player, result.textContent(), false, "done");
                }

                // 10. 同步 session 状态
                syncSessionState(player);
            });
        }).exceptionally(ex -> {
            player.server.execute(() -> {
                sendChatResponse(player, "Error: " + ex.getMessage(), false, "error");
            });
            return null;
        });
    }

    /** 全量重建：清空区域 → 重新构建 */
    private static void rebuildStructure(ServerLevel world, Session session) {
        if (session.selection != null && session.selection.isComplete()) {
            // 有选区：清空选区范围
            clearArea(world, session.selection.min(), session.selection.max());
        }
        // 构建当前完整结构
        if (session.currentScript != null) {
            StructureBuilder.buildV2(world, session.origin, session.currentScript);
        }
    }

    private static void clearArea(ServerLevel world, BlockPos min, BlockPos max) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    mutable.set(x, y, z);
                    world.setBlockAndUpdate(mutable, air);
                }
            }
        }
    }
}
```

### 4.4 客户端 Session 状态

```java
// 新文件: src/client/java/com/p2s/ClientSessionState.java
public class ClientSessionState {
    private static boolean active = false;
    private static String sessionId = "";
    private static int turnCount = 0;
    private static int partCount = 0;
    private static int totalBlocks = 0;
    private static String partsSummary = "";

    /** 由 S2C_SESSION_SYNC handler 调用 */
    public static void onSync(boolean active, String sessionId, int turnCount,
                               int partCount, int totalBlocks, String partsSummary) {
        ClientSessionState.active = active;
        ClientSessionState.sessionId = sessionId;
        ClientSessionState.turnCount = turnCount;
        ClientSessionState.partCount = partCount;
        ClientSessionState.totalBlocks = totalBlocks;
        ClientSessionState.partsSummary = partsSummary;
    }

    public static boolean isActive() { return active; }
    public static int getTurnCount() { return turnCount; }
    public static int getPartCount() { return partCount; }
    // ... getters
}
```

### 4.5 命令设计（向后兼容 + GUI 入口）

命令仍保留，给没有客户端 mod 的用户使用：

```
/p2s session start [x y z]   ← 开启 session（有选区用选区，否则用坐标/脚下）
/p2s session end              ← 结束 session
/p2s session undo             ← 撤回上一轮
/p2s session save [name]      ← 保存结构
/p2s session status           ← 显示 session 状态

/p2s chat <message>           ← 命令行发消息（等价于 GUI 输入）
```

有客户端 mod 的用户：
- 按 `P` 打开 Chat GUI → session 自动 start（如果没有活跃 session）
- 在 GUI 中输入消息 → 等价于 `/p2s chat`
- 关闭 GUI 不会结束 session（session 一直活跃直到 `/p2s session end`）

### 4.6 对话流程图（完整）

```
                          CLIENT                                SERVER
                            │                                     │
  按 P 键 ──────────────────┤                                     │
                            │                                     │
  打开 P2SChatScreen        │                                     │
  (如果无 session)          │── C2S_SESSION_ACTION "start" ──────▶│
                            │                                     │ SessionManager.start()
                            │◀── S2C_SESSION_SYNC (active) ──────│
                            │                                     │
  用户输入 "建一个木屋"     │                                     │
  点击发送                  │── C2S_CHAT_MESSAGE "建一个木屋" ──▶│
                            │                                     │ session.addUserMessage()
  显示 "Thinking..."        │◀── S2C_CHAT_RESPONSE (thinking) ──│
                            │                                     │ LLMService.requestWithHistory()
                            │                                     │ ... LLM 返回 tool_call ...
                            │                                     │ mergeScripts()
  显示 AI 文字              │◀── S2C_CHAT_RESPONSE (building) ──│
  显示 "[Building...]"      │                                     │ rebuildStructure()
                            │                                     │   clearArea()
                            │                                     │   buildV2()
  看到方块实时变化          │  (方块更新自动同步到客户端)         │
                            │                                     │
  显示 "[Done]"             │◀── S2C_CHAT_RESPONSE (done) ───────│
  更新 session 状态         │◀── S2C_SESSION_SYNC ───────────────│
                            │                                     │
  用户输入 "把门改宽一格"   │── C2S_CHAT_MESSAGE ──────────────▶│
                            │                                     │ (重复上述流程)
                            .                                     .
```

---

## 5. 需求三：Tool Call 生成模式

### 5.1 目标

- 不再从 `response.content` 中直接提取 JSON
- 改用 OpenAI-compatible Tool Call（function calling）让 LLM 通过工具调用来提交结构
- 更可靠、结构化、不需要 markdown 清理

### 5.2 Tool 定义

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "apply_structure",
        "description": "Apply a structure definition to the Minecraft world. Call this tool to build or modify blocks in the selected area.",
        "parameters": {
          "type": "object",
          "properties": {
            "palette": {
              "type": "object",
              "description": "Block palette mapping short names to Minecraft block IDs",
              "additionalProperties": { "type": "string" }
            },
            "structures": {
              "type": "array",
              "description": "Array of structure parts, each with a name, priority, and actions",
              "items": {
                "type": "object",
                "properties": {
                  "name": {
                    "type": "string",
                    "description": "Name of this structure part (e.g. 'foundation', 'walls', 'roof')"
                  },
                  "priority": {
                    "type": "integer",
                    "description": "Build priority (lower = built first). Parts with lower priority are built first, higher priority parts can overwrite lower ones."
                  },
                  "actions": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "type": { "type": "string", "enum": ["fill", "frame", "set"] },
                        "block": { "type": "string" },
                        "from": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 },
                        "to": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 },
                        "at": { "type": "array", "items": { "type": "array", "items": { "type": "integer" }, "minItems": 3, "maxItems": 3 } },
                        "facing": { "type": "string", "enum": ["north", "south", "east", "west", "up", "down"] }
                      },
                      "required": ["type", "block"]
                    }
                  }
                },
                "required": ["name", "priority", "actions"]
              }
            }
          },
          "required": ["palette", "structures"]
        }
      }
    }
  ]
}
```

### 5.3 LLM 请求变化

**移除：**
```json
"response_format": {"type": "json_object"}
```

**新增：**
```json
"tools": [ /* 上述 tool 定义 */ ],
"tool_choice": {"type": "function", "function": {"name": "apply_structure"}}
```

**注意：** 在 session 多轮对话中，`tool_choice` 不应为 `required`/`auto` — 因为 AI 有时只想追问细节而不生成结构。改为：
```json
"tool_choice": "auto"
```

### 5.4 LLMService 变更

```java
// LLMService.java — 新增方法

/** Session 模式：发送完整 history */
public static CompletableFuture<SessionResult> requestWithHistory(List<JsonObject> messages) {
    return CompletableFuture.supplyAsync(() -> {
        String bodyJson = buildSessionBody(messages);
        // ... HTTP 调用（与 requestStructure 类似）
        return parseSessionResponse(respBody);
    }, EXECUTOR);
}

private static String buildSessionBody(List<JsonObject> messages) {
    JsonObject body = new JsonObject();
    body.addProperty("model", ModConfig.MODEL);
    body.add("messages", GSON.toJsonTree(messages));
    body.addProperty("temperature", 0.4);

    // Tool 定义
    body.add("tools", buildToolDefinitions());
    body.addProperty("tool_choice", "auto");

    return GSON.toJson(body);
}

private static SessionResult parseSessionResponse(String responseBody) throws IOException {
    JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
    JsonObject firstChoice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
    JsonObject message = firstChoice.getAsJsonObject("message");

    String textContent = message.has("content") && !message.get("content").isJsonNull()
        ? message.get("content").getAsString() : "";

    JsonArray toolCalls = message.has("tool_calls") ? message.getAsJsonArray("tool_calls") : null;

    if (toolCalls != null && !toolCalls.isEmpty()) {
        JsonObject toolCall = toolCalls.get(0).getAsJsonObject();
        String funcName = toolCall.getAsJsonObject("function").get("name").getAsString();
        String arguments = toolCall.getAsJsonObject("function").get("arguments").getAsString();
        String toolCallId = toolCall.get("id").getAsString();

        if ("apply_structure".equals(funcName)) {
            VbsScriptV2 script = GSON.fromJson(arguments, VbsScriptV2.class);
            return new SessionResult(textContent, script, message, toolCallId);
        }
    }

    // AI 只回复文字，没有 tool call
    return new SessionResult(textContent, null, message, null);
}

public record SessionResult(
    String textContent,
    @Nullable VbsScriptV2 script,
    JsonObject rawAssistantMessage,  // 完整的 message 对象，直接存入 history
    @Nullable String toolCallId
) {}
```

### 5.5 Session 中的 Tool Call 消息流

```json
[
  {"role": "system", "content": "..."},
  {"role": "user", "content": "建一个小木屋"},
  {
    "role": "assistant",
    "content": "好的，我来建一个温馨的小木屋。",
    "tool_calls": [{
      "id": "call_abc123",
      "type": "function",
      "function": {
        "name": "apply_structure",
        "arguments": "{\"palette\":{...}, \"structures\":[...]}"
      }
    }]
  },
  {
    "role": "tool",
    "tool_call_id": "call_abc123",
    "content": "Structure applied: 3 parts (foundation, walls, roof). 847 blocks."
  },
  {"role": "user", "content": "把门改宽一格"},
  {
    "role": "assistant",
    "content": "已将门从1格加宽到2格。",
    "tool_calls": [{
      "id": "call_def456",
      "type": "function",
      "function": {
        "name": "apply_structure",
        "arguments": "{\"palette\":{...}, \"structures\":[{\"name\":\"door\", ...}]}"
      }
    }]
  },
  {
    "role": "tool",
    "tool_call_id": "call_def456",
    "content": "Structure applied: 1 part (door). 6 blocks."
  }
]
```

### 5.6 向后兼容

- `/p2s x y z prompt` 单轮命令仍然可用，内部也改用 tool call
- 配置 `"useToolCall": true/false` 开关
- `false` 时回退到旧的 `response_format: json_object` + content 解析

---

## 6. 需求四：增强 JSON 格式 — 多 JSON 分区生成与优先级

### 6.1 目标

- 一次生成可以包含多个结构分区（part），如 "foundation"、"walls"、"roof" 等
- 每个分区有独立的名称和优先级
- 低优先级先构建，高优先级后构建（可覆盖低优先级的方块）
- 避免分区之间互相冲突

### 6.2 新 JSON Schema（VbsScriptV2）

```json
{
  "palette": {
    "stone": "minecraft:stone",
    "oak": "minecraft:oak_planks",
    "glass": "minecraft:glass_pane"
  },
  "structures": [
    {
      "name": "foundation",
      "priority": 0,
      "actions": [
        {"type": "fill", "block": "stone", "from": [0,0,0], "to": [10,0,10]}
      ]
    },
    {
      "name": "walls",
      "priority": 10,
      "actions": [
        {"type": "frame", "block": "oak", "from": [0,1,0], "to": [10,5,10]}
      ]
    },
    {
      "name": "windows",
      "priority": 20,
      "actions": [
        {"type": "set", "block": "glass", "at": [[3,3,0],[7,3,0]]}
      ]
    },
    {
      "name": "roof",
      "priority": 30,
      "actions": [
        {"type": "fill", "block": "oak", "from": [0,6,0], "to": [10,6,10]}
      ]
    }
  ]
}
```

### 6.3 数据模型

```java
// StructureBuilder.java 中新增

public static class VbsScriptV2 {
    public Map<String, String> palette = new HashMap<>();
    public List<StructurePart> structures = new ArrayList<>();
}

public static class StructurePart {
    public String name;         // 分区名
    public int priority = 0;    // 优先级，数值越小越先构建
    public List<VbsAction> actions = new ArrayList<>();
}
```

### 6.4 构建流程

```java
public static void buildV2(ServerLevel world, BlockPos origin, VbsScriptV2 script) {
    Map<String, BlockState> palette = resolvePalette(script.palette);

    // 按优先级升序排列（低先 build，高后 build 可覆盖）
    List<StructurePart> sorted = script.structures.stream()
        .sorted(Comparator.comparingInt(p -> p.priority))
        .toList();

    for (StructurePart part : sorted) {
        P2SMod.LOGGER.info("Building part '{}' (priority={}), {} actions",
            part.name, part.priority, part.actions.size());
        for (VbsAction action : part.actions) {
            executeAction(world, origin, palette, action);
        }
    }
}
```

### 6.5 优先级规则

| 优先级值 | 语义 | 示例 |
|----------|------|------|
| 0 | 地基/清理 | foundation, terrain clearing |
| 10 | 主体结构 | walls, floors |
| 20 | 细节开孔 | windows, doors (覆盖墙体) |
| 30 | 屋顶/上盖 | roof, ceiling |
| 40 | 装饰/内饰 | furniture, lighting, flowers |
| 50 | 最终修饰 | signs, item frames |

### 6.6 与 Session 的合并策略

```java
public static VbsScriptV2 mergeScripts(@Nullable VbsScriptV2 base, VbsScriptV2 delta) {
    if (base == null) return delta;

    VbsScriptV2 merged = new VbsScriptV2();

    // palette: delta 覆盖 base 的同名 key
    merged.palette.putAll(base.palette);
    merged.palette.putAll(delta.palette);

    // structures: 按 name 匹配，同名 part 被 delta 替换
    Map<String, StructurePart> partMap = new LinkedHashMap<>();
    for (StructurePart p : base.structures) partMap.put(p.name, p);
    for (StructurePart p : delta.structures) partMap.put(p.name, p); // 覆盖
    merged.structures = new ArrayList<>(partMap.values());

    return merged;
}
```

AI 修改某部分只需返回该 part（同名替换），其他保持不变。

### 6.7 V1 → V2 兼容

```java
public static VbsScriptV2 fromV1(VbsScript v1) {
    VbsScriptV2 v2 = new VbsScriptV2();
    v2.palette = v1.palette;
    for (int i = 0; i < v1.structure.size(); i++) {
        StructurePart part = new StructurePart();
        part.name = "layer_" + i;
        part.priority = i * 10;
        part.actions = v1.structure.get(i).actions;
        v2.structures.add(part);
    }
    return v2;
}
```

---

## 7. 文件变更总览

### 新增文件 — 客户端 (`src/client/java/com/p2s/`)

| 文件 | 说明 |
|------|------|
| `P2SChatScreen.java` | 聊天 GUI Screen（右侧面板，消息列表 + 输入框） |
| `ClientSelectionManager.java` | 客户端选区状态镜像 + 选区模式开关 |
| `SelectionRenderer.java` | GL 线框渲染（WorldRenderEvents.AFTER_TRANSLUCENT） |
| `ModKeyBindings.java` | 按键绑定（R=选区模式，P=聊天 GUI） |
| `HudOverlay.java` | HUD 叠加层（选区尺寸、session 状态） |
| `ClientSessionState.java` | 客户端 session 状态镜像 |
| `ClientNetworkHandler.java` | 所有 S2C packet 接收处理 |

### 新增文件 — 服务端 (`src/main/java/com/p2s/`)

| 文件 | 说明 |
|------|------|
| `SelectionManager.java` | 服务端权威选区管理 |
| `SessionManager.java` | 会话管理（核心：对话历史、结构版本、rebuild） |
| `ServerNetworkHandler.java` | 所有 C2S packet 接收处理 |
| `P2SNetworkConstants.java` | Packet channel ID 常量 |

### 修改文件

| 文件 | 变更内容 |
|------|----------|
| `StructureBuilder.java` | 新增 `VbsScriptV2`、`StructurePart`、`buildV2()`、`mergeScripts()`、`fromV1()` |
| `LLMService.java` | 新增 tool 定义、`requestWithHistory()`、tool_call 解析、`SessionResult` |
| `ModCommandRegistry.java` | 新增 `/p2s select`、`/p2s session`、`/p2s chat`、`/p2s gen` 子命令 |
| `ModConfig.java` | 新增 `useToolCall` 配置项 |
| `ScriptStorage.java` | 适配 VbsScriptV2 格式，兼容 V1 读取 |
| `P2SMod.java` | 注册 C2S packet handlers、注册 tick 事件 |
| `P2SModClient.java` | 注册 S2C packet handlers、渲染器、按键绑定、HUD |
| `fabric.mod.json` | `environment: "*"`、添加 `client` entrypoint、添加 client mixins |
| `build.gradle` | loom mods 添加 `sourceSet sourceSets.client` |

### 不变文件

| 文件 | 原因 |
|------|------|
| `P2SServerMixin.java` | 暂无需求 |
| `P2SClientMixin.java` | 暂无需求（用 Fabric Events 代替 Mixin） |

---

## 8. 实施顺序与依赖关系

```
Phase 0: 架构升级（基础设施）
├── 0a. fabric.mod.json environment→"*"，添加 client entrypoint
├── 0b. build.gradle 添加 client sourceSet
├── 0c. P2SNetworkConstants — 定义所有 channel ID
├── 0d. ServerNetworkHandler — C2S packet 注册骨架
└── 0e. ClientNetworkHandler — S2C packet 注册骨架

Phase 1: 数据模型升级（需求四核心）
├── 1a. VbsScriptV2 / StructurePart 数据模型
├── 1b. buildV2() 按优先级排序构建
├── 1c. mergeScripts() 合并逻辑
├── 1d. fromV1() 兼容转换
└── 1e. ScriptStorage 适配 V2 + 兼容 V1

Phase 2: Tool Call 模式（需求三）
├── 2a. LLMService.buildToolDefinitions()
├── 2b. LLMService.requestWithHistory() + parseSessionResponse()
├── 2c. 旧 requestStructure() 改用 tool call
└── 2d. ModConfig 添加 useToolCall 配置

Phase 3: 区域选择（需求一）
├── 3a. SelectionManager (服务端)
├── 3b. C2S/S2C selection packets
├── 3c. /p2s select 命令
├── 3d. ClientSelectionManager (客户端)
├── 3e. SelectionRenderer GL 线框
├── 3f. ModKeyBindings (R 键)
├── 3g. 选区模式交互（AttackBlock/UseBlock 拦截）
└── 3h. HudOverlay 选区信息

Phase 4: Session + Chat GUI（需求二）
├── 4a. SessionManager (服务端核心)
├── 4b. C2S/S2C session & chat packets
├── 4c. /p2s session + /p2s chat 命令
├── 4d. P2SChatScreen GUI
├── 4e. ClientSessionState
├── 4f. ModKeyBindings (P 键)
├── 4g. HudOverlay session 信息
└── 4h. 全量 rebuild + clearArea 逻辑

Phase 5: 集成与优化
├── 5a. Chat GUI 中 session auto-start
├── 5b. 选区 → session origin 自动关联
├── 5c. /p2s gen 命令（选区 + session 联动）
├── 5d. System prompt 优化
└── 5e. 测试：完整流程 选区→session→chat→多轮修改→undo
```

### 依赖关系图

```
Phase 0 (架构) ──→ Phase 1 (数据模型) ──→ Phase 2 (Tool Call)
                                                    │
Phase 0 (架构) ──→ Phase 3 (区域选择) ──┐           │
                                        ├──→ Phase 4 (Session+Chat) ──→ Phase 5 (集成)
Phase 0 (架构) ──→ Phase 2 (Tool Call) ─┘
```

**关键路径：** Phase 0 → Phase 1 → Phase 2 → Phase 4 → Phase 5

**可并行：** Phase 3（区域选择）可与 Phase 1-2 并行开发

---

## 附录 A：更新后的 System Prompt 草案

```
You are a Minecraft Architect. You build structures by calling the apply_structure tool.

## Tool: apply_structure
- palette: Map short names to minecraft:block_id
- structures: Array of named parts, each with a priority and actions

## Priority Convention
- 0: foundation/clearing
- 10: main structure (walls, floors)
- 20: openings (windows, doors — overwrites walls)
- 30: roof/ceiling
- 40: interior/decoration
- 50: final touches

## Actions
- "fill": Solid cuboid from [x,y,z] to [x,y,z]
- "frame": Hollow cuboid (faces only)
- "set": Individual blocks at [[x,y,z], ...]
- Optional "facing": "north|south|east|west|up|down"

## Rules
- Coordinates relative to (0,0,0)
- Use standard Minecraft Java Edition block IDs
- Always split structure into logical named parts with appropriate priorities
- When modifying an existing structure, only return the changed parts (same name = replace that part)
- You may reply with just text (no tool call) if you need to ask the user a question
${areaConstraint}
```

`${areaConstraint}` 在有选区时注入：
```
## Build Area
The structure must fit within a {sizeX}x{sizeY}x{sizeZ} region.
Max coordinates: ({sizeX-1}, {sizeY-1}, {sizeZ-1}).
```

## 附录 B：配置文件新增字段

```json
{
  "apiUrl": "...",
  "apiKey": "...",
  "model": "gpt-4o-mini",
  "httpTimeoutSeconds": 30,
  "useToolCall": true,
  "prompts": { ... },
  "activePrompt": "default",
  "keyBindings": {
    "selectMode": "key.keyboard.r",
    "openChat": "key.keyboard.p"
  }
}
```

## 附录 C：Minecraft 1.21.1 Fabric API 关键接口参考

| 用途 | API |
|------|-----|
| 世界渲染后 hook | `WorldRenderEvents.AFTER_TRANSLUCENT` (fabric-rendering-v1) |
| HUD 渲染 hook | `HudRenderCallback.EVENT` (fabric-rendering-v1) |
| 按键绑定 | `KeyBindingHelper.registerKeyBinding()` (fabric-key-binding-api-v1) |
| 客户端 tick | `ClientTickEvents.END_CLIENT_TICK` (fabric-lifecycle-events-v1) |
| 服务端 tick | `ServerTickEvents.END_SERVER_TICK` (fabric-lifecycle-events-v1) |
| 方块攻击回调 | `AttackBlockCallback.EVENT` (fabric-events-interaction-v0) |
| 方块使用回调 | `UseBlockCallback.EVENT` (fabric-events-interaction-v0) |
| C→S 网络 | `ServerPlayNetworking.registerGlobalReceiver()` (fabric-networking-api-v1) |
| S→C 网络 | `ClientPlayNetworking.registerGlobalReceiver()` (fabric-networking-api-v1) |
| 安全发包 | `ServerPlayNetworking.canSend()` / `.send()` |
