package shake1227.freemarket.market;

public final class MarketLimits {
    public static final int MAX_LISTINGS = 100_000;
    public static final int MAX_RETAINED_TERMINAL_LISTINGS = 2_000;
    public static final long TERMINAL_LISTING_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L;
    public static final int MAX_USERS = 100_000;
    public static final int MAX_TAG_DEFINITIONS = 512;
    public static final int MAX_TAGS_PER_LISTING = 16;
    public static final int MAX_COMMENTS_PER_LISTING = 256;
    public static final int MAX_BIDS_PER_LISTING = 512;
    public static final int MAX_LIKES_PER_LISTING = 20_000;
    public static final int MAX_NOTIFICATIONS_PER_USER = 256;
    public static final int MAX_HISTORY_PER_USER = 512;
    public static final int MAX_REVIEWED_LISTINGS_PER_USER = 4_096;
    public static final int MAX_REVIEWS_PER_USER = 200;
    public static final int MAX_REVIEW_COMMENT_LENGTH = 256;
    public static final int MAX_SAVED_SEARCHES_PER_USER = 20;
    public static final int MAX_BLOCKED_USERS_PER_USER = 1_000;
    public static final int MAX_REPORTS = 100_000;
    public static final int MAX_PENDING_REPORTS_PER_USER = 100;
    public static final int MAX_PENDING_REPORTS_PER_LISTING = 1_000;
    public static final int MAX_OFFERS_PER_LISTING = 64;
    public static final int MAX_PENDING_OFFERS_PER_LISTING = 32;
    public static final int MAX_PENDING_OFFERS_PER_USER = 20;
    public static final long OFFER_DURATION_MILLIS = 24L * 60L * 60L * 1_000L;
    public static final int MAX_PAGE_SIZE = 60;
    public static final int MAX_DISPLAY_NAME_JSON_LENGTH = 2_048;
    public static final int MAX_PLAIN_NAME_LENGTH = 128;
    public static final int MAX_PLAYER_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 4_096;
    public static final int MAX_COMMENT_LENGTH = 512;
    public static final int MAX_NOTIFICATION_TITLE_LENGTH = 160;
    public static final int MAX_NOTIFICATION_MESSAGE_LENGTH = 1_024;
    public static final int MAX_TAG_ID_LENGTH = 64;
    public static final int MAX_TAG_LABEL_LENGTH = 96;
    public static final int MAX_TRANSLATION_KEY_LENGTH = 160;
    public static final int MAX_QUERY_LENGTH = 160;
    public static final int MAX_SAVED_SEARCH_NAME_LENGTH = 80;
    public static final int MAX_REPORT_DETAIL_LENGTH = 1_024;
    public static final int MAX_REPORT_RESOLUTION_LENGTH = 1_024;
    public static final double MAX_PRICE = 1_000_000_000_000D;
    public static final long MAX_AUCTION_DURATION_MILLIS = 365L * 24L * 60L * 60L * 1_000L;

    private MarketLimits() {
    }

    public static String bounded(String value, int maximumLength) {
        if (value == null || maximumLength <= 0) {
            return "";
        }
        String normalized = value.replace('\u0000', ' ').strip();
        if (normalized.length() <= maximumLength) {
            return normalized;
        }
        int end = maximumLength;
        if (end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end).stripTrailing();
    }

    public static double requirePrice(double value) {
        if (!Double.isFinite(value) || value < 0D || value > MAX_PRICE) {
            throw new IllegalArgumentException("price");
        }
        return value;
    }

    public static long nonNegativeTime(long value) {
        return Math.max(0L, value);
    }
}
