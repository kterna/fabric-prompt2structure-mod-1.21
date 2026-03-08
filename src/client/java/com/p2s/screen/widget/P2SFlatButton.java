package com.p2s.screen.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class P2SFlatButton extends Button {
    public enum Variant {
        NORMAL,
        PRIMARY,
        DANGER,
        MUTED
    }

    private final Variant variant;

    public P2SFlatButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        this(x, y, width, height, message, onPress, Variant.NORMAL);
    }

    public P2SFlatButton(int x, int y, int width, int height, Component message, OnPress onPress, Variant variant) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.variant = variant == null ? Variant.NORMAL : variant;
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        int left = getX();
        int top = getY();
        int right = left + width;
        int bottom = top + height;
        boolean hovered = isHoveredOrFocused();

        int background = backgroundColor(hovered);
        int border = borderColor(hovered);
        int textColor = active ? 0xEAF2FF : 0x7B8CA6;
        if (!active) {
            hovered = false;
        }

        gfx.fill(left, top, right, bottom, background);
        gfx.fill(left, top, right, top + 1, border);
        gfx.fill(left, bottom - 1, right, bottom, border);
        gfx.fill(left, top, left + 1, bottom, border);
        gfx.fill(right - 1, top, right, bottom, border);

        if (hovered) {
            gfx.fill(left + 1, top + 1, right - 1, top + 2, 0x33FFFFFF);
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.font == null) {
            return;
        }
        int textY = top + (height - 8) / 2;
        gfx.drawCenteredString(minecraft.font, getMessage(), left + width / 2, textY, textColor);
    }

    private int backgroundColor(boolean hovered) {
        if (!active) {
            return switch (variant) {
                case PRIMARY -> 0x55233B59;
                case DANGER -> 0x553C2022;
                case MUTED -> 0x55101620;
                case NORMAL -> 0x55161F2C;
            };
        }
        return switch (variant) {
            case PRIMARY -> hovered ? 0xCC35507A : 0xB02B405F;
            case DANGER -> hovered ? 0xCC6D3235 : 0xB055282A;
            case MUTED -> hovered ? 0xCC263446 : 0xAA1C2736;
            case NORMAL -> hovered ? 0xCC2E425D : 0xAA243447;
        };
    }

    private int borderColor(boolean hovered) {
        if (!active) {
            return 0x553B4A60;
        }
        return switch (variant) {
            case PRIMARY -> hovered ? 0xFF88A6D9 : 0xFF6F8FBE;
            case DANGER -> hovered ? 0xFFDB8D90 : 0xFFC16B6D;
            case MUTED -> hovered ? 0xFF88A2C4 : 0xFF6C86A4;
            case NORMAL -> hovered ? 0xFF86A2CC : 0xFF6E89AE;
        };
    }
}
