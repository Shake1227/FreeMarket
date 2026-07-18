package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import shake1227.freemarket.FreeMarket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Listing {
    public enum SaleType {
        FIXED_PRICE,
        AUCTION
    }

    public enum Status {
        ACTIVE,
        PAUSED,
        SOLD,
        CANCELLED,
        REMOVED,
        EXPIRED,
        PAYMENT_REVIEW
    }

    public record OfferResolution(MarketOffer offer, List<MarketOffer> automaticallyRejected) {
        public OfferResolution {
            Objects.requireNonNull(offer, "offer");
            automaticallyRejected = List.copyOf(automaticallyRejected);
        }
    }

    private static final Pattern VALID_TAG = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final Set<Status> EDITABLE_STATUSES = Collections.unmodifiableSet(EnumSet.of(Status.ACTIVE, Status.PAUSED));

    private final UUID id;
    private final UUID sellerId;
    private String sellerName;
    private UUID buyerId;
    private String buyerName;
    private final ItemStack item;
    private final ItemStack clientSummary;
    private Component displayName;
    private final SaleType saleType;
    private Status status;
    private final double startingPrice;
    private double currentPrice;
    private final long createdAt;
    private long updatedAt;
    private final long auctionEndAt;
    private String description;
    private final LinkedHashSet<String> tags;
    private final ArrayList<MarketComment> comments;
    private final ArrayList<BidRecord> bidHistory;
    private final LinkedHashSet<UUID> likedBy;
    private final ArrayList<MarketOffer> offers;

    private Listing(UUID id, UUID sellerId, String sellerName, UUID buyerId, String buyerName, ItemStack item, Component displayName, SaleType saleType, Status status, double startingPrice, double currentPrice, long createdAt, long updatedAt, long auctionEndAt, String description, Collection<String> tags, Collection<MarketComment> comments, Collection<BidRecord> bidHistory, Collection<UUID> likedBy, Collection<MarketOffer> offers, ItemStack clientSummary, boolean shareItems) {
        this.id = Objects.requireNonNull(id, "id");
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId");
        this.sellerName = checkedPlayerName(sellerName);
        this.buyerId = buyerId;
        this.buyerName = checkedPlayerName(buyerName);
        if (item == null || item.isEmpty()) {
            throw new IllegalArgumentException("item");
        }
        this.item = shareItems ? item : item.copy();
        this.clientSummary = clientSummary == null || clientSummary.isEmpty() ? createClientSummary(this.item) : shareItems ? clientSummary : clientSummary.copy();
        this.displayName = checkedDisplayName(displayName == null ? item.getHoverName() : displayName);
        this.saleType = Objects.requireNonNull(saleType, "saleType");
        this.status = Objects.requireNonNull(status, "status");
        this.startingPrice = MarketLimits.requirePrice(startingPrice);
        this.currentPrice = MarketLimits.requirePrice(currentPrice);
        if (this.currentPrice < this.startingPrice && saleType == SaleType.AUCTION) {
            throw new IllegalArgumentException("currentPrice");
        }
        this.createdAt = Math.min(Long.MAX_VALUE - 1L, MarketLimits.nonNegativeTime(createdAt));
        this.updatedAt = Math.max(this.createdAt, updatedAt);
        this.auctionEndAt = saleType == SaleType.AUCTION ? Math.max(this.createdAt + 1L, MarketLimits.nonNegativeTime(auctionEndAt)) : 0L;
        this.description = MarketLimits.bounded(description, MarketLimits.MAX_DESCRIPTION_LENGTH);
        this.tags = checkedTags(tags);
        this.comments = new ArrayList<>();
        if (comments != null) {
            comments.stream().filter(Objects::nonNull).skip(Math.max(0, comments.size() - MarketLimits.MAX_COMMENTS_PER_LISTING)).forEach(this.comments::add);
        }
        this.bidHistory = new ArrayList<>();
        if (bidHistory != null) {
            bidHistory.stream().filter(Objects::nonNull).skip(Math.max(0, bidHistory.size() - MarketLimits.MAX_BIDS_PER_LISTING)).forEach(this.bidHistory::add);
        }
        this.likedBy = new LinkedHashSet<>();
        if (likedBy != null) {
            likedBy.stream().filter(Objects::nonNull).limit(MarketLimits.MAX_LIKES_PER_LISTING).forEach(this.likedBy::add);
        }
        this.offers = new ArrayList<>();
        if (offers != null) {
            offers.stream().filter(Objects::nonNull).skip(Math.max(0, offers.size() - MarketLimits.MAX_OFFERS_PER_LISTING)).forEach(offer -> {
                if (!offer.getListingId().equals(this.id) || this.offers.stream().anyMatch(existing -> existing.getId().equals(offer.getId()))) {
                    throw new IllegalArgumentException("offer");
                }
                this.offers.add(offer);
            });
        }
        if (status == Status.SOLD && buyerId == null) {
            throw new IllegalArgumentException("buyerId");
        }
    }

    public static Listing fixed(UUID id, UUID sellerId, String sellerName, ItemStack item, Component displayName, double price, String description, Collection<String> tags, long timestamp) {
        return new Listing(id, sellerId, sellerName, null, "", item, displayName, SaleType.FIXED_PRICE, Status.ACTIVE, price, price, timestamp, timestamp, 0L, description, tags, List.of(), List.of(), Set.of(), List.of(), null, false);
    }

    public static Listing fixed(UUID sellerId, String sellerName, ItemStack item, Component displayName, double price, String description, Collection<String> tags, long timestamp) {
        return fixed(UUID.randomUUID(), sellerId, sellerName, item, displayName, price, description, tags, timestamp);
    }

    public static Listing auction(UUID id, UUID sellerId, String sellerName, ItemStack item, Component displayName, double startingPrice, long auctionEndAt, String description, Collection<String> tags, long timestamp) {
        if (timestamp < 0L || auctionEndAt <= timestamp || auctionEndAt - timestamp > MarketLimits.MAX_AUCTION_DURATION_MILLIS) {
            throw new IllegalArgumentException("auctionEndAt");
        }
        return new Listing(id, sellerId, sellerName, null, "", item, displayName, SaleType.AUCTION, Status.ACTIVE, startingPrice, startingPrice, timestamp, timestamp, auctionEndAt, description, tags, List.of(), List.of(), Set.of(), List.of(), null, false);
    }

    public static Listing auction(UUID sellerId, String sellerName, ItemStack item, Component displayName, double startingPrice, long auctionEndAt, String description, Collection<String> tags, long timestamp) {
        return auction(UUID.randomUUID(), sellerId, sellerName, item, displayName, startingPrice, auctionEndAt, description, tags, timestamp);
    }

    public synchronized UUID getId() {
        return id;
    }

    public synchronized UUID getSellerId() {
        return sellerId;
    }

    public synchronized String getSellerName() {
        return sellerName;
    }

    public synchronized Optional<UUID> getBuyerId() {
        return Optional.ofNullable(buyerId);
    }

    public synchronized String getBuyerName() {
        return buyerName;
    }

    public synchronized ItemStack getItem() {
        return item.copy();
    }

    public synchronized ItemStack getClientSummary() {
        return clientSummary.copy();
    }

    public synchronized String getItemName() {
        return item.getHoverName().getString();
    }

    public synchronized String getItemId() {
        return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
    }

    public synchronized Component getDisplayName() {
        return displayName.copy();
    }

    public synchronized String getDisplayNameJson() {
        return Component.Serializer.toJson(displayName);
    }

    public synchronized SaleType getSaleType() {
        return saleType;
    }

    public synchronized Status getStatus() {
        return status;
    }

    public synchronized double getStartingPrice() {
        return startingPrice;
    }

    public synchronized double getCurrentPrice() {
        return currentPrice;
    }

    public synchronized long getCreatedAt() {
        return createdAt;
    }

    public synchronized long getUpdatedAt() {
        return updatedAt;
    }

    public synchronized long getAuctionEndAt() {
        return auctionEndAt;
    }

    public synchronized String getDescription() {
        return description;
    }

    public synchronized Set<String> getTags() {
        return Set.copyOf(tags);
    }

    public synchronized List<MarketComment> getComments() {
        return List.copyOf(comments);
    }

    public synchronized List<BidRecord> getBidHistory() {
        return List.copyOf(bidHistory);
    }

    public synchronized Optional<BidRecord> getHighestBid() {
        return bidHistory.isEmpty() ? Optional.empty() : Optional.of(bidHistory.get(bidHistory.size() - 1));
    }

    public synchronized Set<UUID> getLikedBy() {
        return Set.copyOf(likedBy);
    }

    public synchronized int getLikeCount() {
        return likedBy.size();
    }

    public synchronized List<MarketOffer> getOffers() {
        return List.copyOf(offers);
    }

    public synchronized Optional<MarketOffer> getOffer(UUID offerId) {
        return offers.stream().filter(offer -> offer.getId().equals(offerId)).findFirst();
    }

    public synchronized MarketOffer createOffer(UUID requesterId, String requesterName, double amount, long timestamp) {
        Objects.requireNonNull(requesterId, "requesterId");
        if (saleType != SaleType.FIXED_PRICE || status != Status.ACTIVE || sellerId.equals(requesterId)) {
            throw new IllegalStateException("offer unavailable");
        }
        double checkedAmount = MarketLimits.requirePrice(amount);
        if (checkedAmount <= 0D || checkedAmount >= currentPrice) {
            throw new IllegalArgumentException("offer amount");
        }
        expireOffers(timestamp);
        if (offers.stream().anyMatch(offer -> offer.getRequesterId().equals(requesterId) && offer.isPending(timestamp))) {
            throw new IllegalStateException("duplicate offer");
        }
        long pending = offers.stream().filter(offer -> offer.isPending(timestamp)).count();
        if (pending >= MarketLimits.MAX_PENDING_OFFERS_PER_LISTING) {
            throw new IllegalStateException("offer limit");
        }
        while (offers.size() >= MarketLimits.MAX_OFFERS_PER_LISTING) {
            int removable = -1;
            for (int index = 0; index < offers.size(); index++) {
                if (!offers.get(index).isPending(timestamp)) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) {
                throw new IllegalStateException("offer history full");
            }
            offers.remove(removable);
        }
        MarketOffer offer = new MarketOffer(id, requesterId, requesterName, checkedAmount, timestamp);
        offers.add(offer);
        touch(timestamp);
        return offer;
    }

    public synchronized OfferResolution respondOffer(UUID offerId, boolean accepted, long timestamp) {
        if (saleType != SaleType.FIXED_PRICE || status != Status.ACTIVE) {
            throw new IllegalStateException("offer unavailable");
        }
        expireOffers(timestamp);
        int selectedIndex = -1;
        for (int index = 0; index < offers.size(); index++) {
            if (offers.get(index).getId().equals(offerId)) {
                selectedIndex = index;
                break;
            }
        }
        if (selectedIndex < 0) {
            throw new IllegalArgumentException("offerId");
        }
        MarketOffer current = offers.get(selectedIndex);
        if (!current.isPending(timestamp) || accepted && current.getAmount() >= currentPrice) {
            throw new IllegalStateException("offer unavailable");
        }
        MarketOffer resolved = current.resolve(accepted, timestamp);
        offers.set(selectedIndex, resolved);
        ArrayList<MarketOffer> automaticallyRejected = new ArrayList<>();
        if (accepted) {
            currentPrice = current.getAmount();
            for (int index = 0; index < offers.size(); index++) {
                if (index == selectedIndex) {
                    continue;
                }
                MarketOffer other = offers.get(index);
                if (other.isPending(timestamp)) {
                    MarketOffer rejected = other.resolve(false, timestamp);
                    offers.set(index, rejected);
                    automaticallyRejected.add(rejected);
                }
            }
        }
        touch(timestamp);
        return new OfferResolution(resolved, automaticallyRejected);
    }

    public synchronized List<MarketOffer> expireOffers(long timestamp) {
        ArrayList<MarketOffer> expired = new ArrayList<>();
        for (int index = 0; index < offers.size(); index++) {
            MarketOffer current = offers.get(index);
            MarketOffer updated = current.expire(timestamp);
            if (updated != current) {
                offers.set(index, updated);
                expired.add(updated);
            }
        }
        if (!expired.isEmpty()) {
            touch(timestamp);
        }
        return List.copyOf(expired);
    }

    public synchronized boolean isLikedBy(UUID playerId) {
        return playerId != null && likedBy.contains(playerId);
    }

    public synchronized boolean isAuctionEnded(long timestamp) {
        return saleType == SaleType.AUCTION && timestamp >= auctionEndAt;
    }

    public synchronized boolean isPurchasable(long timestamp) {
        return status == Status.ACTIVE && (saleType == SaleType.FIXED_PRICE || timestamp < auctionEndAt);
    }

    public synchronized void refreshSellerName(String name, long timestamp) {
        requireEditable();
        sellerName = checkedPlayerName(name);
        touch(timestamp);
    }

    public synchronized void edit(Component name, String description, Collection<String> tags, Double fixedPrice, long timestamp) {
        requireEditable();
        displayName = checkedDisplayName(name == null ? displayName : name);
        this.description = MarketLimits.bounded(description, MarketLimits.MAX_DESCRIPTION_LENGTH);
        this.tags.clear();
        this.tags.addAll(checkedTags(tags));
        if (fixedPrice != null) {
            if (saleType != SaleType.FIXED_PRICE) {
                throw new IllegalStateException("auction price cannot be edited");
            }
            currentPrice = MarketLimits.requirePrice(fixedPrice);
        }
        touch(timestamp);
    }

    public synchronized void editMetadata(Component name, String description, Collection<String> tags, long timestamp) {
        edit(name, description, tags, null, timestamp);
    }

    public synchronized void updateFixedPrice(double price, long timestamp) {
        requireEditable();
        if (saleType != SaleType.FIXED_PRICE) {
            throw new IllegalStateException("auction price cannot be edited");
        }
        currentPrice = MarketLimits.requirePrice(price);
        touch(timestamp);
    }

    public synchronized void pause(long timestamp) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("status");
        }
        if (saleType == SaleType.AUCTION && !bidHistory.isEmpty()) {
            throw new IllegalStateException("auction has bids");
        }
        status = Status.PAUSED;
        touch(timestamp);
    }

    public synchronized void resume(long timestamp) {
        if (status != Status.PAUSED || isAuctionEnded(timestamp)) {
            throw new IllegalStateException("status");
        }
        status = Status.ACTIVE;
        touch(timestamp);
    }

    public synchronized void cancel(long timestamp) {
        requireEditable();
        if (saleType == SaleType.AUCTION && !bidHistory.isEmpty()) {
            throw new IllegalStateException("auction has bids");
        }
        status = Status.CANCELLED;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized void remove(long timestamp) {
        if (status == Status.REMOVED) {
            throw new IllegalStateException("status");
        }
        status = Status.REMOVED;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized void expire(long timestamp) {
        if (!EDITABLE_STATUSES.contains(status)) {
            throw new IllegalStateException("status");
        }
        if (saleType == SaleType.AUCTION && timestamp < auctionEndAt) {
            throw new IllegalStateException("auction not ended");
        }
        if (saleType == SaleType.AUCTION && !bidHistory.isEmpty()) {
            throw new IllegalStateException("auction has winner");
        }
        status = Status.EXPIRED;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized void failAuctionSettlement(long timestamp) {
        if (saleType != SaleType.AUCTION || !EDITABLE_STATUSES.contains(status) || timestamp < auctionEndAt || bidHistory.isEmpty()) {
            throw new IllegalStateException("auction");
        }
        status = Status.EXPIRED;
        touch(timestamp);
    }

    public synchronized void holdForPaymentReview(long timestamp) {
        if (!EDITABLE_STATUSES.contains(status)) {
            throw new IllegalStateException("status");
        }
        status = Status.PAYMENT_REVIEW;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized void completeReviewedSale(UUID buyerId, String buyerName, double paidPrice, long timestamp) {
        if (status != Status.PAYMENT_REVIEW || sellerId.equals(buyerId)) {
            throw new IllegalStateException("status");
        }
        double checkedPrice = MarketLimits.requirePrice(paidPrice);
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId");
        this.buyerName = checkedPlayerName(buyerName);
        this.currentPrice = checkedPrice;
        this.status = Status.SOLD;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized void rejectReviewedSale(long timestamp) {
        if (status != Status.PAYMENT_REVIEW && status != Status.SOLD) {
            throw new IllegalStateException("status");
        }
        buyerId = null;
        buyerName = "";
        status = Status.EXPIRED;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized void completeSale(UUID buyerId, String buyerName, double paidPrice, long timestamp) {
        Objects.requireNonNull(buyerId, "buyerId");
        if (sellerId.equals(buyerId)) {
            throw new IllegalArgumentException("buyerId");
        }
        double checkedPrice = MarketLimits.requirePrice(paidPrice);
        if (saleType == SaleType.FIXED_PRICE) {
            if (!isPurchasable(timestamp) || Double.compare(checkedPrice, currentPrice) != 0) {
                throw new IllegalStateException("sale");
            }
        } else {
            if ((status != Status.ACTIVE && status != Status.PAUSED) || timestamp < auctionEndAt || bidHistory.isEmpty()) {
                throw new IllegalStateException("auction");
            }
            BidRecord winner = bidHistory.get(bidHistory.size() - 1);
            if (!winner.getBidderId().equals(buyerId) || Double.compare(checkedPrice, currentPrice) != 0) {
                throw new IllegalArgumentException("winner");
            }
        }
        this.buyerId = buyerId;
        this.buyerName = checkedPlayerName(buyerName);
        this.currentPrice = checkedPrice;
        this.status = Status.SOLD;
        closePendingOffers(timestamp);
        touch(timestamp);
    }

    public synchronized BidRecord placeBid(UUID bidderId, String bidderName, double amount, long timestamp) {
        Objects.requireNonNull(bidderId, "bidderId");
        if (saleType != SaleType.AUCTION || status != Status.ACTIVE || timestamp >= auctionEndAt) {
            throw new IllegalStateException("auction");
        }
        if (sellerId.equals(bidderId)) {
            throw new IllegalArgumentException("bidderId");
        }
        double checkedAmount = MarketLimits.requirePrice(amount);
        if (Double.compare(checkedAmount, currentPrice) <= 0) {
            throw new IllegalArgumentException("amount");
        }
        BidRecord bid = new BidRecord(bidderId, bidderName, checkedAmount, timestamp);
        bidHistory.add(bid);
        trimFront(bidHistory, MarketLimits.MAX_BIDS_PER_LISTING);
        currentPrice = checkedAmount;
        touch(timestamp);
        return bid;
    }

    public synchronized MarketComment addComment(UUID authorId, String authorName, String message, long timestamp) {
        if (status == Status.REMOVED || status == Status.CANCELLED) {
            throw new IllegalStateException("status");
        }
        MarketComment comment = new MarketComment(authorId, authorName, message, timestamp);
        comments.add(comment);
        trimFront(comments, MarketLimits.MAX_COMMENTS_PER_LISTING);
        touch(timestamp);
        return comment;
    }

    public synchronized boolean removeComment(UUID commentId, long timestamp) {
        boolean removed = commentId != null && comments.removeIf(comment -> comment.getId().equals(commentId));
        if (removed) {
            touch(timestamp);
        }
        return removed;
    }

    public synchronized boolean setLiked(UUID playerId, boolean liked, long timestamp) {
        Objects.requireNonNull(playerId, "playerId");
        boolean changed;
        if (liked) {
            if (likedBy.size() >= MarketLimits.MAX_LIKES_PER_LISTING && !likedBy.contains(playerId)) {
                throw new IllegalStateException("like limit");
            }
            changed = likedBy.add(playerId);
        } else {
            changed = likedBy.remove(playerId);
        }
        return changed;
    }

    public synchronized boolean toggleLike(UUID playerId, long timestamp) {
        boolean target = !isLikedBy(playerId);
        setLiked(playerId, target, timestamp);
        return target;
    }

    public synchronized Listing copy() {
        return new Listing(id, sellerId, sellerName, buyerId, buyerName, item, displayName, saleType, status, startingPrice, currentPrice, createdAt, updatedAt, auctionEndAt, description, tags, comments, bidHistory, likedBy, offers, clientSummary, true);
    }

    public synchronized CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("SellerId", sellerId);
        tag.putString("SellerName", sellerName);
        if (buyerId != null) {
            tag.putUUID("BuyerId", buyerId);
            tag.putString("BuyerName", buyerName);
        }
        tag.put("Item", item.save(new CompoundTag()));
        tag.putString("DisplayName", Component.Serializer.toJson(displayName));
        tag.putString("SaleType", saleType.name());
        tag.putString("Status", status.name());
        tag.putDouble("StartingPrice", startingPrice);
        tag.putDouble("CurrentPrice", currentPrice);
        tag.putLong("CreatedAt", createdAt);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putLong("AuctionEndAt", auctionEndAt);
        tag.putString("Description", description);
        ListTag tagList = new ListTag();
        tags.forEach(value -> tagList.add(StringTag.valueOf(value)));
        tag.put("Tags", tagList);
        ListTag commentList = new ListTag();
        comments.forEach(value -> commentList.add(value.toTag()));
        tag.put("Comments", commentList);
        ListTag bidList = new ListTag();
        bidHistory.forEach(value -> bidList.add(value.toTag()));
        tag.put("Bids", bidList);
        ListTag likeList = new ListTag();
        likedBy.forEach(value -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("PlayerId", value);
            likeList.add(entry);
        });
        tag.put("Likes", likeList);
        ListTag offerList = new ListTag();
        offers.forEach(value -> offerList.add(value.toTag()));
        tag.put("Offers", offerList);
        return tag;
    }

    public static Listing fromTag(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("SellerId") || !tag.contains("Item", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("listing");
        }
        ItemStack item = ItemStack.of(tag.getCompound("Item"));
        Component displayName = item.getHoverName();
        if (tag.contains("DisplayName", Tag.TAG_STRING)) {
            String json = MarketLimits.bounded(tag.getString("DisplayName"), MarketLimits.MAX_DISPLAY_NAME_JSON_LENGTH);
            try {
                Component parsed = Component.Serializer.fromJson(json);
                if (parsed != null) {
                    displayName = parsed;
                }
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.warn("Unable to restore the display name for FreeMarket listing {}", tag.getUUID("Id"), exception);
                displayName = item.getHoverName();
            }
        }
        SaleType saleType = parseEnum(SaleType.class, tag.getString("SaleType"));
        Status status = parseEnum(Status.class, tag.getString("Status"));
        double startingPrice = tag.contains("StartingPrice", Tag.TAG_ANY_NUMERIC) ? tag.getDouble("StartingPrice") : 0D;
        double currentPrice = tag.contains("CurrentPrice", Tag.TAG_ANY_NUMERIC) ? tag.getDouble("CurrentPrice") : startingPrice;
        long createdAt = tag.getLong("CreatedAt");
        long updatedAt = tag.getLong("UpdatedAt");
        long auctionEndAt = tag.getLong("AuctionEndAt");
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        ListTag storedTags = tag.getList("Tags", Tag.TAG_STRING);
        for (int i = 0; i < Math.min(storedTags.size(), MarketLimits.MAX_TAGS_PER_LISTING); i++) {
            tags.add(storedTags.getString(i));
        }
        ArrayList<MarketComment> comments = new ArrayList<>();
        ListTag storedComments = tag.getList("Comments", Tag.TAG_COMPOUND);
        int commentStart = Math.max(0, storedComments.size() - MarketLimits.MAX_COMMENTS_PER_LISTING);
        for (int i = commentStart; i < storedComments.size(); i++) {
            try {
                comments.add(MarketComment.fromTag(storedComments.getCompound(i)));
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.warn("Unable to restore a comment for FreeMarket listing {} at index {}", tag.getUUID("Id"), i, exception);
            }
        }
        ArrayList<BidRecord> bids = new ArrayList<>();
        ListTag storedBids = tag.getList("Bids", Tag.TAG_COMPOUND);
        int bidStart = Math.max(0, storedBids.size() - MarketLimits.MAX_BIDS_PER_LISTING);
        double bidFloor = Math.max(0D, startingPrice);
        for (int i = bidStart; i < storedBids.size(); i++) {
            try {
                BidRecord bid = BidRecord.fromTag(storedBids.getCompound(i));
                if (bid.getAmount() > bidFloor) {
                    bids.add(bid);
                    bidFloor = bid.getAmount();
                }
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.warn("Unable to restore a bid for FreeMarket listing {} at index {}", tag.getUUID("Id"), i, exception);
            }
        }
        if (saleType == SaleType.AUCTION && !bids.isEmpty()) {
            currentPrice = bids.get(bids.size() - 1).getAmount();
        }
        LinkedHashSet<UUID> likes = new LinkedHashSet<>();
        ListTag storedLikes = tag.getList("Likes", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(storedLikes.size(), MarketLimits.MAX_LIKES_PER_LISTING); i++) {
            CompoundTag entry = storedLikes.getCompound(i);
            if (entry.hasUUID("PlayerId")) {
                likes.add(entry.getUUID("PlayerId"));
            }
        }
        ArrayList<MarketOffer> offers = new ArrayList<>();
        LinkedHashSet<UUID> offerIds = new LinkedHashSet<>();
        ListTag storedOffers = tag.getList("Offers", Tag.TAG_COMPOUND);
        int offerStart = Math.max(0, storedOffers.size() - MarketLimits.MAX_OFFERS_PER_LISTING);
        for (int i = offerStart; i < storedOffers.size(); i++) {
            try {
                MarketOffer offer = MarketOffer.fromTag(storedOffers.getCompound(i));
                if (!offer.getListingId().equals(tag.getUUID("Id")) || !offerIds.add(offer.getId())) {
                    throw new IllegalArgumentException("offer listing");
                }
                offers.add(offer);
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.warn("Unable to restore an offer for FreeMarket listing {} at index {}", tag.getUUID("Id"), i, exception);
            }
        }
        UUID buyerId = tag.hasUUID("BuyerId") ? tag.getUUID("BuyerId") : null;
        return new Listing(tag.getUUID("Id"), tag.getUUID("SellerId"), tag.getString("SellerName"), buyerId, tag.getString("BuyerName"), item, displayName, saleType, status, startingPrice, currentPrice, createdAt, updatedAt, auctionEndAt, tag.getString("Description"), tags, comments, bids, likes, offers, null, false);
    }

    private static Component checkedDisplayName(Component value) {
        Component checked = Objects.requireNonNull(value, "displayName");
        String json = Component.Serializer.toJson(checked);
        if (json.length() > MarketLimits.MAX_DISPLAY_NAME_JSON_LENGTH || checked.getString().length() > MarketLimits.MAX_PLAIN_NAME_LENGTH || checked.getString().isBlank()) {
            throw new IllegalArgumentException("displayName");
        }
        Component parsed = Component.Serializer.fromJson(json);
        if (parsed == null) {
            throw new IllegalArgumentException("displayName");
        }
        return parsed.copy();
    }

    private static ItemStack createClientSummary(ItemStack source) {
        ItemStack summary = new ItemStack(source.getItem(), source.getCount());
        CompoundTag sourceTag = source.getTag();
        if (sourceTag == null) {
            return summary;
        }
        for (String key : List.of("Damage", "CustomModelData", "Potion", "CustomPotionColor")) {
            Tag value = sourceTag.get(key);
            if (value != null) {
                summary.getOrCreateTag().put(key, value.copy());
            }
        }
        return summary;
    }

    private static String checkedPlayerName(String value) {
        return MarketLimits.bounded(value, MarketLimits.MAX_PLAYER_NAME_LENGTH);
    }

    private static LinkedHashSet<String> checkedTags(Collection<String> values) {
        LinkedHashSet<String> checked = new LinkedHashSet<>();
        if (values == null) {
            return checked;
        }
        for (String value : values) {
            String normalized = MarketTag.normalizeId(value);
            if (!normalized.isEmpty() && VALID_TAG.matcher(normalized).matches()) {
                checked.add(normalized);
                if (checked.size() >= MarketLimits.MAX_TAGS_PER_LISTING) {
                    break;
                }
            }
        }
        return checked;
    }

    private synchronized void requireEditable() {
        if (!EDITABLE_STATUSES.contains(status)) {
            throw new IllegalStateException("status");
        }
    }

    private synchronized void closePendingOffers(long timestamp) {
        for (int index = 0; index < offers.size(); index++) {
            MarketOffer current = offers.get(index);
            MarketOffer updated = current.closeExpired(timestamp);
            if (updated != current) {
                offers.set(index, updated);
            }
        }
    }

    private synchronized void touch(long timestamp) {
        updatedAt = Math.max(updatedAt, MarketLimits.nonNegativeTime(timestamp));
    }

    private static <T> void trimFront(ArrayList<T> values, int maximumSize) {
        if (values.size() > maximumSize) {
            values.subList(0, values.size() - maximumSize).clear();
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        return Enum.valueOf(type, Objects.requireNonNull(value, "enum").toUpperCase(Locale.ROOT));
    }
}
