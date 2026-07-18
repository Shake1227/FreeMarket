package shake1227.freemarket.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import shake1227.freemarket.command.FreeMarketCommand;
import shake1227.freemarket.integration.VaultEconomyBridge;

public final class ServerEvents {
    private static int ticks;

    private ServerEvents() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        FreeMarketCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        VaultEconomyBridge.initialize(event.getServer());
        MarketServerController.serverStarted(event.getServer());
        FreeMarketCommand.serverStarted(event.getServer());
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        MarketServerController.serverStopping(event.getServer());
        FreeMarketCommand.serverStopping();
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MarketServerController.playerLoggedOut(player);
        }
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MarketServerController.playerLoggedIn(player);
            FreeMarketCommand.playerLoggedIn(player);
        }
    }

    @SubscribeEvent
    public static void playerCloned(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer replacement) {
            MarketServerController.playerCloned(event.getOriginal(), replacement);
        }
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ++ticks >= 20) {
            ticks = 0;
            MarketServerController.tick(event.getServer());
        }
    }
}
