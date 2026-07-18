package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
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

public final class AuctionEscrowLog extends SavedData {
    public enum State {
        HELD,
        BID_DEBIT_PREPARED,
        BID_NEW_DEBITED,
        BID_PREVIOUS_REFUNDED,
        SETTLEMENT_PREPARED,
        SELLER_CREDITED,
        MARKET_COMMITTED,
        DELIVERY_QUEUED,
        COMPLETED,
        REFUND_PREPARED,
        REFUNDED,
        MANUAL_REVIEW
    }

    public record Entry(UUID listingId, UUID sellerId, String sellerName, UUID holderId, String holderName, double heldAmount, UUID candidateId, String candidateName, double candidateAmount, double debitAmount, double netAmount, State state, long createdAt, long updatedAt, String detail) {
        public Entry {
            Objects.requireNonNull(listingId, "listingId");
            Objects.requireNonNull(sellerId, "sellerId");
            Objects.requireNonNull(state, "state");
            sellerName = MarketLimits.bounded(sellerName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
            holderName = MarketLimits.bounded(holderName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
            candidateName = MarketLimits.bounded(candidateName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
            heldAmount = validAmount(heldAmount);
            candidateAmount = validAmount(candidateAmount);
            debitAmount = validAmount(debitAmount);
            netAmount = validAmount(netAmount);
            createdAt = Math.max(0L, createdAt);
            updatedAt = Math.max(createdAt, updatedAt);
            detail = MarketLimits.bounded(detail, MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH);
            if ((holderId == null) != (heldAmount == 0D)) {
                throw new IllegalArgumentException("holder");
            }
            if ((candidateId == null) != (candidateAmount == 0D)) {
                throw new IllegalArgumentException("candidate");
            }
            if (sellerId.equals(holderId) || sellerId.equals(candidateId)) {
                throw new IllegalArgumentException("escrow party");
            }
            boolean bidState = state == State.BID_DEBIT_PREPARED || state == State.BID_NEW_DEBITED || state == State.BID_PREVIOUS_REFUNDED;
            if (bidState) {
                if (candidateId == null || debitAmount <= 0D || (holderId != null && candidateAmount <= heldAmount)) {
                    throw new IllegalArgumentException("bid escrow state");
                }
                double expectedDebit = candidateId.equals(holderId) ? candidateAmount - heldAmount : candidateAmount;
                if (Double.compare(debitAmount, expectedDebit) != 0) {
                    throw new IllegalArgumentException("bid escrow debit");
                }
            } else if (state == State.MANUAL_REVIEW) {
                if (holderId == null && candidateId == null) {
                    throw new IllegalArgumentException("manual escrow party");
                }
                if (candidateId != null) {
                    if (debitAmount <= 0D || (holderId != null && candidateAmount <= heldAmount)) {
                        throw new IllegalArgumentException("manual bid escrow");
                    }
                    double expectedDebit = candidateId.equals(holderId) ? candidateAmount - heldAmount : candidateAmount;
                    if (Double.compare(debitAmount, expectedDebit) != 0) {
                        throw new IllegalArgumentException("manual bid debit");
                    }
                } else if (debitAmount != 0D) {
                    throw new IllegalArgumentException("manual escrow debit");
                }
                if (netAmount > (candidateId == null ? heldAmount : candidateAmount)) {
                    throw new IllegalArgumentException("manual escrow net");
                }
            } else if (holderId == null || candidateId != null || debitAmount != 0D) {
                throw new IllegalArgumentException("escrow state");
            }
            if ((state == State.SETTLEMENT_PREPARED || state == State.SELLER_CREDITED || state == State.MARKET_COMMITTED || state == State.DELIVERY_QUEUED || state == State.COMPLETED) && netAmount > heldAmount) {
                throw new IllegalArgumentException("escrow net");
            }
        }

        public boolean terminal() {
            return state == State.COMPLETED || state == State.REFUNDED;
        }

        public UUID effectiveBuyerId() {
            return candidateId == null ? holderId : candidateId;
        }

        public String effectiveBuyerName() {
            return candidateId == null ? holderName : candidateName;
        }

        public double effectiveAmount() {
            return candidateId == null ? heldAmount : candidateAmount;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("ListingId", listingId);
            tag.putUUID("SellerId", sellerId);
            tag.putString("SellerName", sellerName);
            if (holderId != null) {
                tag.putUUID("HolderId", holderId);
                tag.putString("HolderName", holderName);
            }
            tag.putDouble("HeldAmount", heldAmount);
            if (candidateId != null) {
                tag.putUUID("CandidateId", candidateId);
                tag.putString("CandidateName", candidateName);
            }
            tag.putDouble("CandidateAmount", candidateAmount);
            tag.putDouble("DebitAmount", debitAmount);
            tag.putDouble("NetAmount", netAmount);
            tag.putString("State", state.name());
            tag.putLong("CreatedAt", createdAt);
            tag.putLong("UpdatedAt", updatedAt);
            tag.putString("Detail", detail);
            return tag;
        }

        public static Entry fromTag(CompoundTag tag) {
            if (!tag.hasUUID("ListingId") || !tag.hasUUID("SellerId")) {
                throw new IllegalArgumentException("escrow");
            }
            State state = State.valueOf(tag.getString("State").toUpperCase(Locale.ROOT));
            UUID holderId = tag.hasUUID("HolderId") ? tag.getUUID("HolderId") : null;
            UUID candidateId = tag.hasUUID("CandidateId") ? tag.getUUID("CandidateId") : null;
            return new Entry(tag.getUUID("ListingId"), tag.getUUID("SellerId"), tag.getString("SellerName"), holderId, tag.getString("HolderName"), tag.getDouble("HeldAmount"), candidateId, tag.getString("CandidateName"), tag.getDouble("CandidateAmount"), tag.getDouble("DebitAmount"), tag.getDouble("NetAmount"), state, tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"), tag.getString("Detail"));
        }

        private static double validAmount(double amount) {
            if (!Double.isFinite(amount) || amount < 0D || amount > MarketLimits.MAX_PRICE) {
                throw new IllegalArgumentException("amount");
            }
            return amount;
        }
    }

    private static final String DATA_NAME = "freemarket_auction_escrow";
    private static final int MAX_ENTRIES = 4_096;
    private static final int MAX_TERMINAL_HISTORY = 512;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    private final ListTag quarantinedEntries = new ListTag();

    public static AuctionEscrowLog get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(AuctionEscrowLog::load, AuctionEscrowLog::new, DATA_NAME);
    }

    public static void saveNow(MinecraftServer server) {
        AuctionEscrowLog log = get(server);
        Path directory = server.getWorldPath(new LevelResource("data"));
        log.saveAtomic(directory.resolve(DATA_NAME + ".dat"));
    }

    public synchronized Optional<Entry> getEntry(UUID listingId) {
        return Optional.ofNullable(entries.get(listingId));
    }

    public synchronized List<Entry> unresolved() {
        return entries.values().stream().filter(entry -> !entry.terminal()).toList();
    }

    public synchronized boolean recoveryUnsafe() {
        return !quarantinedEntries.isEmpty();
    }

    public synchronized Entry prepareBid(Listing listing, UUID bidderId, String bidderName, double amount, long timestamp) {
        Objects.requireNonNull(listing, "listing");
        Objects.requireNonNull(bidderId, "bidderId");
        double checkedAmount = MarketLimits.requirePrice(amount);
        Entry current = entries.get(listing.getId());
        if (current != null && current.state() != State.HELD && !current.terminal()) {
            throw new IllegalStateException("escrow busy");
        }
        UUID holderId = current != null && current.state() == State.HELD ? current.holderId() : null;
        String holderName = holderId == null ? "" : current.holderName();
        double heldAmount = holderId == null ? 0D : current.heldAmount();
        double debitAmount = bidderId.equals(holderId) ? checkedAmount - heldAmount : checkedAmount;
        if (debitAmount <= 0D) {
            throw new IllegalArgumentException("amount");
        }
        ensureCapacity(current);
        long createdAt = current == null || current.terminal() ? timestamp : current.createdAt();
        Entry prepared = new Entry(listing.getId(), listing.getSellerId(), listing.getSellerName(), holderId, holderName, heldAmount, bidderId, bidderName, checkedAmount, debitAmount, 0D, State.BID_DEBIT_PREPARED, createdAt, timestamp, "");
        entries.put(listing.getId(), prepared);
        setDirty();
        return prepared;
    }

    public synchronized Entry transition(UUID listingId, State state, String detail, long timestamp) {
        Entry current = requireEntry(listingId);
        Entry updated = new Entry(current.listingId(), current.sellerId(), current.sellerName(), current.holderId(), current.holderName(), current.heldAmount(), current.candidateId(), current.candidateName(), current.candidateAmount(), current.debitAmount(), current.netAmount(), state, current.createdAt(), timestamp, detail);
        entries.put(listingId, updated);
        trimTerminalHistory();
        setDirty();
        return updated;
    }

    public synchronized Entry commitBid(UUID listingId, long timestamp) {
        Entry current = requireEntry(listingId);
        if (current.state() != State.BID_NEW_DEBITED && current.state() != State.BID_PREVIOUS_REFUNDED) {
            throw new IllegalStateException("escrow state");
        }
        Entry held = new Entry(current.listingId(), current.sellerId(), current.sellerName(), current.candidateId(), current.candidateName(), current.candidateAmount(), null, "", 0D, 0D, 0D, State.HELD, current.createdAt(), timestamp, "");
        entries.put(listingId, held);
        setDirty();
        return held;
    }

    public synchronized void restoreAfterDecline(UUID listingId, long timestamp) {
        Entry current = requireEntry(listingId);
        if (current.state() != State.BID_DEBIT_PREPARED) {
            throw new IllegalStateException("escrow state");
        }
        if (current.holderId() == null) {
            entries.remove(listingId);
        } else {
            entries.put(listingId, new Entry(current.listingId(), current.sellerId(), current.sellerName(), current.holderId(), current.holderName(), current.heldAmount(), null, "", 0D, 0D, 0D, State.HELD, current.createdAt(), timestamp, ""));
        }
        setDirty();
    }

    public synchronized Entry prepareSettlement(UUID listingId, double netAmount, long timestamp) {
        Entry current = requireEntry(listingId);
        if (current.state() != State.HELD || current.holderId() == null) {
            throw new IllegalStateException("escrow state");
        }
        Entry prepared = new Entry(current.listingId(), current.sellerId(), current.sellerName(), current.holderId(), current.holderName(), current.heldAmount(), null, "", 0D, 0D, MarketLimits.requirePrice(netAmount), State.SETTLEMENT_PREPARED, current.createdAt(), timestamp, "");
        entries.put(listingId, prepared);
        setDirty();
        return prepared;
    }

    public synchronized Entry prepareRefund(UUID listingId, long timestamp) {
        Entry current = requireEntry(listingId);
        if (current.state() != State.HELD || current.holderId() == null) {
            throw new IllegalStateException("escrow state");
        }
        return transition(listingId, State.REFUND_PREPARED, "", timestamp);
    }

    public synchronized Entry manual(UUID listingId, String detail, long timestamp) {
        return transition(listingId, State.MANUAL_REVIEW, detail, timestamp);
    }

    public synchronized Entry resolveManual(UUID listingId, boolean completed, String detail, long timestamp) {
        Entry current = requireEntry(listingId);
        if (current.state() != State.MANUAL_REVIEW || current.effectiveBuyerId() == null) {
            throw new IllegalStateException("escrow state");
        }
        Entry resolved = new Entry(current.listingId(), current.sellerId(), current.sellerName(), current.effectiveBuyerId(), current.effectiveBuyerName(), current.effectiveAmount(), null, "", 0D, 0D, current.netAmount(), completed ? State.COMPLETED : State.REFUNDED, current.createdAt(), timestamp, detail);
        entries.put(listingId, resolved);
        trimTerminalHistory();
        setDirty();
        return resolved;
    }

    public synchronized Entry createLegacyManual(Listing listing, BidRecord bid, String detail, long timestamp) {
        Entry current = entries.get(listing.getId());
        if (current != null) {
            return manual(listing.getId(), detail, timestamp);
        }
        ensureCapacity(null);
        Entry entry = new Entry(listing.getId(), listing.getSellerId(), listing.getSellerName(), null, "", 0D, bid.getBidderId(), bid.getBidderName(), bid.getAmount(), bid.getAmount(), 0D, State.MANUAL_REVIEW, timestamp, timestamp, detail);
        entries.put(listing.getId(), entry);
        setDirty();
        return entry;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        entries.values().forEach(entry -> list.add(entry.toTag()));
        tag.put("Entries", list);
        tag.put("QuarantinedEntries", quarantinedEntries.copy());
        return tag;
    }

    private static AuctionEscrowLog load(CompoundTag tag) {
        AuctionEscrowLog log = new AuctionEscrowLog();
        Tag rawEntries = tag.get("Entries");
        if (!(rawEntries instanceof ListTag rawEntryList) || (!rawEntryList.isEmpty() && rawEntryList.getElementType() != Tag.TAG_COMPOUND)) {
            CompoundTag quarantine = new CompoundTag();
            quarantine.put("RawData", tag.copy());
            quarantine.putString("Reason", "invalid entries container");
            log.quarantinedEntries.add(quarantine);
            log.setDirty();
            FreeMarket.LOGGER.error("Quarantined invalid FreeMarket auction escrow entries container");
            return log;
        }
        ListTag list = rawEntryList;
        if (list.size() > MAX_ENTRIES) {
            int overflow = list.size() - MAX_ENTRIES;
            FreeMarket.LOGGER.error("FreeMarket auction escrow journal exceeded its entry limit by {} records", overflow);
            for (int index = 0; index < overflow; index++) {
                log.quarantinedEntries.add(list.getCompound(index).copy());
            }
        }
        int start = Math.max(0, list.size() - MAX_ENTRIES);
        for (int index = start; index < list.size(); index++) {
            try {
                Entry entry = Entry.fromTag(list.getCompound(index));
                if (log.entries.putIfAbsent(entry.listingId(), entry) != null) {
                    log.quarantinedEntries.add(list.getCompound(index).copy());
                    FreeMarket.LOGGER.error("Quarantined duplicate FreeMarket auction escrow record {}", entry.listingId());
                }
            } catch (RuntimeException exception) {
                log.quarantinedEntries.add(list.getCompound(index).copy());
                FreeMarket.LOGGER.error("Quarantined malformed FreeMarket auction escrow record at index {}", index, exception);
            }
        }
        Tag rawQuarantine = tag.get("QuarantinedEntries");
        if (rawQuarantine instanceof ListTag storedQuarantine && (storedQuarantine.isEmpty() || storedQuarantine.getElementType() == Tag.TAG_COMPOUND)) {
            for (int index = 0; index < storedQuarantine.size(); index++) {
                log.quarantinedEntries.add(storedQuarantine.getCompound(index).copy());
            }
        } else if (rawQuarantine != null) {
            CompoundTag quarantine = new CompoundTag();
            quarantine.put("RawQuarantine", rawQuarantine.copy());
            quarantine.putString("Reason", "invalid quarantine container");
            log.quarantinedEntries.add(quarantine);
            FreeMarket.LOGGER.error("Quarantined invalid FreeMarket auction escrow quarantine container");
        }
        if (!log.quarantinedEntries.isEmpty()) {
            log.setDirty();
        }
        return log;
    }

    private Entry requireEntry(UUID listingId) {
        Entry entry = entries.get(listingId);
        if (entry == null) {
            throw new IllegalArgumentException("escrow");
        }
        return entry;
    }

    private void ensureCapacity(Entry current) {
        if (current != null || entries.size() < MAX_ENTRIES) {
            return;
        }
        UUID terminal = entries.values().stream().filter(Entry::terminal).map(Entry::listingId).findFirst().orElse(null);
        if (terminal == null) {
            throw new IllegalStateException("escrow log full");
        }
        entries.remove(terminal);
    }

    private void trimTerminalHistory() {
        int count = (int) entries.values().stream().filter(Entry::terminal).count();
        if (count <= MAX_TERMINAL_HISTORY) {
            return;
        }
        ArrayList<UUID> removals = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.terminal()) {
                removals.add(entry.listingId());
                if (--count <= MAX_TERMINAL_HISTORY) {
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
            FreeMarket.LOGGER.error("Unable to persist the FreeMarket auction escrow journal", exception);
            throw new IllegalStateException("auction escrow journal save failed", exception);
        }
    }
}
