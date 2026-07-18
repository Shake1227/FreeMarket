package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import shake1227.freemarket.FreeMarket;
import shake1227.freemarket.server.PlayerDataPersistence;
import shake1227.freemarket.server.MarketItemDelivery;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PendingDeliveries extends SavedData {
    private static final String DATA_NAME = "freemarket_deliveries";
    private static final int MAX_PLAYERS = 100_000;
    private static final int MAX_PER_PLAYER = 512;
    private static final String RECEIPTS_KEY = "freemarket_delivery_receipts";
    private final LinkedHashMap<UUID, LinkedHashMap<UUID, ItemStack>> deliveries = new LinkedHashMap<>();
    private final ListTag quarantinedEntries = new ListTag();
    private boolean recoveryUnsafe;

    public static PendingDeliveries get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PendingDeliveries::load, PendingDeliveries::new, DATA_NAME);
    }

    public static void saveNow(MinecraftServer server) {
        PendingDeliveries data = get(server);
        data.saveAtomic(server.getWorldPath(new LevelResource("data")).resolve(DATA_NAME + ".dat"));
    }

    public synchronized boolean enqueue(UUID playerId, UUID operationId, ItemStack item) {
        if (playerId == null || operationId == null || item == null || item.isEmpty()) {
            return false;
        }
        LinkedHashMap<UUID, ItemStack> playerDeliveries = deliveries.get(playerId);
        if (playerDeliveries == null) {
            if (deliveries.size() >= MAX_PLAYERS) {
                return false;
            }
            playerDeliveries = new LinkedHashMap<>();
            deliveries.put(playerId, playerDeliveries);
        }
        if (playerDeliveries.containsKey(operationId)) {
            return true;
        }
        if (playerDeliveries.size() >= MAX_PER_PLAYER) {
            return false;
        }
        playerDeliveries.put(operationId, item.copy());
        setDirty();
        return true;
    }

    public synchronized boolean canEnqueue(UUID playerId, UUID operationId) {
        if (playerId == null || operationId == null) {
            return false;
        }
        LinkedHashMap<UUID, ItemStack> pending = deliveries.get(playerId);
        if (pending != null) {
            return pending.containsKey(operationId) || pending.size() < MAX_PER_PLAYER;
        }
        return deliveries.size() < MAX_PLAYERS;
    }

    public synchronized boolean contains(UUID playerId, UUID operationId) {
        Map<UUID, ItemStack> pending = deliveries.get(playerId);
        return pending != null && pending.containsKey(operationId);
    }

    public synchronized int deliver(ServerPlayer player) {
        LinkedHashMap<UUID, ItemStack> pending = deliveries.get(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        Set<UUID> receipts = readReceipts(player);
        receipts.retainAll(pending.keySet());
        LinkedHashSet<UUID> completed = new LinkedHashSet<>();
        boolean dropped = false;
        for (Map.Entry<UUID, ItemStack> entry : pending.entrySet()) {
            if (receipts.contains(entry.getKey())) {
                completed.add(entry.getKey());
                continue;
            }
            MarketItemDelivery.Outcome outcome = MarketItemDelivery.deliver(player, entry.getValue());
            if (outcome == MarketItemDelivery.Outcome.FAILED) {
                continue;
            }
            receipts.add(entry.getKey());
            completed.add(entry.getKey());
            dropped |= outcome == MarketItemDelivery.Outcome.DROPPED;
        }
        writeReceipts(player, receipts);
        if (dropped) {
            player.server.saveEverything(true, false, false);
        }
        if (!completed.isEmpty()) {
            try {
                PlayerDataPersistence.saveOrThrow(player);
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.error("Unable to persist FreeMarket delivery receipts for {}", player.getGameProfile().getName(), exception);
                return 0;
            }
        }
        completed.forEach(pending::remove);
        if (pending.isEmpty()) {
            deliveries.remove(player.getUUID());
        }
        if (!completed.isEmpty()) {
            setDirty();
        }
        int count = completed.size();
        return count;
    }

    public synchronized boolean remove(UUID playerId, UUID operationId) {
        LinkedHashMap<UUID, ItemStack> pending = deliveries.get(playerId);
        if (pending == null || pending.remove(operationId) == null) {
            return false;
        }
        if (pending.isEmpty()) {
            deliveries.remove(playerId);
        }
        setDirty();
        return true;
    }

    public synchronized int pendingCount(UUID playerId) {
        Map<UUID, ItemStack> values = deliveries.get(playerId);
        return values == null ? 0 : values.size();
    }

    public synchronized Set<UUID> pendingOperationIds() {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        deliveries.values().forEach(values -> result.addAll(values.keySet()));
        return Set.copyOf(result);
    }

    public synchronized boolean isRecoveryUnsafe() {
        return recoveryUnsafe;
    }

    public static void copyReceipts(Player original, ServerPlayer replacement) {
        if (original.getPersistentData().contains(RECEIPTS_KEY, Tag.TAG_LIST)) {
            replacement.getPersistentData().put(RECEIPTS_KEY, original.getPersistentData().getList(RECEIPTS_KEY, Tag.TAG_COMPOUND).copy());
        }
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        tag.putBoolean("RecoveryUnsafe", recoveryUnsafe);
        tag.put("QuarantinedEntries", quarantinedEntries.copy());
        ListTag players = new ListTag();
        for (Map.Entry<UUID, LinkedHashMap<UUID, ItemStack>> playerEntry : deliveries.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("PlayerId", playerEntry.getKey());
            ListTag items = new ListTag();
            for (Map.Entry<UUID, ItemStack> itemEntry : playerEntry.getValue().entrySet()) {
                if (!itemEntry.getValue().isEmpty()) {
                    CompoundTag itemTag = new CompoundTag();
                    itemTag.putUUID("OperationId", itemEntry.getKey());
                    itemTag.put("Item", itemEntry.getValue().save(new CompoundTag()));
                    items.add(itemTag);
                }
            }
            playerTag.put("Items", items);
            players.add(playerTag);
        }
        tag.put("Players", players);
        return tag;
    }

    private static PendingDeliveries load(CompoundTag tag) {
        PendingDeliveries data = new PendingDeliveries();
        data.recoveryUnsafe = tag.getBoolean("RecoveryUnsafe");
        Tag quarantineTag = tag.get("QuarantinedEntries");
        if (quarantineTag != null && (!(quarantineTag instanceof ListTag quarantineList) || (!quarantineList.isEmpty() && quarantineList.getElementType() != Tag.TAG_COMPOUND))) {
            CompoundTag malformed = new CompoundTag();
            malformed.put("OriginalQuarantine", quarantineTag.copy());
            data.quarantine(malformed, -1, -1, new IllegalArgumentException("quarantine list type"));
        }
        if (tag.contains("QuarantinedEntries", Tag.TAG_LIST)) {
            ListTag quarantine = tag.getList("QuarantinedEntries", Tag.TAG_COMPOUND);
            for (int index = 0; index < quarantine.size(); index++) {
                data.quarantinedEntries.add(quarantine.getCompound(index).copy());
            }
            data.recoveryUnsafe |= !data.quarantinedEntries.isEmpty();
        }
        Tag playersTag = tag.get("Players");
        if (playersTag != null && (!(playersTag instanceof ListTag playersList) || (!playersList.isEmpty() && playersList.getElementType() != Tag.TAG_COMPOUND))) {
            CompoundTag malformed = new CompoundTag();
            malformed.put("Players", playersTag.copy());
            data.quarantine(malformed, -1, -1, new IllegalArgumentException("players list"));
        }
        ListTag players = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int playerIndex = 0; playerIndex < players.size(); playerIndex++) {
            CompoundTag playerTag = players.getCompound(playerIndex);
            if (playerIndex >= MAX_PLAYERS || !playerTag.hasUUID("PlayerId")) {
                data.quarantine(playerTag, playerIndex, -1, new IllegalArgumentException(playerIndex >= MAX_PLAYERS ? "player capacity exceeded" : "player id"));
                continue;
            }
            Tag itemsTag = playerTag.get("Items");
            if (!(itemsTag instanceof ListTag itemsList) || (!itemsList.isEmpty() && itemsList.getElementType() != Tag.TAG_COMPOUND)) {
                data.quarantine(playerTag, playerIndex, -1, new IllegalArgumentException("delivery items"));
                continue;
            }
            UUID playerId = playerTag.getUUID("PlayerId");
            if (data.deliveries.containsKey(playerId)) {
                data.quarantine(playerTag, playerIndex, -1, new IllegalArgumentException("duplicate delivery player"));
                continue;
            }
            LinkedHashMap<UUID, ItemStack> items = new LinkedHashMap<>();
            ListTag storedItems = playerTag.getList("Items", Tag.TAG_COMPOUND);
            for (int itemIndex = 0; itemIndex < storedItems.size(); itemIndex++) {
                CompoundTag itemTag = storedItems.getCompound(itemIndex);
                if (itemIndex >= MAX_PER_PLAYER || !itemTag.hasUUID("OperationId") || !itemTag.contains("Item", Tag.TAG_COMPOUND)) {
                    data.quarantine(itemTag, playerIndex, itemIndex, new IllegalArgumentException(itemIndex >= MAX_PER_PLAYER ? "delivery capacity exceeded" : "delivery record"));
                    continue;
                }
                try {
                    ItemStack item = ItemStack.of(itemTag.getCompound("Item"));
                    if (item.isEmpty()) {
                        throw new IllegalArgumentException("empty delivery item");
                    }
                    if (items.putIfAbsent(itemTag.getUUID("OperationId"), item) != null) {
                        throw new IllegalArgumentException("duplicate delivery operation");
                    }
                } catch (RuntimeException exception) {
                    data.quarantine(itemTag, playerIndex, itemIndex, exception);
                }
            }
            if (!items.isEmpty()) {
                data.deliveries.put(playerId, items);
            }
        }
        if (data.recoveryUnsafe) {
            data.setDirty();
            FreeMarket.LOGGER.error("FreeMarket pending-delivery recovery is unsafe. Quarantined records: {}", data.quarantinedEntries.size());
        }
        return data;
    }

    private void quarantine(CompoundTag stored, int playerIndex, int itemIndex, RuntimeException exception) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("PlayerIndex", playerIndex);
        entry.putInt("ItemIndex", itemIndex);
        entry.putLong("QuarantinedAt", System.currentTimeMillis());
        entry.putString("Failure", MarketLimits.bounded(exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage()), MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH));
        entry.put("Record", stored.copy());
        quarantinedEntries.add(entry);
        recoveryUnsafe = true;
        FreeMarket.LOGGER.error("Quarantined FreeMarket pending-delivery record at player index {} item index {}", playerIndex, itemIndex, exception);
    }

    private synchronized void saveAtomic(Path target) {
        if (!isDirty()) {
            return;
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            CompoundTag root = new CompoundTag();
            root.put("data", save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(root);
            NbtIo.writeCompressed(root, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setDirty(false);
        } catch (IOException exception) {
            recoveryUnsafe = true;
            setDirty();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            FreeMarket.LOGGER.error("Unable to persist FreeMarket pending deliveries", exception);
            throw new IllegalStateException("pending delivery save failed", exception);
        } catch (RuntimeException exception) {
            recoveryUnsafe = true;
            setDirty();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            FreeMarket.LOGGER.error("Unable to serialize FreeMarket pending deliveries", exception);
            throw exception;
        }
    }

    private static Set<UUID> readReceipts(ServerPlayer player) {
        LinkedHashSet<UUID> receipts = new LinkedHashSet<>();
        ListTag list = player.getPersistentData().getList(RECEIPTS_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < Math.min(list.size(), MAX_PER_PLAYER); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.hasUUID("Id")) {
                receipts.add(entry.getUUID("Id"));
            }
        }
        return receipts;
    }

    private static void writeReceipts(ServerPlayer player, Set<UUID> receipts) {
        ListTag list = new ListTag();
        receipts.stream().limit(MAX_PER_PLAYER).forEach(id -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            list.add(entry);
        });
        player.getPersistentData().put(RECEIPTS_KEY, list);
    }
}
