package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SavedMarketSearch {
    private final UUID id;
    private final String name;
    private final MarketQuery query;
    private final boolean notificationsEnabled;
    private final long createdAt;
    private final long updatedAt;
    private final long lastNotifiedAt;
    private final UUID lastNotifiedListingId;

    public SavedMarketSearch(UUID id, String name, MarketQuery query, boolean notificationsEnabled, long createdAt, long updatedAt, long lastNotifiedAt, UUID lastNotifiedListingId) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = checkedName(name);
        this.query = Objects.requireNonNull(query, "query").toBuilder().page(0).build();
        this.notificationsEnabled = notificationsEnabled;
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
        this.updatedAt = Math.max(this.createdAt, MarketLimits.nonNegativeTime(updatedAt));
        this.lastNotifiedAt = Math.min(this.updatedAt, MarketLimits.nonNegativeTime(lastNotifiedAt));
        this.lastNotifiedListingId = lastNotifiedListingId;
    }

    public SavedMarketSearch(String name, MarketQuery query, boolean notificationsEnabled, long timestamp) {
        this(UUID.randomUUID(), name, query, notificationsEnabled, timestamp, timestamp, 0L, null);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public MarketQuery getQuery() {
        return query;
    }

    public MarketQuery queryPage(int page, int pageSize) {
        return query.toBuilder().page(page).pageSize(pageSize).build();
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public Optional<UUID> getLastNotifiedListingId() {
        return Optional.ofNullable(lastNotifiedListingId);
    }

    public SavedMarketSearch update(String name, MarketQuery query, boolean notificationsEnabled, long timestamp) {
        return new SavedMarketSearch(id, name, query, notificationsEnabled, createdAt, Math.max(updatedAt, timestamp), lastNotifiedAt, lastNotifiedListingId);
    }

    public SavedMarketSearch markNotified(UUID listingId, long timestamp) {
        return new SavedMarketSearch(id, name, query, notificationsEnabled, createdAt, Math.max(updatedAt, timestamp), Math.max(lastNotifiedAt, timestamp), Objects.requireNonNull(listingId, "listingId"));
    }

    public boolean matchesNewListing(Listing listing, long timestamp) {
        Objects.requireNonNull(listing, "listing");
        return notificationsEnabled
                && listing.getCreatedAt() >= createdAt
                && !listing.getId().equals(lastNotifiedListingId)
                && MarketSavedData.matchesListing(listing, query, timestamp);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.put("Query", query.toTag());
        tag.putBoolean("NotificationsEnabled", notificationsEnabled);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putLong("LastNotifiedAt", lastNotifiedAt);
        if (lastNotifiedListingId != null) {
            tag.putUUID("LastNotifiedListingId", lastNotifiedListingId);
        }
        return tag;
    }

    public static SavedMarketSearch fromTag(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.contains("Query", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("saved search");
        }
        UUID listingId = tag.hasUUID("LastNotifiedListingId") ? tag.getUUID("LastNotifiedListingId") : null;
        return new SavedMarketSearch(tag.getUUID("Id"), tag.getString("Name"), MarketQuery.fromTag(tag.getCompound("Query")), tag.getBoolean("NotificationsEnabled"), tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"), tag.getLong("LastNotifiedAt"), listingId);
    }

    private static String checkedName(String value) {
        String checked = MarketLimits.bounded(value, MarketLimits.MAX_SAVED_SEARCH_NAME_LENGTH);
        if (checked.isBlank()) {
            throw new IllegalArgumentException("name");
        }
        return checked;
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof SavedMarketSearch other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
