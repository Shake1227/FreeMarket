package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class MarketOffer {
    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED,
        EXPIRED
    }

    private final UUID id;
    private final UUID listingId;
    private final UUID requesterId;
    private final String requesterName;
    private final double amount;
    private final Status status;
    private final long createdAt;
    private final long expiresAt;
    private final long updatedAt;

    public MarketOffer(UUID id, UUID listingId, UUID requesterId, String requesterName, double amount, Status status, long createdAt, long expiresAt, long updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.listingId = Objects.requireNonNull(listingId, "listingId");
        this.requesterId = Objects.requireNonNull(requesterId, "requesterId");
        this.requesterName = MarketLimits.bounded(requesterName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
        this.amount = MarketLimits.requirePrice(amount);
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
        this.expiresAt = MarketLimits.nonNegativeTime(expiresAt);
        this.updatedAt = Math.max(this.createdAt, MarketLimits.nonNegativeTime(updatedAt));
        if (this.amount <= 0D || this.expiresAt <= this.createdAt || this.expiresAt - this.createdAt > MarketLimits.OFFER_DURATION_MILLIS) {
            throw new IllegalArgumentException("offer");
        }
    }

    public MarketOffer(UUID listingId, UUID requesterId, String requesterName, double amount, long timestamp) {
        this(UUID.randomUUID(), listingId, requesterId, requesterName, amount, Status.PENDING, timestamp, safeExpiry(timestamp), timestamp);
    }

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public UUID getRequesterId() {
        return requesterId;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public double getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isPending(long timestamp) {
        return status == Status.PENDING && timestamp < expiresAt;
    }

    public MarketOffer expire(long timestamp) {
        if (status != Status.PENDING || timestamp < expiresAt) {
            return this;
        }
        return withStatus(Status.EXPIRED, timestamp);
    }

    public MarketOffer closeExpired(long timestamp) {
        if (status != Status.PENDING) {
            return this;
        }
        return withStatus(Status.EXPIRED, timestamp);
    }

    public MarketOffer resolve(boolean accepted, long timestamp) {
        if (!isPending(timestamp)) {
            throw new IllegalStateException("offer unavailable");
        }
        return withStatus(accepted ? Status.ACCEPTED : Status.REJECTED, timestamp);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("ListingId", listingId);
        tag.putUUID("RequesterId", requesterId);
        tag.putString("RequesterName", requesterName);
        tag.putDouble("Amount", amount);
        tag.putString("Status", status.name());
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("ExpiresAt", expiresAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static MarketOffer fromTag(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("ListingId") || !tag.hasUUID("RequesterId") || !tag.contains("Amount", Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("offer");
        }
        Status status;
        try {
            status = Status.valueOf(tag.getString("Status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("offer status", exception);
        }
        return new MarketOffer(tag.getUUID("Id"), tag.getUUID("ListingId"), tag.getUUID("RequesterId"), tag.getString("RequesterName"), tag.getDouble("Amount"), status, tag.getLong("CreatedAt"), tag.getLong("ExpiresAt"), tag.getLong("UpdatedAt"));
    }

    private MarketOffer withStatus(Status status, long timestamp) {
        return new MarketOffer(id, listingId, requesterId, requesterName, amount, status, createdAt, expiresAt, Math.max(updatedAt, timestamp));
    }

    private static long safeExpiry(long timestamp) {
        long created = MarketLimits.nonNegativeTime(timestamp);
        return created > Long.MAX_VALUE - MarketLimits.OFFER_DURATION_MILLIS ? Long.MAX_VALUE : created + MarketLimits.OFFER_DURATION_MILLIS;
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof MarketOffer other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
