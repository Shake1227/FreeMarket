package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MarketUserData {
    private final UUID userId;
    private String username;
    private final ArrayDeque<MarketNotification> notifications;
    private final ArrayDeque<UUID> viewHistory;
    private final ArrayDeque<UUID> purchaseHistory;
    private final ArrayDeque<UUID> listingHistory;
    private final LinkedHashSet<UUID> reviewedListings;
    private final LinkedHashMap<UUID, SavedMarketSearch> savedSearches;
    private final LinkedHashSet<UUID> blockedUsers;
    private final ArrayDeque<SellerReview> reviews;
    private final ArrayDeque<UUID> pendingReviewPrompts;
    private long ratingTotal;
    private int ratingCount;

    public MarketUserData(UUID userId, String username) {
        this(userId, username, List.of(), List.of(), List.of(), List.of(), Set.of(), List.of(), Set.of(), List.of(), List.of(), 0L, 0);
    }

    private MarketUserData(UUID userId, String username, Iterable<MarketNotification> notifications, Iterable<UUID> viewHistory, Iterable<UUID> purchaseHistory, Iterable<UUID> listingHistory, Iterable<UUID> reviewedListings, Iterable<SavedMarketSearch> savedSearches, Iterable<UUID> blockedUsers, Iterable<SellerReview> reviews, Iterable<UUID> pendingReviewPrompts, long ratingTotal, int ratingCount) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.username = MarketLimits.bounded(username, MarketLimits.MAX_PLAYER_NAME_LENGTH);
        this.notifications = boundedNotificationDeque(notifications);
        this.viewHistory = boundedUuidDeque(viewHistory);
        this.purchaseHistory = boundedUuidDeque(purchaseHistory);
        this.listingHistory = boundedUuidDeque(listingHistory);
        this.reviewedListings = boundedReviewedListingSet(reviewedListings);
        this.savedSearches = boundedSavedSearchMap(savedSearches);
        this.blockedUsers = boundedUuidSet(blockedUsers, userId);
        this.reviews = boundedReviewDeque(reviews);
        this.pendingReviewPrompts = boundedUuidDeque(pendingReviewPrompts);
        this.ratingCount = Math.max(0, ratingCount);
        this.ratingTotal = Math.max(0L, Math.min((long) this.ratingCount * 5L, ratingTotal));
    }

    public synchronized UUID getUserId() {
        return userId;
    }

    public synchronized String getUsername() {
        return username;
    }

    public synchronized List<MarketNotification> getNotifications() {
        return List.copyOf(notifications);
    }

    public synchronized List<UUID> getViewHistory() {
        return List.copyOf(viewHistory);
    }

    public synchronized List<UUID> getPurchaseHistory() {
        return List.copyOf(purchaseHistory);
    }

    public synchronized List<UUID> getListingHistory() {
        return List.copyOf(listingHistory);
    }

    public synchronized Set<UUID> getReviewedListings() {
        return Set.copyOf(reviewedListings);
    }

    public synchronized boolean hasReviewedListing(UUID listingId) {
        return listingId != null && reviewedListings.contains(listingId);
    }

    public synchronized List<SavedMarketSearch> getSavedSearches() {
        return savedSearches.values().stream().sorted(Comparator.comparingLong(SavedMarketSearch::getUpdatedAt).reversed().thenComparing(value -> value.getId().toString())).toList();
    }

    public synchronized Optional<SavedMarketSearch> getSavedSearch(UUID searchId) {
        return Optional.ofNullable(savedSearches.get(searchId));
    }

    public synchronized Set<UUID> getBlockedUsers() {
        return Set.copyOf(blockedUsers);
    }

    public synchronized boolean isUserBlocked(UUID targetUserId) {
        return targetUserId != null && blockedUsers.contains(targetUserId);
    }

    public synchronized int getUnreadNotificationCount() {
        int count = 0;
        for (MarketNotification notification : notifications) {
            if (!notification.isRead()) {
                count++;
            }
        }
        return count;
    }

    public synchronized int getRatingCount() {
        return ratingCount;
    }

    public synchronized List<SellerReview> getReviews() {
        return List.copyOf(reviews);
    }

    public synchronized double getAverageRating() {
        return ratingCount == 0 ? 0D : (double) ratingTotal / ratingCount;
    }

    synchronized void updateUsername(String value) {
        username = MarketLimits.bounded(value, MarketLimits.MAX_PLAYER_NAME_LENGTH);
    }

    synchronized void addNotification(MarketNotification notification) {
        Objects.requireNonNull(notification, "notification");
        notifications.removeIf(value -> value.getId().equals(notification.getId()));
        notifications.addFirst(notification);
        trimLast(notifications, MarketLimits.MAX_NOTIFICATIONS_PER_USER);
    }

    synchronized boolean markNotificationRead(UUID notificationId, long timestamp) {
        if (notificationId == null) {
            return false;
        }
        ArrayList<MarketNotification> replaced = new ArrayList<>(notifications.size());
        boolean changed = false;
        for (MarketNotification notification : notifications) {
            if (notification.getId().equals(notificationId) && !notification.isRead()) {
                replaced.add(notification.markRead(timestamp));
                changed = true;
            } else {
                replaced.add(notification);
            }
        }
        if (changed) {
            notifications.clear();
            notifications.addAll(replaced);
        }
        return changed;
    }

    synchronized boolean markAllNotificationsRead(long timestamp) {
        ArrayList<MarketNotification> replaced = new ArrayList<>(notifications.size());
        boolean changed = false;
        for (MarketNotification notification : notifications) {
            MarketNotification value = notification.isRead() ? notification : notification.markRead(timestamp);
            changed |= value != notification;
            replaced.add(value);
        }
        if (changed) {
            notifications.clear();
            notifications.addAll(replaced);
        }
        return changed;
    }

    synchronized boolean deleteNotification(UUID notificationId) {
        return notificationId != null && notifications.removeIf(value -> value.getId().equals(notificationId));
    }

    synchronized void recordView(UUID listingId) {
        addRecent(viewHistory, listingId);
    }

    synchronized void recordPurchase(UUID listingId) {
        addRecent(purchaseHistory, listingId);
    }

    synchronized boolean removePurchase(UUID listingId) {
        return listingId != null && purchaseHistory.removeIf(listingId::equals);
    }

    synchronized void recordListing(UUID listingId) {
        addRecent(listingHistory, listingId);
    }

    synchronized boolean removeListing(UUID listingId) {
        return listingId != null && listingHistory.removeIf(listingId::equals);
    }

    synchronized void addRating(int stars) {
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("stars");
        }
        if (ratingCount == Integer.MAX_VALUE || ratingTotal > Long.MAX_VALUE - stars) {
            throw new IllegalStateException("rating limit");
        }
        ratingTotal += stars;
        ratingCount++;
    }

    synchronized void addReview(SellerReview review) {
        Objects.requireNonNull(review, "review");
        reviews.removeIf(value -> value.getId().equals(review.getId()));
        reviews.addFirst(review);
        trimLast(reviews, MarketLimits.MAX_REVIEWS_PER_USER);
    }

    synchronized void addPendingReviewPrompt(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        pendingReviewPrompts.remove(listingId);
        pendingReviewPrompts.addLast(listingId);
        trimLast(pendingReviewPrompts, 16);
    }

    synchronized UUID pollPendingReviewPrompt() {
        return pendingReviewPrompts.pollFirst();
    }

    synchronized boolean canRecordReview(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        return reviewedListings.contains(listingId) || reviewedListings.size() < MarketLimits.MAX_REVIEWED_LISTINGS_PER_USER;
    }

    synchronized boolean recordReview(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        if (reviewedListings.contains(listingId)) {
            return false;
        }
        if (reviewedListings.size() >= MarketLimits.MAX_REVIEWED_LISTINGS_PER_USER) {
            throw new IllegalStateException("review history limit");
        }
        reviewedListings.add(listingId);
        return true;
    }

    synchronized void putSavedSearch(SavedMarketSearch search) {
        Objects.requireNonNull(search, "search");
        if (!savedSearches.containsKey(search.getId()) && savedSearches.size() >= MarketLimits.MAX_SAVED_SEARCHES_PER_USER) {
            throw new IllegalStateException("saved search limit");
        }
        savedSearches.put(search.getId(), search);
    }

    synchronized boolean removeSavedSearch(UUID searchId) {
        return searchId != null && savedSearches.remove(searchId) != null;
    }

    synchronized boolean setUserBlocked(UUID targetUserId, boolean blocked) {
        Objects.requireNonNull(targetUserId, "targetUserId");
        if (userId.equals(targetUserId)) {
            throw new IllegalArgumentException("targetUserId");
        }
        if (blocked) {
            if (!blockedUsers.contains(targetUserId) && blockedUsers.size() >= MarketLimits.MAX_BLOCKED_USERS_PER_USER) {
                throw new IllegalStateException("blocked user limit");
            }
            return blockedUsers.add(targetUserId);
        }
        return blockedUsers.remove(targetUserId);
    }

    public synchronized MarketUserData copy() {
        return new MarketUserData(userId, username, notifications, viewHistory, purchaseHistory, listingHistory, reviewedListings, savedSearches.values(), blockedUsers, reviews, pendingReviewPrompts, ratingTotal, ratingCount);
    }

    public synchronized CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UserId", userId);
        tag.putString("Username", username);
        tag.putLong("RatingTotal", ratingTotal);
        tag.putInt("RatingCount", ratingCount);
        ListTag notificationList = new ListTag();
        notifications.forEach(value -> notificationList.add(value.toTag()));
        tag.put("Notifications", notificationList);
        tag.put("Views", uuidList(viewHistory));
        tag.put("Purchases", uuidList(purchaseHistory));
        tag.put("Listings", uuidList(listingHistory));
        tag.put("ReviewedListings", uuidList(reviewedListings));
        ListTag savedSearchList = new ListTag();
        savedSearches.values().forEach(value -> savedSearchList.add(value.toTag()));
        tag.put("SavedSearches", savedSearchList);
        tag.put("BlockedUsers", uuidList(blockedUsers));
        ListTag reviewList = new ListTag();
        reviews.forEach(value -> reviewList.add(value.toTag()));
        tag.put("Reviews", reviewList);
        tag.put("PendingReviewPrompts", uuidList(pendingReviewPrompts));
        return tag;
    }

    public static MarketUserData fromTag(CompoundTag tag) {
        if (!tag.hasUUID("UserId")) {
            throw new IllegalArgumentException("userId");
        }
        ArrayList<MarketNotification> notifications = new ArrayList<>();
        ListTag storedNotifications = tag.getList("Notifications", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(storedNotifications.size(), MarketLimits.MAX_NOTIFICATIONS_PER_USER); i++) {
            try {
                notifications.add(MarketNotification.fromTag(storedNotifications.getCompound(i)));
            } catch (RuntimeException ignored) {
            }
        }
        List<UUID> views = readUuidList(tag.getList("Views", Tag.TAG_COMPOUND), MarketLimits.MAX_HISTORY_PER_USER);
        List<UUID> purchases = readUuidList(tag.getList("Purchases", Tag.TAG_COMPOUND), MarketLimits.MAX_HISTORY_PER_USER);
        List<UUID> listings = readUuidList(tag.getList("Listings", Tag.TAG_COMPOUND), MarketLimits.MAX_HISTORY_PER_USER);
        List<UUID> reviewedListings = readUuidList(tag.getList("ReviewedListings", Tag.TAG_COMPOUND), MarketLimits.MAX_REVIEWED_LISTINGS_PER_USER);
        ArrayList<SavedMarketSearch> savedSearches = new ArrayList<>();
        ListTag storedSearches = tag.getList("SavedSearches", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(storedSearches.size(), MarketLimits.MAX_SAVED_SEARCHES_PER_USER); i++) {
            try {
                savedSearches.add(SavedMarketSearch.fromTag(storedSearches.getCompound(i)));
            } catch (RuntimeException ignored) {
            }
        }
        List<UUID> blockedUsers = readUuidList(tag.getList("BlockedUsers", Tag.TAG_COMPOUND), MarketLimits.MAX_BLOCKED_USERS_PER_USER);
        ArrayList<SellerReview> reviews = new ArrayList<>();
        ListTag storedReviews = tag.getList("Reviews", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(storedReviews.size(), MarketLimits.MAX_REVIEWS_PER_USER); i++) {
            try {
                reviews.add(SellerReview.fromTag(storedReviews.getCompound(i)));
            } catch (RuntimeException ignored) {
            }
        }
        List<UUID> pendingReviewPrompts = readUuidList(tag.getList("PendingReviewPrompts", Tag.TAG_COMPOUND), 16);
        long ratingTotal = tag.getLong("RatingTotal");
        int ratingCount = tag.getInt("RatingCount");
        return new MarketUserData(tag.getUUID("UserId"), tag.getString("Username"), notifications, views, purchases, listings, reviewedListings, savedSearches, blockedUsers, reviews, pendingReviewPrompts, ratingTotal, ratingCount);
    }

    private static ArrayDeque<SellerReview> boundedReviewDeque(Iterable<SellerReview> values) {
        ArrayDeque<SellerReview> result = new ArrayDeque<>();
        if (values != null) {
            for (SellerReview value : values) {
                if (value != null && result.size() < MarketLimits.MAX_REVIEWS_PER_USER) {
                    result.addLast(value);
                }
            }
        }
        return result;
    }

    private static ArrayDeque<MarketNotification> boundedNotificationDeque(Iterable<MarketNotification> values) {
        ArrayDeque<MarketNotification> result = new ArrayDeque<>();
        if (values != null) {
            for (MarketNotification value : values) {
                if (value != null && result.size() < MarketLimits.MAX_NOTIFICATIONS_PER_USER) {
                    result.addLast(value);
                }
            }
        }
        return result;
    }

    private static ArrayDeque<UUID> boundedUuidDeque(Iterable<UUID> values) {
        ArrayDeque<UUID> result = new ArrayDeque<>();
        if (values != null) {
            for (UUID value : values) {
                if (value != null && !result.contains(value) && result.size() < MarketLimits.MAX_HISTORY_PER_USER) {
                    result.addLast(value);
                }
            }
        }
        return result;
    }

    private static LinkedHashMap<UUID, SavedMarketSearch> boundedSavedSearchMap(Iterable<SavedMarketSearch> values) {
        LinkedHashMap<UUID, SavedMarketSearch> result = new LinkedHashMap<>();
        if (values != null) {
            for (SavedMarketSearch value : values) {
                if (value != null && result.size() < MarketLimits.MAX_SAVED_SEARCHES_PER_USER) {
                    result.putIfAbsent(value.getId(), value);
                }
            }
        }
        return result;
    }

    private static LinkedHashSet<UUID> boundedReviewedListingSet(Iterable<UUID> values) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        if (values != null) {
            for (UUID value : values) {
                if (value != null && result.size() < MarketLimits.MAX_REVIEWED_LISTINGS_PER_USER) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static LinkedHashSet<UUID> boundedUuidSet(Iterable<UUID> values, UUID excluded) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        if (values != null) {
            for (UUID value : values) {
                if (value != null && !value.equals(excluded) && result.size() < MarketLimits.MAX_BLOCKED_USERS_PER_USER) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static void addRecent(ArrayDeque<UUID> values, UUID value) {
        Objects.requireNonNull(value, "listingId");
        values.remove(value);
        values.addFirst(value);
        trimLast(values, MarketLimits.MAX_HISTORY_PER_USER);
    }

    private static <T> void trimLast(Deque<T> values, int maximumSize) {
        while (values.size() > maximumSize) {
            values.removeLast();
        }
    }

    private static ListTag uuidList(Iterable<UUID> values) {
        ListTag list = new ListTag();
        for (UUID value : values) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", value);
            list.add(entry);
        }
        return list;
    }

    private static List<UUID> readUuidList(ListTag list, int limit) {
        ArrayList<UUID> values = new ArrayList<>();
        for (int i = 0; i < Math.min(list.size(), limit); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("Id")) {
                UUID value = entry.getUUID("Id");
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }
}
