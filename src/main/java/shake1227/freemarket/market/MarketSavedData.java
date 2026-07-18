package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class MarketSavedData extends SavedData {
    public record AuctionSchedule(UUID listingId, long endsAt) {
    }

    public static final String DATA_NAME = "freemarket_market";
    public static final int SCHEMA_VERSION = 4;
    private static final long MAINTENANCE_INTERVAL_MILLIS = 5L * 60L * 1_000L;

    public record SavedSearchMatch(UUID userId, String username, SavedMarketSearch search) {
        public SavedSearchMatch {
            Objects.requireNonNull(userId, "userId");
            username = MarketLimits.bounded(username, MarketLimits.MAX_PLAYER_NAME_LENGTH);
            Objects.requireNonNull(search, "search");
        }
    }

    public record OfferDecision(Listing listing, MarketOffer offer, List<MarketOffer> automaticallyRejected) {
        public OfferDecision {
            Objects.requireNonNull(listing, "listing");
            Objects.requireNonNull(offer, "offer");
            automaticallyRejected = List.copyOf(automaticallyRejected);
        }
    }

    private record ReportKey(UUID listingId, UUID reporterId) {
    }

    private record OfferKey(UUID listingId, UUID offerId) {
    }

    private final LinkedHashMap<UUID, Listing> listings = new LinkedHashMap<>();
    private final LinkedHashMap<String, MarketTag> tags = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, MarketUserData> users = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, MarketReport> reports = new LinkedHashMap<>();
    private final HashMap<UUID, LinkedHashSet<UUID>> blockedByTarget = new HashMap<>();
    private final LinkedHashSet<UUID> savedSearchOwners = new LinkedHashSet<>();
    private final HashMap<ReportKey, UUID> pendingReportIndex = new HashMap<>();
    private final HashMap<UUID, Integer> pendingReportsByUser = new HashMap<>();
    private final HashMap<UUID, Integer> pendingReportsByListing = new HashMap<>();
    private final HashSet<OfferKey> pendingOfferIndex = new HashSet<>();
    private final HashMap<UUID, LinkedHashSet<OfferKey>> pendingOffersByRequester = new HashMap<>();
    private final ListTag quarantinedListings = new ListTag();
    private FeeConfig feeConfig = FeeConfig.none();
    private long revision;
    private long nextMaintenanceAt;
    private boolean listingWritesSuspended;
    private String listingWritesSuspensionReason = "";

    public MarketSavedData() {
        DefaultMarketTags.create().forEach(tag -> tags.put(tag.getId(), tag));
    }

    public static MarketSavedData get(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return get(level.getServer());
    }

    public static MarketSavedData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        MarketSavedData data = server.overworld().getDataStorage().computeIfAbsent(MarketSavedData::load, MarketSavedData::new, DATA_NAME);
        data.performMaintenance(server, System.currentTimeMillis());
        return data;
    }

    public static void saveNow(MinecraftServer server) {
        MarketSavedData data = get(server);
        data.saveAtomic(server.getWorldPath(new LevelResource("data")).resolve(DATA_NAME + ".dat"));
    }

    public synchronized long getRevision() {
        return revision;
    }

    public synchronized int getListingCount() {
        return listings.size();
    }

    public synchronized boolean areListingWritesSuspended() {
        return listingWritesSuspended;
    }

    public synchronized String getListingWritesSuspensionReason() {
        return listingWritesSuspensionReason;
    }

    public synchronized int getQuarantinedListingCount() {
        return quarantinedListings.size();
    }

    public synchronized Optional<Listing> getListing(UUID listingId) {
        Listing listing = listings.get(listingId);
        return listing == null ? Optional.empty() : Optional.of(listing.copy());
    }

    public synchronized List<Listing> getListings() {
        return listings.values().stream().map(Listing::copy).toList();
    }

    public synchronized long countListings(UUID sellerId, Set<Listing.Status> statuses) {
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(statuses, "statuses");
        return listings.values().stream().filter(listing -> listing.getSellerId().equals(sellerId) && statuses.contains(listing.getStatus())).count();
    }

    public synchronized List<AuctionSchedule> getActiveAuctionSchedules() {
        return listings.values().stream()
            .filter(listing -> listing.getSaleType() == Listing.SaleType.AUCTION && (listing.getStatus() == Listing.Status.ACTIVE || listing.getStatus() == Listing.Status.PAUSED))
            .map(listing -> new AuctionSchedule(listing.getId(), listing.getAuctionEndAt()))
            .toList();
    }

    public synchronized List<Listing> getListings(Collection<UUID> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return List.of();
        }
        ArrayList<Listing> result = new ArrayList<>();
        for (UUID listingId : listingIds) {
            Listing listing = listings.get(listingId);
            if (listing != null) {
                result.add(listing.copy());
            }
            if (result.size() >= MarketLimits.MAX_HISTORY_PER_USER) {
                break;
            }
        }
        return List.copyOf(result);
    }

    public synchronized boolean putListing(Listing listing) {
        ensureListingWritesAllowed();
        Objects.requireNonNull(listing, "listing");
        Listing copy = listing.copy();
        UUID id = copy.getId();
        if (listings.containsKey(id)) {
            return false;
        }
        if (listings.size() >= MarketLimits.MAX_LISTINGS) {
            throw new IllegalStateException("listing limit");
        }
        if (copy.getStatus() != Listing.Status.ACTIVE || copy.getBuyerId().isPresent()) {
            throw new IllegalArgumentException("new listing status");
        }
        validateListingTags(copy, List.of());
        MarketUserData seller = getOrCreateUserInternal(copy.getSellerId(), copy.getSellerName());
        listings.put(id, copy);
        indexListingOffers(copy, System.currentTimeMillis());
        seller.recordListing(id);
        changed();
        return true;
    }

    public synchronized boolean rollbackUnpersistedListing(UUID listingId) {
        Listing removed = listings.remove(listingId);
        if (removed == null) {
            return false;
        }
        deindexListingOffers(removed);
        MarketUserData seller = users.get(removed.getSellerId());
        if (seller != null) {
            seller.removeListing(listingId);
        }
        changed();
        return true;
    }

    public synchronized boolean updateListing(UUID listingId, Consumer<Listing> update) {
        ensureListingWritesAllowed();
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(update, "update");
        Listing current = listings.get(listingId);
        if (current == null) {
            return false;
        }
        Listing working = current.copy();
        update.accept(working);
        if (!working.getId().equals(listingId)) {
            throw new IllegalStateException("listing id changed");
        }
        validateListingTags(working, current.getTags());
        replaceListing(current, working.copy(), System.currentTimeMillis());
        changed();
        return true;
    }

    public synchronized boolean adminRemoveListing(UUID listingId, long timestamp) {
        return updateListing(listingId, listing -> listing.remove(timestamp));
    }

    public synchronized boolean setListingLiked(UUID listingId, UUID playerId, boolean liked, long timestamp) {
        ensureListingWritesAllowed();
        Listing current = listings.get(listingId);
        if (current == null) {
            return false;
        }
        if (isInteractionBlockedInternal(playerId, current.getSellerId())) {
            throw new IllegalStateException("interaction blocked");
        }
        Listing working = current.copy();
        boolean changedLike = working.setLiked(playerId, liked, timestamp);
        if (!changedLike) {
            return false;
        }
        listings.put(listingId, working);
        changed();
        return true;
    }

    public synchronized Optional<MarketComment> addComment(UUID listingId, UUID authorId, String authorName, String message, long timestamp) {
        ensureListingWritesAllowed();
        Listing current = listings.get(listingId);
        if (current == null) {
            return Optional.empty();
        }
        if (isInteractionBlockedInternal(authorId, current.getSellerId())) {
            throw new IllegalStateException("interaction blocked");
        }
        Listing working = current.copy();
        MarketComment comment = working.addComment(authorId, authorName, message, timestamp);
        listings.put(listingId, working);
        changed();
        return Optional.of(comment);
    }

    public synchronized MarketOffer submitOffer(UUID listingId, UUID requesterId, String requesterName, double amount, long timestamp) {
        ensureListingWritesAllowed();
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(requesterId, "requesterId");
        expireOffersForRequester(requesterId, timestamp);
        Listing current = listings.get(listingId);
        if (current == null || current.getSaleType() != Listing.SaleType.FIXED_PRICE || current.getStatus() != Listing.Status.ACTIVE) {
            throw new IllegalStateException("offer unavailable");
        }
        if (isInteractionBlockedInternal(requesterId, current.getSellerId())) {
            throw new IllegalStateException("interaction blocked");
        }
        if (pendingOffersByRequester.getOrDefault(requesterId, new LinkedHashSet<>()).size() >= MarketLimits.MAX_PENDING_OFFERS_PER_USER) {
            throw new IllegalStateException("requester offer limit");
        }
        if (amount != Math.rint(amount)) {
            throw new IllegalArgumentException("offer amount");
        }
        Listing working = current.copy();
        MarketOffer offer = working.createOffer(requesterId, requesterName, amount, timestamp);
        replaceListing(current, working, timestamp);
        changed();
        return offer;
    }

    public synchronized Optional<OfferDecision> respondOffer(UUID listingId, UUID offerId, UUID sellerId, boolean accepted, long timestamp) {
        ensureListingWritesAllowed();
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(sellerId, "sellerId");
        Listing current = listings.get(listingId);
        if (current == null) {
            return Optional.empty();
        }
        if (!current.getSellerId().equals(sellerId)) {
            throw new IllegalStateException("offer owner");
        }
        Listing working = current.copy();
        if (!working.expireOffers(timestamp).isEmpty()) {
            replaceListing(current, working, timestamp);
            changed();
            current = working;
            working = current.copy();
        }
        MarketOffer target = working.getOffer(offerId).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        if (accepted && isInteractionBlockedInternal(sellerId, target.getRequesterId())) {
            throw new IllegalStateException("interaction blocked");
        }
        Listing.OfferResolution resolution = working.respondOffer(offerId, accepted, timestamp);
        replaceListing(current, working, timestamp);
        changed();
        return Optional.of(new OfferDecision(working.copy(), resolution.offer(), resolution.automaticallyRejected()));
    }

    public synchronized List<MarketOffer> expireOffers(UUID listingId, long timestamp) {
        Listing current = listings.get(Objects.requireNonNull(listingId, "listingId"));
        if (current == null) {
            return List.of();
        }
        Listing working = current.copy();
        List<MarketOffer> expired = working.expireOffers(timestamp);
        if (!expired.isEmpty()) {
            replaceListing(current, working, timestamp);
            changed();
        }
        return expired;
    }

    public synchronized Optional<BidRecord> placeBid(UUID listingId, UUID bidderId, String bidderName, double amount, long timestamp) {
        ensureListingWritesAllowed();
        Listing current = listings.get(listingId);
        if (current == null) {
            return Optional.empty();
        }
        if (isInteractionBlockedInternal(bidderId, current.getSellerId())) {
            throw new IllegalStateException("interaction blocked");
        }
        Listing working = current.copy();
        BidRecord bid = working.placeBid(bidderId, bidderName, amount, timestamp);
        listings.put(listingId, working);
        changed();
        return Optional.of(bid);
    }

    public synchronized boolean completeSale(UUID listingId, UUID buyerId, String buyerName, double paidPrice, long timestamp) {
        ensureListingWritesAllowed();
        Listing current = listings.get(listingId);
        if (current == null) {
            return false;
        }
        if (current.getSaleType() == Listing.SaleType.FIXED_PRICE && isInteractionBlockedInternal(buyerId, current.getSellerId())) {
            throw new IllegalStateException("interaction blocked");
        }
        Listing working = current.copy();
        working.completeSale(buyerId, buyerName, paidPrice, timestamp);
        MarketUserData buyer = getOrCreateUserInternal(buyerId, buyerName);
        replaceListing(current, working, timestamp);
        buyer.recordPurchase(listingId);
        changed();
        return true;
    }

    public MarketSearchResult search(MarketQuery query) {
        return search(query, System.currentTimeMillis());
    }

    public MarketSearchResult search(MarketQuery query, long timestamp) {
        return searchInternal(query, timestamp, Set.of());
    }

    public MarketSearchResult searchForUser(UUID viewerId, MarketQuery query) {
        return searchForUser(viewerId, query, System.currentTimeMillis());
    }

    public MarketSearchResult searchForUser(UUID viewerId, MarketQuery query, long timestamp) {
        Objects.requireNonNull(viewerId, "viewerId");
        HashSet<UUID> excludedSellers = new HashSet<>();
        synchronized (this) {
            MarketUserData viewer = users.get(viewerId);
            if (viewer != null) {
                excludedSellers.addAll(viewer.getBlockedUsers());
            }
            Set<UUID> blockers = blockedByTarget.get(viewerId);
            if (blockers != null) {
                excludedSellers.addAll(blockers);
            }
        }
        return searchInternal(query, timestamp, excludedSellers);
    }

    private MarketSearchResult searchInternal(MarketQuery query, long timestamp, Set<UUID> excludedSellers) {
        Objects.requireNonNull(query, "query");
        List<Listing> snapshot;
        long snapshotRevision;
        synchronized (this) {
            snapshot = List.copyOf(listings.values());
            snapshotRevision = revision;
        }
        ArrayList<Listing> matches = new ArrayList<>();
        for (Listing listing : snapshot) {
            if (!excludedSellers.contains(listing.getSellerId()) && matchesListing(listing, query, timestamp)) {
                matches.add(listing);
            }
        }
        matches.sort(comparator(query.getSortOrder()));
        int total = matches.size();
        long rawStart = (long) query.getPage() * query.getPageSize();
        if (rawStart >= total) {
            return new MarketSearchResult(List.of(), total, query.getPage(), query.getPageSize(), snapshotRevision);
        }
        int start = (int) rawStart;
        int end = Math.min(total, start + query.getPageSize());
        return new MarketSearchResult(matches.subList(start, end), total, query.getPage(), query.getPageSize(), snapshotRevision);
    }

    public synchronized List<MarketTag> getTags() {
        return tags.values().stream().sorted(Comparator.comparingInt(MarketTag::getSortOrder).thenComparing(MarketTag::getId)).toList();
    }

    public synchronized Optional<MarketTag> getTag(String id) {
        return Optional.ofNullable(tags.get(MarketTag.normalizeId(id)));
    }

    public synchronized void upsertTag(MarketTag tag) {
        Objects.requireNonNull(tag, "tag");
        if (!tags.containsKey(tag.getId()) && tags.size() >= MarketLimits.MAX_TAG_DEFINITIONS) {
            throw new IllegalStateException("tag limit");
        }
        validateParent(tag);
        tags.put(tag.getId(), tag);
        changed();
    }

    public synchronized boolean removeTag(String id) {
        String normalized = MarketTag.normalizeId(id);
        MarketTag removed = tags.remove(normalized);
        if (removed == null) {
            return false;
        }
        ArrayList<MarketTag> children = new ArrayList<>();
        for (MarketTag tag : tags.values()) {
            if (tag.getParentId().equals(normalized)) {
                children.add(new MarketTag(tag.getId(), tag.getTranslationKey(), tag.getFallbackLabel(), "", tag.getSortOrder(), tag.isEnabled()));
            }
        }
        children.forEach(tag -> tags.put(tag.getId(), tag));
        changed();
        return true;
    }

    public synchronized void resetDefaultTags() {
        tags.clear();
        DefaultMarketTags.create().forEach(tag -> tags.put(tag.getId(), tag));
        changed();
    }

    public synchronized FeeConfig getFeeConfig() {
        return feeConfig;
    }

    public synchronized void setFeeConfig(FeeConfig value) {
        FeeConfig checked = Objects.requireNonNull(value, "feeConfig");
        if (!feeConfig.equals(checked)) {
            feeConfig = checked;
            changed();
        }
    }

    public synchronized Optional<MarketUserData> getUser(UUID userId) {
        MarketUserData user = users.get(userId);
        return user == null ? Optional.empty() : Optional.of(user.copy());
    }

    public synchronized List<MarketUserData> getUsers() {
        return users.values().stream().map(MarketUserData::copy).toList();
    }

    public synchronized int getUnreadNotificationCount(UUID userId) {
        MarketUserData user = users.get(userId);
        return user == null ? 0 : user.getUnreadNotificationCount();
    }

    public synchronized MarketUserData touchUser(UUID userId, String username) {
        MarketUserData user = getOrCreateUserInternal(userId, username);
        user.updateUsername(username);
        changed();
        return user.copy();
    }

    public synchronized void addNotification(UUID userId, String username, MarketNotification notification) {
        getOrCreateUserInternal(userId, username).addNotification(notification);
        changed();
    }

    public synchronized boolean markNotificationRead(UUID userId, UUID notificationId, long timestamp) {
        MarketUserData user = users.get(userId);
        if (user == null || !user.markNotificationRead(notificationId, timestamp)) {
            return false;
        }
        changed();
        return true;
    }

    public synchronized boolean markAllNotificationsRead(UUID userId, long timestamp) {
        MarketUserData user = users.get(userId);
        if (user == null || !user.markAllNotificationsRead(timestamp)) {
            return false;
        }
        changed();
        return true;
    }

    public synchronized boolean deleteNotification(UUID userId, UUID notificationId) {
        MarketUserData user = users.get(userId);
        if (user == null || !user.deleteNotification(notificationId)) {
            return false;
        }
        changed();
        return true;
    }

    public synchronized void recordView(UUID userId, String username, UUID listingId) {
        getOrCreateUserInternal(userId, username).recordView(requireListing(listingId));
        changed();
    }

    public synchronized void recordPurchase(UUID userId, String username, UUID listingId) {
        UUID checkedId = requireListing(listingId);
        Listing listing = listings.get(checkedId);
        if (listing.getBuyerId().isEmpty() || !listing.getBuyerId().get().equals(userId)) {
            throw new IllegalArgumentException("buyerId");
        }
        getOrCreateUserInternal(userId, username).recordPurchase(checkedId);
        changed();
    }

    public synchronized void removePurchase(UUID userId, UUID listingId) {
        MarketUserData user = users.get(userId);
        if (user != null && user.removePurchase(listingId)) {
            changed();
        }
    }

    public synchronized void recordListing(UUID userId, String username, UUID listingId) {
        UUID checkedId = requireListing(listingId);
        if (!listings.get(checkedId).getSellerId().equals(userId)) {
            throw new IllegalArgumentException("sellerId");
        }
        getOrCreateUserInternal(userId, username).recordListing(checkedId);
        changed();
    }

    public synchronized void addRating(UUID userId, String username, int stars) {
        getOrCreateUserInternal(userId, username).addRating(stars);
        changed();
    }

    public synchronized void addPendingReviewPrompt(UUID userId, String username, UUID listingId) {
        getOrCreateUserInternal(userId, username).addPendingReviewPrompt(Objects.requireNonNull(listingId, "listingId"));
        changed();
    }

    public synchronized Optional<UUID> pollPendingReviewPrompt(UUID userId) {
        MarketUserData user = users.get(userId);
        if (user == null) {
            return Optional.empty();
        }
        UUID listingId = user.pollPendingReviewPrompt();
        if (listingId != null) {
            changed();
        }
        return Optional.ofNullable(listingId);
    }

    public synchronized boolean rateCompletedSale(UUID buyerId, String buyerName, UUID listingId, int stars) {
        return rateCompletedSale(buyerId, buyerName, listingId, stars, "");
    }

    public synchronized boolean rateCompletedSale(UUID buyerId, String buyerName, UUID listingId, int stars, String comment) {
        Objects.requireNonNull(buyerId, "buyerId");
        Listing listing = listings.get(Objects.requireNonNull(listingId, "listingId"));
        if (listing == null || listing.getStatus() != Listing.Status.SOLD || listing.getBuyerId().isEmpty() || !listing.getBuyerId().get().equals(buyerId)) {
            throw new IllegalArgumentException("completed sale");
        }
        if (stars < 1 || stars > 5) {
            throw new IllegalArgumentException("stars");
        }
        MarketUserData buyer = getOrCreateUserInternal(buyerId, buyerName);
        if (buyer.hasReviewedListing(listingId)) {
            return false;
        }
        if (!buyer.canRecordReview(listingId)) {
            throw new IllegalStateException("review history limit");
        }
        MarketUserData seller = getOrCreateUserInternal(listing.getSellerId(), listing.getSellerName());
        seller.addRating(stars);
        seller.addReview(new SellerReview(listingId, buyerId, buyerName, stars, comment == null ? "" : comment, System.currentTimeMillis()));
        buyer.recordReview(listingId);
        changed();
        return true;
    }

    public synchronized List<SavedMarketSearch> getSavedSearches(UUID userId) {
        MarketUserData user = users.get(userId);
        return user == null ? List.of() : user.getSavedSearches();
    }

    public synchronized Optional<SavedMarketSearch> getSavedSearch(UUID userId, UUID searchId) {
        MarketUserData user = users.get(userId);
        return user == null ? Optional.empty() : user.getSavedSearch(searchId);
    }

    public synchronized SavedMarketSearch saveSearch(UUID userId, String username, String name, MarketQuery query, boolean notificationsEnabled, long timestamp) {
        MarketUserData user = getOrCreateUserInternal(userId, username);
        SavedMarketSearch search = new SavedMarketSearch(name, query, notificationsEnabled, timestamp);
        user.putSavedSearch(search);
        savedSearchOwners.add(userId);
        changed();
        return search;
    }

    public synchronized Optional<SavedMarketSearch> updateSavedSearch(UUID userId, UUID searchId, String name, MarketQuery query, boolean notificationsEnabled, long timestamp) {
        MarketUserData user = users.get(userId);
        if (user == null) {
            return Optional.empty();
        }
        SavedMarketSearch current = user.getSavedSearch(searchId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        SavedMarketSearch updated = current.update(name, query, notificationsEnabled, timestamp);
        user.putSavedSearch(updated);
        changed();
        return Optional.of(updated);
    }

    public synchronized boolean deleteSavedSearch(UUID userId, UUID searchId) {
        MarketUserData user = users.get(userId);
        if (user == null || !user.removeSavedSearch(searchId)) {
            return false;
        }
        if (user.getSavedSearches().isEmpty()) {
            savedSearchOwners.remove(userId);
        }
        changed();
        return true;
    }

    public MarketSearchResult searchSavedSearch(UUID userId, UUID searchId, int page, int pageSize, long timestamp) {
        SavedMarketSearch savedSearch;
        synchronized (this) {
            MarketUserData user = users.get(userId);
            if (user == null) {
                throw new IllegalArgumentException("userId");
            }
            savedSearch = user.getSavedSearch(searchId).orElseThrow(() -> new IllegalArgumentException("searchId"));
        }
        return searchForUser(userId, savedSearch.queryPage(page, pageSize), timestamp);
    }

    public synchronized List<SavedSearchMatch> previewSavedSearchMatches(UUID listingId, long timestamp) {
        return savedSearchMatches(requireListing(listingId), timestamp, false);
    }

    public synchronized List<SavedSearchMatch> consumeSavedSearchMatches(UUID listingId, long timestamp) {
        return savedSearchMatches(requireListing(listingId), timestamp, true);
    }

    public synchronized Set<UUID> getBlockedUsers(UUID userId) {
        MarketUserData user = users.get(userId);
        return user == null ? Set.of() : user.getBlockedUsers();
    }

    public synchronized boolean setUserBlocked(UUID userId, String username, UUID targetUserId, boolean blocked) {
        MarketUserData user = getOrCreateUserInternal(userId, username);
        if (!user.setUserBlocked(targetUserId, blocked)) {
            return false;
        }
        if (blocked) {
            blockedByTarget.computeIfAbsent(targetUserId, ignored -> new LinkedHashSet<>()).add(userId);
        } else {
            LinkedHashSet<UUID> blockers = blockedByTarget.get(targetUserId);
            if (blockers != null) {
                blockers.remove(userId);
                if (blockers.isEmpty()) {
                    blockedByTarget.remove(targetUserId);
                }
            }
        }
        changed();
        return true;
    }

    public synchronized boolean isUserBlocked(UUID userId, UUID targetUserId) {
        MarketUserData user = users.get(userId);
        return user != null && user.isUserBlocked(targetUserId);
    }

    public synchronized boolean isInteractionBlocked(UUID firstUserId, UUID secondUserId) {
        Objects.requireNonNull(firstUserId, "firstUserId");
        Objects.requireNonNull(secondUserId, "secondUserId");
        if (firstUserId.equals(secondUserId)) {
            return false;
        }
        return isInteractionBlockedInternal(firstUserId, secondUserId);
    }

    public synchronized boolean canInteract(UUID firstUserId, UUID secondUserId) {
        return !isInteractionBlocked(firstUserId, secondUserId);
    }

    public synchronized boolean canInteractWithListing(UUID actorId, UUID listingId) {
        Listing listing = listings.get(Objects.requireNonNull(listingId, "listingId"));
        return listing != null && !isInteractionBlocked(Objects.requireNonNull(actorId, "actorId"), listing.getSellerId());
    }

    public synchronized Optional<MarketReport> getReport(UUID reportId) {
        return Optional.ofNullable(reports.get(reportId));
    }

    public synchronized int getPendingReportCount() {
        return pendingReportIndex.size();
    }

    public synchronized List<MarketReport> getReports(MarketReport.Status status, int offset, int limit) {
        int checkedOffset = Math.max(0, offset);
        int checkedLimit = Math.max(1, Math.min(MarketLimits.MAX_PAGE_SIZE, limit));
        return reports.values().stream()
                .filter(report -> status == null || report.getStatus() == status)
                .sorted(Comparator.comparingLong(MarketReport::getUpdatedAt).reversed().thenComparing(value -> value.getId().toString()))
                .skip(checkedOffset)
                .limit(checkedLimit)
                .toList();
    }

    public synchronized MarketReport submitReport(UUID listingId, UUID reporterId, String reporterName, MarketReport.Reason reason, String detail, long timestamp) {
        Listing listing = listings.get(Objects.requireNonNull(listingId, "listingId"));
        Objects.requireNonNull(reporterId, "reporterId");
        if (listing == null || !listing.isPurchasable(timestamp)) {
            throw new IllegalStateException("listing unavailable");
        }
        if (listing.getSellerId().equals(reporterId)) {
            throw new IllegalArgumentException("reporterId");
        }
        ReportKey key = new ReportKey(listingId, reporterId);
        if (pendingReportIndex.containsKey(key)) {
            throw new IllegalStateException("duplicate report");
        }
        if (pendingReportsByUser.getOrDefault(reporterId, 0) >= MarketLimits.MAX_PENDING_REPORTS_PER_USER) {
            throw new IllegalStateException("reporter report limit");
        }
        if (pendingReportsByListing.getOrDefault(listingId, 0) >= MarketLimits.MAX_PENDING_REPORTS_PER_LISTING) {
            throw new IllegalStateException("listing report limit");
        }
        pruneClosedReports(MarketLimits.MAX_REPORTS - 1);
        if (reports.size() >= MarketLimits.MAX_REPORTS) {
            throw new IllegalStateException("report limit");
        }
        MarketReport report = new MarketReport(listingId, reporterId, reporterName, reason, detail, timestamp);
        reports.put(report.getId(), report);
        addPendingReportIndex(report);
        changed();
        return report;
    }

    public synchronized Optional<MarketReport> reviewReport(UUID reportId, MarketReport.Status status, UUID reviewerId, String resolution, long timestamp) {
        MarketReport current = reports.get(reportId);
        if (current == null) {
            return Optional.empty();
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reviewerId, "reviewerId");
        MarketReport updated = current.review(status, reviewerId, resolution, timestamp);
        if (!current.isPending() && updated.isPending()) {
            ReportKey key = new ReportKey(updated.getListingId(), updated.getReporterId());
            UUID duplicate = pendingReportIndex.get(key);
            if (duplicate != null && !duplicate.equals(reportId)) {
                throw new IllegalStateException("duplicate report");
            }
            if (pendingReportsByUser.getOrDefault(updated.getReporterId(), 0) >= MarketLimits.MAX_PENDING_REPORTS_PER_USER
                    || pendingReportsByListing.getOrDefault(updated.getListingId(), 0) >= MarketLimits.MAX_PENDING_REPORTS_PER_LISTING) {
                throw new IllegalStateException("pending report limit");
            }
        }
        removePendingReportIndex(current);
        reports.put(reportId, updated);
        addPendingReportIndex(updated);
        changed();
        return Optional.of(updated);
    }

    public synchronized boolean deleteClosedReport(UUID reportId) {
        MarketReport report = reports.get(reportId);
        if (report == null || report.isPending()) {
            return false;
        }
        reports.remove(reportId);
        changed();
        return true;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putLong("Revision", revision);
        tag.putBoolean("ListingWritesSuspended", listingWritesSuspended);
        tag.putString("ListingWritesSuspensionReason", listingWritesSuspensionReason);
        tag.put("QuarantinedListings", quarantinedListings.copy());
        tag.put("Fee", feeConfig.toTag());
        ListTag listingList = new ListTag();
        listings.values().forEach(value -> listingList.add(value.toTag()));
        tag.put("Listings", listingList);
        ListTag tagList = new ListTag();
        tags.values().forEach(value -> tagList.add(value.toTag()));
        tag.put("Tags", tagList);
        ListTag userList = new ListTag();
        users.values().forEach(value -> userList.add(value.toTag()));
        tag.put("Users", userList);
        ListTag reportList = new ListTag();
        reports.values().forEach(value -> reportList.add(value.toTag()));
        tag.put("Reports", reportList);
        return tag;
    }

    public static MarketSavedData load(CompoundTag tag) {
        MarketSavedData data = new MarketSavedData();
        int storedSchema = Math.max(0, tag.getInt("SchemaVersion"));
        boolean unsupportedSchema = storedSchema > SCHEMA_VERSION;
        data.revision = Math.max(0L, tag.getLong("Revision"));
        data.listingWritesSuspended = tag.getBoolean("ListingWritesSuspended");
        data.listingWritesSuspensionReason = MarketLimits.bounded(tag.getString("ListingWritesSuspensionReason"), MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH);
        if (unsupportedSchema) {
            data.suspendListingWrites("market data was written by an unsupported newer schema");
            FreeMarket.LOGGER.error("FreeMarket market data schema {} is newer than supported schema {}", storedSchema, SCHEMA_VERSION);
        }
        Tag quarantineTag = tag.get("QuarantinedListings");
        if (quarantineTag != null && (!(quarantineTag instanceof ListTag quarantineList) || (!quarantineList.isEmpty() && quarantineList.getElementType() != Tag.TAG_COMPOUND))) {
            CompoundTag malformed = new CompoundTag();
            malformed.put("OriginalQuarantine", quarantineTag.copy());
            data.quarantineListing(malformed, -1, new IllegalArgumentException("quarantine list type"));
        }
        if (tag.contains("QuarantinedListings", Tag.TAG_LIST)) {
            ListTag storedQuarantine = tag.getList("QuarantinedListings", Tag.TAG_COMPOUND);
            for (int index = 0; index < storedQuarantine.size(); index++) {
                data.quarantinedListings.add(storedQuarantine.getCompound(index).copy());
            }
            if (!data.quarantinedListings.isEmpty()) {
                data.suspendListingWrites("quarantined listing data requires operator recovery");
            }
        }
        if (tag.contains("Fee", Tag.TAG_COMPOUND)) {
            try {
                data.feeConfig = FeeConfig.fromTag(tag.getCompound("Fee"));
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.error("Unable to restore the FreeMarket fee configuration; the safe no-fee default is active", exception);
            }
        }
        data.listings.clear();
        Tag listingsTag = tag.get("Listings");
        if (listingsTag != null && (!(listingsTag instanceof ListTag listingsList) || (!listingsList.isEmpty() && listingsList.getElementType() != Tag.TAG_COMPOUND))) {
            CompoundTag malformed = new CompoundTag();
            malformed.put("OriginalListings", listingsTag.copy());
            data.quarantineListing(malformed, -1, new IllegalArgumentException("listing list type"));
        }
        ListTag listingList = tag.getList("Listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < listingList.size(); i++) {
            CompoundTag storedListing = listingList.getCompound(i);
            if (unsupportedSchema) {
                data.quarantineListing(storedListing, i, new IllegalStateException("unsupported market schema " + storedSchema));
                continue;
            }
            if (i >= MarketLimits.MAX_LISTINGS) {
                data.quarantineListing(storedListing, i, new IllegalStateException("listing capacity exceeded"));
                continue;
            }
            try {
                Listing listing = Listing.fromTag(storedListing);
                if (data.listings.putIfAbsent(listing.getId(), listing) != null) {
                    data.quarantineListing(storedListing, i, new IllegalStateException("duplicate listing id"));
                }
            } catch (RuntimeException exception) {
                data.quarantineListing(storedListing, i, exception);
            }
        }
        if (tag.contains("Tags", Tag.TAG_LIST)) {
            data.tags.clear();
            ListTag storedTags = tag.getList("Tags", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(storedTags.size(), MarketLimits.MAX_TAG_DEFINITIONS); i++) {
                try {
                    MarketTag marketTag = MarketTag.fromTag(storedTags.getCompound(i));
                    data.tags.putIfAbsent(marketTag.getId(), marketTag);
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to restore FreeMarket tag definition at index {}", i, exception);
                }
            }
        }
        data.users.clear();
        ListTag userList = tag.getList("Users", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(userList.size(), MarketLimits.MAX_USERS); i++) {
            try {
                MarketUserData user = MarketUserData.fromTag(userList.getCompound(i));
                data.users.putIfAbsent(user.getUserId(), user);
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.error("Unable to restore FreeMarket user data at index {}", i, exception);
            }
        }
        data.reports.clear();
        ListTag reportList = tag.getList("Reports", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(reportList.size(), MarketLimits.MAX_REPORTS); i++) {
            try {
                MarketReport report = MarketReport.fromTag(reportList.getCompound(i));
                data.reports.putIfAbsent(report.getId(), report);
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.error("Unable to restore FreeMarket report at index {}", i, exception);
            }
        }
        data.rebuildFeatureIndexes();
        if (data.listingWritesSuspended) {
            data.setDirty();
            FreeMarket.LOGGER.error("FreeMarket listing writes are suspended: {}. Quarantined records: {}", data.listingWritesSuspensionReason, data.quarantinedListings.size());
        }
        return data;
    }

    public synchronized int pruneTerminalListings(long timestamp, Collection<UUID> protectedListingIds) {
        if (listingWritesSuspended) {
            return 0;
        }
        long now = Math.max(0L, timestamp);
        long cutoff = now > MarketLimits.TERMINAL_LISTING_RETENTION_MILLIS ? now - MarketLimits.TERMINAL_LISTING_RETENTION_MILLIS : 0L;
        Set<UUID> protectedIds = protectedListingIds == null ? Set.of() : Set.copyOf(protectedListingIds);
        ArrayList<Listing> candidates = new ArrayList<>();
        int terminalCount = 0;
        for (Listing listing : listings.values()) {
            if (!isTerminal(listing.getStatus())) {
                continue;
            }
            terminalCount++;
            if (!protectedIds.contains(listing.getId()) && pendingReportsByListing.getOrDefault(listing.getId(), 0) == 0) {
                candidates.add(listing);
            }
        }
        candidates.sort(Comparator.comparingLong(Listing::getUpdatedAt).thenComparing(value -> value.getId().toString()));
        LinkedHashSet<UUID> removals = new LinkedHashSet<>();
        for (Listing listing : candidates) {
            if (listing.getUpdatedAt() <= cutoff) {
                removals.add(listing.getId());
            }
        }
        int retainedTerminalCount = terminalCount - removals.size();
        for (Listing listing : candidates) {
            if (retainedTerminalCount <= MarketLimits.MAX_RETAINED_TERMINAL_LISTINGS) {
                break;
            }
            if (removals.add(listing.getId())) {
                retainedTerminalCount--;
            }
        }
        removals.forEach(id -> {
            Listing removed = listings.remove(id);
            if (removed != null) {
                deindexListingOffers(removed);
            }
        });
        if (!removals.isEmpty()) {
            changed();
        }
        return removals.size();
    }

    private void performMaintenance(MinecraftServer server, long timestamp) {
        if (!claimMaintenance(timestamp)) {
            return;
        }
        HashSet<UUID> protectedIds = new HashSet<>();
        try {
            PendingDeliveries deliveries = PendingDeliveries.get(server);
            if (deliveries.isRecoveryUnsafe()) {
                suspendListingWrites("pending delivery data requires operator recovery");
                FreeMarket.LOGGER.error("FreeMarket listing writes were suspended because pending-delivery data is quarantined");
                return;
            }
            protectedIds.addAll(deliveries.pendingOperationIds());
            MarketTransactionLog transactionLog = MarketTransactionLog.get(server);
            AuctionEscrowLog escrowLog = AuctionEscrowLog.get(server);
            if (transactionLog.recoveryUnsafe() || escrowLog.recoveryUnsafe()) {
                suspendListingWrites("transaction recovery data requires operator recovery");
                FreeMarket.LOGGER.error("FreeMarket listing writes were suspended because transaction data is quarantined");
                return;
            }
            transactionLog.unresolved().forEach(entry -> protectedIds.add(entry.listingId()));
            escrowLog.unresolved().forEach(entry -> protectedIds.add(entry.listingId()));
        } catch (RuntimeException exception) {
            FreeMarket.LOGGER.error("FreeMarket listing retention was skipped because protection data could not be restored", exception);
            return;
        }
        int expiredOffers = expireDueOffers(timestamp);
        if (expiredOffers > 0) {
            FreeMarket.LOGGER.info("Expired {} FreeMarket price requests", expiredOffers);
        }
        int removed = pruneTerminalListings(timestamp, protectedIds);
        if (removed > 0) {
            FreeMarket.LOGGER.info("Pruned {} retained FreeMarket terminal listings", removed);
        }
    }

    private synchronized boolean claimMaintenance(long timestamp) {
        if (listingWritesSuspended) {
            return false;
        }
        long now = Math.max(0L, timestamp);
        if (now < nextMaintenanceAt && listings.size() < MarketLimits.MAX_LISTINGS) {
            return false;
        }
        nextMaintenanceAt = now > Long.MAX_VALUE - MAINTENANCE_INTERVAL_MILLIS ? Long.MAX_VALUE : now + MAINTENANCE_INTERVAL_MILLIS;
        return true;
    }

    private void quarantineListing(CompoundTag storedListing, int sourceIndex, RuntimeException exception) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("SourceIndex", sourceIndex);
        entry.putLong("QuarantinedAt", System.currentTimeMillis());
        entry.putString("Failure", MarketLimits.bounded(exception.getClass().getSimpleName() + ": " + Objects.toString(exception.getMessage(), ""), MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH));
        entry.put("Listing", storedListing.copy());
        quarantinedListings.add(entry);
        suspendListingWrites("one or more listing records failed validation during load");
        String id = storedListing.hasUUID("Id") ? storedListing.getUUID("Id").toString() : "unknown";
        FreeMarket.LOGGER.error("Quarantined FreeMarket listing {} from source index {}", id, sourceIndex, exception);
    }

    private synchronized void suspendListingWrites(String reason) {
        listingWritesSuspended = true;
        if (listingWritesSuspensionReason.isBlank()) {
            listingWritesSuspensionReason = MarketLimits.bounded(reason, MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH);
        }
        setDirty();
    }

    private synchronized void ensureListingWritesAllowed() {
        if (listingWritesSuspended) {
            throw new IllegalStateException("listing writes suspended: " + listingWritesSuspensionReason);
        }
    }

    private static boolean isTerminal(Listing.Status status) {
        return status == Listing.Status.SOLD || status == Listing.Status.CANCELLED || status == Listing.Status.REMOVED || status == Listing.Status.EXPIRED;
    }

    private synchronized MarketUserData getOrCreateUserInternal(UUID userId, String username) {
        Objects.requireNonNull(userId, "userId");
        MarketUserData existing = users.get(userId);
        if (existing != null) {
            if (username != null && !username.isBlank()) {
                existing.updateUsername(username);
            }
            return existing;
        }
        if (users.size() >= MarketLimits.MAX_USERS) {
            throw new IllegalStateException("user limit");
        }
        MarketUserData created = new MarketUserData(userId, username);
        users.put(userId, created);
        return created;
    }

    private synchronized UUID requireListing(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        if (!listings.containsKey(listingId)) {
            throw new IllegalArgumentException("listingId");
        }
        return listingId;
    }

    private synchronized List<SavedSearchMatch> savedSearchMatches(UUID listingId, long timestamp, boolean consume) {
        Listing listing = listings.get(listingId);
        if (listing == null) {
            return List.of();
        }
        ArrayList<SavedSearchMatch> matches = new ArrayList<>();
        for (UUID ownerId : savedSearchOwners) {
            if (ownerId.equals(listing.getSellerId()) || isInteractionBlockedInternal(ownerId, listing.getSellerId())) {
                continue;
            }
            MarketUserData user = users.get(ownerId);
            if (user == null) {
                continue;
            }
            for (SavedMarketSearch search : user.getSavedSearches()) {
                if (!search.matchesNewListing(listing, timestamp)) {
                    continue;
                }
                matches.add(new SavedSearchMatch(ownerId, user.getUsername(), search));
                if (consume) {
                    user.putSavedSearch(search.markNotified(listingId, timestamp));
                }
            }
        }
        if (consume && !matches.isEmpty()) {
            changed();
        }
        return List.copyOf(matches);
    }

    private synchronized boolean isInteractionBlockedInternal(UUID firstUserId, UUID secondUserId) {
        MarketUserData first = users.get(firstUserId);
        if (first != null && first.isUserBlocked(secondUserId)) {
            return true;
        }
        MarketUserData second = users.get(secondUserId);
        return second != null && second.isUserBlocked(firstUserId);
    }

    private synchronized void replaceListing(Listing current, Listing replacement, long timestamp) {
        deindexListingOffers(current);
        listings.put(replacement.getId(), replacement);
        indexListingOffers(replacement, timestamp);
    }

    private synchronized void indexListingOffers(Listing listing, long timestamp) {
        if (listing.getSaleType() != Listing.SaleType.FIXED_PRICE || (listing.getStatus() != Listing.Status.ACTIVE && listing.getStatus() != Listing.Status.PAUSED)) {
            return;
        }
        for (MarketOffer offer : listing.getOffers()) {
            if (!offer.isPending(timestamp)) {
                continue;
            }
            OfferKey key = new OfferKey(listing.getId(), offer.getId());
            if (pendingOfferIndex.add(key)) {
                pendingOffersByRequester.computeIfAbsent(offer.getRequesterId(), ignored -> new LinkedHashSet<>()).add(key);
            }
        }
    }

    private synchronized void deindexListingOffers(Listing listing) {
        if (listing == null) {
            return;
        }
        for (MarketOffer offer : listing.getOffers()) {
            OfferKey key = new OfferKey(listing.getId(), offer.getId());
            if (!pendingOfferIndex.remove(key)) {
                continue;
            }
            LinkedHashSet<OfferKey> requesterOffers = pendingOffersByRequester.get(offer.getRequesterId());
            if (requesterOffers != null) {
                requesterOffers.remove(key);
                if (requesterOffers.isEmpty()) {
                    pendingOffersByRequester.remove(offer.getRequesterId());
                }
            }
        }
    }

    private synchronized void expireOffersForRequester(UUID requesterId, long timestamp) {
        LinkedHashSet<OfferKey> indexed = pendingOffersByRequester.get(requesterId);
        if (indexed == null || indexed.isEmpty()) {
            return;
        }
        boolean modified = false;
        for (OfferKey key : List.copyOf(indexed)) {
            Listing current = listings.get(key.listingId());
            if (current == null) {
                pendingOfferIndex.remove(key);
                indexed.remove(key);
                continue;
            }
            Listing working = current.copy();
            if (!working.expireOffers(timestamp).isEmpty()) {
                replaceListing(current, working, timestamp);
                modified = true;
            }
        }
        if (indexed.isEmpty()) {
            pendingOffersByRequester.remove(requesterId);
        }
        if (modified) {
            changed();
        }
    }

    private synchronized int expireDueOffers(long timestamp) {
        LinkedHashSet<UUID> listingIds = new LinkedHashSet<>();
        pendingOfferIndex.forEach(key -> listingIds.add(key.listingId()));
        int expiredCount = 0;
        for (UUID listingId : listingIds) {
            Listing current = listings.get(listingId);
            if (current == null) {
                continue;
            }
            Listing working = current.copy();
            List<MarketOffer> expired = working.expireOffers(timestamp);
            if (!expired.isEmpty()) {
                replaceListing(current, working, timestamp);
                expiredCount += expired.size();
            }
        }
        if (expiredCount > 0) {
            changed();
        }
        return expiredCount;
    }

    private synchronized void rebuildFeatureIndexes() {
        blockedByTarget.clear();
        savedSearchOwners.clear();
        pendingOfferIndex.clear();
        pendingOffersByRequester.clear();
        long now = System.currentTimeMillis();
        boolean offersExpired = false;
        for (Listing listing : listings.values()) {
            if (!listing.expireOffers(now).isEmpty()) {
                offersExpired = true;
            }
            indexListingOffers(listing, now);
        }
        if (offersExpired) {
            setDirty();
        }
        for (MarketUserData user : users.values()) {
            if (!user.getSavedSearches().isEmpty()) {
                savedSearchOwners.add(user.getUserId());
            }
            for (UUID targetId : user.getBlockedUsers()) {
                blockedByTarget.computeIfAbsent(targetId, ignored -> new LinkedHashSet<>()).add(user.getUserId());
            }
        }
        pendingReportIndex.clear();
        pendingReportsByUser.clear();
        pendingReportsByListing.clear();
        ArrayList<UUID> invalidReports = new ArrayList<>();
        for (MarketReport report : reports.values()) {
            if (!report.isPending()) {
                continue;
            }
            ReportKey key = new ReportKey(report.getListingId(), report.getReporterId());
            if (pendingReportIndex.containsKey(key)
                    || pendingReportsByUser.getOrDefault(report.getReporterId(), 0) >= MarketLimits.MAX_PENDING_REPORTS_PER_USER
                    || pendingReportsByListing.getOrDefault(report.getListingId(), 0) >= MarketLimits.MAX_PENDING_REPORTS_PER_LISTING) {
                invalidReports.add(report.getId());
            } else {
                addPendingReportIndex(report);
            }
        }
        invalidReports.forEach(reports::remove);
    }

    private synchronized void addPendingReportIndex(MarketReport report) {
        if (!report.isPending()) {
            return;
        }
        pendingReportIndex.put(new ReportKey(report.getListingId(), report.getReporterId()), report.getId());
        pendingReportsByUser.merge(report.getReporterId(), 1, Integer::sum);
        pendingReportsByListing.merge(report.getListingId(), 1, Integer::sum);
    }

    private synchronized void removePendingReportIndex(MarketReport report) {
        if (!report.isPending()) {
            return;
        }
        ReportKey key = new ReportKey(report.getListingId(), report.getReporterId());
        if (!report.getId().equals(pendingReportIndex.get(key))) {
            return;
        }
        pendingReportIndex.remove(key);
        decrement(pendingReportsByUser, report.getReporterId());
        decrement(pendingReportsByListing, report.getListingId());
    }

    private synchronized void pruneClosedReports(int maximumSize) {
        if (reports.size() <= maximumSize) {
            return;
        }
        ArrayList<UUID> removals = new ArrayList<>();
        for (MarketReport report : reports.values()) {
            if (!report.isPending()) {
                removals.add(report.getId());
                if (reports.size() - removals.size() <= maximumSize) {
                    break;
                }
            }
        }
        removals.forEach(reports::remove);
    }

    private static void decrement(HashMap<UUID, Integer> counts, UUID id) {
        int next = counts.getOrDefault(id, 0) - 1;
        if (next <= 0) {
            counts.remove(id);
        } else {
            counts.put(id, next);
        }
    }

    private synchronized void validateParent(MarketTag candidate) {
        String parent = candidate.getParentId();
        int visited = 0;
        while (!parent.isEmpty()) {
            if (parent.equals(candidate.getId())) {
                throw new IllegalArgumentException("tag cycle");
            }
            MarketTag parentTag = tags.get(parent);
            if (parentTag == null) {
                throw new IllegalArgumentException("parentId");
            }
            parent = parentTag.getParentId();
            if (++visited > MarketLimits.MAX_TAG_DEFINITIONS) {
                throw new IllegalArgumentException("tag cycle");
            }
        }
    }

    private synchronized void validateListingTags(Listing listing, Collection<String> previouslyAccepted) {
        for (String tagId : listing.getTags()) {
            if (previouslyAccepted.contains(tagId)) {
                continue;
            }
            MarketTag definition = tags.get(tagId);
            if (definition == null || !definition.isEnabled()) {
                throw new IllegalArgumentException("tag");
            }
        }
    }

    private synchronized void changed() {
        revision = revision == Long.MAX_VALUE ? 1L : revision + 1L;
        setDirty();
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
            listingWritesSuspended = true;
            listingWritesSuspensionReason = "market data persistence failed";
            setDirty();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            FreeMarket.LOGGER.error("Unable to persist FreeMarket market data", exception);
            throw new IllegalStateException("market data save failed", exception);
        } catch (RuntimeException exception) {
            listingWritesSuspended = true;
            listingWritesSuspensionReason = "market data serialization failed";
            setDirty();
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            FreeMarket.LOGGER.error("Unable to serialize FreeMarket market data", exception);
            throw exception;
        }
    }

    public static boolean matchesListing(Listing listing, MarketQuery query, long timestamp) {
        Objects.requireNonNull(listing, "listing");
        Objects.requireNonNull(query, "query");
        if (!query.getSaleTypes().contains(listing.getSaleType()) || !query.getStatuses().contains(listing.getStatus())) {
            return false;
        }
        if (query.isAvailableOnly() && !listing.isPurchasable(timestamp)) {
            return false;
        }
        if (query.getSellerId().isPresent() && !query.getSellerId().get().equals(listing.getSellerId())) {
            return false;
        }
        if (query.getLikedBy().isPresent() && !listing.isLikedBy(query.getLikedBy().get())) {
            return false;
        }
        double price = listing.getCurrentPrice();
        if (query.getMinimumPrice().isPresent() && price < query.getMinimumPrice().get()) {
            return false;
        }
        if (query.getMaximumPrice().isPresent() && price > query.getMaximumPrice().get()) {
            return false;
        }
        Collection<String> listingTags = listing.getTags();
        if (!query.getTags().isEmpty()) {
            boolean tagMatch = query.getTagMatch() == MarketQuery.TagMatch.ALL ? listingTags.containsAll(query.getTags()) : query.getTags().stream().anyMatch(listingTags::contains);
            if (!tagMatch) {
                return false;
            }
        }
        String seller = normalized(listing.getSellerName());
        if (!contains(seller, query.getSellerText())) {
            return false;
        }
        String itemName = normalized(listing.getDisplayName().getString() + " " + listing.getItemName() + " " + listing.getItemId());
        if (!contains(itemName, query.getItemText())) {
            return false;
        }
        if (!query.getText().isBlank()) {
            String combined = itemName + " " + seller + " " + normalized(String.join(" ", listingTags));
            if (!contains(combined, query.getText())) {
                return false;
            }
        }
        return true;
    }

    private static Comparator<Listing> comparator(MarketQuery.SortOrder order) {
        Comparator<Listing> primary = switch (order) {
            case NAME_ASC -> Comparator.comparing(value -> normalized(value.getDisplayName().getString()));
            case UPDATED_DESC -> Comparator.comparingLong(Listing::getUpdatedAt).reversed();
            case LIKES_DESC -> Comparator.comparingInt(Listing::getLikeCount).reversed();
            case PRICE_ASC -> Comparator.comparingDouble(Listing::getCurrentPrice);
            case PRICE_DESC -> Comparator.comparingDouble(Listing::getCurrentPrice).reversed();
        };
        return primary.thenComparing(Comparator.comparingLong(Listing::getUpdatedAt).reversed()).thenComparing(value -> value.getId().toString());
    }

    private static boolean contains(String normalizedHaystack, String rawNeedle) {
        String needle = normalized(rawNeedle);
        return needle.isEmpty() || normalizedHaystack.contains(needle);
    }

    private static String normalized(String value) {
        String bounded = MarketLimits.bounded(value, MarketLimits.MAX_DESCRIPTION_LENGTH + MarketLimits.MAX_QUERY_LENGTH);
        return Normalizer.normalize(bounded, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
