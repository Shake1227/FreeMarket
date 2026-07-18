package shake1227.freemarket.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

final class CyberTextArea extends MultiLineEditBox {
    private float focusAmount;
    private long lastFrame;

    CyberTextArea(Font font, int x, int y, int width, int height, Component message) {
        super(font, x, y, width, height, message, message);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        long now = System.currentTimeMillis();
        float delta = lastFrame == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float target = isFocused() ? 1.0F : isHovered() ? 0.45F : 0.0F;
        focusAmount = CyberUiFx.approach(focusAmount, target, delta, 13.0F);
        int x = getX();
        int y = getY();
        int accent = CyberUiFx.mix(0xFF29463A, 0xFF35F29A, focusAmount);
        graphics.fillGradient(x, y, x + width, y + height, 0xE0101E18, 0xE008130E);
        CyberUiFx.border(graphics, x, y, width, height, accent);
        int lineWidth = Math.max(1, (int)((width - 4) * CyberUiFx.smoothstep(focusAmount)));
        graphics.fill(x + 2, y + height - 2, x + 2 + lineWidth, y + height - 1, CyberUiFx.alpha(0x35F29A, 0.55F + focusAmount * 0.45F));
        if (focusAmount > 0.03F) {
            CyberUiFx.corners(graphics, x - 1, y - 1, width + 2, height + 2, 5, CyberUiFx.alpha(0x8BFFC6, focusAmount * 0.75F));
        }
        CyberUiFx.scissor(graphics, x + 1, y + 1, x + width - 1, y + height - 1);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, -scrollAmount(), 0.0D);
        renderContents(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose();
        graphics.disableScissor();
        renderDecorations(graphics);
    }
}
