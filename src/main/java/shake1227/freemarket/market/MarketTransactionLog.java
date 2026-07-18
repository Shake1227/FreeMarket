package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import shake1227.freemarket.FreeMarket;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MarketTransactionLog extends SavedData {
    public enum State {
        PREPARED,
        BUYER_DEBITED,
        SELLER_CREDITED,
        MARKET_COMMITTED,
        DELIVERY_QUEUED,
        COMPLETED,
        ROLLED_BACK,
        MANUAL_REVIEW
    }

    public record Entry(UUID id, UUID listingId, UUID buyerId, String buyerName, UUID sellerId, String sellerName, double gross, double net, State state, long createdAt, long updatedAt, String detail) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(listingId, "listingId");
            Objects.requireNonNull(buyerId, "buyerId");
            Objects.requireNonNull(sellerId, "sellerId");
            Objects.requireNonNull(state, "state");
            buyerName = MarketLimits.bounded(buyerName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
            sellerName = MarketLimits.bounded(sellerName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
            gross = MarketLimits.requirePrice(gross);
            net = MarketLimits.requirePrice(net);
            createdAt = Math.max(0L, createdAt);
            updatedAt = Math.max(createdAt, updatedAt);
            detail = MarketLimits.bounded(detail, MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH);
        }

        public boolean terminal() {
            return state == State.COMPLETED || state == State.ROLLED_BACK;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", id);
            tag.putUUID("ListingId", listingId);
            tag.putUUID("BuyerId", buyerId);
            tag.putString("BuyerName", buyerName);
            tag.putUUID("SellerId", sellerId);
            tag.putString("SellerName", sellerName);
            tag.putDouble("Gross", gross);
            tag.putDouble("Net", net);
            tag.putString("State", state.name());
            tag.putLong("CreatedAt", createdAt);
            tag.putLong("UpdatedAt", updatedAt);
            tag.putString("Detail", detail);
            return tag;
        }

        public static Entry fromTag(CompoundTag tag) {
            if (!tag.hasUUID("Id") || !tag.hasUUID("ListingId") || !tag.hasUUID("BuyerId") || !tag.hasUUID("SellerId")) {
                throw new IllegalArgumentException("transaction");
            }
            State state;
            try {
                state = State.valueOf(tag.getString("State").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                state = State.MANUAL_REVIEW;
            }
            return new Entry(tag.getUUID("Id"), tag.getUUID("ListingId"), tag.getUUID("BuyerId"), tag.getString("BuyerName"), tag.getUUID("SellerId"), tag.getString("SellerName"), tag.getDouble("Gross"), tag.getDouble("Net"), state, tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"), tag.getString("Detail"));
        }
    }

    private static final String DATA_NAME = "freemarket_transactions";
    private static final int MAX_ENTRIES = 4_096;
    private static final int MAX_TERMINAL_HISTORY = 512;
    private static final long TERMINAL_RETENTION = 7L * 24L * 60L * 60L * 1000L;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    private final ArrayList<CompoundTag> quarantinedEntries = new ArrayList<>();

    public static MarketTransactionLog get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MarketTransactionLog::load, MarketTransactionLog::new, DATA_NAME);
    }

    public static void saveNow(MinecraftServer server) {
        MarketTransactionLog log = get(server);
        Path directory = server.getWorldPath(new LevelResource("data"));
        log.saveAtomic(directory.resolve(DATA_NAME + ".dat"));
    }

    public synchronized Entry begin(Listing listing, UUID buyerId, String buyerName, double gross, double net, long timestamp) {
        Objects.requireNonNull(listing, "listing");
        prune(timestamp);
        Entry existing = entries.get(listing.getId());
        if (existing != null && !existing.terminal()) {
            throw new IllegalStateException("transaction exists");
        }
        if (entries.size() >= MAX_ENTRIES) {
            UUID oldestTerminal = entries.values().stream().filter(Entry::terminal).map(Entry::id).findFirst().orElse(null);
            if (oldestTerminal == null) {
                throw new IllegalStateException("transaction log full");
            }
            entries.remove(oldestTerminal);
        }
        Entry entry = new Entry(listing.getId(), listing.getId(), buyerId, buyerName, listing.getSellerId(), listing.getSellerName(), gross, net, State.PREPARED, timestamp, timestamp, "");
        entries.put(entry.id(), entry);
        setDirty();
        return entry;
    }

    public synchronized Entry transition(UUID id, State state, String detail, long timestamp) {
        Entry current = entries.get(id);
        if (current == null) {
            throw new IllegalArgumentException("transaction");
        }
        Entry updated = new Entry(current.id(), current.listingId(), current.buyerId(), current.buyerName(), current.sellerId(), current.sellerName(), current.gross(), current.net(), state, current.createdAt(), timestamp, detail);
        entries.put(id, updated);
        if (updated.terminal()) {
            trimTerminalHistory();
        }
        setDirty();
        return updated;
    }

    public synchronized Entry mirrorAuctionEscrow(AuctionEscrowLog.Entry escrow, String detail, long timestamp) {
        Objects.requireNonNull(escrow, "escrow");
        UUID buyerId = escrow.effectiveBuyerId();
        if (buyerId == null) {
            throw new IllegalArgumentException("escrow buyer");
        }
        prune(timestamp);
        Entry existing = entries.get(escrow.listingId());
        if (existing == null && entries.size() >= MAX_ENTRIES) {
            UUID oldestTerminal = entries.values().stream().filter(Entry::terminal).map(Entry::id).findFirst().orElse(null);
            if (oldestTerminal == null) {
                throw new IllegalStateException("transaction log full");
            }
            entries.remove(oldestTerminal);
        }
        long createdAt = existing == null ? timestamp : existing.createdAt();
        Entry mirrored = new Entry(escrow.listingId(), escrow.listingId(), buyerId, escrow.effectiveBuyerName(), escrow.sellerId(), escrow.sellerName(), escrow.effectiveAmount(), escrow.netAmount(), State.MANUAL_REVIEW, createdAt, timestamp, detail);
        entries.put(mirrored.id(), mirrored);
        setDirty();
        return mirrored;
    }

    public synchronized Optional<Entry> getEntry(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    public synchronized List<Entry> unresolved() {
        return entries.values().stream().filter(entry -> !entry.terminal()).toList();
    }

    public synchronized List<Entry> manualReviews() {
        return entries.values().stream().filter(entry -> entry.state() == State.MANUAL_REVIEW).toList();
    }

    public synchronized boolean recoveryUnsafe() {
        return !quarantinedEntries.isEmpty();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        entries.values().forEach(entry -> list.add(entry.toTag()));
        tag.put("Entries", list);
        ListTag quarantine = new ListTag();
        quarantinedEntries.forEach(entry -> quarantine.add(entry.copy()));
        tag.put("QuarantinedEntries", quarantine);
        return tag;
    }

    private static MarketTransactionLog load(CompoundTag tag) {
        MarketTransactionLog log = new MarketTransactionLog();
        ListTag storedQuarantine = tag.getList("QuarantinedEntries", Tag.TAG_COMPOUND);
        for (int index = 0; index < storedQuarantine.size(); index++) {
            log.quarantinedEntries.add(storedQuarantine.getCompound(index).copy());
        }
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        int start = Math.max(0, list.size() - MAX_ENTRIES);
        for (int index = 0; index < start; index++) {
            log.quarantinedEntries.add(list.getCompound(index).copy());
            log.setDirty();
            FreeMarket.LOGGER.error("Quarantined overflow FreeMarket transaction entry at index {}", index);
        }
        for (int index = start; index < list.size(); index++) {
            try {
                Entry entry = Entry.fromTag(list.getCompound(index));
                if (log.entries.putIfAbsent(entry.id(), entry) != null) {
                    throw new IllegalArgumentException("duplicate transaction id");
                }
            } catch (RuntimeException exception) {
                log.quarantinedEntries.add(list.getCompound(index).copy());
                log.setDirty();
                FreeMarket.LOGGER.error("Quarantined malformed FreeMarket transaction entry at index {}", index, exception);
            }
        }
        return log;
    }

    private synchronized void prune(long now) {
        ArrayList<UUID> removals = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.terminal() && entry.updatedAt() < now - TERMINAL_RETENTION) {
                removals.add(entry.id());
            }
        }
        removals.forEach(entries::remove);
        if (!removals.isEmpty()) {
            setDirty();
        }
    }

    private synchronized void trimTerminalHistory() {
        int terminalCount = (int) entries.values().stream().filter(Entry::terminal).count();
        if (terminalCount <= MAX_TERMINAL_HISTORY) {
            return;
        }
        ArrayList<UUID> removals = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.terminal()) {
                removals.add(entry.id());
                terminalCount--;
                if (terminalCount <= MAX_TERMINAL_HISTORY) {
                    break;
                }
            }
        }
        removals.forEach(entries::remove);
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
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            FreeMarket.LOGGER.error("Unable to persist the FreeMarket transaction journal", exception);
            throw new IllegalStateException("transaction journal save failed", exception);
        }
    }
}
