package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MarketNotification {
    public enum Type {
        SOLD,
        PURCHASED,
        BID_PLACED,
        OUTBID,
        AUCTION_WON,
        COMMENT,
        PRICE_REQUEST,
        LISTING_REMOVED,
        SYSTEM
    }

    private final UUID id;
    private final Type type;
    private final UUID listingId;
    private final String title;
    private final String message;
    private final long createdAt;
    private final long readAt;

    public MarketNotification(UUID id, Type type, UUID listingId, String title, String message, long createdAt, long readAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.listingId = listingId;
        this.title = MarketLimits.bounded(title, MarketLimits.MAX_NOTIFICATION_TITLE_LENGTH);
        this.message = MarketLimits.bounded(message, MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH);
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
        this.readAt = MarketLimits.nonNegativeTime(readAt);
    }

    public MarketNotification(Type type, UUID listingId, String title, String message, long createdAt) {
        this(UUID.randomUUID(), type, listingId, title, message, createdAt, 0L);
    }

    public UUID getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public Optional<UUID> getListingId() {
        return Optional.ofNullable(listingId);
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getReadAt() {
        return readAt;
    }

    public boolean isRead() {
        return readAt > 0L;
    }

    public MarketNotification markRead(long timestamp) {
        if (isRead()) {
            return this;
        }
        return new MarketNotification(id, type, listingId, title, message, createdAt, Math.max(1L, timestamp));
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Type", type.name());
        if (listingId != null) {
            tag.putUUID("ListingId", listingId);
        }
        tag.putString("Title", title);
        tag.putString("Message", message);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("ReadAt", readAt);
        return tag;
    }

    public static MarketNotification fromTag(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        Type type = Type.SYSTEM;
        if (tag.contains("Type", Tag.TAG_STRING)) {
            try {
                type = Type.valueOf(tag.getString("Type").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                type = Type.SYSTEM;
            }
        }
        UUID listingId = tag.hasUUID("ListingId") ? tag.getUUID("ListingId") : null;
        return new MarketNotification(id, type, listingId, tag.getString("Title"), tag.getString("Message"), tag.getLong("CreatedAt"), tag.getLong("ReadAt"));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof MarketNotification other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
