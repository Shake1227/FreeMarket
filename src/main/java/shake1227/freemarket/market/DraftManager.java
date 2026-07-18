package shake1227.freemarket.market;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import shake1227.freemarket.server.PlayerDataPersistence;
import shake1227.freemarket.server.MarketItemDelivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DraftManager {
    private static final String PERSISTENT_KEY = "freemarket_draft";
    private static final String COMMIT_KEY = "CommitListingId";
    private static final String RETURN_KEY = "ReturnOperationId";
    public enum SelectionResult {
        SELECTED,
        INVALID_SLOT,
        EMPTY_SLOT
    }

    private final Map<UUID, DraftSelection> selections = new HashMap<>();

    public synchronized SelectionResult selectFromSlot(ServerPlayer player, int inventorySlot) {
        Objects.requireNonNull(player, "player");
        Inventory inventory = player.getInventory();
        if (inventorySlot < 0 || inventorySlot >= inventory.getContainerSize()) {
            return SelectionResult.INVALID_SLOT;
        }
        ItemStack inSlot = inventory.getItem(inventorySlot);
        if (inSlot.isEmpty()) {
            return SelectionResult.EMPTY_SLOT;
        }
        if (selections.containsKey(player.getUUID()) || player.getPersistentData().contains(PERSISTENT_KEY, Tag.TAG_COMPOUND)) {
            cancel(player);
            if (player.getPersistentData().contains(PERSISTENT_KEY, Tag.TAG_COMPOUND)) {
                return SelectionResult.INVALID_SLOT;
            }
            inSlot = inventory.getItem(inventorySlot);
            if (inSlot.isEmpty()) {
                return SelectionResult.EMPTY_SLOT;
            }
        }
        ItemStack selected = inSlot.copy();
        inventory.setItem(inventorySlot, ItemStack.EMPTY);
        selections.put(player.getUUID(), new DraftSelection(selected, inventorySlot, System.currentTimeMillis()));
        writePersistent(player, selections.get(player.getUUID()));
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        PlayerDataPersistence.saveOrThrow(player);
        return SelectionResult.SELECTED;
    }

    public synchronized Optional<ItemStack> getSelected(UUID playerId) {
        DraftSelection selection = selections.get(playerId);
        return selection == null ? Optional.empty() : Optional.of(selection.item().copy());
    }

    public synchronized Optional<ItemStack> getSelected(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return getSelected(player.getUUID());
    }

    public synchronized Optional<Integer> getSourceSlot(UUID playerId) {
        DraftSelection selection = selections.get(playerId);
        return selection == null ? Optional.empty() : Optional.of(selection.sourceSlot());
    }

    public synchronized long getSelectedAt(UUID playerId) {
        DraftSelection selection = selections.get(playerId);
        return selection == null ? 0L : selection.selectedAt();
    }

    public synchronized boolean hasDraft(UUID playerId) {
        return selections.containsKey(playerId);
    }

    public synchronized ItemStack take(UUID playerId) {
        DraftSelection selection = selections.remove(Objects.requireNonNull(playerId, "playerId"));
        return selection == null ? ItemStack.EMPTY : selection.item().copy();
    }

    public synchronized ItemStack take(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        ItemStack item = take(player.getUUID());
        player.getPersistentData().remove(PERSISTENT_KEY);
        return item;
    }

    public synchronized ItemStack prepareCommit(ServerPlayer player, UUID listingId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listingId, "listingId");
        DraftSelection selection = selections.get(player.getUUID());
        if (selection == null) {
            selection = readPersistent(player).orElse(null);
            if (selection == null) {
                return ItemStack.EMPTY;
            }
            selections.put(player.getUUID(), selection);
        }
        writePersistent(player, selection, listingId);
        PlayerDataPersistence.saveOrThrow(player);
        return selection.item().copy();
    }

    public synchronized boolean finishCommit(ServerPlayer player, UUID listingId) {
        Objects.requireNonNull(player, "player");
        CompoundTag stored = player.getPersistentData().getCompound(PERSISTENT_KEY);
        if (!stored.hasUUID(COMMIT_KEY) || !stored.getUUID(COMMIT_KEY).equals(listingId)) {
            return false;
        }
        selections.remove(player.getUUID());
        player.getPersistentData().remove(PERSISTENT_KEY);
        PlayerDataPersistence.saveOrThrow(player);
        return true;
    }

    public synchronized void abortCommit(ServerPlayer player, UUID listingId) {
        Objects.requireNonNull(player, "player");
        CompoundTag stored = player.getPersistentData().getCompound(PERSISTENT_KEY);
        if (stored.hasUUID(COMMIT_KEY) && stored.getUUID(COMMIT_KEY).equals(listingId)) {
            stored.remove(COMMIT_KEY);
            player.getPersistentData().put(PERSISTENT_KEY, stored);
            PlayerDataPersistence.saveOrThrow(player);
        }
    }

    public synchronized boolean cancel(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        CompoundTag persistent = player.getPersistentData().getCompound(PERSISTENT_KEY);
        if (persistent.hasUUID(COMMIT_KEY) && MarketSavedData.get(player.server).getListing(persistent.getUUID(COMMIT_KEY)).isPresent()) {
            selections.remove(player.getUUID());
            player.getPersistentData().remove(PERSISTENT_KEY);
            PlayerDataPersistence.saveOrThrow(player);
            return true;
        }
        DraftSelection selection = selections.remove(player.getUUID());
        if (selection == null) {
            selection = readPersistent(player).orElse(null);
            if (selection == null) {
                return false;
            }
        }
        returnSafely(player, selection);
        return true;
    }

    public synchronized boolean recover(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (selections.containsKey(player.getUUID())) {
            return false;
        }
        Optional<DraftSelection> stored = readPersistent(player);
        if (stored.isEmpty()) {
            return false;
        }
        CompoundTag persistent = player.getPersistentData().getCompound(PERSISTENT_KEY);
        if (persistent.hasUUID(COMMIT_KEY) && MarketSavedData.get(player.server).getListing(persistent.getUUID(COMMIT_KEY)).isPresent()) {
            player.getPersistentData().remove(PERSISTENT_KEY);
            PlayerDataPersistence.saveOrThrow(player);
            return true;
        }
        if (persistent.hasUUID(RETURN_KEY)) {
            UUID operationId = persistent.getUUID(RETURN_KEY);
            PendingDeliveries deliveries = PendingDeliveries.get(player.server);
            if (!deliveries.contains(player.getUUID(), operationId)) {
                if (!deliveries.enqueue(player.getUUID(), operationId, stored.get().item())) {
                    return false;
                }
                try {
                    PendingDeliveries.saveNow(player.server);
                } catch (RuntimeException exception) {
                    return false;
                }
            }
            player.getPersistentData().remove(PERSISTENT_KEY);
            PlayerDataPersistence.saveOrThrow(player);
            return true;
        }
        returnSafely(player, stored.get());
        PlayerDataPersistence.saveOrThrow(player);
        return true;
    }

    public synchronized void onLogout(ServerPlayer player) {
        cancel(player);
    }

    public synchronized void onClone(Player original, ServerPlayer replacement) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(replacement, "replacement");
        if (!original.getPersistentData().contains(PERSISTENT_KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag stored = original.getPersistentData().getCompound(PERSISTENT_KEY).copy();
        replacement.getPersistentData().put(PERSISTENT_KEY, stored);
        if (stored.hasUUID(COMMIT_KEY) && MarketSavedData.get(replacement.server).getListing(stored.getUUID(COMMIT_KEY)).isPresent()) {
            selections.remove(replacement.getUUID());
            replacement.getPersistentData().remove(PERSISTENT_KEY);
        }
    }

    public synchronized int returnAll(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        int returned = 0;
        List<UUID> ids = new ArrayList<>(selections.keySet());
        for (UUID playerId : ids) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && cancel(player)) {
                returned++;
            }
        }
        return returned;
    }

    public synchronized int size() {
        return selections.size();
    }

    private static void returnSafely(ServerPlayer player, DraftSelection selection) {
        Objects.requireNonNull(player, "player");
        ItemStack stack = selection.item();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        MarketItemDelivery.Outcome outcome = MarketItemDelivery.deliver(player, stack);
        if (outcome == MarketItemDelivery.Outcome.FAILED) {
            PendingDeliveries deliveries = PendingDeliveries.get(player.server);
            UUID operationId = UUID.nameUUIDFromBytes(("freemarket-draft-return:" + player.getUUID() + ":" + selection.selectedAt()).getBytes(StandardCharsets.UTF_8));
            writePersistent(player, selection, null, operationId);
            PlayerDataPersistence.saveOrThrow(player);
            if (deliveries.enqueue(player.getUUID(), operationId, stack)) {
                try {
                    PendingDeliveries.saveNow(player.server);
                    player.getPersistentData().remove(PERSISTENT_KEY);
                } catch (RuntimeException exception) {
                    return;
                }
                PlayerDataPersistence.saveOrThrow(player);
            }
        } else if (outcome == MarketItemDelivery.Outcome.DROPPED) {
            player.getPersistentData().remove(PERSISTENT_KEY);
            player.server.saveEverything(true, false, false);
        } else {
            player.getPersistentData().remove(PERSISTENT_KEY);
            PlayerDataPersistence.saveOrThrow(player);
        }
    }

    private record DraftSelection(ItemStack item, int sourceSlot, long selectedAt) {
        private DraftSelection {
            item = item.copy();
        }
    }

    private static void writePersistent(ServerPlayer player, DraftSelection selection) {
        writePersistent(player, selection, null);
    }

    private static void writePersistent(ServerPlayer player, DraftSelection selection, UUID commitListingId) {
        writePersistent(player, selection, commitListingId, null);
    }

    private static void writePersistent(ServerPlayer player, DraftSelection selection, UUID commitListingId, UUID returnOperationId) {
        CompoundTag tag = new CompoundTag();
        tag.put("Item", selection.item().save(new CompoundTag()));
        tag.putInt("Slot", selection.sourceSlot());
        tag.putLong("SelectedAt", selection.selectedAt());
        if (commitListingId != null) {
            tag.putUUID(COMMIT_KEY, commitListingId);
        }
        if (returnOperationId != null) {
            tag.putUUID(RETURN_KEY, returnOperationId);
        }
        player.getPersistentData().put(PERSISTENT_KEY, tag);
    }

    private static Optional<DraftSelection> readPersistent(ServerPlayer player) {
        if (!player.getPersistentData().contains(PERSISTENT_KEY, Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        CompoundTag tag = player.getPersistentData().getCompound(PERSISTENT_KEY);
        if (!tag.contains("Item", Tag.TAG_COMPOUND)) {
            return Optional.empty();
        }
        ItemStack item = ItemStack.of(tag.getCompound("Item"));
        return item.isEmpty() ? Optional.empty() : Optional.of(new DraftSelection(item, tag.getInt("Slot"), tag.getLong("SelectedAt")));
    }
}
