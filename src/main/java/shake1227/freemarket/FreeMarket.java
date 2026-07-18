package shake1227.freemarket;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import shake1227.freemarket.network.NetworkHandler;
import shake1227.freemarket.registry.ModContent;
import shake1227.freemarket.server.ServerEvents;

@Mod(FreeMarket.MODID)
public final class FreeMarket {
    public static final String MODID = "freemarket";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FreeMarket() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModContent.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::addCreativeItems);
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::register);
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModContent.MARKET_TERMINAL_ITEM.get());
        }
    }
}

