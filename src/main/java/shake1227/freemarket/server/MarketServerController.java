package shake1227.freemarket.server;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import shake1227.freemarket.FreeMarket;
import shake1227.freemarket.integration.ModernNotificationBridge;
import shake1227.freemarket.integration.VaultEconomyBridge;
import shake1227.freemarket.market.AuctionEscrowLog;
import shake1227.freemarket.market.BidRecord;
import shake1227.freemarket.market.DraftManager;
import shake1227.freemarket.market.FeeConfig;
import shake1227.freemarket.market.Listing;
import shake1227.freemarket.market.MarketComment;
import shake1227.freemarket.market.MarketLimits;
import shake1227.freemarket.market.MarketNotification;
import shake1227.freemarket.market.MarketOffer;
import shake1227.freemarket.market.MarketQuery;
import shake1227.freemarket.market.MarketReport;
import shake1227.freemarket.market.MarketSavedData;
import shake1227.freemarket.market.MarketSearchResult;
import shake1227.freemarket.market.MarketTag;
import shake1227.freemarket.market.MarketTransactionLog;
import shake1227.freemarket.market.MarketUserData;
import shake1227.freemarket.market.PendingDeliveries;
import shake1227.freemarket.market.SavedMarketSearch;
import shake1227.freemarket.market.SellerReview;
import shake1227.freemarket.network.NetworkHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MarketServerController {
    private static final DraftManager DRAFTS = new DraftManager();
    private static final Set<UUID> VIEWERS = new HashSet<>();
    private static final Set<UUID> OPERATIONS = new HashSet<>();
    private static final Map<UUID, ArrayDeque<Long>> ACTION_RATE_LIMIT = new HashMap<>();
    private static final Map<UUID, ArrayDeque<Long>> QUERY_RATE_LIMIT = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> ACTION_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Long> QUERY_SEQUENCE = new ConcurrentHashMap<>();
    private static final Map<UUID, QueryRequest> QUERY_PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> QUERY_RUNNING = ConcurrentHashMap.newKeySet();
    private static final PriorityQueue<AuctionDeadline> AUCTIONS = new PriorityQueue<>(Comparator.comparingLong(AuctionDeadline::endsAt));
    private static final AtomicInteger SEARCH_THREAD_ID = new AtomicInteger();
    private static boolean transactionsSuspended;
    private static final ThreadPoolExecutor SEARCH_EXECUTOR = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
        Thread thread = new Thread(runnable, "FreeMarket-Search-" + SEARCH_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final int MAX_ACTIVE_LISTINGS_PER_PLAYER = 50;
    private static final long MIN_AUCTION_DURATION = 5L * 60L * 1000L;
    private static final long MAX_AUCTION_DURATION = 30L * 24L * 60L * 60L * 1000L;
    private static final int MAX_AUCTION_SETTLEMENTS_PER_TICK = 3;
    private static final int MAX_AUCTION_DEADLINES_SCANNED_PER_TICK = 64;
    private static final long AUCTION_RETRY_DELAY = 30_000L;
    private static final long DELIVERY_SWEEP_INTERVAL_MILLIS = 5_000L;
    private static final long AUCTION_RESCAN_INTERVAL_MILLIS = 60_000L;
    private static long nextDeliverySweepAt;
    private static long nextAuctionRescanAt;
    private static volatile boolean invalidatePending;

    private MarketServerController() {
    }

    public static void open(ServerPlayer player) {
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.touchUser(player.getUUID(), player.getGameProfile().getName());
        flushDeliveries(player.server, player, "terminal open delivery acknowledgement failed");
        VIEWERS.add(player.getUUID());
        NetworkHandler.sendToPlayer(player, "open", snapshot(player, defaultQuery(), true));
        while (true) {
            Optional<UUID> promptId = savedData.pollPendingReviewPrompt(player.getUUID());
            if (promptId.isEmpty()) {
                break;
            }
            Optional<Listing> listing = savedData.getListing(promptId.get());
            boolean reviewed = savedData.getUser(player.getUUID()).map(user -> user.hasReviewedListing(promptId.get())).orElse(false);
            if (listing.isPresent()
                && listing.get().getStatus() == Listing.Status.SOLD
                && listing.get().getBuyerId().filter(player.getUUID()::equals).isPresent()
                && !reviewed) {
                sendReviewPrompt(player, listing.get());
                break;
            }
        }
    }

    private static void sendReviewPrompt(ServerPlayer buyer, Listing listing) {
        CompoundTag prompt = new CompoundTag();
        prompt.putString("Id", listing.getId().toString());
        prompt.putString("SellerName", listing.getSellerName());
        prompt.putString("ItemName", listing.getDisplayName().getString());
        NetworkHandler.sendToPlayer(buyer, "review_prompt", prompt);
    }

    public static void openAdmin(ServerPlayer player) {
        if (!isAdmin(player)) {
            result(player, false, "message.freemarket.permission_denied");
            return;
        }
        MarketSavedData.get(player.server).touchUser(player.getUUID(), player.getGameProfile().getName());
        VIEWERS.add(player.getUUID());
        CompoundTag payload = snapshot(player, defaultQuery(), true);
        payload.putBoolean("OpenAdmin", true);
        NetworkHandler.sendToPlayer(player, "open_admin", payload);
    }

    public static boolean resolveTransaction(ServerPlayer operator, UUID listingId, boolean complete) {
        if (!isAdmin(operator)) {
            return false;
        }
        return resolveTransaction(operator.server, listingId, complete);
    }

    public static boolean resolveTransaction(MinecraftServer server, UUID listingId, boolean complete) {
        Optional<AuctionEscrowLog.Entry> escrow = AuctionEscrowLog.get(server).getEntry(listingId);
        if (escrow.isPresent()) {
            if (escrow.get().state() == AuctionEscrowLog.State.MANUAL_REVIEW) {
                return resolveAuctionEscrow(server, escrow.get(), complete);
            }
            if (escrow.get().terminal()) {
                Optional<MarketTransactionLog.Entry> mirrored = MarketTransactionLog.get(server).getEntry(listingId);
                if (mirrored.isPresent() && mirrored.get().state() == MarketTransactionLog.State.MANUAL_REVIEW) {
                    MarketTransactionLog.get(server).transition(listingId, escrow.get().state() == AuctionEscrowLog.State.COMPLETED ? MarketTransactionLog.State.COMPLETED : MarketTransactionLog.State.ROLLED_BACK, "reconciled from resolved auction escrow journal", System.currentTimeMillis());
                    try {
                        MarketTransactionLog.saveNow(server);
                    } catch (RuntimeException exception) {
                        suspendTransactions(server, "auction escrow mirror reconciliation failed");
                        return false;
                    }
                    return true;
                }
            }
            return false;
        }
        MarketTransactionLog log = MarketTransactionLog.get(server);
        Optional<MarketTransactionLog.Entry> optionalEntry = log.getEntry(listingId);
        Optional<Listing> optionalListing = MarketSavedData.get(server).getListing(listingId);
        if (optionalEntry.isEmpty() || optionalListing.isEmpty() || optionalEntry.get().state() != MarketTransactionLog.State.MANUAL_REVIEW) {
            return false;
        }
        MarketTransactionLog.Entry entry = optionalEntry.get();
        Listing listing = optionalListing.get();
        MarketSavedData savedData = MarketSavedData.get(server);
        if (complete) {
            if (listing.getStatus() != Listing.Status.PAYMENT_REVIEW && listing.getStatus() != Listing.Status.SOLD) {
                return false;
            }
            PendingDeliveries deliveries = PendingDeliveries.get(server);
            if (listing.getStatus() == Listing.Status.SOLD && !deliveries.contains(entry.buyerId(), listingId)) {
                return false;
            }
            if (!deliveries.canEnqueue(entry.buyerId(), listingId)) {
                return false;
            }
            if (listing.getStatus() != Listing.Status.SOLD) {
                savedData.updateListing(listingId, value -> value.completeReviewedSale(entry.buyerId(), entry.buyerName(), entry.gross(), System.currentTimeMillis()));
                savedData.recordPurchase(entry.buyerId(), entry.buyerName(), listingId);
            }
            if (!deliveries.enqueue(entry.buyerId(), listingId, listing.getItem())) {
                return false;
            }
            try {
                MarketSavedData.saveNow(server);
                PendingDeliveries.saveNow(server);
            } catch (RuntimeException exception) {
                suspendTransactions(server, "manual settlement data save failed");
                return false;
            }
            log.transition(entry.id(), MarketTransactionLog.State.COMPLETED, "operator confirmed settlement", System.currentTimeMillis());
            try {
                MarketTransactionLog.saveNow(server);
            } catch (RuntimeException exception) {
                suspendTransactions(server, "manual settlement journal save failed");
                return false;
            }
            ServerPlayer buyer = server.getPlayerList().getPlayer(entry.buyerId());
            if (buyer != null) {
                deliveries.deliver(buyer);
                try {
                    PendingDeliveries.saveNow(server);
                } catch (RuntimeException exception) {
                    suspendTransactions(server, "manual buyer delivery acknowledgement save failed");
                    return false;
                }
            }
            notifySaleParties(server, listing, entry.buyerId(), entry.buyerName(), entry.gross(), listing.getSaleType() == Listing.SaleType.AUCTION);
        } else {
            boolean returnedTerminal = listing.getStatus() == Listing.Status.EXPIRED || listing.getStatus() == Listing.Status.CANCELLED || listing.getStatus() == Listing.Status.REMOVED;
            if (listing.getStatus() != Listing.Status.PAYMENT_REVIEW && listing.getStatus() != Listing.Status.SOLD && !returnedTerminal) {
                return false;
            }
            PendingDeliveries deliveries = PendingDeliveries.get(server);
            deliveries.remove(entry.buyerId(), listingId);
            boolean processReturn = !returnedTerminal || deliveries.contains(listing.getSellerId(), listingId);
            if (processReturn && !deliveries.canEnqueue(listing.getSellerId(), listingId)) {
                return false;
            }
            if (!returnedTerminal) {
                savedData.updateListing(listingId, value -> value.rejectReviewedSale(System.currentTimeMillis()));
                savedData.removePurchase(listing.getBuyerId().orElse(entry.buyerId()), listingId);
            }
            if (processReturn && !deliveries.enqueue(listing.getSellerId(), listingId, listing.getItem())) {
                return false;
            }
            try {
                MarketSavedData.saveNow(server);
                PendingDeliveries.saveNow(server);
            } catch (RuntimeException exception) {
                suspendTransactions(server, "manual return data save failed");
                return false;
            }
            log.transition(entry.id(), MarketTransactionLog.State.ROLLED_BACK, "operator returned item after external economy audit", System.currentTimeMillis());
            try {
                MarketTransactionLog.saveNow(server);
            } catch (RuntimeException exception) {
                suspendTransactions(server, "manual return journal save failed");
                return false;
            }
            ServerPlayer seller = server.getPlayerList().getPlayer(listing.getSellerId());
            if (processReturn && seller != null) {
                deliveries.deliver(seller);
                try {
                    PendingDeliveries.saveNow(server);
                } catch (RuntimeException exception) {
                    suspendTransactions(server, "manual seller delivery acknowledgement save failed");
                    return false;
                }
            }
        }
        invalidate(server);
        return true;
    }

    private static boolean resolveAuctionEscrow(MinecraftServer server, AuctionEscrowLog.Entry escrow, boolean complete) {
        UUID buyerId = escrow.effectiveBuyerId();
        Optional<Listing> optionalListing = MarketSavedData.get(server).getListing(escrow.listingId());
        if (buyerId == null || optionalListing.isEmpty()) {
            return false;
        }
        Listing listing = optionalListing.get();
        if (listing.getSaleType() != Listing.SaleType.AUCTION || !listing.getSellerId().equals(escrow.sellerId())) {
            suspendTransactions(server, "auction manual review parties do not match listing");
            return false;
        }
        MarketSavedData savedData = MarketSavedData.get(server);
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        boolean paymentReview = listing.getStatus() == Listing.Status.PAYMENT_REVIEW;
        boolean sold = listing.getStatus() == Listing.Status.SOLD;
        boolean returnedTerminal = listing.getStatus() == Listing.Status.EXPIRED || listing.getStatus() == Listing.Status.CANCELLED || listing.getStatus() == Listing.Status.REMOVED;
        if ((complete && returnedTerminal) || (!paymentReview && !sold && !returnedTerminal)) {
            return false;
        }
        UUID recipient = complete ? buyerId : listing.getSellerId();
        if (!complete) {
            deliveries.remove(buyerId, listing.getId());
        }
        if (complete && sold && !deliveries.contains(recipient, listing.getId())) {
            return false;
        }
        boolean processDelivery = complete || !returnedTerminal || deliveries.contains(recipient, listing.getId());
        if (processDelivery && !deliveries.canEnqueue(recipient, listing.getId())) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (complete && paymentReview) {
            savedData.updateListing(listing.getId(), value -> value.completeReviewedSale(buyerId, escrow.effectiveBuyerName(), escrow.effectiveAmount(), now));
            savedData.recordPurchase(buyerId, escrow.effectiveBuyerName(), listing.getId());
        } else if (!complete && (paymentReview || sold)) {
            savedData.updateListing(listing.getId(), value -> value.rejectReviewedSale(now));
            savedData.removePurchase(listing.getBuyerId().orElse(buyerId), listing.getId());
        }
        if (processDelivery && !deliveries.enqueue(recipient, listing.getId(), listing.getItem())) {
            return false;
        }
        try {
            MarketSavedData.saveNow(server);
            PendingDeliveries.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction manual resolution data save failed");
            return false;
        }
        AuctionEscrowLog log = AuctionEscrowLog.get(server);
        log.resolveManual(listing.getId(), complete, complete ? "operator confirmed audited escrow settlement" : "operator confirmed audited escrow return", now);
        Optional<MarketTransactionLog.Entry> mirrored = MarketTransactionLog.get(server).getEntry(listing.getId());
        if (mirrored.isPresent() && mirrored.get().state() == MarketTransactionLog.State.MANUAL_REVIEW) {
            MarketTransactionLog.get(server).transition(listing.getId(), complete ? MarketTransactionLog.State.COMPLETED : MarketTransactionLog.State.ROLLED_BACK, "operator resolved auction escrow after external economy audit", now);
        }
        try {
            AuctionEscrowLog.saveNow(server);
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction escrow resolution journal failed");
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(recipient);
        if (processDelivery && online != null) {
            deliveries.deliver(online);
            try {
                PendingDeliveries.saveNow(server);
            } catch (RuntimeException exception) {
                suspendTransactions(server, "auction manual delivery acknowledgement save failed");
                return false;
            }
        }
        if (complete && paymentReview) {
            notifySaleParties(server, listing, buyerId, escrow.effectiveBuyerName(), escrow.effectiveAmount(), true);
        }
        invalidate(server);
        return true;
    }

    public static void handle(ServerPlayer player, String rawAction, CompoundTag payload) {
        String action = rawAction == null ? "" : rawAction.toLowerCase(Locale.ROOT);
        CompoundTag data = payload == null ? new CompoundTag() : payload;
        if (!VIEWERS.contains(player.getUUID()) && !action.equals("close")) {
            result(player, false, "message.freemarket.session_required");
            return;
        }
        if (!allow(player, action)) {
            result(player, false, "message.freemarket.rate_limited");
            return;
        }
        try {
            switch (action) {
                case "refresh" -> open(player);
                case "query", "search" -> query(player, data);
                case "view", "view_listing" -> view(player, data);
                case "select_item", "draft_select" -> selectItem(player, data);
                case "cancel_draft", "draft_cancel" -> cancelDraft(player);
                case "create", "publish", "create_listing" -> createListing(player, data);
                case "like", "toggle_like" -> toggleLike(player, data);
                case "buy", "purchase" -> purchase(player, data);
                case "bid", "place_bid" -> bid(player, data);
                case "comment", "add_comment" -> comment(player, data);
                case "offer", "price_request" -> priceRequest(player, data);
                case "accept_offer" -> respondOffer(player, data, true);
                case "reject_offer" -> respondOffer(player, data, false);
                case "pause", "toggle_pause", "pause_listing", "set_listing_paused" -> setPaused(player, data);
                case "cancel_listing", "withdraw_listing" -> cancelListing(player, data);
                case "edit", "edit_listing" -> editListing(player, data);
                case "admin_delete", "admin_delete_listing" -> adminDelete(player, data);
                case "notification_read" -> readNotification(player, data);
                case "notification_read_all" -> readAllNotifications(player);
                case "notification_delete" -> deleteNotification(player, data);
                case "admin_tag_save", "admin_tag_upsert", "admin_tag_add", "admin_tag_edit" -> saveTag(player, data);
                case "admin_tag_delete" -> deleteTag(player, data);
                case "admin_fee", "admin_fee_save", "admin_fee_set" -> saveFee(player, data);
                case "rate", "review" -> rateSeller(player, data);
                case "seller_profile" -> sellerProfile(player, data);
                case "save_search" -> saveSearch(player, data);
                case "run_saved_search" -> runSavedSearch(player, data);
                case "delete_saved_search" -> deleteSavedSearch(player, data);
                case "block_seller", "block_user" -> setSellerBlocked(player, data, true);
                case "unblock_seller", "unblock_user" -> setSellerBlocked(player, data, false);
                case "report_listing" -> reportListing(player, data);
                case "admin_report_review" -> reviewReport(player, data);
                case "close" -> close(player);
                default -> result(player, false, "message.freemarket.invalid_action");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            FreeMarket.LOGGER.debug("Rejected market action {} from {}: {}", action, player.getGameProfile().getName(), exception.getMessage());
            result(player, false, "message.freemarket.invalid_request");
        } catch (RuntimeException exception) {
            FreeMarket.LOGGER.error("Market action {} failed for {}", action, player.getGameProfile().getName(), exception);
            result(player, false, "message.freemarket.internal_error");
        }
    }

    public static void serverStarted(MinecraftServer server) {
        transactionsSuspended = false;
        AUCTIONS.clear();
        long now = System.currentTimeMillis();
        MarketSavedData savedData = MarketSavedData.get(server);
        for (MarketSavedData.AuctionSchedule auction : savedData.getActiveAuctionSchedules()) {
            AUCTIONS.add(new AuctionDeadline(auction.listingId(), Math.max(now, auction.endsAt())));
        }
        if (savedData.areListingWritesSuspended()
            || PendingDeliveries.get(server).isRecoveryUnsafe()
            || MarketTransactionLog.get(server).recoveryUnsafe()
            || AuctionEscrowLog.get(server).recoveryUnsafe()) {
            quarantineUnresolvedForReview(server);
            suspendTransactions(server, "persistent market recovery data requires operator audit");
            return;
        }
        recoverAuctionEscrows(server);
        recoverTransactions(server);
    }

    public static void serverStopping(MinecraftServer server) {
        DRAFTS.returnAll(server);
        VIEWERS.clear();
        OPERATIONS.clear();
        ACTION_RATE_LIMIT.clear();
        QUERY_RATE_LIMIT.clear();
        ACTION_COOLDOWNS.clear();
        QUERY_SEQUENCE.clear();
        QUERY_PENDING.clear();
        QUERY_RUNNING.clear();
        AUCTIONS.clear();
        transactionsSuspended = false;
        invalidatePending = false;
        VaultEconomyBridge.reset();
    }

    public static void playerLoggedIn(ServerPlayer player) {
        DRAFTS.recover(player);
        MarketSavedData.get(player.server).touchUser(player.getUUID(), player.getGameProfile().getName());
        flushDeliveries(player.server, player, "login delivery acknowledgement failed");
        if (transactionsSuspended && player.hasPermissions(2)) {
            ModernNotificationBridge.notify(player, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.FAILURE, "notification.freemarket.transactions_suspended.title", "notification.freemarket.transactions_suspended.message");
        }
    }

    public static void playerLoggedOut(ServerPlayer player) {
        DRAFTS.onLogout(player);
        VIEWERS.remove(player.getUUID());
        ACTION_RATE_LIMIT.remove(player.getUUID());
        QUERY_RATE_LIMIT.remove(player.getUUID());
        ACTION_COOLDOWNS.remove(player.getUUID());
        QUERY_SEQUENCE.remove(player.getUUID());
        QUERY_PENDING.remove(player.getUUID());
    }

    public static void playerCloned(Player original, ServerPlayer replacement) {
        DRAFTS.onClone(original, replacement);
        PendingDeliveries.copyReceipts(original, replacement);
        PlayerDataPersistence.save(replacement);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        flushInvalidate(server);
        int settled = 0;
        int scanned = 0;
        while (!AUCTIONS.isEmpty() && AUCTIONS.peek().endsAt() <= now && settled < MAX_AUCTION_SETTLEMENTS_PER_TICK && scanned < MAX_AUCTION_DEADLINES_SCANNED_PER_TICK) {
            AuctionDeadline deadline = AUCTIONS.poll();
            scanned++;
            Optional<Listing> current = MarketSavedData.get(server).getListing(deadline.listingId());
            if (current.isEmpty()) {
                continue;
            }
            Listing listing = current.get();
            if (listing.getSaleType() != Listing.SaleType.AUCTION || listing.getAuctionEndAt() > now || (listing.getStatus() != Listing.Status.ACTIVE && listing.getStatus() != Listing.Status.PAUSED)) {
                continue;
            }
            try {
                finishAuction(server, listing, now);
            } catch (RuntimeException exception) {
                FreeMarket.LOGGER.error("Auction settlement for {} failed; retrying later", listing.getId(), exception);
                AUCTIONS.add(new AuctionDeadline(listing.getId(), now + AUCTION_RETRY_DELAY));
            }
            settled++;
        }
        if (now >= nextAuctionRescanAt) {
            nextAuctionRescanAt = now + AUCTION_RESCAN_INTERVAL_MILLIS;
            rescanEndedAuctions(server, now);
        }
        if (now >= nextDeliverySweepAt) {
            nextDeliverySweepAt = now + DELIVERY_SWEEP_INTERVAL_MILLIS;
            sweepOnlineDeliveries(server);
        }
    }

    private static void rescanEndedAuctions(MinecraftServer server, long now) {
        Set<UUID> queued = new HashSet<>();
        for (AuctionDeadline deadline : AUCTIONS) {
            queued.add(deadline.listingId());
        }
        for (MarketSavedData.AuctionSchedule schedule : MarketSavedData.get(server).getActiveAuctionSchedules()) {
            if (schedule.endsAt() <= now && !queued.contains(schedule.listingId())) {
                AUCTIONS.add(new AuctionDeadline(schedule.listingId(), now));
            }
        }
    }

    private static void sweepOnlineDeliveries(MinecraftServer server) {
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (deliveries.pendingCount(player.getUUID()) <= 0) {
                continue;
            }
            flushDeliveries(server, player, "online delivery sweep acknowledgement failed");
        }
    }

    private static void flushDeliveries(MinecraftServer server, ServerPlayer player, String failureReason) {
        int delivered = PendingDeliveries.get(server).deliver(player);
        if (delivered > 0) {
            persistPendingDeliveries(server, failureReason);
            player.sendSystemMessage(Component.translatable("message.freemarket.items_delivered", delivered));
        }
    }

    private static void query(ServerPlayer player, CompoundTag data) {
        VIEWERS.add(player.getUUID());
        queueQuery(player, queryFrom(data, player));
    }

    private static void queueQuery(ServerPlayer player, MarketQuery query) {
        MinecraftServer server = player.server;
        MarketSavedData savedData = MarketSavedData.get(server);
        UUID playerId = player.getUUID();
        long sequence = QUERY_SEQUENCE.merge(playerId, 1L, (current, increment) -> current == Long.MAX_VALUE ? 1L : current + 1L);
        QUERY_PENDING.put(playerId, new QueryRequest(query, sequence));
        if (!QUERY_RUNNING.add(playerId)) {
            return;
        }
        submitSearch(server, savedData, playerId);
    }

    private static void submitSearch(MinecraftServer server, MarketSavedData savedData, UUID playerId) {
        try {
            SEARCH_EXECUTOR.execute(() -> runSearchLoop(server, savedData, playerId));
        } catch (RejectedExecutionException exception) {
            QUERY_RUNNING.remove(playerId);
            QUERY_PENDING.remove(playerId);
            ServerPlayer online = server.getPlayerList().getPlayer(playerId);
            if (online != null) {
                result(online, false, "message.freemarket.search_busy");
            }
        }
    }

    private static void runSearchLoop(MinecraftServer server, MarketSavedData savedData, UUID playerId) {
        try {
            while (true) {
                QueryRequest request = QUERY_PENDING.remove(playerId);
                if (request == null) {
                    return;
                }
                MarketSearchResult search = savedData.searchForUser(playerId, request.query(), System.currentTimeMillis());
                if (QUERY_PENDING.containsKey(playerId)) {
                    continue;
                }
                server.execute(() -> {
                    ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                    if (online != null && QUERY_SEQUENCE.getOrDefault(playerId, -1L) == request.sequence() && !QUERY_PENDING.containsKey(playerId)) {
                        NetworkHandler.sendToPlayer(online, "sync", snapshot(online, search, false));
                    }
                });
                return;
            }
        } catch (RuntimeException exception) {
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null) {
                    result(online, false, "message.freemarket.search_failed");
                }
            });
        } finally {
            QUERY_RUNNING.remove(playerId);
            if (QUERY_PENDING.containsKey(playerId) && QUERY_RUNNING.add(playerId)) {
                server.execute(() -> submitSearch(server, savedData, playerId));
            }
        }
    }

    private static void view(ServerPlayer player, CompoundTag data) {
        UUID listingId = listingId(data);
        Listing listing = requiredListing(player.server, listingId);
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.recordView(player.getUUID(), player.getGameProfile().getName(), listingId);
        CompoundTag response = new CompoundTag();
        CompoundTag listingPayload = listingTag(listing, player.getUUID(), true);
        listingPayload.putBoolean("SellerBlocked", savedData.isUserBlocked(player.getUUID(), listing.getSellerId()));
        response.put("Listing", listingPayload);
        response.put("User", userSummaryTag(savedData, player));
        response.putLong("Revision", savedData.getRevision());
        NetworkHandler.sendToPlayer(player, "detail", response);
    }

    private static void selectItem(ServerPlayer player, CompoundTag data) {
        int slot = data.getInt("Slot");
        if (slot < 0 || slot >= player.getInventory().getContainerSize()) {
            result(player, false, "message.freemarket.invalid_slot");
            return;
        }
        if (player.getInventory().getItem(slot).isEmpty()) {
            result(player, false, "message.freemarket.empty_slot");
            return;
        }
        if (!MarketItemSafety.canEscrow(player.getInventory().getItem(slot))) {
            result(player, false, "message.freemarket.item_data_too_large");
            return;
        }
        DraftManager.SelectionResult selected = DRAFTS.selectFromSlot(player, slot);
        if (selected != DraftManager.SelectionResult.SELECTED) {
            result(player, false, selected == DraftManager.SelectionResult.EMPTY_SLOT ? "message.freemarket.empty_slot" : "message.freemarket.invalid_slot");
            return;
        }
        CompoundTag response = new CompoundTag();
        DRAFTS.getSelected(player).ifPresent(stack -> response.put("Draft", stack.save(new CompoundTag())));
        response.putString("MessageKey", "message.freemarket.item_selected");
        NetworkHandler.sendToPlayer(player, "draft", response);
    }

    private static void cancelDraft(ServerPlayer player) {
        DRAFTS.cancel(player);
        CompoundTag response = new CompoundTag();
        response.putBoolean("ClearDraft", true);
        response.putString("MessageKey", "message.freemarket.draft_cancelled");
        NetworkHandler.sendToPlayer(player, "draft", response);
    }

    private static void createListing(ServerPlayer player, CompoundTag data) {
        ItemStack selected = DRAFTS.getSelected(player).orElse(ItemStack.EMPTY);
        if (selected.isEmpty()) {
            result(player, false, "message.freemarket.select_item_first");
            return;
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        long activeCount = savedData.countListings(player.getUUID(), Set.of(Listing.Status.ACTIVE, Listing.Status.PAUSED));
        if (activeCount >= MAX_ACTIVE_LISTINGS_PER_PLAYER) {
            result(player, false, "message.freemarket.listing_limit");
            return;
        }
        double price = positivePrice(data, "Price");
        String type = string(data, "Type", 24).toUpperCase(Locale.ROOT);
        boolean auction = type.contains("AUCTION") || data.getBoolean("Auction");
        long now = System.currentTimeMillis();
        long durationMinutes = numericLong(data, "Duration", numericLong(data, "DurationMinutes", 60L));
        long duration = Math.multiplyExact(Math.max(0L, durationMinutes), 60_000L);
        if (auction && (duration < MIN_AUCTION_DURATION || duration > MAX_AUCTION_DURATION)) {
            result(player, false, "message.freemarket.invalid_duration");
            return;
        }
        Set<String> tags = validTags(savedData, data);
        Component name = MarketText.parseDisplayName(string(data, "Name", MarketLimits.MAX_PLAIN_NAME_LENGTH * 2), selected.getHoverName(), MarketLimits.MAX_PLAIN_NAME_LENGTH * 2);
        String description = MarketText.clean(data.getString("Description"), MarketLimits.MAX_DESCRIPTION_LENGTH);
        UUID listingId = UUID.randomUUID();
        Listing listing = auction
            ? Listing.auction(listingId, player.getUUID(), player.getGameProfile().getName(), selected, name, price, now + duration, description, tags, now)
            : Listing.fixed(listingId, player.getUUID(), player.getGameProfile().getName(), selected, name, price, description, tags, now);
        ItemStack prepared = DRAFTS.prepareCommit(player, listingId);
        if (prepared.isEmpty()) {
            result(player, false, "message.freemarket.select_item_first");
            return;
        }
        boolean committed = false;
        try {
            if (!savedData.putListing(listing)) {
                throw new IllegalStateException("listing id collision");
            }
            MarketSavedData.saveNow(player.server);
            committed = true;
            if (!DRAFTS.finishCommit(player, listingId)) {
                throw new IllegalStateException("draft commit marker missing");
            }
            notifySavedSearchMatches(player.server, savedData, listing, now);
            if (auction) {
                AUCTIONS.add(new AuctionDeadline(listing.getId(), listing.getAuctionEndAt()));
            }
            ModernNotificationBridge.notify(player, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SUCCESS, "notification.freemarket.listed.title", "notification.freemarket.listed.message", listing.getDisplayName().getString());
            CompoundTag response = snapshot(player, defaultQuery(), true);
            response.putBoolean("ClearDraft", true);
            response.putString("MessageKey", "message.freemarket.listed");
            NetworkHandler.sendToPlayer(player, "sync", response);
            invalidate(player.server);
        } catch (RuntimeException exception) {
            if (!committed) {
                savedData.rollbackUnpersistedListing(listingId);
                DRAFTS.abortCommit(player, listingId);
            }
            throw exception;
        }
    }

    private static void toggleLike(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        MarketSavedData savedData = MarketSavedData.get(player.server);
        Listing listing = requiredListing(player.server, id);
        if (!ensureCanInteract(player, savedData, id)) {
            return;
        }
        boolean liked = data.contains("Liked", Tag.TAG_BYTE) ? data.getBoolean("Liked") : !listing.isLikedBy(player.getUUID());
        savedData.setListingLiked(id, player.getUUID(), liked, System.currentTimeMillis());
        sendListingUpdate(player, savedData, id, "message.freemarket.like_updated");
        invalidate(player.server);
    }

    private static void purchase(ServerPlayer buyer, CompoundTag data) {
        UUID id = listingId(data);
        Listing listing = requiredListing(buyer.server, id);
        long now = System.currentTimeMillis();
        if (!ensureCanInteract(buyer, MarketSavedData.get(buyer.server), id)) {
            return;
        }
        if (listing.getSaleType() != Listing.SaleType.FIXED_PRICE || !listing.isPurchasable(now) || listing.getSellerId().equals(buyer.getUUID())) {
            result(buyer, false, "message.freemarket.not_purchasable");
            return;
        }
        if (!OPERATIONS.add(id)) {
            result(buyer, false, "message.freemarket.transaction_busy");
            return;
        }
        try {
            SaleResult sale = settle(buyer.server, listing, buyer.getUUID(), buyer.getGameProfile().getName(), listing.getCurrentPrice(), now);
            if (!sale.success()) {
                result(buyer, false, sale.messageKey());
                return;
            }
            flushDeliveries(buyer.server, buyer, "purchase delivery acknowledgement failed");
            notifySaleParties(buyer.server, listing, buyer.getUUID(), buyer.getGameProfile().getName(), listing.getCurrentPrice(), false);
            NetworkHandler.sendToPlayer(buyer, "sync", withMessage(snapshot(buyer, defaultQuery(), true), "message.freemarket.purchased"));
            sendReviewPrompt(buyer, listing);
            invalidate(buyer.server);
        } finally {
            OPERATIONS.remove(id);
        }
    }

    private static void bid(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        double amount = data.contains("Amount") ? positivePrice(data, "Amount") : positivePrice(data, "Price");
        if (!transactionsAvailable(player.server)) {
            result(player, false, "message.freemarket.transactions_suspended");
            return;
        }
        if (!OPERATIONS.add(id)) {
            result(player, false, "message.freemarket.transaction_busy");
            return;
        }
        try {
            Listing listing = requiredListing(player.server, id);
            MarketSavedData savedData = MarketSavedData.get(player.server);
            long now = System.currentTimeMillis();
            if (!ensureCanInteract(player, savedData, id)) {
                return;
            }
            if (listing.getSaleType() != Listing.SaleType.AUCTION || !listing.isPurchasable(now) || listing.getSellerId().equals(player.getUUID()) || amount <= listing.getCurrentPrice()) {
                result(player, false, "message.freemarket.invalid_bid");
                return;
            }
            Optional<BidRecord> previous = listing.getHighestBid();
            AuctionEscrowLog escrowLog = AuctionEscrowLog.get(player.server);
            Optional<AuctionEscrowLog.Entry> currentEscrow = escrowLog.getEntry(id);
            if (!escrowMatchesListingBid(listing, currentEscrow, previous)) {
                holdLegacyAuctionForReview(player.server, listing, previous.orElse(null), "auction bid does not match escrow journal");
                result(player, false, "message.freemarket.payment_review");
                return;
            }
            double requiredFunds = currentEscrow.filter(entry -> entry.state() == AuctionEscrowLog.State.HELD && player.getUUID().equals(entry.holderId())).map(entry -> amount - entry.heldAmount()).orElse(amount);
            VaultEconomyBridge.HasResult funds = VaultEconomyBridge.has(player.getUUID(), requiredFunds);
            if (!funds.success()) {
                result(player, false, "message.freemarket.economy_unavailable");
                return;
            }
            if (!funds.has()) {
                result(player, false, "message.freemarket.insufficient_funds");
                return;
            }
            AuctionEscrowLog.Entry prepared = escrowLog.prepareBid(listing, player.getUUID(), player.getGameProfile().getName(), amount, now);
            try {
                AuctionEscrowLog.saveNow(player.server);
            } catch (RuntimeException exception) {
                suspendTransactions(player.server, "auction bid preparation journal failed");
                result(player, false, "message.freemarket.transactions_suspended");
                return;
            }
            VaultEconomyBridge.TransactionResult withdrawal = VaultEconomyBridge.withdraw(player.getUUID(), prepared.debitAmount());
            if (!withdrawal.success()) {
                if (withdrawal.status() == VaultEconomyBridge.Status.DECLINED) {
                    escrowLog.restoreAfterDecline(id, System.currentTimeMillis());
                    try {
                        AuctionEscrowLog.saveNow(player.server);
                    } catch (RuntimeException exception) {
                        suspendTransactions(player.server, "auction declined debit journal failed");
                        result(player, false, "message.freemarket.transactions_suspended");
                        return;
                    }
                    result(player, false, "message.freemarket.insufficient_funds");
                    return;
                }
                holdAuctionEscrowForReview(player.server, listing, "auction bidder debit outcome unknown");
                result(player, false, "message.freemarket.payment_review");
                return;
            }
            escrowLog.transition(id, AuctionEscrowLog.State.BID_NEW_DEBITED, "", System.currentTimeMillis());
            try {
                AuctionEscrowLog.saveNow(player.server);
            } catch (RuntimeException exception) {
                suspendTransactions(player.server, "auction bidder debit journal failed");
                holdAuctionEscrowForReview(player.server, listing, "auction bidder debit confirmed but journal persistence failed");
                result(player, false, "message.freemarket.payment_review");
                return;
            }
            if (prepared.holderId() != null && !prepared.holderId().equals(player.getUUID())) {
                VaultEconomyBridge.TransactionResult refund = VaultEconomyBridge.deposit(prepared.holderId(), prepared.heldAmount());
                if (!refund.success()) {
                    holdAuctionEscrowForReview(player.server, listing, refund.status() == VaultEconomyBridge.Status.DECLINED ? "previous bidder refund declined" : "previous bidder refund outcome unknown");
                    result(player, false, "message.freemarket.payment_review");
                    return;
                }
                escrowLog.transition(id, AuctionEscrowLog.State.BID_PREVIOUS_REFUNDED, "", System.currentTimeMillis());
                try {
                    AuctionEscrowLog.saveNow(player.server);
                } catch (RuntimeException exception) {
                    suspendTransactions(player.server, "auction previous bidder refund journal failed");
                    holdAuctionEscrowForReview(player.server, listing, "previous bidder refund confirmed but journal persistence failed");
                    result(player, false, "message.freemarket.payment_review");
                    return;
                }
            }
            escrowLog.commitBid(id, System.currentTimeMillis());
            try {
                AuctionEscrowLog.saveNow(player.server);
            } catch (RuntimeException exception) {
                suspendTransactions(player.server, "auction escrow commit journal failed");
                holdAuctionEscrowForReview(player.server, listing, "new auction escrow could not be committed durably");
                result(player, false, "message.freemarket.payment_review");
                return;
            }
            try {
                savedData.placeBid(id, player.getUUID(), player.getGameProfile().getName(), amount, System.currentTimeMillis());
                MarketSavedData.saveNow(player.server);
            } catch (RuntimeException exception) {
                suspendTransactions(player.server, "auction listing bid save failed");
                try {
                    holdAuctionEscrowForReview(player.server, listing, "listing bid commit failed after escrow debit");
                } catch (RuntimeException reviewException) {
                    FreeMarket.LOGGER.error("Unable to hold auction {} after listing save failure", listing.getId(), reviewException);
                }
                result(player, false, "message.freemarket.payment_review");
                return;
            }
            previous.filter(value -> !value.getBidderId().equals(player.getUUID())).ifPresent(value -> {
                persistentNotification(player.server, value.getBidderId(), value.getBidderName(), MarketNotification.Type.OUTBID, listing, "notification.freemarket.outbid.title", listing.getDisplayName().getString());
                ServerPlayer previousPlayer = player.server.getPlayerList().getPlayer(value.getBidderId());
                if (previousPlayer != null) {
                    ModernNotificationBridge.notify(previousPlayer, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.WARNING, "notification.freemarket.outbid.title", "notification.freemarket.outbid.message", listing.getDisplayName().getString());
                }
            });
            ModernNotificationBridge.notify(player, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SUCCESS, "notification.freemarket.bid.title", "notification.freemarket.bid.message", listing.getDisplayName().getString(), Math.round(amount));
            sendListingUpdate(player, savedData, id, "message.freemarket.bid_placed");
            invalidate(player.server);
        } finally {
            OPERATIONS.remove(id);
        }
    }

    private static boolean escrowMatchesBid(Optional<AuctionEscrowLog.Entry> escrow, Optional<BidRecord> bid) {
        if (bid.isEmpty()) {
            return escrow.isEmpty() || escrow.get().terminal();
        }
        if (escrow.isEmpty()) {
            return false;
        }
        AuctionEscrowLog.Entry entry = escrow.get();
        BidRecord highest = bid.get();
        return entry.state() == AuctionEscrowLog.State.HELD
            && highest.getBidderId().equals(entry.holderId())
            && Double.compare(highest.getAmount(), entry.heldAmount()) == 0;
    }

    private static boolean escrowMatchesListingBid(Listing listing, Optional<AuctionEscrowLog.Entry> escrow, Optional<BidRecord> bid) {
        if (!escrowMatchesBid(escrow, bid)) {
            return false;
        }
        return escrow.isEmpty() || escrow.get().terminal() || listing.getSellerId().equals(escrow.get().sellerId());
    }

    private static boolean escrowSettlementMatches(Listing listing, AuctionEscrowLog.Entry entry) {
        if (listing.getSaleType() != Listing.SaleType.AUCTION
            || entry.holderId() == null
            || !listing.getSellerId().equals(entry.sellerId())
            || Double.compare(listing.getCurrentPrice(), entry.heldAmount()) != 0) {
            return false;
        }
        Optional<BidRecord> highest = listing.getHighestBid();
        if (highest.isEmpty()
            || !highest.get().getBidderId().equals(entry.holderId())
            || Double.compare(highest.get().getAmount(), entry.heldAmount()) != 0) {
            return false;
        }
        if (listing.getStatus() == Listing.Status.SOLD) {
            return listing.getBuyerId().filter(entry.holderId()::equals).isPresent();
        }
        return listing.getStatus() == Listing.Status.ACTIVE || listing.getStatus() == Listing.Status.PAYMENT_REVIEW;
    }

    private static void holdLegacyAuctionForReview(MinecraftServer server, Listing listing, BidRecord bid, String detail) {
        AuctionEscrowLog log = AuctionEscrowLog.get(server);
        if (bid != null && log.getEntry(listing.getId()).isEmpty()) {
            log.createLegacyManual(listing, bid, detail, System.currentTimeMillis());
        } else if (log.getEntry(listing.getId()).isPresent()) {
            log.manual(listing.getId(), detail, System.currentTimeMillis());
        } else {
            return;
        }
        holdAuctionEscrowForReview(server, listing, detail);
    }

    private static void holdAuctionEscrowForReview(MinecraftServer server, Listing listing, String detail) {
        AuctionEscrowLog escrowLog = AuctionEscrowLog.get(server);
        Optional<AuctionEscrowLog.Entry> optional = escrowLog.getEntry(listing.getId());
        if (optional.isEmpty()) {
            return;
        }
        AuctionEscrowLog.Entry escrow = optional.get();
        if (escrow.state() != AuctionEscrowLog.State.MANUAL_REVIEW) {
            escrow = escrowLog.manual(listing.getId(), detail, System.currentTimeMillis());
        }
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction escrow manual review journal failed");
        }
        MarketSavedData savedData = MarketSavedData.get(server);
        try {
            savedData.updateListing(listing.getId(), value -> {
                if (value.getStatus() == Listing.Status.ACTIVE || value.getStatus() == Listing.Status.PAUSED) {
                    value.holdForPaymentReview(System.currentTimeMillis());
                }
            });
            MarketSavedData.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction payment review listing save failed");
        }
        mirrorEscrowManualReview(server, escrow, detail);
        UUID buyerId = escrow.effectiveBuyerId();
        if (buyerId != null) {
            notifyManualReview(server, listing, buyerId, escrow.effectiveBuyerName());
        }
        invalidate(server);
    }

    private static void mirrorEscrowManualReview(MinecraftServer server, AuctionEscrowLog.Entry escrow, String detail) {
        MarketTransactionLog transactionLog = MarketTransactionLog.get(server);
        try {
            transactionLog.mirrorAuctionEscrow(escrow, detail, System.currentTimeMillis());
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction escrow review mirror journal failed");
        }
    }

    private static void comment(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        Listing listing = requiredListing(player.server, id);
        if (listing.getStatus() != Listing.Status.ACTIVE && listing.getStatus() != Listing.Status.PAUSED) {
            result(player, false, "message.freemarket.comment_closed");
            return;
        }
        if (!ensureCanInteract(player, MarketSavedData.get(player.server), id)) {
            return;
        }
        String message = MarketText.clean(data.getString("Message"), MarketLimits.MAX_COMMENT_LENGTH);
        if (message.isBlank()) {
            result(player, false, "message.freemarket.empty_comment");
            return;
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.addComment(id, player.getUUID(), player.getGameProfile().getName(), message, System.currentTimeMillis());
        if (!listing.getSellerId().equals(player.getUUID())) {
            persistentNotification(player.server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.COMMENT, listing, "notification.freemarket.comment.title", player.getGameProfile().getName() + ": " + message);
            ServerPlayer seller = player.server.getPlayerList().getPlayer(listing.getSellerId());
            if (seller != null) {
                ModernNotificationBridge.notify(seller, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SYSTEM, "notification.freemarket.comment.title", "notification.freemarket.comment.message", player.getGameProfile().getName(), listing.getDisplayName().getString());
            }
        }
        sendListingUpdate(player, savedData, id, "message.freemarket.comment_sent");
        invalidate(player.server);
    }

    private static void priceRequest(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        Listing listing = requiredListing(player.server, id);
        MarketSavedData savedData = MarketSavedData.get(player.server);
        if (!ensureCanInteract(player, savedData, id)) {
            return;
        }
        double amount = data.contains("Amount") ? positivePrice(data, "Amount") : positivePrice(data, "Price");
        if (listing.getSaleType() != Listing.SaleType.FIXED_PRICE || !listing.isPurchasable(System.currentTimeMillis()) || listing.getSellerId().equals(player.getUUID()) || amount >= listing.getCurrentPrice()) {
            result(player, false, "message.freemarket.invalid_offer");
            return;
        }
        try {
            savedData.submitOffer(id, player.getUUID(), player.getGameProfile().getName(), amount, System.currentTimeMillis());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            result(player, false, "message.freemarket.invalid_offer");
            return;
        }
        String detail = player.getGameProfile().getName() + " -> " + Math.round(amount) + ": " + listing.getDisplayName().getString();
        persistentNotification(player.server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.PRICE_REQUEST, listing, "notification.freemarket.offer.title", detail);
        ServerPlayer seller = player.server.getPlayerList().getPlayer(listing.getSellerId());
        if (seller != null) {
            ModernNotificationBridge.notify(seller, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SYSTEM, "notification.freemarket.offer.title", "notification.freemarket.offer.message", player.getGameProfile().getName(), Math.round(amount), listing.getDisplayName().getString());
        }
        sendListingUpdate(player, savedData, id, "message.freemarket.offer_sent");
        invalidate(player.server);
    }

    private static void respondOffer(ServerPlayer player, CompoundTag data, boolean accepted) {
        UUID id;
        UUID offerId;
        try {
            id = UUID.fromString(data.contains("ListingId", Tag.TAG_STRING) ? data.getString("ListingId") : data.getString("Id"));
            offerId = UUID.fromString(data.getString("OfferId"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("offerId");
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        Optional<MarketSavedData.OfferDecision> decision;
        try {
            decision = savedData.respondOffer(id, offerId, player.getUUID(), accepted, System.currentTimeMillis());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            result(player, false, "message.freemarket.invalid_offer");
            return;
        }
        if (decision.isEmpty()) {
            result(player, false, "message.freemarket.invalid_offer");
            return;
        }
        MarketSavedData.saveNow(player.server);
        Listing listing = decision.get().listing();
        MarketOffer offer = decision.get().offer();
        String itemName = listing.getDisplayName().getString();
        if (accepted) {
            persistentNotification(player.server, offer.getRequesterId(), offer.getRequesterName(), MarketNotification.Type.PRICE_REQUEST, listing, "notification.freemarket.offer_accepted.title", itemName + " / " + Math.round(offer.getAmount()));
            ServerPlayer requester = player.server.getPlayerList().getPlayer(offer.getRequesterId());
            if (requester != null) {
                ModernNotificationBridge.notify(requester, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SYSTEM, "notification.freemarket.offer_accepted.title", "notification.freemarket.offer_accepted.message", itemName, Math.round(offer.getAmount()));
            }
            for (MarketOffer rejected : decision.get().automaticallyRejected()) {
                persistentNotification(player.server, rejected.getRequesterId(), rejected.getRequesterName(), MarketNotification.Type.PRICE_REQUEST, listing, "notification.freemarket.offer_rejected.title", itemName);
            }
        } else {
            persistentNotification(player.server, offer.getRequesterId(), offer.getRequesterName(), MarketNotification.Type.PRICE_REQUEST, listing, "notification.freemarket.offer_rejected.title", itemName);
            ServerPlayer requester = player.server.getPlayerList().getPlayer(offer.getRequesterId());
            if (requester != null) {
                ModernNotificationBridge.notify(requester, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SYSTEM, "notification.freemarket.offer_rejected.title", "notification.freemarket.offer_rejected.message", itemName);
            }
        }
        sendListingUpdate(player, savedData, id, accepted ? "message.freemarket.offer_accepted" : "message.freemarket.offer_rejected");
        invalidate(player.server);
    }

    private static void setPaused(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        Listing listing = ownedListing(player, id);
        long now = System.currentTimeMillis();
        MarketSavedData savedData = MarketSavedData.get(player.server);
        boolean requestedPause = data.contains("Paused", Tag.TAG_BYTE) ? data.getBoolean("Paused") : listing.getStatus() == Listing.Status.ACTIVE;
        savedData.updateListing(id, value -> {
            if (requestedPause && value.getStatus() == Listing.Status.ACTIVE) {
                value.pause(now);
            } else if (!requestedPause && value.getStatus() == Listing.Status.PAUSED) {
                value.resume(now);
            }
        });
        MarketSavedData.saveNow(player.server);
        sendListingUpdate(player, savedData, id, requestedPause ? "message.freemarket.paused" : "message.freemarket.resumed");
        invalidate(player.server);
    }

    private static void cancelListing(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        Listing listing = ownedListing(player, id);
        if (!PendingDeliveries.get(player.server).canEnqueue(listing.getSellerId(), id)) {
            result(player, false, "message.freemarket.delivery_queue_full");
            return;
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.updateListing(id, value -> value.cancel(System.currentTimeMillis()));
        MarketSavedData.saveNow(player.server);
        deliver(player.server, listing.getSellerId(), id, listing.getItem());
        sendListingUpdate(player, savedData, id, "message.freemarket.listing_cancelled");
        invalidate(player.server);
    }

    private static void editListing(ServerPlayer player, CompoundTag data) {
        UUID id = listingId(data);
        Listing listing = ownedListing(player, id);
        Component name = MarketText.parseDisplayName(string(data, "Name", MarketLimits.MAX_PLAIN_NAME_LENGTH * 2), listing.getDisplayName(), MarketLimits.MAX_PLAIN_NAME_LENGTH * 2);
        String description = data.contains("Description", Tag.TAG_STRING) ? MarketText.clean(data.getString("Description"), MarketLimits.MAX_DESCRIPTION_LENGTH) : listing.getDescription();
        Set<String> tags = data.contains("Tags", Tag.TAG_LIST) || data.contains("Tags", Tag.TAG_STRING) ? validTags(MarketSavedData.get(player.server), data) : listing.getTags();
        Double price = null;
        if (listing.getSaleType() == Listing.SaleType.FIXED_PRICE && (data.contains("Price", Tag.TAG_ANY_NUMERIC) || data.contains("Price", Tag.TAG_STRING))) {
            price = positivePrice(data, "Price");
        }
        Double finalPrice = price;
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.updateListing(id, value -> value.edit(name, description, tags, finalPrice, System.currentTimeMillis()));
        MarketSavedData.saveNow(player.server);
        if (finalPrice != null && finalPrice < listing.getCurrentPrice()) {
            savedData.getListing(id).ifPresent(updated -> notifyPriceDrop(player.server, savedData, updated));
        }
        sendListingUpdate(player, savedData, id, "message.freemarket.listing_edited");
        invalidate(player.server);
    }

    private static void adminDelete(ServerPlayer player, CompoundTag data) {
        if (!isAdmin(player)) {
            result(player, false, "message.freemarket.permission_denied");
            return;
        }
        UUID id = listingId(data);
        Listing listing = requiredListing(player.server, id);
        if (listing.getStatus() == Listing.Status.CANCELLED || listing.getStatus() == Listing.Status.REMOVED || listing.getStatus() == Listing.Status.PAYMENT_REVIEW) {
            result(player, false, "message.freemarket.not_removable");
            return;
        }
        if (!PendingDeliveries.get(player.server).canEnqueue(listing.getSellerId(), id)) {
            result(player, false, "message.freemarket.delivery_queue_full");
            return;
        }
        if (listing.getStatus() == Listing.Status.SOLD) {
            adminReverseCompletedSale(player, listing);
            return;
        }
        if (listing.getSaleType() == Listing.SaleType.AUCTION && listing.getHighestBid().isPresent()) {
            if (!refundAuctionEscrow(player.server, listing, "auction removed by operator")) {
                result(player, false, "message.freemarket.payment_review");
                return;
            }
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.adminRemoveListing(id, System.currentTimeMillis());
        MarketSavedData.saveNow(player.server);
        if (listing.getStatus() == Listing.Status.ACTIVE || listing.getStatus() == Listing.Status.PAUSED) {
            deliver(player.server, listing.getSellerId(), id, listing.getItem());
        }
        persistentNotification(player.server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.LISTING_REMOVED, listing, "notification.freemarket.removed.title", listing.getDisplayName().getString());
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.admin_removed"));
        invalidate(player.server);
    }

    private static void adminReverseCompletedSale(ServerPlayer player, Listing listing) {
        MinecraftServer server = player.server;
        if (!transactionsAvailable(server)) {
            result(player, false, "message.freemarket.transactions_suspended");
            return;
        }
        UUID buyerId = listing.getBuyerId().orElse(null);
        if (buyerId == null) {
            result(player, false, "message.freemarket.not_removable");
            return;
        }
        String buyerName = listing.getBuyerName();
        double gross = listing.getCurrentPrice();
        FeeConfig fee = MarketSavedData.get(server).getFeeConfig();
        double net = Math.max(0D, gross - fee.calculate(gross));
        if (gross > 0D) {
            VaultEconomyBridge.TransactionResult refund = VaultEconomyBridge.deposit(buyerId, gross);
            if (!refund.success()) {
                result(player, false, "message.freemarket.refund_failed");
                return;
            }
        }
        if (net > 0D) {
            VaultEconomyBridge.TransactionResult clawback = VaultEconomyBridge.withdraw(listing.getSellerId(), net);
            if (!clawback.success()) {
                FreeMarket.LOGGER.warn("Admin reversal of listing {}: unable to withdraw {} from seller {} ({})", listing.getId(), net, listing.getSellerName(), clawback.message());
            }
        }
        PendingDeliveries.get(server).remove(buyerId, listing.getId());
        persistPendingDeliveries(server, "admin reversal pending delivery removal save failed");
        MarketSavedData savedData = MarketSavedData.get(server);
        savedData.adminRemoveListing(listing.getId(), System.currentTimeMillis());
        savedData.removePurchase(buyerId, listing.getId());
        MarketSavedData.saveNow(server);
        deliver(server, listing.getSellerId(), listing.getId(), listing.getItem());
        String itemName = listing.getDisplayName().getString();
        persistentNotification(server, buyerId, buyerName, MarketNotification.Type.SYSTEM, listing, "notification.freemarket.admin_refund.title", itemName + " / " + Math.round(gross));
        ServerPlayer buyerOnline = server.getPlayerList().getPlayer(buyerId);
        if (buyerOnline != null) {
            ModernNotificationBridge.notify(buyerOnline, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SYSTEM, "notification.freemarket.admin_refund.title", "notification.freemarket.admin_refund.message", itemName, Math.round(gross));
        }
        persistentNotification(server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.LISTING_REMOVED, listing, "notification.freemarket.removed.title", itemName);
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.admin_refunded"));
        invalidate(server);
    }

    private static void readNotification(ServerPlayer player, CompoundTag data) {
        UUID id = id(data, "Id");
        if (id != null) {
            MarketSavedData.get(player.server).markNotificationRead(player.getUUID(), id, System.currentTimeMillis());
        }
        sendPersonalSync(player, "message.freemarket.notification_read");
    }

    private static void readAllNotifications(ServerPlayer player) {
        MarketSavedData.get(player.server).markAllNotificationsRead(player.getUUID(), System.currentTimeMillis());
        sendPersonalSync(player, "message.freemarket.notifications_read");
    }

    private static void deleteNotification(ServerPlayer player, CompoundTag data) {
        UUID id = id(data, "Id");
        if (id != null) {
            MarketSavedData.get(player.server).deleteNotification(player.getUUID(), id);
        }
        sendPersonalSync(player, "message.freemarket.notification_deleted");
    }

    private static void saveTag(ServerPlayer player, CompoundTag data) {
        requireAdmin(player);
        MarketSavedData savedData = MarketSavedData.get(player.server);
        String inputName = firstString(data, "Id", "Tag");
        if (inputName.isBlank()) {
            inputName = data.getString("Name");
        }
        String oldName = MarketText.cleanTag(data.getString("OldName"));
        String tagId = MarketText.cleanTag(inputName);
        if (tagId.isBlank()) {
            throw new IllegalArgumentException("tag");
        }
        MarketTag previous = savedData.getTag(oldName.isBlank() ? tagId : oldName).orElse(null);
        String label = MarketText.clean(data.getString("Label"), MarketLimits.MAX_TAG_LABEL_LENGTH);
        if (label.isBlank()) {
            label = previous == null ? tagId : previous.getFallbackLabel();
        }
        String parent = data.contains("Parent", Tag.TAG_STRING) ? MarketText.cleanTag(data.getString("Parent")) : previous == null ? "" : previous.getParentId();
        int order = data.contains("Order", Tag.TAG_ANY_NUMERIC) ? data.getInt("Order") : previous == null ? savedData.getTags().size() : previous.getSortOrder();
        boolean enabled = data.contains("Enabled", Tag.TAG_BYTE) ? data.getBoolean("Enabled") : previous == null || previous.isEnabled();
        String translationKey = previous != null && previous.getId().equals(tagId) ? previous.getTranslationKey() : "tag.freemarket." + tagId.replace('.', '_');
        MarketTag tag = new MarketTag(tagId, translationKey, label, parent, order, enabled);
        savedData.upsertTag(tag);
        if (!oldName.isBlank() && !oldName.equals(tagId)) {
            savedData.removeTag(oldName);
        }
        MarketSavedData.saveNow(player.server);
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.tag_saved"));
        invalidate(player.server);
    }

    private static void deleteTag(ServerPlayer player, CompoundTag data) {
        requireAdmin(player);
        String inputName = firstString(data, "Id", "Tag");
        if (inputName.isBlank()) {
            inputName = data.getString("Name");
        }
        String tagId = MarketText.cleanTag(inputName);
        MarketSavedData.get(player.server).removeTag(tagId);
        MarketSavedData.saveNow(player.server);
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.tag_deleted"));
        invalidate(player.server);
    }

    private static void saveFee(ServerPlayer player, CompoundTag data) {
        requireAdmin(player);
        String modeValue = data.getString("Mode").toUpperCase(Locale.ROOT);
        FeeConfig.Mode mode = modeValue.contains("FIXED") ? FeeConfig.Mode.FIXED : FeeConfig.Mode.PERCENT;
        double amount = number(data, "Value", number(data, "Amount", 0D));
        MarketSavedData.get(player.server).setFeeConfig(new FeeConfig(mode, amount));
        MarketSavedData.saveNow(player.server);
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.fee_saved"));
        invalidate(player.server);
    }

    private static void rateSeller(ServerPlayer player, CompoundTag data) {
        Listing listing = requiredListing(player.server, listingId(data));
        int stars = data.getInt("Stars");
        if (listing.getStatus() != Listing.Status.SOLD || listing.getBuyerId().isEmpty() || !listing.getBuyerId().get().equals(player.getUUID()) || stars < 1 || stars > 5) {
            result(player, false, "message.freemarket.invalid_review");
            return;
        }
        String comment = MarketText.clean(data.getString("Comment"), MarketLimits.MAX_REVIEW_COMMENT_LENGTH);
        boolean saved = MarketSavedData.get(player.server).rateCompletedSale(player.getUUID(), player.getGameProfile().getName(), listing.getId(), stars, comment);
        result(player, saved, saved ? "message.freemarket.review_saved" : "message.freemarket.review_already_submitted");
    }

    private static void sellerProfile(ServerPlayer player, CompoundTag data) {
        UUID sellerId;
        try {
            sellerId = UUID.fromString(data.getString("SellerId"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sellerId");
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        Optional<MarketUserData> userData = savedData.getUser(sellerId);
        CompoundTag seller = new CompoundTag();
        seller.putString("Id", sellerId.toString());
        String fallbackName = MarketText.clean(data.getString("SellerName"), MarketLimits.MAX_PLAYER_NAME_LENGTH);
        String name = userData.map(MarketUserData::getUsername).filter(value -> !value.isBlank()).orElse(fallbackName);
        seller.putString("Name", name);
        seller.putDouble("Rating", userData.map(MarketUserData::getAverageRating).orElse(0D));
        seller.putInt("ReviewCount", userData.map(MarketUserData::getRatingCount).orElse(0));
        ListTag reviews = new ListTag();
        userData.ifPresent(value -> value.getReviews().forEach(review -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", review.getId().toString());
            entry.putString("ReviewerId", review.getReviewerId().toString());
            entry.putString("Reviewer", review.getReviewerName());
            entry.putInt("Stars", review.getStars());
            entry.putString("Comment", review.getComment());
            entry.putLong("CreatedAt", review.getCreatedAt());
            reviews.add(entry);
        }));
        seller.put("Reviews", reviews);
        boolean privileged = player.getUUID().equals(sellerId) || isAdmin(player);
        List<Listing> sellerListings = savedData.getListings().stream()
            .filter(listing -> sellerId.equals(listing.getSellerId()))
            .filter(listing -> listing.getStatus() == Listing.Status.ACTIVE
                || listing.getStatus() == Listing.Status.SOLD
                || privileged && listing.getStatus() == Listing.Status.PAUSED)
            .sorted(Comparator.comparingLong(Listing::getUpdatedAt).reversed())
            .limit(60)
            .toList();
        ListTag listingsTag = new ListTag();
        for (Listing listing : sellerListings) {
            listingsTag.add(listingTag(listing, player.getUUID(), false));
        }
        CompoundTag payload = new CompoundTag();
        payload.put("Seller", seller);
        payload.put("SellerListings", listingsTag);
        NetworkHandler.sendToPlayer(player, "seller", payload);
    }

    private static void saveSearch(ServerPlayer player, CompoundTag data) {
        MarketSavedData savedData = MarketSavedData.get(player.server);
        MarketQuery searchQuery = queryFrom(data, player).toBuilder().page(0).build();
        String name = MarketText.clean(data.getString("Name"), MarketLimits.MAX_SAVED_SEARCH_NAME_LENGTH);
        if (name.isBlank()) {
            name = searchQuery.getText().isBlank() ? "Search " + (savedData.getSavedSearches(player.getUUID()).size() + 1) : searchQuery.getText();
        }
        boolean notifications = !data.contains("NotificationsEnabled", Tag.TAG_BYTE) || data.getBoolean("NotificationsEnabled");
        savedData.saveSearch(player.getUUID(), player.getGameProfile().getName(), name, searchQuery, notifications, System.currentTimeMillis());
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.search_saved"));
    }

    private static void runSavedSearch(ServerPlayer player, CompoundTag data) {
        UUID searchId = id(data, "SearchId");
        if (searchId == null) {
            searchId = id(data, "Id");
        }
        SavedMarketSearch search = MarketSavedData.get(player.server).getSavedSearch(player.getUUID(), searchId).orElseThrow(() -> new IllegalArgumentException("search"));
        VIEWERS.add(player.getUUID());
        queueQuery(player, search.queryPage(0, 30));
        result(player, true, "message.freemarket.saved_search_opened");
    }

    private static void deleteSavedSearch(ServerPlayer player, CompoundTag data) {
        UUID searchId = id(data, "SearchId");
        if (searchId == null) {
            searchId = id(data, "Id");
        }
        if (searchId == null || !MarketSavedData.get(player.server).deleteSavedSearch(player.getUUID(), searchId)) {
            result(player, false, "message.freemarket.saved_search_missing");
            return;
        }
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.saved_search_deleted"));
    }

    private static void setSellerBlocked(ServerPlayer player, CompoundTag data, boolean blocked) {
        MarketSavedData savedData = MarketSavedData.get(player.server);
        UUID targetId = id(data, "TargetId");
        if (targetId == null) {
            UUID listingId = listingId(data);
            targetId = requiredListing(player.server, listingId).getSellerId();
        }
        if (targetId.equals(player.getUUID())) {
            throw new IllegalArgumentException("self block");
        }
        boolean changed = savedData.setUserBlocked(player.getUUID(), player.getGameProfile().getName(), targetId, blocked);
        String messageKey = blocked ? "message.freemarket.user_blocked" : "message.freemarket.user_unblocked";
        if (!changed) {
            result(player, true, messageKey);
            return;
        }
        CompoundTag response = withMessage(snapshot(player, defaultQuery(), true), messageKey);
        response.putBoolean("SellerBlocked", blocked);
        NetworkHandler.sendToPlayer(player, "sync", response);
        invalidate(player.server);
    }

    private static void reportListing(ServerPlayer player, CompoundTag data) {
        UUID listingId = listingId(data);
        String reasonName = firstString(data, "Reason", "ReportReason").toUpperCase(Locale.ROOT);
        MarketReport.Reason reason;
        try {
            reason = MarketReport.Reason.valueOf(reasonName);
        } catch (IllegalArgumentException exception) {
            reason = MarketReport.Reason.OTHER;
        }
        String detail = MarketText.clean(data.getString("Detail"), MarketLimits.MAX_REPORT_DETAIL_LENGTH);
        if (reason == MarketReport.Reason.OTHER && detail.isBlank()) {
            result(player, false, "message.freemarket.report_detail_required");
            return;
        }
        MarketSavedData savedData = MarketSavedData.get(player.server);
        savedData.submitReport(listingId, player.getUUID(), player.getGameProfile().getName(), reason, detail, System.currentTimeMillis());
        UUID reportedSellerId = savedData.getListing(listingId).map(Listing::getSellerId).orElse(null);
        for (ServerPlayer operator : player.server.getPlayerList().getPlayers()) {
            if (operator.hasPermissions(2) && !operator.getUUID().equals(reportedSellerId) && !operator.getUUID().equals(player.getUUID())) {
                ModernNotificationBridge.notify(operator, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.WARNING, "notification.freemarket.report.title", "notification.freemarket.report.message", player.getGameProfile().getName());
            }
        }
        result(player, true, "message.freemarket.report_submitted");
    }

    private static void reviewReport(ServerPlayer player, CompoundTag data) {
        requireAdmin(player);
        UUID reportId = id(data, "ReportId");
        if (reportId == null) {
            reportId = id(data, "Id");
        }
        String statusName = data.getString("Status").toUpperCase(Locale.ROOT);
        MarketReport.Status status;
        try {
            status = MarketReport.Status.valueOf(statusName);
        } catch (IllegalArgumentException exception) {
            status = MarketReport.Status.REVIEWING;
        }
        MarketReport.Status reviewStatus = status;
        String resolution = MarketText.clean(data.getString("Resolution"), MarketLimits.MAX_REPORT_RESOLUTION_LENGTH);
        MarketSavedData savedData = MarketSavedData.get(player.server);
        MarketReport report = savedData.reviewReport(reportId, reviewStatus, player.getUUID(), resolution, System.currentTimeMillis()).orElseThrow(() -> new IllegalArgumentException("report"));
        if (reviewStatus == MarketReport.Status.RESOLVED || reviewStatus == MarketReport.Status.DISMISSED) {
            savedData.getListing(report.getListingId()).ifPresent(listing -> {
                String key = reviewStatus == MarketReport.Status.RESOLVED ? "notification.freemarket.report_resolved.title" : "notification.freemarket.report_dismissed.title";
                persistentNotification(player.server, report.getReporterId(), report.getReporterName(), MarketNotification.Type.SYSTEM, listing, key, listing.getDisplayName().getString());
            });
        }
        NetworkHandler.sendToPlayer(player, "sync", withMessage(snapshot(player, defaultQuery(), true), "message.freemarket.report_reviewed"));
    }

    private static boolean ensureCanInteract(ServerPlayer player, MarketSavedData data, UUID listingId) {
        if (data.canInteractWithListing(player.getUUID(), listingId)) {
            return true;
        }
        result(player, false, "message.freemarket.interaction_blocked");
        return false;
    }

    private static void notifySavedSearchMatches(MinecraftServer server, MarketSavedData data, Listing listing, long timestamp) {
        for (MarketSavedData.SavedSearchMatch match : data.consumeSavedSearchMatches(listing.getId(), timestamp)) {
            String message = match.search().getName() + ": " + listing.getDisplayName().getString();
            persistentNotification(server, match.userId(), match.username(), MarketNotification.Type.SYSTEM, listing, "notification.freemarket.saved_search.title", message);
            ServerPlayer online = server.getPlayerList().getPlayer(match.userId());
            if (online != null) {
                ModernNotificationBridge.notify(online, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SYSTEM, "notification.freemarket.saved_search.title", "notification.freemarket.saved_search.message", match.search().getName(), listing.getDisplayName().getString());
            }
        }
    }

    private static void notifyPriceDrop(MinecraftServer server, MarketSavedData data, Listing listing) {
        for (UUID userId : listing.getLikedBy()) {
            if (userId.equals(listing.getSellerId()) || !data.canInteract(userId, listing.getSellerId())) {
                continue;
            }
            String username = data.getUser(userId).map(MarketUserData::getUsername).orElse(userId.toString());
            persistentNotification(server, userId, username, MarketNotification.Type.SYSTEM, listing, "notification.freemarket.price_drop.title", listing.getDisplayName().getString() + ": " + Math.round(listing.getCurrentPrice()));
            ServerPlayer online = server.getPlayerList().getPlayer(userId);
            if (online != null) {
                ModernNotificationBridge.notify(online, ModernNotificationBridge.Placement.LEFT, ModernNotificationBridge.Category.SUCCESS, "notification.freemarket.price_drop.title", "notification.freemarket.price_drop.message", listing.getDisplayName().getString(), Math.round(listing.getCurrentPrice()));
            }
        }
    }

    private static void close(ServerPlayer player) {
        DRAFTS.cancel(player);
        VIEWERS.remove(player.getUUID());
    }

    private static SaleResult settle(MinecraftServer server, Listing listing, UUID buyerId, String buyerName, double gross, long now) {
        if (!transactionsAvailable(server)) {
            return SaleResult.failed("message.freemarket.transactions_suspended");
        }
        VaultEconomyBridge.HasResult funds = VaultEconomyBridge.has(buyerId, gross);
        if (!funds.success()) {
            return SaleResult.failed("message.freemarket.economy_unavailable");
        }
        if (!funds.has()) {
            return SaleResult.failed("message.freemarket.insufficient_funds");
        }
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        if (!deliveries.canEnqueue(buyerId, listing.getId())) {
            return SaleResult.failed("message.freemarket.delivery_queue_full");
        }
        FeeConfig fee = MarketSavedData.get(server).getFeeConfig();
        double net = Math.max(0D, gross - fee.calculate(gross));
        MarketTransactionLog log = MarketTransactionLog.get(server);
        log.begin(listing, buyerId, buyerName, gross, net, now);
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "journal preparation failed");
            return SaleResult.failed("message.freemarket.transactions_suspended");
        }
        VaultEconomyBridge.TransactionResult withdrawal = VaultEconomyBridge.withdraw(buyerId, gross);
        if (!withdrawal.success()) {
            if (withdrawal.status() == VaultEconomyBridge.Status.DECLINED) {
                log.transition(listing.getId(), MarketTransactionLog.State.ROLLED_BACK, withdrawal.message(), System.currentTimeMillis());
                try {
                    MarketTransactionLog.saveNow(server);
                } catch (RuntimeException exception) {
                    suspendTransactions(server, "journal rollback failed");
                    return SaleResult.failed("message.freemarket.transactions_suspended");
                }
                return SaleResult.failed("message.freemarket.insufficient_funds");
            }
            return holdForManualReview(server, listing, buyerId, buyerName, "buyer debit outcome unknown");
        }
        log.transition(listing.getId(), MarketTransactionLog.State.BUYER_DEBITED, "", System.currentTimeMillis());
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "buyer debit journal failed");
            return holdForManualReview(server, listing, buyerId, buyerName, "buyer debit confirmed but journal persistence failed");
        }
        if (net > 0D) {
            VaultEconomyBridge.TransactionResult deposit = VaultEconomyBridge.deposit(listing.getSellerId(), net);
            if (!deposit.success()) {
                if (deposit.status() != VaultEconomyBridge.Status.DECLINED) {
                    return holdForManualReview(server, listing, buyerId, buyerName, "seller credit outcome unknown");
                }
                VaultEconomyBridge.TransactionResult refund = VaultEconomyBridge.deposit(buyerId, gross);
                if (!refund.success()) {
                    return holdForManualReview(server, listing, buyerId, buyerName, "buyer refund outcome unknown");
                }
                log.transition(listing.getId(), MarketTransactionLog.State.ROLLED_BACK, deposit.message(), System.currentTimeMillis());
                try {
                    MarketTransactionLog.saveNow(server);
                } catch (RuntimeException exception) {
                    suspendTransactions(server, "refund journal failed");
                    return SaleResult.failed("message.freemarket.transactions_suspended");
                }
                return SaleResult.failed("message.freemarket.seller_payment_failed");
            }
        }
        log.transition(listing.getId(), MarketTransactionLog.State.SELLER_CREDITED, "", System.currentTimeMillis());
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "seller credit journal failed");
            return holdForManualReview(server, listing, buyerId, buyerName, "seller credit confirmed but journal persistence failed");
        }
        try {
            boolean completed = MarketSavedData.get(server).completeSale(listing.getId(), buyerId, buyerName, gross, now);
            if (!completed) {
                return holdForManualReview(server, listing, buyerId, buyerName, "listing commit rejected after payment");
            }
            MarketSavedData.saveNow(server);
        } catch (RuntimeException exception) {
            FreeMarket.LOGGER.error("Listing commit failed after confirmed payment {}", listing.getId(), exception);
            return holdForManualReview(server, listing, buyerId, buyerName, "listing commit failed after payment");
        }
        log.transition(listing.getId(), MarketTransactionLog.State.MARKET_COMMITTED, "", System.currentTimeMillis());
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "market commit journal failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        if (!deliveries.enqueue(buyerId, listing.getId(), listing.getItem())) {
            return holdForManualReview(server, listing, buyerId, buyerName, "delivery queue rejected committed sale");
        }
        try {
            PendingDeliveries.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "delivery queue save failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        log.transition(listing.getId(), MarketTransactionLog.State.DELIVERY_QUEUED, "", System.currentTimeMillis());
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "delivery journal save failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        log.transition(listing.getId(), MarketTransactionLog.State.COMPLETED, "", System.currentTimeMillis());
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "transaction completion journal failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        return SaleResult.completed();
    }

    private static SaleResult settleEscrowedAuction(MinecraftServer server, Listing listing, BidRecord bid, long now) {
        AuctionEscrowLog escrowLog = AuctionEscrowLog.get(server);
        Optional<AuctionEscrowLog.Entry> optional = escrowLog.getEntry(listing.getId());
        if (!escrowMatchesListingBid(listing, optional, Optional.of(bid))) {
            holdLegacyAuctionForReview(server, listing, bid, "auction winner does not match escrow journal");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        if (!transactionsAvailable(server)) {
            return SaleResult.failed("message.freemarket.transactions_suspended");
        }
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        if (!deliveries.canEnqueue(bid.getBidderId(), listing.getId())) {
            return SaleResult.failed("message.freemarket.delivery_queue_full");
        }
        double gross = bid.getAmount();
        FeeConfig fee = MarketSavedData.get(server).getFeeConfig();
        double net = Math.max(0D, gross - fee.calculate(gross));
        escrowLog.prepareSettlement(listing.getId(), net, now);
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction settlement preparation journal failed");
            holdAuctionEscrowForReview(server, listing, "auction settlement could not be prepared durably");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        if (net > 0D) {
            VaultEconomyBridge.TransactionResult deposit = VaultEconomyBridge.deposit(listing.getSellerId(), net);
            if (!deposit.success()) {
                if (deposit.status() == VaultEconomyBridge.Status.DECLINED) {
                    escrowLog.transition(listing.getId(), AuctionEscrowLog.State.HELD, deposit.message(), System.currentTimeMillis());
                    try {
                        AuctionEscrowLog.saveNow(server);
                    } catch (RuntimeException exception) {
                        suspendTransactions(server, "auction seller decline journal failed");
                        holdAuctionEscrowForReview(server, listing, "seller credit declined and escrow state could not be restored durably");
                        return SaleResult.manual("message.freemarket.payment_review");
                    }
                    holdAuctionEscrowForReview(server, listing, "auction seller credit declined");
                    return SaleResult.manual("message.freemarket.payment_review");
                }
                holdAuctionEscrowForReview(server, listing, "auction seller credit outcome unknown");
                return SaleResult.manual("message.freemarket.payment_review");
            }
        }
        escrowLog.transition(listing.getId(), AuctionEscrowLog.State.SELLER_CREDITED, "", System.currentTimeMillis());
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction seller credit journal failed");
            holdAuctionEscrowForReview(server, listing, "auction seller credit confirmed but journal persistence failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        try {
            if (!MarketSavedData.get(server).completeSale(listing.getId(), bid.getBidderId(), bid.getBidderName(), gross, now)) {
                holdAuctionEscrowForReview(server, listing, "auction listing commit rejected after escrow settlement");
                return SaleResult.manual("message.freemarket.payment_review");
            }
            MarketSavedData.saveNow(server);
        } catch (RuntimeException exception) {
            FreeMarket.LOGGER.error("Auction listing commit failed after confirmed escrow settlement {}", listing.getId(), exception);
            holdAuctionEscrowForReview(server, listing, "auction listing commit failed after escrow settlement");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        escrowLog.transition(listing.getId(), AuctionEscrowLog.State.MARKET_COMMITTED, "", System.currentTimeMillis());
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction market commit journal failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        if (!deliveries.enqueue(bid.getBidderId(), listing.getId(), listing.getItem())) {
            holdAuctionEscrowForReview(server, listing, "auction delivery queue rejected committed sale");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        try {
            PendingDeliveries.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction delivery queue save failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        escrowLog.transition(listing.getId(), AuctionEscrowLog.State.DELIVERY_QUEUED, "", System.currentTimeMillis());
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction delivery journal failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        escrowLog.transition(listing.getId(), AuctionEscrowLog.State.COMPLETED, "", System.currentTimeMillis());
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction completion journal failed");
            return SaleResult.manual("message.freemarket.payment_review");
        }
        return SaleResult.completed();
    }

    private static boolean refundAuctionEscrow(MinecraftServer server, Listing listing, String detail) {
        if (!transactionsAvailable(server)) {
            return false;
        }
        AuctionEscrowLog log = AuctionEscrowLog.get(server);
        Optional<AuctionEscrowLog.Entry> optional = log.getEntry(listing.getId());
        if (!escrowMatchesListingBid(listing, optional, listing.getHighestBid())) {
            listing.getHighestBid().ifPresent(bid -> holdLegacyAuctionForReview(server, listing, bid, "auction refund does not match escrow journal"));
            return false;
        }
        AuctionEscrowLog.Entry entry = optional.orElse(null);
        if (entry == null || entry.holderId() == null) {
            return true;
        }
        log.prepareRefund(listing.getId(), System.currentTimeMillis());
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction refund preparation journal failed");
            holdAuctionEscrowForReview(server, listing, "auction refund could not be prepared durably");
            return false;
        }
        VaultEconomyBridge.TransactionResult refund = VaultEconomyBridge.deposit(entry.holderId(), entry.heldAmount());
        if (!refund.success()) {
            if (refund.status() == VaultEconomyBridge.Status.DECLINED) {
                log.transition(listing.getId(), AuctionEscrowLog.State.HELD, refund.message(), System.currentTimeMillis());
                try {
                    AuctionEscrowLog.saveNow(server);
                } catch (RuntimeException exception) {
                    suspendTransactions(server, "auction declined refund journal failed");
                    holdAuctionEscrowForReview(server, listing, "auction refund declined and state restore failed");
                    return false;
                }
                holdAuctionEscrowForReview(server, listing, "auction refund declined");
            } else {
                holdAuctionEscrowForReview(server, listing, "auction refund outcome unknown");
            }
            return false;
        }
        log.transition(listing.getId(), AuctionEscrowLog.State.REFUNDED, detail, System.currentTimeMillis());
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction refund completion journal failed");
            holdAuctionEscrowForReview(server, listing, "auction refund confirmed but journal persistence failed");
            return false;
        }
        return true;
    }

    private static SaleResult holdForManualReview(MinecraftServer server, Listing listing, UUID buyerId, String buyerName, String detail) {
        MarketTransactionLog log = MarketTransactionLog.get(server);
        log.transition(listing.getId(), MarketTransactionLog.State.MANUAL_REVIEW, detail, System.currentTimeMillis());
        try {
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "payment review journal save failed");
        }
        MarketSavedData savedData = MarketSavedData.get(server);
        try {
            savedData.updateListing(listing.getId(), value -> {
                if (value.getStatus() == Listing.Status.ACTIVE || value.getStatus() == Listing.Status.PAUSED) {
                    value.holdForPaymentReview(System.currentTimeMillis());
                }
            });
            MarketSavedData.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "payment review listing save failed");
        }
        notifyManualReview(server, listing, buyerId, buyerName);
        invalidate(server);
        return SaleResult.manual("message.freemarket.payment_review");
    }

    private static void suspendTransactions(MinecraftServer server, String detail) {
        transactionsSuspended = true;
        FreeMarket.LOGGER.error("FreeMarket transactions suspended: {}", detail);
        for (ServerPlayer operator : server.getPlayerList().getPlayers()) {
            if (operator.hasPermissions(2)) {
                ModernNotificationBridge.notify(operator, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.FAILURE, "notification.freemarket.transactions_suspended.title", "notification.freemarket.transactions_suspended.message");
            }
        }
    }

    private static boolean transactionsAvailable(MinecraftServer server) {
        if (transactionsSuspended) {
            return false;
        }
        if (MarketSavedData.get(server).areListingWritesSuspended()
            || PendingDeliveries.get(server).isRecoveryUnsafe()
            || MarketTransactionLog.get(server).recoveryUnsafe()
            || AuctionEscrowLog.get(server).recoveryUnsafe()) {
            quarantineUnresolvedForReview(server);
            suspendTransactions(server, "persistent market state became unsafe");
            return false;
        }
        return true;
    }

    private static void notifyManualReview(MinecraftServer server, Listing listing, UUID buyerId, String buyerName) {
        String itemName = listing.getDisplayName().getString();
        persistentNotification(server, buyerId, buyerName, MarketNotification.Type.SYSTEM, listing, "notification.freemarket.payment_review.title", itemName);
        persistentNotification(server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.SYSTEM, listing, "notification.freemarket.payment_review.title", itemName);
        ServerPlayer buyer = server.getPlayerList().getPlayer(buyerId);
        if (buyer != null) {
            ModernNotificationBridge.notify(buyer, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.FAILURE, "notification.freemarket.payment_review.title", "notification.freemarket.payment_review.message", itemName);
        }
        ServerPlayer seller = server.getPlayerList().getPlayer(listing.getSellerId());
        if (seller != null) {
            ModernNotificationBridge.notify(seller, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.FAILURE, "notification.freemarket.payment_review.title", "notification.freemarket.payment_review.message", itemName);
        }
        for (ServerPlayer operator : server.getPlayerList().getPlayers()) {
            if (operator.hasPermissions(2) && !operator.getUUID().equals(buyerId) && !operator.getUUID().equals(listing.getSellerId())) {
                ModernNotificationBridge.notify(operator, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.FAILURE, "notification.freemarket.payment_review.title", "notification.freemarket.payment_review.operator", listing.getId().toString());
            }
        }
    }

    private static void quarantineUnresolvedForReview(MinecraftServer server) {
        MarketSavedData savedData = MarketSavedData.get(server);
        MarketTransactionLog transactionLog = MarketTransactionLog.get(server);
        for (MarketTransactionLog.Entry entry : transactionLog.unresolved()) {
            if (entry.state() != MarketTransactionLog.State.MANUAL_REVIEW) {
                transactionLog.transition(entry.id(), MarketTransactionLog.State.MANUAL_REVIEW, "persistent recovery data requires operator audit", System.currentTimeMillis());
            }
            savedData.getListing(entry.listingId()).ifPresent(listing -> {
                try {
                    savedData.updateListing(listing.getId(), value -> {
                        if (value.getStatus() == Listing.Status.ACTIVE || value.getStatus() == Listing.Status.PAUSED) {
                            value.holdForPaymentReview(System.currentTimeMillis());
                        }
                    });
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to hold unsafe market listing {} for review", listing.getId(), exception);
                }
            });
        }
        AuctionEscrowLog escrowLog = AuctionEscrowLog.get(server);
        for (AuctionEscrowLog.Entry entry : escrowLog.unresolved()) {
            Optional<Listing> listing = savedData.getListing(entry.listingId());
            AuctionEscrowLog.Entry manual = entry.state() == AuctionEscrowLog.State.MANUAL_REVIEW ? entry : escrowLog.manual(entry.listingId(), "persistent recovery data requires operator audit", System.currentTimeMillis());
            if (listing.isEmpty()) {
                try {
                    MarketTransactionLog.get(server).mirrorAuctionEscrow(manual, manual.detail(), System.currentTimeMillis());
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to mirror orphaned auction escrow {} for review", entry.listingId(), exception);
                }
            }
            listing.ifPresent(value -> {
                try {
                    savedData.updateListing(value.getId(), working -> {
                        if (working.getStatus() == Listing.Status.ACTIVE || working.getStatus() == Listing.Status.PAUSED) {
                            working.holdForPaymentReview(System.currentTimeMillis());
                        }
                    });
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to hold unsafe auction escrow listing {} for review", entry.listingId(), exception);
                }
                try {
                    mirrorEscrowManualReview(server, manual, manual.detail());
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to mirror unsafe auction escrow transaction {} for review", entry.listingId(), exception);
                }
            });
        }
        try {
            MarketTransactionLog.saveNow(server);
            AuctionEscrowLog.saveNow(server);
            MarketSavedData.saveNow(server);
            PendingDeliveries.saveNow(server);
        } catch (RuntimeException exception) {
            FreeMarket.LOGGER.error("Unable to persist quarantined FreeMarket recovery state", exception);
        }
    }

    private static void recoverAuctionEscrows(MinecraftServer server) {
        AuctionEscrowLog escrowLog = AuctionEscrowLog.get(server);
        MarketSavedData savedData = MarketSavedData.get(server);
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        for (AuctionEscrowLog.Entry entry : escrowLog.unresolved()) {
            Optional<Listing> optional = savedData.getListing(entry.listingId());
            if (optional.isEmpty()) {
                AuctionEscrowLog.Entry manual = escrowLog.manual(entry.listingId(), "listing missing during auction escrow recovery", System.currentTimeMillis());
                try {
                    MarketTransactionLog.get(server).mirrorAuctionEscrow(manual, manual.detail(), System.currentTimeMillis());
                    MarketTransactionLog.saveNow(server);
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to mirror missing-listing auction escrow {}", entry.listingId(), exception);
                }
                suspendTransactions(server, "auction escrow references a missing listing");
                continue;
            }
            Listing listing = optional.get();
            if (entry.state() == AuctionEscrowLog.State.HELD) {
                if (listing.getStatus() == Listing.Status.ACTIVE || listing.getStatus() == Listing.Status.PAUSED) {
                    if (!escrowMatchesListingBid(listing, Optional.of(entry), listing.getHighestBid())) {
                        holdAuctionEscrowForReview(server, listing, "auction listing and escrow differ during recovery");
                    }
                } else {
                    holdAuctionEscrowForReview(server, listing, "auction escrow remained held for a terminal listing");
                }
                continue;
            }
            if (entry.state() == AuctionEscrowLog.State.SELLER_CREDITED || entry.state() == AuctionEscrowLog.State.MARKET_COMMITTED || entry.state() == AuctionEscrowLog.State.DELIVERY_QUEUED) {
                if (!escrowSettlementMatches(listing, entry)) {
                    holdAuctionEscrowForReview(server, listing, "auction settlement parties differ during recovery");
                    continue;
                }
                try {
                    UUID buyerId = entry.holderId();
                    if (buyerId == null || !deliveries.canEnqueue(buyerId, listing.getId())) {
                        throw new IllegalStateException("delivery queue unavailable");
                    }
                    if (listing.getStatus() != Listing.Status.SOLD) {
                        if (listing.getStatus() == Listing.Status.PAYMENT_REVIEW) {
                            savedData.updateListing(listing.getId(), value -> value.completeReviewedSale(buyerId, entry.holderName(), entry.heldAmount(), System.currentTimeMillis()));
                            savedData.recordPurchase(buyerId, entry.holderName(), listing.getId());
                        } else {
                            savedData.completeSale(listing.getId(), buyerId, entry.holderName(), entry.heldAmount(), System.currentTimeMillis());
                        }
                        MarketSavedData.saveNow(server);
                    }
                    escrowLog.transition(listing.getId(), AuctionEscrowLog.State.MARKET_COMMITTED, "recovered", System.currentTimeMillis());
                    AuctionEscrowLog.saveNow(server);
                    if (!deliveries.enqueue(buyerId, listing.getId(), listing.getItem())) {
                        throw new IllegalStateException("delivery queue rejected recovery");
                    }
                    PendingDeliveries.saveNow(server);
                    escrowLog.transition(listing.getId(), AuctionEscrowLog.State.DELIVERY_QUEUED, "recovered", System.currentTimeMillis());
                    AuctionEscrowLog.saveNow(server);
                    escrowLog.transition(listing.getId(), AuctionEscrowLog.State.COMPLETED, "recovered", System.currentTimeMillis());
                    AuctionEscrowLog.saveNow(server);
                    ServerPlayer online = server.getPlayerList().getPlayer(buyerId);
                    if (online != null) {
                        deliveries.deliver(online);
                        persistPendingDeliveries(server, "recovered auction delivery acknowledgement failed");
                    }
                    notifySaleParties(server, listing, buyerId, entry.holderName(), entry.heldAmount(), true);
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to recover auction escrow {}", entry.listingId(), exception);
                    holdAuctionEscrowForReview(server, listing, "automatic auction escrow recovery failed");
                }
                continue;
            }
            if (entry.state() == AuctionEscrowLog.State.MANUAL_REVIEW) {
                holdAuctionEscrowForReview(server, listing, entry.detail().isBlank() ? "auction escrow requires operator audit" : entry.detail());
            } else {
                holdAuctionEscrowForReview(server, listing, "ambiguous auction escrow recovery state " + entry.state().name());
            }
        }
        for (MarketSavedData.AuctionSchedule schedule : savedData.getActiveAuctionSchedules()) {
            Optional<Listing> optional = savedData.getListing(schedule.listingId());
            if (optional.isEmpty()) {
                continue;
            }
            Listing listing = optional.get();
            Optional<BidRecord> bid = listing.getHighestBid();
            if (bid.isPresent() && escrowLog.getEntry(listing.getId()).isEmpty()) {
                holdLegacyAuctionForReview(server, listing, bid.get(), "legacy auction bid was not escrow funded");
            }
        }
        try {
            AuctionEscrowLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction escrow recovery journal failed");
        }
        try {
            MarketSavedData.saveNow(server);
            PendingDeliveries.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "auction recovery data save failed");
        }
    }

    private static void recoverTransactions(MinecraftServer server) {
        MarketTransactionLog log = MarketTransactionLog.get(server);
        MarketSavedData savedData = MarketSavedData.get(server);
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        for (MarketTransactionLog.Entry entry : log.unresolved()) {
            Optional<Listing> optional = savedData.getListing(entry.listingId());
            if (optional.isEmpty()) {
                log.transition(entry.id(), MarketTransactionLog.State.MANUAL_REVIEW, "listing missing during recovery", System.currentTimeMillis());
                continue;
            }
            Listing listing = optional.get();
            if (entry.state() == MarketTransactionLog.State.SELLER_CREDITED || entry.state() == MarketTransactionLog.State.MARKET_COMMITTED || entry.state() == MarketTransactionLog.State.DELIVERY_QUEUED) {
                try {
                    if (listing.getStatus() != Listing.Status.SOLD) {
                        if (listing.getStatus() == Listing.Status.PAYMENT_REVIEW) {
                            savedData.updateListing(listing.getId(), value -> value.completeReviewedSale(entry.buyerId(), entry.buyerName(), entry.gross(), System.currentTimeMillis()));
                            savedData.recordPurchase(entry.buyerId(), entry.buyerName(), listing.getId());
                        } else {
                            savedData.completeSale(listing.getId(), entry.buyerId(), entry.buyerName(), entry.gross(), System.currentTimeMillis());
                        }
                    }
                    MarketSavedData.saveNow(server);
                    log.transition(entry.id(), MarketTransactionLog.State.MARKET_COMMITTED, "recovered", System.currentTimeMillis());
                    MarketTransactionLog.saveNow(server);
                    if (!deliveries.enqueue(entry.buyerId(), listing.getId(), listing.getItem())) {
                        throw new IllegalStateException("delivery queue full");
                    }
                    PendingDeliveries.saveNow(server);
                    log.transition(entry.id(), MarketTransactionLog.State.DELIVERY_QUEUED, "recovered", System.currentTimeMillis());
                    MarketTransactionLog.saveNow(server);
                    log.transition(entry.id(), MarketTransactionLog.State.COMPLETED, "recovered", System.currentTimeMillis());
                    MarketTransactionLog.saveNow(server);
                    ServerPlayer online = server.getPlayerList().getPlayer(entry.buyerId());
                    if (online != null) {
                        deliveries.deliver(online);
                        persistPendingDeliveries(server, "recovered transaction delivery acknowledgement failed");
                    }
                    notifySaleParties(server, listing, entry.buyerId(), entry.buyerName(), entry.gross(), listing.getSaleType() == Listing.SaleType.AUCTION);
                } catch (RuntimeException exception) {
                    FreeMarket.LOGGER.error("Unable to recover market transaction {}", entry.id(), exception);
                    log.transition(entry.id(), MarketTransactionLog.State.MANUAL_REVIEW, "automatic recovery failed", System.currentTimeMillis());
                    savedData.updateListing(listing.getId(), value -> {
                        if (value.getStatus() == Listing.Status.ACTIVE || value.getStatus() == Listing.Status.PAUSED) {
                            value.holdForPaymentReview(System.currentTimeMillis());
                        }
                    });
                    notifyManualReview(server, listing, entry.buyerId(), entry.buyerName());
                }
            } else {
                savedData.updateListing(listing.getId(), value -> {
                    if (value.getStatus() == Listing.Status.ACTIVE || value.getStatus() == Listing.Status.PAUSED) {
                        value.holdForPaymentReview(System.currentTimeMillis());
                    }
                });
                if (entry.state() != MarketTransactionLog.State.MANUAL_REVIEW) {
                    log.transition(entry.id(), MarketTransactionLog.State.MANUAL_REVIEW, "ambiguous crash recovery state " + entry.state().name(), System.currentTimeMillis());
                    notifyManualReview(server, listing, entry.buyerId(), entry.buyerName());
                }
            }
        }
        try {
            MarketSavedData.saveNow(server);
            PendingDeliveries.saveNow(server);
            MarketTransactionLog.saveNow(server);
        } catch (RuntimeException exception) {
            suspendTransactions(server, "transaction recovery data save failed");
        }
    }

    private static boolean persistPendingDeliveries(MinecraftServer server, String detail) {
        try {
            PendingDeliveries.saveNow(server);
            return true;
        } catch (RuntimeException exception) {
            suspendTransactions(server, detail);
            return false;
        }
    }

    private static void finishAuction(MinecraftServer server, Listing listing, long now) {
        if (!transactionsAvailable(server)) {
            AUCTIONS.add(new AuctionDeadline(listing.getId(), now + AUCTION_RETRY_DELAY));
            return;
        }
        if (!OPERATIONS.add(listing.getId())) {
            AUCTIONS.add(new AuctionDeadline(listing.getId(), now + 1000L));
            return;
        }
        try {
            Optional<BidRecord> winningBid = listing.getBidHistory().stream().max(Comparator.comparingDouble(BidRecord::getAmount));
            if (winningBid.isEmpty()) {
                expireAuction(server, listing, now, "notification.freemarket.auction_expired.title");
                return;
            }
            BidRecord bid = winningBid.get();
            SaleResult result = settleEscrowedAuction(server, listing, bid, now);
            if (!result.success()) {
                if (result.manualReview()) {
                    return;
                }
                AUCTIONS.add(new AuctionDeadline(listing.getId(), now + AUCTION_RETRY_DELAY));
                return;
            }
            ServerPlayer winner = server.getPlayerList().getPlayer(bid.getBidderId());
            if (winner != null) {
                flushDeliveries(server, winner, "auction winner delivery acknowledgement failed");
            }
            notifySaleParties(server, listing, bid.getBidderId(), bid.getBidderName(), bid.getAmount(), true);
            MarketSavedData savedData = MarketSavedData.get(server);
            if (winner != null && VIEWERS.contains(bid.getBidderId())) {
                savedData.getListing(listing.getId()).ifPresent(sold -> sendReviewPrompt(winner, sold));
            } else {
                savedData.addPendingReviewPrompt(bid.getBidderId(), bid.getBidderName(), listing.getId());
            }
            invalidate(server);
        } finally {
            OPERATIONS.remove(listing.getId());
        }
    }

    private static void expireAuction(MinecraftServer server, Listing listing, long now, String titleKey) {
        if (!PendingDeliveries.get(server).canEnqueue(listing.getSellerId(), listing.getId())) {
            AUCTIONS.add(new AuctionDeadline(listing.getId(), now + AUCTION_RETRY_DELAY));
            return;
        }
        MarketSavedData.get(server).updateListing(listing.getId(), value -> {
            if (value.getBidHistory().isEmpty()) {
                value.expire(now);
            } else {
                value.failAuctionSettlement(now);
            }
        });
        deliver(server, listing.getSellerId(), listing.getId(), listing.getItem());
        persistentNotification(server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.SYSTEM, listing, titleKey, listing.getDisplayName().getString());
        invalidate(server);
    }

    private static void notifySaleParties(MinecraftServer server, Listing listing, UUID buyerId, String buyerName, double price, boolean auction) {
        String itemName = listing.getDisplayName().getString();
        persistentNotification(server, listing.getSellerId(), listing.getSellerName(), MarketNotification.Type.SOLD, listing, "notification.freemarket.sold.title", itemName + " / " + Math.round(price) + " / " + buyerName);
        persistentNotification(server, buyerId, buyerName, auction ? MarketNotification.Type.AUCTION_WON : MarketNotification.Type.PURCHASED, listing, auction ? "notification.freemarket.auction_won.title" : "notification.freemarket.purchased.title", itemName + " / " + Math.round(price));
        ServerPlayer seller = server.getPlayerList().getPlayer(listing.getSellerId());
        if (seller != null) {
            ModernNotificationBridge.notify(seller, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.SUCCESS, "notification.freemarket.sold.title", "notification.freemarket.sold.message", itemName, Math.round(price));
        }
        ServerPlayer buyer = server.getPlayerList().getPlayer(buyerId);
        if (buyer != null) {
            ModernNotificationBridge.notify(buyer, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.SUCCESS, auction ? "notification.freemarket.auction_won.title" : "notification.freemarket.purchased.title", auction ? "notification.freemarket.auction_won.message" : "notification.freemarket.purchased.message", itemName, Math.round(price));
        }
    }

    private static void persistentNotification(MinecraftServer server, UUID userId, String username, MarketNotification.Type type, Listing listing, String titleKey, String message) {
        MarketSavedData savedData = MarketSavedData.get(server);
        savedData.addNotification(userId, username, new MarketNotification(type, listing.getId(), titleKey, MarketText.clean(message, MarketLimits.MAX_NOTIFICATION_MESSAGE_LENGTH), System.currentTimeMillis()));
        ServerPlayer online = server.getPlayerList().getPlayer(userId);
        if (online != null && VIEWERS.contains(userId)) {
            CompoundTag payload = new CompoundTag();
            payload.put("User", userSummaryTag(savedData, online));
            payload.put("Notifications", notificationsTag(savedData, userId));
            NetworkHandler.sendToPlayer(online, "notification_sync", payload);
        }
    }

    private static void deliver(MinecraftServer server, UUID playerId, UUID operationId, ItemStack item) {
        PendingDeliveries deliveries = PendingDeliveries.get(server);
        if (!deliveries.enqueue(playerId, operationId, item)) {
            ServerPlayer fallback = server.getPlayerList().getPlayer(playerId);
            if (fallback == null || MarketItemDelivery.deliver(fallback, item) == MarketItemDelivery.Outcome.FAILED) {
                FreeMarket.LOGGER.error("Unable to queue item return {} for player {}", operationId, playerId);
            }
            return;
        }
        if (!persistPendingDeliveries(server, "item return queue save failed")) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            flushDeliveries(server, online, "item return acknowledgement save failed");
        }
    }

    private static CompoundTag snapshot(ServerPlayer player, MarketQuery query, boolean includePersonal) {
        MarketSavedData savedData = MarketSavedData.get(player.server);
        MarketSearchResult search = savedData.searchForUser(player.getUUID(), query, System.currentTimeMillis());
        return snapshot(player, search, includePersonal);
    }

    private static CompoundTag snapshot(ServerPlayer player, MarketSearchResult search, boolean includePersonal) {
        MarketSavedData savedData = MarketSavedData.get(player.server);
        CompoundTag root = new CompoundTag();
        ListTag listings = new ListTag();
        search.getListings().forEach(listing -> listings.add(listingTag(listing, player.getUUID(), false)));
        root.put("Listings", listings);
        root.putLong("Revision", search.getRevision());
        root.putInt("TotalCount", search.getTotalCount());
        root.putInt("Page", search.getPage());
        root.putInt("TotalPages", search.getTotalPages());
        if (includePersonal) {
            ListTag tags = new ListTag();
            ListTag tagDefinitions = new ListTag();
            savedData.getTags().forEach(tag -> {
                tagDefinitions.add(tag.toTag());
                if (tag.isEnabled()) {
                    tags.add(StringTag.valueOf(tag.getId()));
                }
            });
            root.put("Tags", tags);
            root.put("TagDefinitions", tagDefinitions);
            root.put("Fee", feeTag(savedData.getFeeConfig()));
            root.putBoolean("IsAdmin", isAdmin(player));
            VaultEconomyBridge.BalanceResult balance = VaultEconomyBridge.balance(player.getUUID());
            root.putBoolean("EconomyAvailable", balance.success());
            if (balance.success()) {
                root.putDouble("Balance", balance.balance());
            }
            DRAFTS.getSelected(player).ifPresent(stack -> root.put("Draft", stack.save(new CompoundTag())));
            root.put("User", userTag(savedData, player));
            root.put("Notifications", notificationsTag(savedData, player.getUUID()));
            putPersonalFeatures(root, savedData, player);
        }
        return root;
    }

    private static void putPersonalFeatures(CompoundTag root, MarketSavedData data, ServerPlayer player) {
        ListTag savedSearches = new ListTag();
        for (SavedMarketSearch search : data.getSavedSearches(player.getUUID())) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", search.getId().toString());
            entry.putString("Name", search.getName());
            entry.putBoolean("NotificationsEnabled", search.isNotificationsEnabled());
            entry.putLong("UpdatedAt", search.getUpdatedAt());
            entry.put("Query", search.getQuery().toTag());
            savedSearches.add(entry);
        }
        root.put("SavedSearches", savedSearches);
        ListTag blockedUsers = new ListTag();
        for (UUID blockedId : data.getBlockedUsers(player.getUUID())) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", blockedId.toString());
            entry.putString("Name", data.getUser(blockedId).map(MarketUserData::getUsername).orElse(blockedId.toString()));
            blockedUsers.add(entry);
        }
        root.put("BlockedUsers", blockedUsers);
        if (isAdmin(player)) {
            root.putInt("PendingReportCount", data.getPendingReportCount());
            root.put("Reports", reportsTag(data));
        }
    }

    private static ListTag reportsTag(MarketSavedData data) {
        ListTag result = new ListTag();
        ArrayList<MarketReport> reports = new ArrayList<>(data.getReports(MarketReport.Status.OPEN, 0, MarketLimits.MAX_PAGE_SIZE));
        reports.addAll(data.getReports(MarketReport.Status.REVIEWING, 0, MarketLimits.MAX_PAGE_SIZE));
        reports.stream().sorted(Comparator.comparingLong(MarketReport::getUpdatedAt).reversed()).limit(MarketLimits.MAX_PAGE_SIZE).forEach(report -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", report.getId().toString());
            entry.putString("ListingId", report.getListingId().toString());
            entry.putString("ReporterId", report.getReporterId().toString());
            entry.putString("Reporter", report.getReporterName());
            entry.putString("Reason", report.getReason().name());
            entry.putString("Detail", report.getDetail());
            entry.putString("Status", report.getStatus().name());
            entry.putLong("CreatedAt", report.getCreatedAt());
            data.getListing(report.getListingId()).ifPresent(listing -> entry.putString("ListingName", listing.getDisplayName().getString()));
            result.add(entry);
        });
        return result;
    }

    private static CompoundTag userTag(MarketSavedData data, ServerPlayer player) {
        CompoundTag tag = userSummaryTag(data, player);
        MarketUserData user = data.getUser(player.getUUID()).orElse(new MarketUserData(player.getUUID(), player.getGameProfile().getName()));
        tag.put("ViewHistory", historyTag(data, user.getViewHistory(), player.getUUID()));
        tag.put("PurchaseHistory", historyTag(data, user.getPurchaseHistory(), player.getUUID()));
        tag.put("ListingHistory", historyTag(data, user.getListingHistory(), player.getUUID()));
        return tag;
    }

    private static CompoundTag userSummaryTag(MarketSavedData data, ServerPlayer player) {
        MarketUserData user = data.getUser(player.getUUID()).orElse(new MarketUserData(player.getUUID(), player.getGameProfile().getName()));
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", player.getUUID().toString());
        tag.putString("Name", player.getGameProfile().getName());
        tag.putDouble("Rating", user.getAverageRating());
        tag.putInt("RatingCount", user.getRatingCount());
        tag.putInt("Unread", user.getUnreadNotificationCount());
        return tag;
    }

    private static ListTag historyTag(MarketSavedData data, List<UUID> ids, UUID viewer) {
        ListTag result = new ListTag();
        int added = 0;
        for (UUID id : ids) {
            if (added >= 24) {
                break;
            }
            Optional<Listing> listing = data.getListing(id);
            if (listing.isPresent() && listing.get().getStatus() != Listing.Status.REMOVED) {
                result.add(listingTag(listing.get(), viewer, false));
                added++;
            }
        }
        return result;
    }

    private static ListTag notificationsTag(MarketSavedData data, UUID playerId) {
        ListTag result = new ListTag();
        data.getUser(playerId).ifPresent(user -> user.getNotifications().forEach(notification -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("Id", notification.getId().toString());
            tag.putString("Type", notification.getType().name());
            notification.getListingId().ifPresent(id -> tag.putString("ListingId", id.toString()));
            if (notification.getTitle().startsWith("notification.freemarket.")) {
                tag.putString("TitleKey", notification.getTitle());
            } else {
                tag.putString("Title", notification.getTitle());
            }
            tag.putString("Message", notification.getMessage());
            tag.putLong("CreatedAt", notification.getCreatedAt());
            tag.putBoolean("Read", notification.isRead());
            result.add(tag);
        }));
        return result;
    }

    private static CompoundTag listingTag(Listing listing, UUID viewer, boolean details) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", listing.getId().toString());
        tag.putString("SellerId", listing.getSellerId().toString());
        tag.putString("SellerName", listing.getSellerName());
        ItemStack clientItem = details ? MarketItemSafety.forClient(listing.getItem(), true) : listing.getClientSummary();
        tag.put("Item", clientItem.save(new CompoundTag()));
        tag.putString("Name", legacy(listing.getDisplayName()));
        if (details) {
            tag.putString("Description", listing.getDescription());
        }
        ListTag tags = new ListTag();
        listing.getTags().forEach(value -> tags.add(StringTag.valueOf(value)));
        tag.put("Tags", tags);
        tag.putString("Type", listing.getSaleType() == Listing.SaleType.AUCTION ? "AUCTION" : "FIXED");
        tag.putString("Status", listing.getStatus().name());
        tag.putLong("Price", Math.round(listing.getStartingPrice()));
        tag.putLong("CurrentPrice", Math.round(listing.getCurrentPrice()));
        tag.putLong("CreatedAt", listing.getCreatedAt());
        tag.putLong("UpdatedAt", listing.getUpdatedAt());
        tag.putLong("EndsAt", listing.getAuctionEndAt());
        tag.putString("BuyerName", listing.getBuyerName());
        tag.putInt("LikeCount", listing.getLikeCount());
        tag.putBoolean("Liked", listing.isLikedBy(viewer));
        if (details) {
            ListTag comments = new ListTag();
            listing.getComments().forEach(comment -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("Id", comment.getId().toString());
                entry.putString("Author", comment.getAuthorName());
                entry.putString("AuthorId", comment.getAuthorId().toString());
                entry.putString("Message", comment.getMessage());
                entry.putLong("CreatedAt", comment.getCreatedAt());
                comments.add(entry);
            });
            tag.put("Comments", comments);
            ListTag bids = new ListTag();
            listing.getBidHistory().forEach(bid -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("Id", bid.getId().toString());
                entry.putString("Bidder", bid.getBidderName());
                entry.putLong("Amount", Math.round(bid.getAmount()));
                entry.putLong("CreatedAt", bid.getCreatedAt());
                bids.add(entry);
            });
            tag.put("Bids", bids);
            ListTag offers = new ListTag();
            listing.getOffers().forEach(offer -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("Id", offer.getId().toString());
                entry.putString("RequesterId", offer.getRequesterId().toString());
                entry.putString("RequesterName", offer.getRequesterName());
                entry.putLong("Amount", Math.round(offer.getAmount()));
                entry.putString("Status", offer.getStatus().name());
                entry.putLong("CreatedAt", offer.getCreatedAt());
                entry.putLong("ExpiresAt", offer.getExpiresAt());
                entry.putLong("UpdatedAt", offer.getUpdatedAt());
                offers.add(entry);
            });
            tag.put("Offers", offers);
        }
        return tag;
    }

    private static CompoundTag feeTag(FeeConfig fee) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", fee.getMode().name());
        tag.putDouble("Value", fee.getAmount());
        return tag;
    }

    private static void sendListingUpdate(ServerPlayer player, MarketSavedData data, UUID listingId, String messageKey) {
        CompoundTag response = new CompoundTag();
        data.getListing(listingId).ifPresent(listing -> {
            CompoundTag payload = listingTag(listing, player.getUUID(), true);
            payload.putBoolean("SellerBlocked", data.isUserBlocked(player.getUUID(), listing.getSellerId()));
            response.put("Listing", payload);
        });
        response.put("User", userSummaryTag(data, player));
        response.put("Notifications", notificationsTag(data, player.getUUID()));
        response.putString("MessageKey", messageKey);
        response.putLong("Revision", data.getRevision());
        NetworkHandler.sendToPlayer(player, "detail", response);
    }

    private static void sendPersonalSync(ServerPlayer player, String messageKey) {
        MarketSavedData data = MarketSavedData.get(player.server);
        CompoundTag response = new CompoundTag();
        response.put("User", userSummaryTag(data, player));
        response.put("Notifications", notificationsTag(data, player.getUUID()));
        response.putString("MessageKey", messageKey);
        response.putLong("Revision", data.getRevision());
        NetworkHandler.sendToPlayer(player, "sync", response);
    }

    private static void result(ServerPlayer player, boolean success, String messageKey) {
        CompoundTag response = new CompoundTag();
        response.putBoolean("Success", success);
        response.putString("MessageKey", messageKey);
        NetworkHandler.sendToPlayer(player, "result", response);
    }

    private static CompoundTag withMessage(CompoundTag tag, String messageKey) {
        tag.putString("MessageKey", messageKey);
        return tag;
    }

    private static void invalidate(MinecraftServer server) {
        invalidatePending = true;
    }

    private static void flushInvalidate(MinecraftServer server) {
        if (!invalidatePending) {
            return;
        }
        invalidatePending = false;
        MarketSavedData savedData = MarketSavedData.get(server);
        CompoundTag tag = new CompoundTag();
        tag.putLong("Revision", savedData.getRevision());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (VIEWERS.contains(player.getUUID())) {
                CompoundTag personal = tag.copy();
                personal.put("User", userSummaryTag(savedData, player));
                NetworkHandler.sendToPlayer(player, "invalidate", personal);
            }
        }
    }

    private static MarketQuery defaultQuery() {
        return MarketQuery.builder().pageSize(30).statuses(Set.of(Listing.Status.ACTIVE, Listing.Status.SOLD)).sortOrder(MarketQuery.SortOrder.UPDATED_DESC).build();
    }

    private static MarketQuery queryFrom(CompoundTag data, ServerPlayer player) {
        MarketQuery.Builder builder = MarketQuery.builder()
            .text(firstString(data, "Text", "Query"))
            .sellerText(firstString(data, "Seller", "SellerName"))
            .itemText(firstString(data, "Item", "ItemName"))
            .availableOnly(data.getBoolean("AvailableOnly") || data.getBoolean("ActiveOnly"))
            .sortOrder(sortOrder(firstString(data, "Sort", "SortOrder")))
            .page(Math.max(0, data.getInt("Page")))
            .pageSize(Math.max(1, Math.min(60, data.contains("PageSize", Tag.TAG_ANY_NUMERIC) ? data.getInt("PageSize") : 30)));
        Set<String> queryTags = inputTags(data);
        if (!queryTags.isEmpty()) {
            builder.tags(queryTags);
        }
        Double minimum = optionalNumber(data, "MinPrice");
        Double maximum = optionalNumber(data, "MaxPrice");
        if (minimum != null) {
            builder.minimumPrice(minimum);
        }
        if (maximum != null) {
            builder.maximumPrice(maximum);
        }
        if (data.getBoolean("LikedOnly")) {
            builder.likedBy(player.getUUID());
        }
        return builder.build();
    }

    private static MarketQuery.SortOrder sortOrder(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (normalized.contains("NAME")) {
            return MarketQuery.SortOrder.NAME_ASC;
        }
        if (normalized.contains("LIKE")) {
            return MarketQuery.SortOrder.LIKES_DESC;
        }
        if (normalized.contains("PRICE_ASC")) {
            return MarketQuery.SortOrder.PRICE_ASC;
        }
        if (normalized.contains("PRICE_DESC")) {
            return MarketQuery.SortOrder.PRICE_DESC;
        }
        return MarketQuery.SortOrder.UPDATED_DESC;
    }

    private static Set<String> validTags(MarketSavedData data, CompoundTag payload) {
        Set<String> requested = inputTags(payload);
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (String value : requested) {
            data.getTag(value).filter(MarketTag::isEnabled).ifPresent(tag -> allowed.add(tag.getId()));
            if (allowed.size() >= MarketLimits.MAX_TAGS_PER_LISTING) {
                break;
            }
        }
        return allowed;
    }

    private static Set<String> inputTags(CompoundTag data) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (data.contains("Tags", Tag.TAG_LIST)) {
            ListTag list = data.getList("Tags", Tag.TAG_STRING);
            for (int index = 0; index < Math.min(list.size(), MarketLimits.MAX_TAGS_PER_LISTING); index++) {
                String value = MarketText.cleanTag(list.getString(index));
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        String raw = data.getString("Tag");
        if (raw.isBlank()) {
            raw = data.getString("Tags");
        }
        for (String entry : raw.split("[,;\\s]+")) {
            String value = MarketText.cleanTag(entry);
            if (!value.isBlank()) {
                values.add(value);
            }
            if (values.size() >= MarketLimits.MAX_TAGS_PER_LISTING) {
                break;
            }
        }
        return values;
    }

    private static Listing requiredListing(MinecraftServer server, UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("listing");
        }
        return MarketSavedData.get(server).getListing(id).orElseThrow(() -> new IllegalArgumentException("listing"));
    }

    private static Listing ownedListing(ServerPlayer player, UUID id) {
        Listing listing = requiredListing(player.server, id);
        if (!listing.getSellerId().equals(player.getUUID())) {
            throw new IllegalArgumentException("owner");
        }
        return listing;
    }

    private static UUID listingId(CompoundTag data) {
        UUID value = id(data, "ListingId");
        return value == null ? id(data, "Id") : value;
    }

    private static UUID id(CompoundTag data, String key) {
        if (data.hasUUID(key)) {
            return data.getUUID(key);
        }
        String raw = data.getString(key);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isAdmin(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    private static void requireAdmin(ServerPlayer player) {
        if (!isAdmin(player)) {
            throw new IllegalArgumentException("permission");
        }
    }

    private static boolean allow(ServerPlayer player, String action) {
        long now = System.currentTimeMillis();
        boolean query = action.equals("query") || action.equals("search");
        long cooldown = actionCooldown(action);
        if (cooldown > 0L) {
            Map<String, Long> playerCooldowns = ACTION_COOLDOWNS.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
            if (now < playerCooldowns.getOrDefault(action, 0L)) {
                return false;
            }
            playerCooldowns.put(action, now + cooldown);
        }
        Map<UUID, ArrayDeque<Long>> limits = query ? QUERY_RATE_LIMIT : ACTION_RATE_LIMIT;
        ArrayDeque<Long> entries = limits.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        long window = query ? 1000L : 2000L;
        int maximum = query ? 5 : 50;
        while (!entries.isEmpty() && entries.peekFirst() < now - window) {
            entries.removeFirst();
        }
        if (entries.size() >= maximum) {
            return false;
        }
        entries.addLast(now);
        return true;
    }

    private static long actionCooldown(String action) {
        return switch (action) {
            case "buy", "purchase" -> 750L;
            case "bid", "place_bid" -> 300L;
            case "create", "publish", "create_listing" -> 1_500L;
            case "comment", "add_comment" -> 400L;
            case "offer", "price_request" -> 1_000L;
            case "accept_offer", "reject_offer" -> 400L;
            case "report_listing" -> 10_000L;
            case "like", "toggle_like" -> 150L;
            case "rate", "review" -> 1_000L;
            case "seller_profile" -> 300L;
            default -> 0L;
        };
    }

    private static double positivePrice(CompoundTag data, String key) {
        double value = number(data, key, -1D);
        double rounded = Math.rint(value);
        if (!Double.isFinite(value) || value <= 0D || value > MarketLimits.MAX_PRICE || Double.compare(value, rounded) != 0) {
            throw new IllegalArgumentException("price");
        }
        return rounded;
    }

    private static double positivePrice(CompoundTag data, String key, double fallback) {
        if (!data.contains(key)) {
            return fallback;
        }
        return positivePrice(data, key);
    }

    private static double number(CompoundTag data, String key, double fallback) {
        if (data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return data.getDouble(key);
        }
        String raw = data.getString(key).trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Double optionalNumber(CompoundTag data, String key) {
        if (!data.contains(key)) {
            return null;
        }
        double value = number(data, key, Double.NaN);
        return Double.isFinite(value) && value >= 0D && value <= MarketLimits.MAX_PRICE ? value : null;
    }

    private static long numericLong(CompoundTag data, String key, long fallback) {
        if (data.contains(key, Tag.TAG_ANY_NUMERIC)) {
            return data.getLong(key);
        }
        try {
            return Long.parseLong(data.getString(key));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String string(CompoundTag data, String key, int limit) {
        return MarketText.clean(data.getString(key), limit);
    }

    private static String firstString(CompoundTag data, String first, String second) {
        String value = data.getString(first);
        return value.isBlank() ? data.getString(second) : value;
    }

    private static String legacy(Component component) {
        StringBuilder result = new StringBuilder();
        component.visit((style, text) -> {
            if (!text.isEmpty()) {
                appendStyle(result, style);
                result.append(text.replace("&", "&&"));
            }
            return Optional.<Void>empty();
        }, Style.EMPTY);
        return result.toString();
    }

    private static void appendStyle(StringBuilder output, Style style) {
        if (output.length() > 0) {
            output.append("&r");
        }
        if (style.getColor() != null) {
            for (ChatFormatting formatting : ChatFormatting.values()) {
                if (formatting.isColor() && formatting.getColor() != null && formatting.getColor() == style.getColor().getValue()) {
                    output.append('&').append(formatting.getChar());
                    break;
                }
            }
        }
        if (style.isBold()) {
            output.append("&l");
        }
        if (style.isItalic()) {
            output.append("&o");
        }
        if (style.isUnderlined()) {
            output.append("&n");
        }
        if (style.isStrikethrough()) {
            output.append("&m");
        }
        if (style.isObfuscated()) {
            output.append("&k");
        }
    }

    private record AuctionDeadline(UUID listingId, long endsAt) {
    }

    private record QueryRequest(MarketQuery query, long sequence) {
    }

    private record SaleResult(boolean success, boolean manualReview, String messageKey) {
        private static SaleResult completed() {
            return new SaleResult(true, false, "");
        }

        private static SaleResult failed(String key) {
            return new SaleResult(false, false, key);
        }

        private static SaleResult manual(String key) {
            return new SaleResult(false, true, key);
        }
    }
}
