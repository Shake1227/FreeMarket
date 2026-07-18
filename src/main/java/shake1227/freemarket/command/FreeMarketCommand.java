package shake1227.freemarket.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import shake1227.freemarket.integration.ModernNotificationBridge;
import shake1227.freemarket.market.AuctionEscrowLog;
import shake1227.freemarket.market.Listing;
import shake1227.freemarket.market.MarketSavedData;
import shake1227.freemarket.market.MarketTransactionLog;
import shake1227.freemarket.server.MarketServerController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FreeMarketCommand {
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final Set<UUID> STARTUP_NOTIFIED = ConcurrentHashMap.newKeySet();

    private FreeMarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("freemarket")
            .then(Commands.literal("open")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    MarketServerController.open(context.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.literal("admin")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    MarketServerController.openAdmin(context.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.literal("resolve")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                    .executes(context -> list(context.getSource(), 1))
                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("info")
                    .then(Commands.argument("transaction", UuidArgument.uuid())
                        .suggests((context, builder) -> suggestManualReviews(context.getSource(), builder))
                        .executes(context -> info(context.getSource(), UuidArgument.getUuid(context, "transaction")))))
                .then(Commands.argument("listing", UuidArgument.uuid())
                    .suggests((context, builder) -> suggestManualReviews(context.getSource(), builder))
                    .then(Commands.literal("complete").executes(context -> resolve(context.getSource(), UuidArgument.getUuid(context, "listing"), true)))
                    .then(Commands.literal("return").executes(context -> resolve(context.getSource(), UuidArgument.getUuid(context, "listing"), false))))));
    }

    public static void serverStarted(MinecraftServer server) {
        STARTUP_NOTIFIED.clear();
        server.getPlayerList().getPlayers().forEach(FreeMarketCommand::notifyPendingReviews);
    }

    public static void serverStopping() {
        STARTUP_NOTIFIED.clear();
    }

    public static void playerLoggedIn(ServerPlayer player) {
        notifyPendingReviews(player);
    }

    private static int list(CommandSourceStack source, int requestedPage) {
        List<MarketTransactionLog.Entry> entries = manualReviews(source.getServer());
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.none").withStyle(ChatFormatting.GREEN), false);
            return Command.SINGLE_SUCCESS;
        }
        int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (requestedPage > pageCount) {
            source.sendFailure(Component.translatable("command.freemarket.resolve.invalid_page", pageCount));
            return 0;
        }
        int start = (requestedPage - 1) * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.list_header", entries.size(), requestedPage, pageCount).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (MarketTransactionLog.Entry entry : entries.subList(start, end)) {
            MutableComponent line = Component.translatable(
                "command.freemarket.resolve.list_entry",
                entry.id().toString(),
                displayName(entry.buyerName(), entry.buyerId()),
                displayName(entry.sellerName(), entry.sellerId()),
                formatMoney(entry.gross()),
                formatTime(entry.updatedAt())
            ).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/freemarket resolve info " + entry.id()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("command.freemarket.resolve.click_info"))));
            source.sendSuccess(() -> line, false);
        }
        if (requestedPage < pageCount) {
            source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.next_page", requestedPage + 1).withStyle(ChatFormatting.GRAY), false);
        }
        return entries.size();
    }

    private static int info(CommandSourceStack source, UUID transactionId) {
        Optional<MarketTransactionLog.Entry> optional = MarketTransactionLog.get(source.getServer()).getEntry(transactionId);
        if (optional.isEmpty()) {
            source.sendFailure(Component.translatable("command.freemarket.resolve.not_found", transactionId.toString()));
            return 0;
        }
        MarketTransactionLog.Entry entry = optional.get();
        Optional<Listing> listing = MarketSavedData.get(source.getServer()).getListing(entry.listingId());
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_header", entry.id().toString()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_listing", entry.listingId().toString(), listing.map(value -> value.getDisplayName().getString()).orElse("-")), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_state", entry.state().name(), listing.map(value -> value.getStatus().name()).orElse("MISSING")).withStyle(entry.state() == MarketTransactionLog.State.MANUAL_REVIEW ? ChatFormatting.RED : ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_buyer", displayName(entry.buyerName(), entry.buyerId()), entry.buyerId().toString()), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_seller", displayName(entry.sellerName(), entry.sellerId()), entry.sellerId().toString()), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_amount", formatMoney(entry.gross()), formatMoney(entry.net())), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_time", formatTime(entry.createdAt()), formatTime(entry.updatedAt())), false);
        source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_detail", entry.detail().isBlank() ? "-" : entry.detail()).withStyle(ChatFormatting.YELLOW), false);
        AuctionEscrowLog.get(source.getServer()).getEntry(entry.listingId()).ifPresent(escrow -> {
            source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_escrow_state", escrow.state().name(), escrow.detail().isBlank() ? "-" : escrow.detail()).withStyle(ChatFormatting.AQUA), false);
            source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_escrow_holder", displayName(escrow.holderName(), escrow.holderId()), escrow.holderId() == null ? "-" : escrow.holderId().toString(), formatMoney(escrow.heldAmount())), false);
            source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_escrow_candidate", displayName(escrow.candidateName(), escrow.candidateId()), escrow.candidateId() == null ? "-" : escrow.candidateId().toString(), formatMoney(escrow.candidateAmount()), formatMoney(escrow.debitAmount())), false);
        });
        if (entry.state() == MarketTransactionLog.State.MANUAL_REVIEW) {
            source.sendSuccess(() -> Component.translatable("command.freemarket.resolve.info_actions", entry.id().toString()).withStyle(ChatFormatting.AQUA), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int resolve(CommandSourceStack source, UUID listingId, boolean complete) {
        boolean resolved = MarketServerController.resolveTransaction(source.getServer(), listingId, complete);
        if (resolved) {
            source.sendSuccess(() -> Component.translatable("message.freemarket.resolve_success").withStyle(ChatFormatting.GREEN), true);
            return Command.SINGLE_SUCCESS;
        }
        source.sendFailure(Component.translatable("message.freemarket.resolve_failed"));
        return 0;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestManualReviews(CommandSourceStack source, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(manualReviews(source.getServer()).stream().map(entry -> entry.id().toString()), builder);
    }

    private static List<MarketTransactionLog.Entry> manualReviews(MinecraftServer server) {
        return MarketTransactionLog.get(server).manualReviews().stream().sorted(Comparator.comparingLong(MarketTransactionLog.Entry::updatedAt)).toList();
    }

    private static void notifyPendingReviews(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            return;
        }
        int count = MarketTransactionLog.get(player.server).manualReviews().size();
        if (count > 0 && STARTUP_NOTIFIED.add(player.getUUID())) {
            ModernNotificationBridge.notify(player, ModernNotificationBridge.Placement.TOP, ModernNotificationBridge.Category.FAILURE, "notification.freemarket.manual_reviews_pending.title", "notification.freemarket.manual_reviews_pending.message", count);
        }
    }

    private static String displayName(String name, UUID id) {
        return name == null || name.isBlank() ? id == null ? "-" : id.toString() : name;
    }

    private static String formatMoney(double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static String formatTime(long timestamp) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }
}
