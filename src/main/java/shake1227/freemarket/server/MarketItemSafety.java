package shake1227.freemarket.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

public final class MarketItemSafety {
    public static final int MAX_ESCROW_BYTES = 256 * 1024;
    private static final int MAX_SUMMARY_BYTES = 8 * 1024;
    private static final List<String> SUMMARY_KEYS = List.of("Damage", "CustomModelData", "Enchantments", "StoredEnchantments", "Potion", "CustomPotionColor", "display");

    private MarketItemSafety() {
    }

    public static boolean canEscrow(ItemStack stack) {
        return !stack.isEmpty() && serializedBytes(stack) <= MAX_ESCROW_BYTES;
    }

    public static ItemStack forClient(ItemStack stack, boolean details) {
        ItemStack full = stack.copy();
        int limit = details ? MAX_ESCROW_BYTES : MAX_SUMMARY_BYTES;
        if (serializedBytes(full) <= limit) {
            return full;
        }
        ItemStack summary = new ItemStack(stack.getItem(), stack.getCount());
        CompoundTag source = stack.getTag();
        if (source == null) {
            return summary;
        }
        for (String key : SUMMARY_KEYS) {
            Tag value = source.get(key);
            if (value == null) {
                continue;
            }
            summary.getOrCreateTag().put(key, value.copy());
            if (serializedBytes(summary) > MAX_SUMMARY_BYTES) {
                summary.getOrCreateTag().remove(key);
            }
        }
        return summary;
    }

    public static int serializedBytes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(stack.save(new CompoundTag()), output);
            return bytes.size();
        } catch (IOException | RuntimeException exception) {
            return Integer.MAX_VALUE;
        }
    }
}

