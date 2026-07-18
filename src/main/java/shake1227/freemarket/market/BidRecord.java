package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;
import java.util.UUID;

public final class BidRecord {
    private final UUID id;
    private final UUID bidderId;
    private final String bidderName;
    private final double amount;
    private final long createdAt;

    public BidRecord(UUID id, UUID bidderId, String bidderName, double amount, long createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.bidderId = Objects.requireNonNull(bidderId, "bidderId");
        this.bidderName = MarketLimits.bounded(bidderName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
        this.amount = MarketLimits.requirePrice(amount);
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
    }

    public BidRecord(UUID bidderId, String bidderName, double amount, long createdAt) {
        this(UUID.randomUUID(), bidderId, bidderName, amount, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBidderId() {
        return bidderId;
    }

    public String getBidderName() {
        return bidderName;
    }

    public double getAmount() {
        return amount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("BidderId", bidderId);
        tag.putString("BidderName", bidderName);
        tag.putDouble("Amount", amount);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static BidRecord fromTag(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        if (!tag.hasUUID("BidderId")) {
            throw new IllegalArgumentException("bidderId");
        }
        double amount = tag.contains("Amount", Tag.TAG_ANY_NUMERIC) ? tag.getDouble("Amount") : -1D;
        return new BidRecord(id, tag.getUUID("BidderId"), tag.getString("BidderName"), amount, tag.getLong("CreatedAt"));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof BidRecord other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
