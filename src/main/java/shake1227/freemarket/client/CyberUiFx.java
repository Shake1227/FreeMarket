package shake1227.freemarket.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;

final class CyberUiFx {
    private static double uiScale = 1.0D;

    private CyberUiFx() {
    }

    static void setUiScale(double scale) {
        uiScale = scale <= 0.0D ? 1.0D : scale;
    }

    static double uiScale() {
        return uiScale;
    }

    static void scissor(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.enableScissor((int)Math.floor(x1 * uiScale), (int)Math.floor(y1 * uiScale), (int)Math.ceil(x2 * uiScale), (int)Math.ceil(y2 * uiScale));
    }

    static void play(String soundId, float pitch) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation id = ResourceLocation.tryParse(soundId);
        if (id == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(id);
        if (sound != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
        }
    }

    static float approach(float current, float target, float deltaSeconds, float response) {
        float weight = 1.0F - (float)Math.exp(-Math.max(0.0F, deltaSeconds) * response);
        return Mth.lerp(weight, current, target);
    }

    static float smoothstep(float value) {
        float t = Mth.clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    static float easeOutBack(float value) {
        float t = Mth.clamp(value, 0.0F, 1.0F) - 1.0F;
        return 1.0F + 2.70158F * t * t * t + 1.70158F * t * t;
    }

    static int alpha(int rgb, float alpha) {
        return ((int)(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | rgb & 0xFFFFFF;
    }

    static int mix(int first, int second, float amount) {
        float t = Mth.clamp(amount, 0.0F, 1.0F);
        int a = (int)Mth.lerp(t, first >>> 24, second >>> 24);
        int r = (int)Mth.lerp(t, first >> 16 & 0xFF, second >> 16 & 0xFF);
        int g = (int)Mth.lerp(t, first >> 8 & 0xFF, second >> 8 & 0xFF);
        int b = (int)Mth.lerp(t, first & 0xFF, second & 0xFF);
        return a << 24 | r << 16 | g << 8 | b;
    }

    static void border(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    static void corners(GuiGraphics graphics, int x, int y, int width, int height, int length, int color) {
        int edge = Math.max(2, Math.min(length, Math.min(width, height) / 2));
        graphics.fill(x, y, x + edge, y + 2, color);
        graphics.fill(x, y, x + 2, y + edge, color);
        graphics.fill(x + width - edge, y, x + width, y + 2, color);
        graphics.fill(x + width - 2, y, x + width, y + edge, color);
        graphics.fill(x, y + height - 2, x + edge, y + height, color);
        graphics.fill(x, y + height - edge, x + 2, y + height, color);
        graphics.fill(x + width - edge, y + height - 2, x + width, y + height, color);
        graphics.fill(x + width - 2, y + height - edge, x + width, y + height, color);
    }

    static void scanlines(GuiGraphics graphics, int x, int y, int width, int height, long time, float strength) {
        if (width <= 0 || height <= 0 || strength <= 0.0F) {
            return;
        }
        int lineColor = alpha(0x001A10, 0.08F * strength);
        int offset = (int)(time / 42L % 4L);
        for (int lineY = y + offset; lineY < y + height; lineY += 4) {
            graphics.fill(x, lineY, x + width, lineY + 1, lineColor);
        }
        float cycle = time % 2800L / 2800.0F;
        int sweepY = y + (int)(height * cycle);
        int sweepHeight = Math.max(5, Math.min(18, height / 18));
        graphics.fillGradient(x, sweepY - sweepHeight, x + width, sweepY, alpha(0x35F29A, 0.0F), alpha(0x35F29A, 0.055F * strength));
        graphics.fillGradient(x, sweepY, x + width, sweepY + sweepHeight, alpha(0x35F29A, 0.055F * strength), alpha(0x35F29A, 0.0F));
    }

    static void vignette(GuiGraphics graphics, int x, int y, int width, int height, float strength) {
        if (width <= 0 || height <= 0 || strength <= 0.0F) {
            return;
        }
        int vertical = Math.max(10, Math.min(70, height / 7));
        int horizontal = Math.max(10, Math.min(70, width / 10));
        int edge = alpha(0x000000, 0.48F * strength);
        int clear = alpha(0x000000, 0.0F);
        graphics.fillGradient(x, y, x + width, y + vertical, edge, clear);
        graphics.fillGradient(x, y + height - vertical, x + width, y + height, clear, edge);
        for (int i = 0; i < horizontal; i += 4) {
            float amount = 1.0F - i / (float)horizontal;
            int color = alpha(0x000000, 0.11F * amount * amount * strength);
            graphics.fill(x + i, y, x + Math.min(horizontal, i + 4), y + height, color);
            graphics.fill(x + width - Math.min(horizontal, i + 4), y, x + width - i, y + height, color);
        }
    }

    static void glitch(GuiGraphics graphics, int x, int y, int width, int height, long time, float strength) {
        long frame = time / 58L;
        int phase = (int)Math.floorMod(frame, 53L);
        if (phase > 2 || width < 24 || height < 24 || strength <= 0.0F) {
            return;
        }
        long seed = frame * 6364136223846793005L + 1442695040888963407L;
        int count = phase == 0 ? 6 : 3;
        for (int i = 0; i < count; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            int stripY = y + 3 + Math.floorMod((int)(seed >>> 18), Math.max(1, height - 7));
            int stripW = Math.max(18, width / (5 + Math.floorMod((int)seed, 6)));
            int stripX = x + Math.floorMod((int)(seed >>> 33), Math.max(1, width - stripW));
            int shift = 2 + Math.floorMod((int)(seed >>> 47), 8);
            graphics.fill(stripX + shift, stripY, Math.min(x + width, stripX + stripW + shift), stripY + 1, alpha(0x8BFFC6, 0.24F * strength));
            graphics.fill(stripX, stripY + 1, Math.min(x + width, stripX + stripW), stripY + 2, alpha(0x02130B, 0.62F * strength));
        }
    }
}
