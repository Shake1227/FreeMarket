package shake1227.freemarket.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public final class MarketItemDelivery {
    public enum Outcome {
        INVENTORY,
        DROPPED,
        FAILED
    }

    private MarketItemDelivery() {
    }

    public static Outcome deliver(ServerPlayer player, ItemStack source) {
        if (source == null || source.isEmpty()) {
            return Outcome.FAILED;
        }
        if (fitsInventory(player, source)) {
            insertFully(player, source);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return Outcome.INVENTORY;
        }
        ItemEntity dropped = player.drop(source.copy(), false);
        return dropped == null || dropped.isRemoved() || !dropped.isAddedToWorld() ? Outcome.FAILED : Outcome.DROPPED;
    }

    private static boolean fitsInventory(ServerPlayer player, ItemStack source) {
        long capacity = 0L;
        int maximum = source.getMaxStackSize();
        for (ItemStack existing : player.getInventory().items) {
            if (existing.isEmpty()) {
                capacity += maximum;
            } else if (ItemStack.isSameItemSameTags(existing, source)) {
                capacity += Math.max(0, Math.min(maximum, existing.getMaxStackSize()) - existing.getCount());
            }
            if (capacity >= source.getCount()) {
                return true;
            }
        }
        return false;
    }

    private static void insertFully(ServerPlayer player, ItemStack source) {
        ItemStack remaining = source.copy();
        int maximum = source.getMaxStackSize();
        for (ItemStack existing : player.getInventory().items) {
            if (remaining.isEmpty()) {
                return;
            }
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining)) {
                int transfer = Math.min(remaining.getCount(), Math.max(0, Math.min(maximum, existing.getMaxStackSize()) - existing.getCount()));
                existing.grow(transfer);
                remaining.shrink(transfer);
            }
        }
        for (int slot = 0; slot < player.getInventory().items.size() && !remaining.isEmpty(); slot++) {
            if (player.getInventory().items.get(slot).isEmpty()) {
                int transfer = Math.min(remaining.getCount(), maximum);
                ItemStack placed = remaining.copy();
                placed.setCount(transfer);
                player.getInventory().items.set(slot, placed);
                remaining.shrink(transfer);
            }
        }
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("inventory capacity changed during delivery");
        }
    }
}
