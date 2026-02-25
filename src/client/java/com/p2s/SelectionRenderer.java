package com.p2s;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;

public final class SelectionRenderer {
    private SelectionRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            BlockPos p1 = ClientSelectionManager.getPos1();
            BlockPos p2 = ClientSelectionManager.getPos2();
            if (p1 == null && p2 == null) {
                return;
            }

            PoseStack poseStack = context.matrixStack();
            Camera camera = context.camera();
            Vec3 camPos = camera.getPosition();

            poseStack.pushPose();
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

            MultiBufferSource consumers = context.consumers();
            VertexConsumer lines = consumers.getBuffer(RenderType.lines());

            if (p1 != null && p2 != null) {
                int minX = Math.min(p1.getX(), p2.getX());
                int minY = Math.min(p1.getY(), p2.getY());
                int minZ = Math.min(p1.getZ(), p2.getZ());
                int maxX = Math.max(p1.getX(), p2.getX()) + 1;
                int maxY = Math.max(p1.getY(), p2.getY()) + 1;
                int maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;
                AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
                LevelRenderer.renderLineBox(poseStack, lines, box, 0.0f, 1.0f, 0.0f, 0.8f);
            } else {
                BlockPos p = p1 != null ? p1 : p2;
                if (p != null) {
                    AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
                    LevelRenderer.renderLineBox(poseStack, lines, box, 1.0f, 1.0f, 0.0f, 0.8f);
                }
            }

            poseStack.popPose();
        });
    }
}
