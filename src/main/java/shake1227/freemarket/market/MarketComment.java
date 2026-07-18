package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public final class MarketComment {
    private final UUID id;
    private final UUID authorId;
    private final String authorName;
    private final String message;
    private final long createdAt;

    public MarketComment(UUID id, UUID authorId, String authorName, String message, long createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.authorId = Objects.requireNonNull(authorId, "authorId");
        this.authorName = MarketLimits.bounded(authorName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
        this.message = MarketLimits.bounded(message, MarketLimits.MAX_COMMENT_LENGTH);
        if (this.message.isBlank()) {
            throw new IllegalArgumentException("message");
        }
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
    }

    public MarketComment(UUID authorId, String authorName, String message, long createdAt) {
        this(UUID.randomUUID(), authorId, authorName, message, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getMessage() {
        return message;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("AuthorId", authorId);
        tag.putString("AuthorName", authorName);
        tag.putString("Message", message);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static MarketComment fromTag(CompoundTag tag) {
        if (!tag.hasUUID("AuthorId")) {
            throw new IllegalArgumentException("authorId");
        }
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        return new MarketComment(id, tag.getUUID("AuthorId"), tag.getString("AuthorName"), tag.getString("Message"), tag.getLong("CreatedAt"));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof MarketComment other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
