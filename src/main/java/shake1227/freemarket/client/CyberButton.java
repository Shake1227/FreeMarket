package shake1227.freemarket.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public final class CyberButton extends AbstractButton {
    private final Runnable action;
    private final boolean danger;
    private float hoverAmount;
    private long pressedAt;
    private long lastFrame;

    public CyberButton(int x, int y, int width, int height, Component label, Runnable action) {
        this(x, y, width, height, label, action, false);
    }

    public CyberButton(int x, int y, int width, int height, Component label, Runnable action, boolean danger) {
        super(x, y, width, height, label);
        this.action = action;
        this.danger = danger;
    }

    @Override
    public void onPress() {
        pressedAt = System.currentTimeMillis();
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        float delta = lastFrame == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float target = isHoveredOrFocused() && active ? 1.0F : 0.0F;
        hoverAmount = CyberUiFx.approach(hoverAmount, target, delta, 14.0F);
        float pressAmount = pressedAt == 0L ? 0.0F : 1.0F - Math.min(1.0F, (now - pressedAt) / 170.0F);
        int accent = danger ? 0xFFD74656 : 0xFF35F29A;
        int x = getX();
        int y = getY() + (pressAmount > 0.35F ? 1 : 0);
        int top = active ? CyberUiFx.mix(0xE014221D, danger ? 0xE0432027 : 0xE026493A, hoverAmount) : 0xB0101412;
        int bottom = active ? CyberUiFx.mix(0xE008120E, danger ? 0xE0221116 : 0xE00E281D, hoverAmount) : 0xB0080C0A;
        graphics.fill(x + 2, y + height, x + width + 2, y + height + 2, 0x56000000);
        graphics.fillGradient(x, y, x + width, y + height, top, bottom);
        CyberUiFx.border(graphics, x, y, width, height, CyberUiFx.mix(accent & 0x88FFFFFF, accent, hoverAmount));
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, CyberUiFx.alpha(0xFFFFFF, active ? 0.08F + hoverAmount * 0.18F : 0.03F));
        int rail = Math.max(2, (int)((height - 4) * (0.35F + hoverAmount * 0.65F)));
        graphics.fill(x + 2, y + 2, x + 4, y + 2 + rail, accent);
        if (hoverAmount > 0.02F && active) {
            int sweep = x + Math.floorMod((int)(now / 7L), Math.max(1, width + 22)) - 11;
            CyberUiFx.scissor(graphics, x + 1, y + 1, x + width - 1, y + height - 1);
            graphics.fill(sweep, y + 2, sweep + 7, y + height - 2, CyberUiFx.alpha(0xB9FFE0, 0.05F + hoverAmount * 0.13F));
            graphics.disableScissor();
            CyberUiFx.corners(graphics, x - 1, y - 1, width + 2, height + 2, 6, CyberUiFx.alpha(accent, hoverAmount * 0.8F));
        }
        int color = active ? 0xFFE8FFF4 : 0xFF65726B;
        var font = Minecraft.getInstance().font;
        int labelWidth = Math.max(1, font.width(getMessage()));
        float scale = Math.min(1.15F, Math.max(0.8F, (width - 10) / (float)labelWidth));
        graphics.pose().pushPose();
        graphics.pose().translate(x + width / 2.0F, y + (height - 9.0F * scale) / 2.0F + 1.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, getMessage(), 0, 0, color);
        graphics.pose().popPose();
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager manager) {
        CyberUiFx.play("minecraft:ui.button.click", danger ? 0.72F : 1.24F);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
