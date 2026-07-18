package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MarketReport {
    public enum Reason {
        PROHIBITED_ITEM,
        MISLEADING,
        FRAUD,
        HARASSMENT,
        SPAM,
        OTHER
    }

    public enum Status {
        OPEN,
        REVIEWING,
        RESOLVED,
        DISMISSED
    }

    private final UUID id;
    private final UUID listingId;
    private final UUID reporterId;
    private final String reporterName;
    private final Reason reason;
    private final String detail;
    private final Status status;
    private final UUID reviewerId;
    private final String resolution;
    private final long createdAt;
    private final long updatedAt;

    public MarketReport(UUID id, UUID listingId, UUID reporterId, String reporterName, Reason reason, String detail, Status status, UUID reviewerId, String resolution, long createdAt, long updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.listingId = Objects.requireNonNull(listingId, "listingId");
        this.reporterId = Objects.requireNonNull(reporterId, "reporterId");
        this.reporterName = MarketLimits.bounded(reporterName, MarketLimits.MAX_PLAYER_NAME_LENGTH);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.detail = MarketLimits.bounded(detail, MarketLimits.MAX_REPORT_DETAIL_LENGTH);
        this.status = Objects.requireNonNull(status, "status");
        this.reviewerId = reviewerId;
        this.resolution = MarketLimits.bounded(resolution, MarketLimits.MAX_REPORT_RESOLUTION_LENGTH);
        this.createdAt = MarketLimits.nonNegativeTime(createdAt);
        this.updatedAt = Math.max(this.createdAt, MarketLimits.nonNegativeTime(updatedAt));
        if (reason == Reason.OTHER && this.detail.isBlank()) {
            throw new IllegalArgumentException("detail");
        }
        if (status != Status.OPEN && reviewerId == null) {
            throw new IllegalArgumentException("reviewerId");
        }
    }

    public MarketReport(UUID listingId, UUID reporterId, String reporterName, Reason reason, String detail, long timestamp) {
        this(UUID.randomUUID(), listingId, reporterId, reporterName, reason, detail, Status.OPEN, null, "", timestamp, timestamp);
    }

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public String getReporterName() {
        return reporterName;
    }

    public Reason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public Status getStatus() {
        return status;
    }

    public Optional<UUID> getReviewerId() {
        return Optional.ofNullable(reviewerId);
    }

    public String getResolution() {
        return resolution;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isPending() {
        return status == Status.OPEN || status == Status.REVIEWING;
    }

    public MarketReport review(Status status, UUID reviewerId, String resolution, long timestamp) {
        return new MarketReport(id, listingId, reporterId, reporterName, reason, detail, status, reviewerId, resolution, createdAt, Math.max(updatedAt, timestamp));
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("ListingId", listingId);
        tag.putUUID("ReporterId", reporterId);
        tag.putString("ReporterName", reporterName);
        tag.putString("Reason", reason.name());
        tag.putString("Detail", detail);
        tag.putString("Status", status.name());
        if (reviewerId != null) {
            tag.putUUID("ReviewerId", reviewerId);
        }
        tag.putString("Resolution", resolution);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static MarketReport fromTag(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("ListingId") || !tag.hasUUID("ReporterId")) {
            throw new IllegalArgumentException("report");
        }
        Reason reason = parseEnum(Reason.class, tag.getString("Reason"), Reason.OTHER);
        Status status = parseEnum(Status.class, tag.getString("Status"), Status.OPEN);
        UUID reviewerId = tag.hasUUID("ReviewerId") ? tag.getUUID("ReviewerId") : null;
        if (status != Status.OPEN && reviewerId == null) {
            status = Status.OPEN;
        }
        return new MarketReport(tag.getUUID("Id"), tag.getUUID("ListingId"), tag.getUUID("ReporterId"), tag.getString("ReporterName"), reason, tag.getString("Detail"), status, reviewerId, tag.getString("Resolution"), tag.getLong("CreatedAt"), tag.getLong("UpdatedAt"));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (!value.isBlank()) {
            try {
                return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return fallback;
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof MarketReport other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
