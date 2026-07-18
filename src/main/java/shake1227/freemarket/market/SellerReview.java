package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public final class SellerReview {
    private final UUID id;
    private final UUID listingId;
    private final UUID reviewerId;
    private final String reviewerName;
    private final int stars;
    private final String comment;
    private final long createdAt;

    public SellerReview(UUID id, UUID listingId, UUID reviewerId, String reviewerName, int stars, String comment, long createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.listingId = Objects.requireNonNull(listingId, "listingId");
        this.reviewerId = Objects.requireNonNull(reviewerId, "reviewerId");
        this.reviewerName = MarketLimits.bounded(reviewerName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("stars");
        }
        this.stars = stars;
        this.comment = MarketLimits.bounded(comment, MarketLimits.MAX_REVIEW_COMMENT_LENGTH);
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
    }

    public SellerReview(UUID listingId, UUID reviewerId, String reviewerName, int stars, String comment, long createdAt) {
        this(UUID.randomUUID(), listingId, reviewerId, reviewerName, stars, comment, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public int getStars() {
        return stars;
    }

    public String getComment() {
        return comment;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("ListingId", listingId);
        tag.putUUID("ReviewerId", reviewerId);
        tag.putString("ReviewerName", reviewerName);
        tag.putInt("Stars", stars);
        tag.putString("Comment", comment);
        tag.putLong("CreatedAt", createdAt);
        return tag;
    }

    public static SellerReview fromTag(CompoundTag tag) {
        if (!tag.hasUUID("ListingId") || !tag.hasUUID("ReviewerId")) {
            throw new IllegalArgumentException("review");
        }
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        return new SellerReview(id, tag.getUUID("ListingId"), tag.getUUID("ReviewerId"), tag.getString("ReviewerName"), tag.getInt("Stars"), tag.getString("Comment"), tag.getLong("CreatedAt"));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SellerReview other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
