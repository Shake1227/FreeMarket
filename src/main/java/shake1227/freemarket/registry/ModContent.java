package shake1227.freemarket.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import shake1227.freemarket.FreeMarket;
import shake1227.freemarket.block.MarketTerminalBlock;

public final class ModContent {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, FreeMarket.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FreeMarket.MODID);
    public static final RegistryObject<Block> MARKET_TERMINAL = BLOCKS.register("market_terminal", () -> new MarketTerminalBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.5F, 8.0F).sound(SoundType.METAL).lightLevel(state -> 5).requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<Item> MARKET_TERMINAL_ITEM = ITEMS.register("market_terminal", () -> new BlockItem(MARKET_TERMINAL.get(), new Item.Properties()));

    private ModContent() {
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
