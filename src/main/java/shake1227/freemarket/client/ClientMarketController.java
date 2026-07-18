package shake1227.freemarket.client;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public final class ClientMarketController {
    private ClientMarketController() {
    }

    public static void handle(String action, CompoundTag data) {
        Minecraft minecraft = Minecraft.getInstance();
        CompoundTag payload = data == null ? new CompoundTag() : data.copy();
        Runnable task = () -> apply(minecraft, action == null ? "" : action.toUpperCase(Locale.ROOT), payload);
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
    }

    private static void apply(Minecraft minecraft, String action, CompoundTag data) {
        if (("OPEN".equals(action) || "OPEN_ADMIN".equals(action)) && !data.contains("Draft", 10)) {
            data.putBoolean("ClearDraft", true);
        }
        MarketClientState.INSTANCE.apply(data);
        if ("OPEN".equals(action) || "OPEN_ADMIN".equals(action)) {
            boolean openAdmin = "OPEN_ADMIN".equals(action) || data.getBoolean("OpenAdmin");
            minecraft.setScreen(new MarketScreen(MarketClientState.INSTANCE, true, openAdmin));
            return;
        }
        if ("CLOSE".equals(action)) {
            if (minecraft.screen instanceof MarketScreen) {
                minecraft.setScreen(null);
            }
            return;
        }
        if (minecraft.screen instanceof MarketScreen screen) {
            screen.serverStateChanged(action, data);
        } else if ("SYNC".equals(action) && data.getBoolean("Open")) {
            minecraft.setScreen(new MarketScreen(MarketClientState.INSTANCE, false));
        }
    }
}
