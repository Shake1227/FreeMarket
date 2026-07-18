package shake1227.freemarket.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;
import shake1227.freemarket.network.NetworkHandler;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class MarketScreen extends Screen {
    private static final ResourceLocation BACKGROUND = new ResourceLocation("freemarket", "textures/gui/market_background.png");
    private static final ResourceLocation ICON_FONT = new ResourceLocation("freemarket", "icons");
    private static final Map<UUID, ResourceLocation> SKIN_CACHE = new HashMap<>();
    private static final Set<UUID> SKIN_REQUESTED = new HashSet<>();
    private static final int GREEN = 0xFF35F29A;
    private static final int GREEN_DIM = 0xFF16865A;
    private static final int TEXT = 0xFFE8FFF4;
    private static final int MUTED = 0xFF8BA89B;
    private static final int RED = 0xFFE83F54;
    private static final int PANEL = 0xED08110D;
    private static final int CARD = 0xE814211C;
    private static final long LOAD_DELAY = 1000L;
    private static final long LOAD_DURATION = 2500L;
    private static final long LOAD_FADE = 350L;
    private static final long CONTENT_FADE = 320L;
    private static final long TRANSITION_DURATION = 360L;
    private static final long SHUTDOWN_DURATION = 240L;
    private static final int MIN_WIDTH = 640;
    private static final int MIN_HEIGHT = 360;
    private static final int OFFER_CARD_HEIGHT = 54;
    private static final int OFFER_CARD_GAP = 5;

    private final MarketClientState state;
    private final Deque<View> history = new ArrayDeque<>();
    private final long openedAt;
    private final boolean animateOpening;
    private View view = View.HOME;
    private View pendingView;
    private MarketListing selected;
    private MarketListing pendingListing;
    private Modal modal = Modal.NONE;
    private Sort sort = Sort.UPDATED;
    private ListingHistoryFilter listingHistoryFilter = ListingHistoryFilter.ALL;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentTop;
    private int contentBottom;
    private int scroll;
    private int maxScroll;
    private float scrollPosition;
    private int scrollTarget;
    private boolean scrollAnimating;
    private long scrollSoundAt;
    private long transitionStarted;
    private long seenRevision;
    private long toastUntil;
    private Component toast = Component.empty();
    private List<MarketListing> visibleListings = List.of();
    private List<MarketListing> cardListings = List.of();
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private int hoveredItemX;
    private int hoveredItemY;
    private String query = "";
    private String tagQuery = "";
    private String minimumPrice = "";
    private String maximumPrice = "";
    private boolean activeOnly = true;
    private EditBox queryBox;
    private EditBox tagBox;
    private EditBox minPriceBox;
    private EditBox maxPriceBox;
    private EditBox bidBox;
    private CyberTextArea commentBox;
    private EditBox offerBox;
    private EditBox createNameBox;
    private EditBox createPriceBox;
    private EditBox createDurationBox;
    private CyberTextArea createDescriptionBox;
    private EditBox createTagBox;
    private EditBox editNameBox;
    private EditBox editPriceBox;
    private CyberTextArea editDescriptionBox;
    private EditBox adminTagBox;
    private EditBox adminTagLabelBox;
    private EditBox adminFeeBox;
    private CyberTextArea reportDetailBox;
    private String createName = "";
    private String createPrice = "";
    private String createDuration = "60";
    private String createDescription = "";
    private String createTagsText = "";
    private final Set<String> createTags = new LinkedHashSet<>();
    private boolean createAuction;
    private boolean draftDirty;
    private boolean detailEditing;
    private String adminSelectedTag = "";
    private boolean adminPercentFee;
    private String adminFeeInput = "";
    private boolean adminFeeDirty;
    private double uiScale = 1.0D;
    private boolean pendingClose;
    private boolean publishing;
    private long queryDue;
    private int requestedPage;
    private boolean requestedLikedOnly;
    private long detailRefreshDue;
    private long offerUiRefreshAt;
    private final Map<String, Float> cardHoverAmounts = new HashMap<>();
    private final float[] bottomTabAmounts = new float[3];
    private long lastFrameAt;
    private float frameDelta = 1.0F / 60.0F;
    private int transitionDirection = 1;
    private long modalOpenedAt;
    private long toastStartedAt;
    private long syncPulseStartedAt;
    private long feedbackStartedAt;
    private int feedbackX;
    private int feedbackY;
    private boolean bootSoundPlayed;
    private boolean loadSoundPlayed;
    private boolean readySoundPlayed;
    private boolean closing;
    private long closingAt;
    private String pendingSavedSearchId = "";
    private String pendingReportId = "";
    private String reportDetail = "";
    private long reportValidationUntil;
    private final Set<String> searchTags = new LinkedHashSet<>();
    private int createTagScroll;
    private int createTagContentHeight;
    private String reviewListingId = "";
    private String reviewSellerName = "";
    private int reviewStars;
    private String reviewComment = "";
    private CyberTextArea reviewCommentBox;
    private long reviewValidationUntil;
    private long pendingBidAmount;
    private String pendingUnblockUserId = "";
    private long tagCacheRevision = -1L;
    private final Map<String, Component> tagChipLabels = new HashMap<>();
    private final Map<String, Integer> tagChipWidths = new HashMap<>();
    private long draftCacheRevision = -1L;
    private ItemStack cachedDraft = ItemStack.EMPTY;
    private List<Component> cachedDraftLore = List.of();
    private String detailLoreId = "";
    private long detailLoreRevision = -1L;
    private List<Component> detailLoreCache = List.of();
    private final List<AuthorZone> commentAuthorZones = new ArrayList<>();
    private final List<AuthorZone> reviewAuthorZones = new ArrayList<>();

    private record AuthorZone(int x, int y, int w, int h, String id, String name) {
    }

    public MarketScreen(MarketClientState state, boolean animateOpening) {
        this(state, animateOpening, false);
    }

    public MarketScreen(MarketClientState state, boolean animateOpening, boolean openAdmin) {
        super(Component.translatable("gui.freemarket.title"));
        this.state = state;
        this.animateOpening = animateOpening;
        this.openedAt = System.currentTimeMillis();
        this.seenRevision = state.revision();
        this.view = openAdmin ? View.ADMIN : View.HOME;
    }

    @Override
    protected void init() {
        applyForcedScale();
        updateLayout();
        buildWidgets();
    }

    private void applyForcedScale() {
        Window window = (minecraft == null ? Minecraft.getInstance() : minecraft).getWindow();
        int forced = window.getWidth() >= 1280 && window.getHeight() >= 800 ? 2 : 1;
        uiScale = forced / Math.max(0.01D, window.getGuiScale());
        this.width = Mth.ceil(window.getWidth() / (double)forced);
        this.height = Mth.ceil(window.getHeight() / (double)forced);
        CyberUiFx.setUiScale(uiScale);
    }

    @Override
    public void tick() {
        if (closing && System.currentTimeMillis() - closingAt >= SHUTDOWN_DURATION) {
            completeClose();
            return;
        }
        if (state.revision() != seenRevision) {
            seenRevision = state.revision();
            refreshSelected();
            syncPulseStartedAt = System.currentTimeMillis();
            String messageKey = state.takeMessageKey();
            if (!messageKey.isBlank()) {
                showToast(Component.translatable(messageKey), 3500L);
            }
        }
        tickBox(queryBox);
        tickBox(tagBox);
        tickBox(minPriceBox);
        tickBox(maxPriceBox);
        tickBox(bidBox);
        tickBox(offerBox);
        tickBox(createNameBox);
        tickBox(createPriceBox);
        tickBox(createDurationBox);
        tickBox(createTagBox);
        tickBox(editNameBox);
        tickBox(editPriceBox);
        tickBox(adminTagBox);
        tickBox(adminTagLabelBox);
        tickBox(adminFeeBox);
        if (queryDue > 0L && System.currentTimeMillis() >= queryDue) {
            queryDue = 0L;
            if (requestedLikedOnly) {
                sendLikedQuery(requestedPage);
            } else {
                sendQuery(requestedPage);
            }
        }
        if (detailRefreshDue > 0L && System.currentTimeMillis() >= detailRefreshDue) {
            detailRefreshDue = 0L;
            if (view == View.DETAIL && selected != null) {
                send("view", listingId(selected));
            }
        }
        if (offerUiRefreshAt > 0L && System.currentTimeMillis() >= offerUiRefreshAt) {
            offerUiRefreshAt = 0L;
            if (view == View.DETAIL && selected != null) {
                buildWidgets();
            }
        }
    }

    private void tickBox(EditBox box) {
        if (box != null) {
            box.tick();
        }
    }

    public void serverStateChanged(String action, CompoundTag payload) {
        seenRevision = state.revision();
        syncPulseStartedAt = System.currentTimeMillis();
        if (publishing && "SYNC".equalsIgnoreCase(action) && payload.getBoolean("ClearDraft")) {
            publishing = false;
            draftDirty = false;
            clearCreateForm();
            navigateRoot(View.HOME);
        } else if (publishing && "RESULT".equalsIgnoreCase(action) && !payload.getBoolean("Success")) {
            publishing = false;
        }
        if ("INVALIDATE".equalsIgnoreCase(action)) {
            if (view == View.LIKES) {
                scheduleLikedQuery(state.page());
            } else {
                scheduleQuery(state.page());
            }
            if (view == View.DETAIL && selected != null) {
                detailRefreshDue = System.currentTimeMillis() + 220L;
            }
        }
        if ("DETAIL".equalsIgnoreCase(action) && state.detail() != null) {
            selected = state.detail();
        }
        if ("SELLER".equalsIgnoreCase(action)) {
            if (view != View.SELLER) {
                navigate(View.SELLER);
            } else {
                buildWidgets();
            }
        }
        if ("REVIEW_PROMPT".equalsIgnoreCase(action) && modal == Modal.NONE) {
            reviewListingId = payload.getString("Id");
            reviewSellerName = payload.getString("SellerName");
            reviewStars = 0;
            reviewComment = "";
            setModal(Modal.REVIEW);
        }
        refreshSelected();
        if ("DETAIL".equalsIgnoreCase(action) && view == View.DETAIL) {
            buildWidgets();
        }
        if ("DRAFT".equalsIgnoreCase(action) && (view == View.CREATE || view == View.INVENTORY)) {
            fillDraftDefaults();
            buildWidgets();
        }
        String messageKey = state.takeMessageKey();
        if (!messageKey.isBlank()) {
            showToast(Component.translatable(messageKey), 3500L);
        }
    }

    private void refreshSelected() {
        if (selected == null) {
            return;
        }
        MarketListing detail = state.detail();
        if (detail != null && detail.id().equals(selected.id())) {
            selected = detail;
            return;
        }
        for (MarketListing listing : state.listings()) {
            if (listing.id().equals(selected.id())) {
                selected = listing;
                return;
            }
        }
    }

    private void updateLayout() {
        panelW = Math.min(1180, Math.max(300, width - 18));
        panelH = Math.min(680, Math.max(220, height - 18));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        contentTop = panelY + 51;
        contentBottom = panelY + panelH - 35;
    }

    private void buildWidgets() {
        clearWidgets();
        queryBox = null;
        tagBox = null;
        minPriceBox = null;
        maxPriceBox = null;
        bidBox = null;
        commentBox = null;
        offerBox = null;
        offerUiRefreshAt = 0L;
        createNameBox = null;
        createPriceBox = null;
        createDurationBox = null;
        createDescriptionBox = null;
        createTagBox = null;
        editNameBox = null;
        editPriceBox = null;
        editDescriptionBox = null;
        adminTagBox = null;
        adminTagLabelBox = null;
        adminFeeBox = null;
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            addRenderableWidget(new CyberButton(panelX + panelW / 2 - 50, panelY + panelH - 34, 100, 22, Component.translatable("gui.freemarket.close"), this::closeNow));
            return;
        }
        if (view == View.HOME) {
            buildHomeWidgets();
        } else if (view == View.DETAIL) {
            buildDetailWidgets();
        } else if (view == View.CREATE) {
            buildCreateWidgets();
        } else if (view == View.INVENTORY) {
            buildInventoryWidgets();
        } else if (view == View.PROFILE) {
            buildProfileWidgets();
        } else if (view == View.NOTIFICATIONS) {
            buildNotificationWidgets();
        } else if (view == View.ADMIN) {
            buildAdminWidgets();
        } else if (view == View.SELLER || view == View.SELLER_REVIEWS || view == View.BLOCKED_USERS) {
            addBackButton();
        } else {
            buildHistoryWidgets();
        }
    }

    private void buildHomeWidgets() {
        int sideW = Math.max(168, panelW * 22 / 100);
        int sideX = panelX + panelW - sideW;
        int x = sideX + 10;
        int w = sideW - 20;
        FilterLayout layout = filterLayout();
        int fieldHeight = layout.compact ? 18 : 22;
        queryBox = addBox(x, layout.queryY, w, layout.compact ? 20 : 26, "gui.freemarket.search", query, value -> {
            query = value;
            resetScroll();
            scheduleQuery();
        });
        tagBox = addBox(x, layout.tagY, w, fieldHeight, "gui.freemarket.search_tags", tagQuery, value -> {
            tagQuery = value;
            resetScroll();
            scheduleQuery();
        });
        minPriceBox = addBox(x, layout.minimumY, w, fieldHeight, "gui.freemarket.minimum_price", minimumPrice, value -> {
            minimumPrice = numeric(value);
            if (!value.equals(minimumPrice)) {
                minPriceBox.setValue(minimumPrice);
            }
            resetScroll();
            scheduleQuery();
        });
        maxPriceBox = addBox(x, layout.maximumY, w, fieldHeight, "gui.freemarket.maximum_price", maximumPrice, value -> {
            maximumPrice = numeric(value);
            if (!value.equals(maximumPrice)) {
                maxPriceBox.setValue(maximumPrice);
            }
            resetScroll();
            scheduleQuery();
        });
        addRenderableWidget(new CyberButton(x, layout.saveY, w, 20, Component.translatable("gui.freemarket.save_search"), this::saveCurrentSearch));
        addRenderableWidget(new CyberButton(x, contentBottom - 27, w, 20, Component.translatable("gui.freemarket.close"), this::onClose));
    }

    private EditBox addBox(int x, int y, int w, int h, String hintKey, String value, Consumer<String> responder) {
        EditBox box = new CyberEditBox(font, x, y, w, h, Component.translatable(hintKey));
        box.setMaxLength(512);
        box.setHint(Component.translatable(hintKey));
        box.setValue(value == null ? "" : value);
        box.setResponder(responder);
        box.setTextColor(TEXT);
        box.setTextColorUneditable(MUTED);
        return addRenderableWidget(box);
    }

    private CyberTextArea addArea(int x, int y, int w, int h, String hintKey, String value, Consumer<String> responder) {
        CyberTextArea area = new CyberTextArea(font, x, y, w, h, Component.translatable(hintKey));
        area.setCharacterLimit(512);
        area.setValue(value == null ? "" : value);
        area.setValueListener(responder);
        return addRenderableWidget(area);
    }

    private void buildDetailWidgets() {
        addBackButton();
        if (selected == null) {
            return;
        }
        int rightW = Math.max(240, panelW * 32 / 100);
        int rightX = panelX + panelW - rightW - 12;
        int buttonY = contentBottom - 27;
        boolean mine = isMine(selected);
        MarketListing.MarketOffer ownPendingOffer = !mine && !selected.auction() ? pendingOfferForCurrentUser() : null;
        if (ownPendingOffer != null) {
            trackOfferExpiry(ownPendingOffer);
        }
        if (!selected.sold() && selected.active() && !mine) {
            if (selected.auction()) {
                bidBox = addBox(rightX, buttonY - 25, rightW - 92, 20, "gui.freemarket.bid_amount", "", value -> {
                    String number = numeric(value);
                    if (!number.equals(value)) {
                        bidBox.setValue(number);
                    }
                });
                addRenderableWidget(new CyberButton(rightX + rightW - 86, buttonY - 25, 86, 20, Component.translatable("gui.freemarket.bid"), this::sendBid));
            } else {
                addRenderableWidget(new CyberButton(rightX, buttonY - 25, rightW, 20, Component.translatable("gui.freemarket.buy"), this::sendBuy));
                if (ownPendingOffer == null) {
                    offerBox = addBox(rightX, buttonY, rightW - 92, 20, "gui.freemarket.offer_amount", "", value -> {
                        String number = numeric(value);
                        if (!number.equals(value)) {
                            offerBox.setValue(number);
                        }
                    });
                    addRenderableWidget(new CyberButton(rightX + rightW - 86, buttonY, 86, 20, Component.translatable("gui.freemarket.offer"), this::sendOffer));
                }
            }
        }
        int commentInputY = detailCommentInputY();
        if (!selected.sold()) {
            commentBox = addArea(rightX, commentInputY, rightW - 70, 32, "gui.freemarket.comment_hint", "", value -> {
            });
            addRenderableWidget(new CyberButton(rightX + rightW - 65, commentInputY + 6, 65, 20, Component.translatable("gui.freemarket.send"), this::sendComment));
        }
        if (mine) {
            if (!selected.auction()) {
                List<MarketListing.MarketOffer> offers = pendingOffers();
                offers.forEach(this::trackOfferExpiry);
                int buttonW = detailOfferButtonWidth(rightW);
                int rejectX = rightX + rightW - 9 - buttonW;
                int acceptX = rejectX - OFFER_CARD_GAP - buttonW;
                int cardCount = Math.min(detailOfferCardLimit(), offers.size());
                for (int i = 0; i < cardCount; i++) {
                    MarketListing.MarketOffer offer = offers.get(i);
                    int cardY = detailOfferCardsY() + i * (OFFER_CARD_HEIGHT + OFFER_CARD_GAP);
                    addRenderableWidget(new CyberButton(acceptX, cardY + 29, buttonW, 19, Component.translatable("gui.freemarket.accept"), () -> respondToOffer("accept_offer", offer)));
                    addRenderableWidget(new CyberButton(rejectX, cardY + 29, buttonW, 19, Component.translatable("gui.freemarket.reject"), () -> respondToOffer("reject_offer", offer), true));
                }
            }
            int actionX = panelX + 12;
            int y = contentBottom - 27;
            addRenderableWidget(new CyberButton(actionX, y, 74, 20, Component.translatable(detailEditing ? "gui.freemarket.cancel_edit" : "gui.freemarket.edit"), this::toggleEdit));
            addRenderableWidget(new CyberButton(actionX + 80, y, 74, 20, Component.translatable(selected.active() ? "gui.freemarket.pause_hide" : "gui.freemarket.pause_show"), this::togglePause));
            addRenderableWidget(new CyberButton(actionX + 160, y, 74, 20, Component.translatable("gui.freemarket.cancel_listing"), () -> openListingModal(Modal.CANCEL_LISTING), true));
            if (detailEditing) {
                buildEditFields();
            }
        } else {
            boolean blocked = state.detailSellerBlocked(selected.sellerId());
            int moderationY = contentBottom - 27;
            addRenderableWidget(new CyberButton(panelX + 12, moderationY, 96, 20, Component.translatable(blocked ? "gui.freemarket.unblock_seller" : "gui.freemarket.block_seller"), () -> openSellerBlockModal(blocked), true));
            addRenderableWidget(new CyberButton(panelX + 114, moderationY, 82, 20, Component.translatable("gui.freemarket.report_listing"), this::openReportModal, true));
        }
        if (state.admin()) {
            addRenderableWidget(new CyberButton(panelX + panelW - 113, panelY + 13, 98, 22, Component.translatable("gui.freemarket.admin_delete"), () -> openListingModal(Modal.ADMIN_DELETE), true));
        }
    }

    private void buildCreateWidgets() {
        addBackButton();
        int left = panelX + 24;
        int labelW = 122;
        int fieldX = left + labelW;
        int fieldW = Math.max(150, panelW / 2 - labelW - 40);
        int y = contentTop + 63;
        createNameBox = addBox(fieldX, y, fieldW, 20, "gui.freemarket.item_name", createName, value -> {
            createName = value;
            draftDirty = true;
        });
        createNameBox.setMaxLength(96);
        createDescriptionBox = addArea(fieldX, y + 31, fieldW, 44, "gui.freemarket.description", createDescription, value -> {
            createDescription = value;
            draftDirty = true;
        });
        int priceBoxW = fieldW >= 280 ? fieldW - 130 : fieldW;
        createPriceBox = addBox(fieldX, y + 86, priceBoxW, 20, createAuction ? "gui.freemarket.starting_price" : "gui.freemarket.listing_price", createPrice, value -> {
            createPrice = numeric(value);
            if (!value.equals(createPrice)) {
                createPriceBox.setValue(createPrice);
            }
            draftDirty = true;
        });
        if (createAuction) {
            createDurationBox = addBox(fieldX, y + 117, fieldW, 20, "gui.freemarket.duration_minutes", createDuration, value -> {
                createDuration = numeric(value);
                if (!value.equals(createDuration)) {
                    createDurationBox.setValue(createDuration);
                }
                draftDirty = true;
            });
        }
        createTagBox = addBox(fieldX, y + 148, fieldW, 20, "gui.freemarket.tags_comma", createTagsText, value -> {
            createTagsText = value;
            draftDirty = true;
        });
        createTagBox.setMaxLength(256);
        if (createAuction) {
            String[] presetKeys = {"gui.freemarket.preset_30m", "gui.freemarket.preset_1h", "gui.freemarket.preset_6h", "gui.freemarket.preset_1d", "gui.freemarket.preset_3d"};
            long[] presetMinutes = {30L, 60L, 360L, 1440L, 4320L};
            int presetY = y + 176;
            int presetW = (labelW + fieldW - 16) / 5;
            for (int i = 0; i < presetKeys.length; i++) {
                long minutes = presetMinutes[i];
                addRenderableWidget(new CyberButton(left + i * (presetW + 4), presetY, presetW, 18, Component.translatable(presetKeys[i]), () -> {
                    createDuration = Long.toString(minutes);
                    if (createDurationBox != null) {
                        createDurationBox.setValue(createDuration);
                    }
                    draftDirty = true;
                }));
            }
        }
        int bottomY = contentBottom - 27;
        addRenderableWidget(new CyberButton(panelX + panelW - 218, bottomY, 92, 20, Component.translatable("gui.freemarket.cancel"), this::requestLeaveDraft, true));
        addRenderableWidget(new CyberButton(panelX + panelW - 118, bottomY, 102, 20, Component.translatable("gui.freemarket.publish"), this::publish));
    }

    private void buildInventoryWidgets() {
        addBackButton();
    }

    private void buildProfileWidgets() {
        addBackButton();
    }

    private void buildNotificationWidgets() {
        addBackButton();
        addRenderableWidget(new CyberButton(panelX + panelW - 132, panelY + 13, 116, 22, Component.translatable("gui.freemarket.read_all"), () -> send("notification_read_all", new CompoundTag())));
    }

    private void buildAdminWidgets() {
        addBackButton();
        CompoundTag fee = state.fee();
        if (!adminFeeDirty) {
            adminPercentFee = "PERCENT".equalsIgnoreCase(fee.getString("Mode"));
            adminFeeInput = fee.contains("Value") ? formatFeeValue(fee.getDouble("Value")) : "0";
        }
        int splitX = panelX + panelW / 2 - 8;
        int leftX = panelX + 32;
        int leftW = Math.max(220, splitX - leftX - 16);
        int half = (leftW - 8) / 2;
        adminTagBox = addBox(leftX, contentTop + 56, half, 20, "gui.freemarket.tag_name", adminSelectedTag, text -> {
        });
        adminTagBox.setMaxLength(64);
        adminTagLabelBox = addBox(leftX + half + 8, contentTop + 56, leftW - half - 8, 20, "gui.freemarket.tag_label", adminSelectedTag.isBlank() ? "" : state.tagFallbackLabel(adminSelectedTag), text -> {
        });
        adminTagLabelBox.setMaxLength(96);
        int actionY = contentTop + 82;
        int actionW = (leftW - 16) / 3;
        addRenderableWidget(new CyberButton(leftX, actionY, actionW, 20, Component.translatable(adminSelectedTag.isBlank() ? "gui.freemarket.add" : "gui.freemarket.save"), this::saveAdminTag));
        addRenderableWidget(new CyberButton(leftX + actionW + 8, actionY, actionW, 20, Component.translatable("gui.freemarket.delete"), this::deleteAdminTag, true));
        addRenderableWidget(new CyberButton(leftX + (actionW + 8) * 2, actionY, actionW, 20, Component.translatable("gui.freemarket.clear_selection"), () -> {
            adminSelectedTag = "";
            buildWidgets();
        }));
        int feeY = adminFeeSectionY();
        adminFeeBox = addBox(leftX, feeY + 80, leftW - 98, 20, adminPercentFee ? "gui.freemarket.fee_hint_percent" : "gui.freemarket.fee_hint_fixed", adminFeeInput, text -> {
            adminFeeInput = text;
            adminFeeDirty = true;
        });
        addRenderableWidget(new CyberButton(leftX + leftW - 90, feeY + 80, 90, 20, Component.translatable("gui.freemarket.apply"), this::saveFee));
    }

    private int adminFeeSectionY() {
        return contentTop + 132;
    }

    private String formatFeeValue(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value) ? Long.toString((long)value) : Double.toString(value);
    }

    private void buildHistoryWidgets() {
        addBackButton();
    }

    private void addBackButton() {
        addRenderableWidget(new CyberButton(panelX + 14, panelY + 13, 74, 22, Component.translatable("gui.freemarket.back"), this::back));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int virtualMouseX = (int)(mouseX / uiScale);
        int virtualMouseY = (int)(mouseY / uiScale);
        graphics.pose().pushPose();
        graphics.pose().scale((float)uiScale, (float)uiScale, 1.0F);
        renderScaled(graphics, virtualMouseX, virtualMouseY, partialTick);
        graphics.pose().popPose();
    }

    private void renderScaled(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateLayout();
        hoveredStack = ItemStack.EMPTY;
        long now = System.currentTimeMillis();
        frameDelta = lastFrameAt == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastFrameAt) / 1000.0F);
        lastFrameAt = now;
        updateSmoothScroll();
        graphics.fillGradient(0, 0, width, height, 0xD9000805, 0xEE010302);
        CyberUiFx.scanlines(graphics, 0, 0, width, height, now, 0.32F);
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            renderSmallScreen(graphics, mouseX, mouseY, partialTick);
            CyberUiFx.vignette(graphics, 0, 0, width, height, 0.85F);
            renderShutdown(graphics, now);
            return;
        }
        long elapsed = now - openedAt;
        long contentStart = LOAD_DELAY + LOAD_DURATION + LOAD_FADE;
        if (animateOpening && elapsed < contentStart) {
            renderLoading(graphics, elapsed);
            CyberUiFx.vignette(graphics, 0, 0, width, height, 0.9F);
            renderShutdown(graphics, now);
            return;
        }
        float contentAlpha = animateOpening ? clamp((elapsed - contentStart) / (float)CONTENT_FADE) : 1.0F;
        renderWindow(graphics, 1.0F, contentAlpha);
        float transition = transitionStarted == 0L ? 1.0F : clamp((now - transitionStarted) / (float)TRANSITION_DURATION);
        float slide = transitionStarted == 0L ? 0.0F : transitionDirection * (1.0F - CyberUiFx.easeOutBack(transition)) * 58.0F;
        CyberUiFx.scissor(graphics, panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1);
        graphics.pose().pushPose();
        graphics.pose().translate(slide, 0.0F, 0.0F);
        renderCurrent(graphics, mouseX - (int)slide, mouseY, partialTick);
        super.render(graphics, mouseX - (int)slide, mouseY, partialTick);
        if (view == View.HOME) {
            renderTagSuggestions(graphics, mouseX - (int)slide, mouseY);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
        renderBottomBar(graphics, mouseX, mouseY);
        renderSyncPulse(graphics, now);
        renderTransitionFx(graphics, transition, now);
        CyberUiFx.scanlines(graphics, panelX + 1, panelY + 1, panelW - 2, panelH - 2, now, 0.82F);
        CyberUiFx.glitch(graphics, panelX + 2, panelY + 2, panelW - 4, panelH - 4, now, 0.85F);
        CyberUiFx.vignette(graphics, 0, 0, width, height, 0.82F);
        if (contentAlpha < 1.0F) {
            int coverAlpha = (int)((1.0F - contentAlpha) * 235.0F);
            graphics.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + panelH - 1, coverAlpha << 24);
        }
        renderFeedback(graphics, now);
        if (!hoveredStack.isEmpty() && modal == Modal.NONE) {
            renderItemTooltip(graphics, hoveredStack, hoveredItemX, hoveredItemY);
        }
        if (now < toastUntil) {
            renderToast(graphics, now);
        }
        if (modal != Modal.NONE) {
            renderModal(graphics, mouseX, mouseY, now, partialTick);
        }
        renderShutdown(graphics, now);
    }

    private void renderSmallScreen(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderWindow(graphics, 1.0F, 1.0F);
        drawCenteredScaled(graphics, Component.translatable("gui.freemarket.screen_too_small"), width / 2, height / 2 - 10, 1.15F, TEXT);
        drawCenteredScaled(graphics, Component.translatable("gui.freemarket.minimum_size", MIN_WIDTH, MIN_HEIGHT), width / 2, height / 2 + 8, 1.05F, MUTED);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderLoading(GuiGraphics graphics, long elapsed) {
        updateBootSounds(elapsed);
        float horizontal = easeOut(clamp(elapsed / 260.0F));
        float vertical = CyberUiFx.easeOutBack(clamp((elapsed - 90L) / 430.0F));
        float flicker = elapsed < 420L ? 0.82F + 0.18F * (float)Math.abs(Math.sin(elapsed * 0.071D)) : 1.0F;
        renderWindow(graphics, Math.max(0.004F, horizontal), Math.max(0.004F, vertical), flicker);
        if (vertical < 0.16F) {
            int half = Math.max(2, (int)(panelW * horizontal / 2.0F));
            graphics.fill(width / 2 - half, height / 2 - 1, width / 2 + half, height / 2 + 1, CyberUiFx.alpha(0x8BFFC6, 0.72F * flicker));
        }
        if (elapsed < 180L) {
            return;
        }
        float loadingIn = clamp((elapsed - 180L) / 420.0F);
        float loadingOut = elapsed > LOAD_DELAY + LOAD_DURATION ? 1.0F - clamp((elapsed - LOAD_DELAY - LOAD_DURATION) / (float)LOAD_FADE) : 1.0F;
        int alpha = (int)(255.0F * loadingIn * loadingOut);
        int centerY = panelY + panelH / 2;
        Component loading = Component.translatable("gui.freemarket.loading");
        float titlePulse = 0.82F + 0.18F * (float)Math.sin(elapsed * 0.006D);
        graphics.drawCenteredString(font, loading, width / 2 + 1, centerY - 26, CyberUiFx.alpha(0x001109, alpha / 255.0F * 0.8F));
        graphics.drawCenteredString(font, loading, width / 2, centerY - 27, CyberUiFx.alpha(0x35F29A, alpha / 255.0F * titlePulse));
        int barW = Math.min(360, panelW * 55 / 100);
        int x = width / 2 - barW / 2;
        int y = centerY - 4;
        graphics.fillGradient(x, y, x + barW, y + 9, CyberUiFx.alpha(0x153C2B, alpha / 765.0F), CyberUiFx.alpha(0x06130D, alpha / 510.0F));
        border(graphics, x, y, barW, 9, CyberUiFx.alpha(0x35F29A, alpha / 255.0F));
        CyberUiFx.corners(graphics, x - 2, y - 2, barW + 4, 13, 8, CyberUiFx.alpha(0x8BFFC6, alpha / 318.0F));
        float progress = elapsed < LOAD_DELAY ? 0.0F : loadingProgress(clamp((elapsed - LOAD_DELAY) / (float)LOAD_DURATION));
        int fill = Math.max(0, (int)((barW - 4) * progress));
        float power = 0.72F + 0.28F * (float)Math.sin(elapsed * 0.011D);
        if (fill > 0) {
            graphics.fillGradient(x + 2, y + 2, x + 2 + fill, y + 7, CyberUiFx.alpha(0x78FFC2, alpha / 255.0F * power), CyberUiFx.alpha(0x1AC67C, alpha / 255.0F * power));
            int shine = x + 2 + Math.floorMod((int)(elapsed / 6L), Math.max(1, fill));
            graphics.fill(Math.max(x + 2, shine - 2), y + 2, Math.min(x + 2 + fill, shine + 2), y + 7, CyberUiFx.alpha(0xE5FFF2, alpha / 255.0F * 0.48F));
        }
        for (int marker = 1; marker < 10; marker++) {
            int markerX = x + 2 + (barW - 4) * marker / 10;
            graphics.fill(markerX, y + 2, markerX + 1, y + 7, CyberUiFx.alpha(0x001109, alpha / 255.0F * 0.62F));
        }
        graphics.drawCenteredString(font, Component.translatable("gui.freemarket.loading_percent", (int)(progress * 100.0F)), width / 2, y + 17, CyberUiFx.alpha(0xB8D9CA, alpha / 255.0F));
        renderNoise(graphics, panelX, panelY, panelW, panelH, alpha / 2);
        CyberUiFx.scanlines(graphics, panelX + 1, panelY + 1, panelW - 2, panelH - 2, elapsed, alpha / 255.0F);
        CyberUiFx.glitch(graphics, panelX + 2, panelY + 2, panelW - 4, panelH - 4, elapsed + 2900L, alpha / 255.0F);
    }

    private float loadingProgress(float value) {
        float eased = value < 0.28F ? 0.18F * easeInOut(value / 0.28F) : value < 0.72F ? 0.18F + 0.62F * easeInOut((value - 0.28F) / 0.44F) : 0.80F + 0.20F * easeInOut((value - 0.72F) / 0.28F);
        return clamp(eased);
    }

    private void renderWindow(GuiGraphics graphics, float scale, float alpha) {
        renderWindow(graphics, scale, scale, alpha);
    }

    private void renderWindow(GuiGraphics graphics, float scaleX, float scaleY, float alpha) {
        int w = Math.max(2, (int)(panelW * Math.max(0.0F, scaleX)));
        int h = Math.max(2, (int)(panelH * Math.max(0.0F, scaleY)));
        int x = width / 2 - w / 2;
        int y = height / 2 - h / 2;
        int a = (int)(237 * alpha);
        graphics.fillGradient(x, y, x + w, y + h, (a << 24) | 0x0A1812, (a << 24) | 0x030906);
        graphics.setColor(0.45F, 1.0F, 0.70F, 0.26F * alpha);
        graphics.blit(BACKGROUND, x, y, w, h, 0.0F, 0.0F, 1024, 1024, 1024, 1024);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        border(graphics, x, y, w, h, ((int)(255 * alpha) << 24) | 0x35F29A);
        if (w > 8 && h > 8) {
            border(graphics, x + 3, y + 3, w - 6, h - 6, CyberUiFx.alpha(0x183E2D, alpha * 0.62F));
            CyberUiFx.corners(graphics, x - 2, y - 2, w + 4, h + 4, Math.min(18, Math.min(w, h) / 3), CyberUiFx.alpha(0x8BFFC6, alpha * 0.88F));
            graphics.fill(x + 3, y + 3, x + Math.min(w - 3, 46), y + 5, CyberUiFx.alpha(0x35F29A, alpha));
            graphics.fill(x + Math.max(3, w - 92), y + h - 5, x + w - 3, y + h - 3, CyberUiFx.alpha(0x35F29A, alpha * 0.72F));
            int trackW = Math.max(1, w - 16);
            int trackX = x + 8 + Math.floorMod((int)(System.currentTimeMillis() / 9L), trackW);
            graphics.fill(trackX, y + 1, Math.min(x + w - 2, trackX + 13), y + 2, CyberUiFx.alpha(0xC9FFE3, alpha * 0.62F));
        }
        if (scaleX > 0.82F && scaleY > 0.82F) {
            renderNoise(graphics, x, y, w, h, (int)(28 * alpha));
        }
    }

    private void renderNoise(GuiGraphics graphics, int x, int y, int w, int h, int alpha) {
        if (alpha <= 0 || w < 10 || h < 10) {
            return;
        }
        long frame = System.currentTimeMillis() / 70L;
        for (int i = 0; i < 28; i++) {
            long seed = frame * 1103515245L + i * 12345L;
            int px = x + 3 + Math.floorMod((int)(seed >>> 8), Math.max(1, w - 6));
            int py = y + 3 + Math.floorMod((int)(seed >>> 21), Math.max(1, h - 6));
            int length = 1 + Math.floorMod((int)(seed >>> 31), 9);
            graphics.fill(px, py, Math.min(x + w - 2, px + length), py + 1, (alpha << 24) | (i % 3 == 0 ? 0x35F29A : 0x6EA88D));
        }
        CyberUiFx.glitch(graphics, x + 2, y + 2, w - 4, h - 4, System.currentTimeMillis(), alpha / 96.0F);
    }

    private void updateBootSounds(long elapsed) {
        if (!bootSoundPlayed) {
            bootSoundPlayed = true;
            CyberUiFx.play("minecraft:block.beacon.activate", 0.62F);
        }
        if (!loadSoundPlayed && elapsed >= LOAD_DELAY) {
            loadSoundPlayed = true;
            CyberUiFx.play("minecraft:block.note_block.hat", 0.82F);
        }
        if (!readySoundPlayed && elapsed >= LOAD_DELAY + LOAD_DURATION) {
            readySoundPlayed = true;
            CyberUiFx.play("minecraft:block.note_block.chime", 1.72F);
        }
    }

    private void renderSyncPulse(GuiGraphics graphics, long now) {
        if (syncPulseStartedAt == 0L) {
            return;
        }
        float progress = (now - syncPulseStartedAt) / 520.0F;
        if (progress < 0.0F || progress >= 1.0F) {
            return;
        }
        float eased = CyberUiFx.smoothstep(progress);
        float alpha = (1.0F - progress) * 0.38F;
        int y = contentTop + (int)((contentBottom - contentTop) * eased);
        graphics.fillGradient(panelX + 5, y - 7, panelX + panelW - 5, y, CyberUiFx.alpha(0x35F29A, 0.0F), CyberUiFx.alpha(0x35F29A, alpha * 0.55F));
        graphics.fill(panelX + 5, y, panelX + panelW - 5, y + 1, CyberUiFx.alpha(0xB7FFDA, alpha));
        graphics.fillGradient(panelX + 5, y + 1, panelX + panelW - 5, y + 9, CyberUiFx.alpha(0x35F29A, alpha * 0.25F), CyberUiFx.alpha(0x35F29A, 0.0F));
    }

    private void renderTransitionFx(GuiGraphics graphics, float transition, long now) {
        if (transitionStarted == 0L || transition >= 1.0F) {
            return;
        }
        float remainder = 1.0F - CyberUiFx.smoothstep(transition);
        for (int trail = 1; trail <= 3; trail++) {
            int offset = transitionDirection * (int)(remainder * (28.0F + trail * 12.0F));
            CyberUiFx.corners(graphics, panelX + offset, panelY, panelW, panelH, 18, CyberUiFx.alpha(0x35F29A, remainder * (0.24F / trail)));
        }
        int wipeX = transitionDirection > 0
                ? panelX + (int)(panelW * CyberUiFx.smoothstep(transition))
                : panelX + panelW - (int)(panelW * CyberUiFx.smoothstep(transition));
        graphics.fill(wipeX - 1, contentTop, wipeX + 1, contentBottom, CyberUiFx.alpha(0xB7FFDA, remainder * 0.52F));
        CyberUiFx.glitch(graphics, panelX + 2, contentTop, panelW - 4, Math.max(1, contentBottom - contentTop), now + transitionDirection * 700L, remainder);
    }

    private void renderFeedback(GuiGraphics graphics, long now) {
        if (feedbackStartedAt == 0L) {
            return;
        }
        float progress = (now - feedbackStartedAt) / 340.0F;
        if (progress < 0.0F || progress >= 1.0F) {
            return;
        }
        int radius = 4 + (int)(CyberUiFx.smoothstep(progress) * 15.0F);
        float alpha = (1.0F - progress) * 0.86F;
        CyberUiFx.corners(graphics, feedbackX - radius, feedbackY - radius, radius * 2, radius * 2, Math.max(2, radius / 2), CyberUiFx.alpha(0x8BFFC6, alpha));
        graphics.fill(feedbackX - 1, feedbackY - 1, feedbackX + 2, feedbackY + 2, CyberUiFx.alpha(0xE8FFF4, alpha * 0.8F));
    }

    private void updateSmoothScroll() {
        if (!scrollAnimating) {
            scrollPosition = scroll;
            scrollTarget = scroll;
            return;
        }
        scrollPosition = CyberUiFx.approach(scrollPosition, scrollTarget, frameDelta, 13.0F);
        scroll = Math.round(scrollPosition);
        if (Math.abs(scrollPosition - scrollTarget) < 0.35F) {
            scrollPosition = scrollTarget;
            scroll = scrollTarget;
            scrollAnimating = false;
        }
    }

    private void resetScroll() {
        scroll = 0;
        scrollPosition = 0.0F;
        scrollTarget = 0;
        scrollAnimating = false;
    }

    private void clampSmoothScroll() {
        scroll = Mth.clamp(scroll, 0, maxScroll);
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll);
        scrollPosition = Mth.clamp(scrollPosition, 0.0F, maxScroll);
        if (!scrollAnimating) {
            scrollPosition = scroll;
            scrollTarget = scroll;
        }
    }

    private void renderShutdown(GuiGraphics graphics, long now) {
        if (!closing) {
            return;
        }
        float progress = CyberUiFx.smoothstep(clamp((now - closingAt) / (float)SHUTDOWN_DURATION));
        int cover = (int)(panelH * 0.5F * progress);
        int center = panelY + panelH / 2;
        graphics.fill(panelX, panelY, panelX + panelW, panelY + cover, CyberUiFx.alpha(0x000201, 0.96F));
        graphics.fill(panelX, panelY + panelH - cover, panelX + panelW, panelY + panelH, CyberUiFx.alpha(0x000201, 0.96F));
        if (cover > 0) {
            graphics.fill(panelX, panelY + cover - 1, panelX + panelW, panelY + cover + 1, CyberUiFx.alpha(0x8BFFC6, 0.7F * (1.0F - progress)));
            graphics.fill(panelX, panelY + panelH - cover - 1, panelX + panelW, panelY + panelH - cover + 1, CyberUiFx.alpha(0x8BFFC6, 0.7F * (1.0F - progress)));
        }
        if (progress > 0.78F) {
            float beam = 1.0F - (progress - 0.78F) / 0.22F;
            graphics.fill(panelX, center - 1, panelX + panelW, center + 1, CyberUiFx.alpha(0xB7FFDA, beam));
        }
        CyberUiFx.glitch(graphics, panelX, panelY, panelW, panelH, now + 1700L, 1.0F - progress * 0.3F);
        graphics.fill(0, 0, width, height, CyberUiFx.alpha(0x000000, progress * 0.38F));
    }

    private void renderCurrent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderHeader(graphics, mouseX, mouseY);
        if (view == View.HOME) {
            renderHome(graphics, mouseX, mouseY);
        } else if (view == View.DETAIL) {
            renderDetail(graphics, mouseX, mouseY);
        } else if (view == View.CREATE) {
            renderCreate(graphics, mouseX, mouseY);
        } else if (view == View.INVENTORY) {
            renderInventory(graphics, mouseX, mouseY);
        } else if (view == View.PROFILE) {
            renderProfile(graphics, mouseX, mouseY);
        } else if (view == View.NOTIFICATIONS) {
            renderNotifications(graphics, mouseX, mouseY);
        } else if (view == View.ADMIN) {
            renderAdmin(graphics, mouseX, mouseY);
        } else if (view == View.SELLER) {
            renderSeller(graphics, mouseX, mouseY);
        } else if (view == View.SELLER_REVIEWS) {
            renderSellerReviews(graphics, mouseX, mouseY);
        } else if (view == View.BLOCKED_USERS) {
            renderBlockedUsers(graphics, mouseX, mouseY);
        } else {
            renderHistory(graphics, mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        int headerAccent = view == View.ADMIN ? 0xFFFFC460 : GREEN_DIM;
        graphics.fillGradient(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 42, 0xD111281D, 0xCC06110C);
        graphics.fill(panelX + 1, panelY + 41, panelX + panelW - 1, panelY + 42, headerAccent);
        int signal = panelX + 220 + Math.floorMod((int)(System.currentTimeMillis() / 12L), Math.max(1, panelW - 270));
        graphics.fill(signal, panelY + 40, Math.min(panelX + panelW - 2, signal + 28), panelY + 42, CyberUiFx.alpha(view == View.ADMIN ? 0xFFC460 : 0x8BFFC6, 0.72F));
        if (view == View.HOME) {
            renderRoundHead(graphics, panelX + 12, panelY + 7, 28);
            drawScaled(graphics, Component.translatable("gui.freemarket.market"), panelX + 48, panelY + 8, 1.3F, TEXT);
            CompoundTag user = state.user();
            Component name = Component.literal(user.getString("Name").isBlank() && minecraft != null ? minecraft.getUser().getName() : user.getString("Name"));
            drawClipped(graphics, name, panelX + 48, panelY + 24, 112, MUTED);
            int bellX = panelX + 170;
            graphics.pose().pushPose();
            graphics.pose().translate(bellX, panelY + 11, 0.0F);
            graphics.pose().scale(1.35F, 1.35F, 1.0F);
            drawBell(graphics, 0, 0, GREEN);
            graphics.pose().popPose();
            int unread = unreadCount();
            if (unread > 0) {
                circle(graphics, bellX + 19, panelY + 11, 7, RED);
                Component count = Component.literal(unread > 99 ? "99+" : Integer.toString(unread));
                graphics.drawCenteredString(font, count, bellX + 19, panelY + 8, 0xFFFFFFFF);
            }
        } else {
            int titleColor = view == View.ADMIN ? 0xFFFFD787 : TEXT;
            drawCenteredScaled(graphics, viewTitle(), width / 2 + 1, panelY + 16, 1.35F, 0xAA001109);
            drawCenteredScaled(graphics, viewTitle(), width / 2, panelY + 15, 1.35F, titleColor);
        }
    }

    private Component viewTitle() {
        return Component.translatable(switch (view) {
            case DETAIL -> "gui.freemarket.detail";
            case CREATE -> "gui.freemarket.create";
            case INVENTORY -> "gui.freemarket.select_item";
            case PROFILE -> "gui.freemarket.profile";
            case LIKES -> "gui.freemarket.likes";
            case VIEW_HISTORY -> "gui.freemarket.view_history";
            case PURCHASE_HISTORY -> "gui.freemarket.purchase_history";
            case LISTING_HISTORY -> "gui.freemarket.listing_history";
            case NOTIFICATIONS -> "gui.freemarket.notifications";
            case ADMIN -> "gui.freemarket.admin";
            case SELLER -> "gui.freemarket.seller_page";
            case SELLER_REVIEWS -> "gui.freemarket.reviews_title";
            case BLOCKED_USERS -> "gui.freemarket.blocked_users";
            default -> "gui.freemarket.market";
        });
    }

    private void renderHome(GuiGraphics graphics, int mouseX, int mouseY) {
        int sideW = Math.max(168, panelW * 22 / 100);
        int sideX = panelX + panelW - sideW;
        graphics.fillGradient(sideX, contentTop - 9, panelX + panelW - 1, contentBottom, 0xC010231A, 0xD006100B);
        graphics.fill(sideX, contentTop - 9, sideX + 1, contentBottom, GREEN_DIM);
        CyberUiFx.corners(graphics, sideX + 3, contentTop - 6, sideW - 7, contentBottom - contentTop + 3, 10, 0x6635F29A);
        renderSortBar(graphics, mouseX, mouseY, sideX);
        renderFilters(graphics, sideX, sideW, mouseX, mouseY);
        renderListingGrid(graphics, panelX + 12, contentTop + 23, sideX - panelX - 20, contentBottom - contentTop - 27, filteredListings(), mouseX, mouseY);
    }

    private int sortBarButtonsX() {
        return panelX + 12 + Math.round(font.width(Component.translatable("gui.freemarket.sort")) * 1.1F) + 10;
    }

    private void renderSortBar(GuiGraphics graphics, int mouseX, int mouseY, int sideX) {
        int x = panelX + 12;
        int y = contentTop - 6;
        drawScaled(graphics, Component.translatable("gui.freemarket.sort"), x, y + 7, 1.1F, MUTED);
        int bx = sortBarButtonsX();
        int bw = 78;
        for (Sort option : Sort.values()) {
            boolean selected = sort == option;
            graphics.fill(bx, y, bx + bw, y + 22, selected ? 0xD0226D4B : 0xA012211B);
            border(graphics, bx, y, bw, 22, selected ? GREEN : 0xFF315043);
            Component label = Component.translatable(option.key);
            drawCenteredScaled(graphics, label, bx + bw / 2, y + 6, 1.1F, selected ? TEXT : MUTED);
            bx += bw + 6;
            if (bx + bw >= sideX) {
                break;
            }
        }
    }

    private void renderFilters(GuiGraphics graphics, int sideX, int sideW, int mouseX, int mouseY) {
        int x = sideX + 10;
        int w = sideW - 20;
        FilterLayout layout = filterLayout();
        drawScaled(graphics, Component.translatable("gui.freemarket.filter"), x, contentTop, 1.2F, TEXT);
        graphics.fill(x, layout.queryY - 2, x + w, layout.queryY, CyberUiFx.alpha(0x35F29A, 0.52F));
        drawScaled(graphics, Component.translatable("gui.freemarket.name_or_seller"), x, layout.queryY - 12, 1.05F, MUTED);
        drawScaled(graphics, Component.translatable("gui.freemarket.tags"), x, layout.tagY - 12, 1.05F, MUTED);
        renderSearchTagChips(graphics, x, layout.chipsY, w, layout.compact ? 1 : 2);
        drawScaled(graphics, Component.translatable("gui.freemarket.price_range"), x, layout.minimumY - 12, 1.05F, MUTED);
        drawScaled(graphics, Component.translatable("gui.freemarket.to"), x, layout.maximumY - 12, 1.05F, MUTED);
        int cy = layout.activeY;
        graphics.fill(x, cy, x + 16, cy + 16, activeOnly ? GREEN_DIM : 0xFF17241E);
        border(graphics, x, cy, 16, 16, GREEN);
        if (activeOnly) {
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.icon.check"), x + 8, cy + 3, 1.2F, TEXT);
        }
        drawScaled(graphics, Component.translatable("gui.freemarket.active_only"), x + 22, cy + 4, 1.1F, TEXT);
        drawScaled(graphics, Component.translatable("gui.freemarket.results", state.totalCount()), x, layout.resultsY, 1.05F, MUTED);
        int pageY = contentBottom - 53;
        drawCenteredScaled(graphics, Component.translatable("gui.freemarket.page", state.page() + 1, state.totalPages()), sideX + sideW / 2, pageY + 5, 1.05F, TEXT);
        drawPageArrow(graphics, x, pageY, 32, state.page() > 0, true, inside(mouseX, mouseY, x, pageY, 32, 18));
        drawPageArrow(graphics, x + w - 32, pageY, 32, state.page() + 1 < state.totalPages(), false, inside(mouseX, mouseY, x + w - 32, pageY, 32, 18));
        if (state.admin() && !layout.compact) {
            graphics.drawString(font, Component.translatable("gui.freemarket.admin_right_click_hint"), x, layout.resultsY + 16, 0xFFD8A75B, false);
        }
    }

    private void renderSearchTagChips(GuiGraphics graphics, int x, int y, int w, int maxRows) {
        int cx = x;
        int cy = y;
        int rows = 1;
        for (String tag : searchTags) {
            Component label = Component.translatable("gui.freemarket.tag_chip_remove", state.tagLabel(tag));
            int chipW = Math.min(w, font.width(label) + 14);
            if (cx + chipW > x + w) {
                cx = x;
                cy += 19;
                rows++;
            }
            if (rows > maxRows) {
                break;
            }
            graphics.fill(cx, cy, cx + chipW, cy + 16, 0xDB216D4A);
            border(graphics, cx, cy, chipW, 16, GREEN);
            drawClipped(graphics, label, cx + 7, cy + 4, chipW - 14, TEXT);
            cx += chipW + 5;
        }
    }

    private String searchTagChipAt(double mouseX, double mouseY, int x, int y, int w, int maxRows) {
        int cx = x;
        int cy = y;
        int rows = 1;
        for (String tag : searchTags) {
            Component label = Component.translatable("gui.freemarket.tag_chip_remove", state.tagLabel(tag));
            int chipW = Math.min(w, font.width(label) + 14);
            if (cx + chipW > x + w) {
                cx = x;
                cy += 19;
                rows++;
            }
            if (rows > maxRows) {
                return null;
            }
            if (inside(mouseX, mouseY, cx, cy, chipW, 16)) {
                return tag;
            }
            cx += chipW + 5;
        }
        return null;
    }

    private List<String> tagSuggestions() {
        if (view != View.HOME || tagBox == null || !tagBox.isFocused()) {
            return List.of();
        }
        String text = tagQuery.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String tag : state.tags()) {
            if (searchTags.contains(tag)) {
                continue;
            }
            if (tag.toLowerCase(Locale.ROOT).contains(lower) || state.tagLabel(tag).toLowerCase(Locale.ROOT).contains(lower)) {
                result.add(tag);
                if (result.size() >= 6) {
                    break;
                }
            }
        }
        return result;
    }

    private void renderTagSuggestions(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> suggestions = tagSuggestions();
        if (suggestions.isEmpty()) {
            return;
        }
        int sideW = Math.max(168, panelW * 22 / 100);
        int sideX = panelX + panelW - sideW;
        int x = sideX + 10;
        int w = sideW - 20;
        FilterLayout layout = filterLayout();
        int y = layout.tagY + (layout.compact ? 18 : 22) + 2;
        int rowH = 18;
        int h = suggestions.size() * rowH + 2;
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xF8081711);
        border(graphics, x - 1, y - 1, w + 2, h + 2, GREEN_DIM);
        for (int i = 0; i < suggestions.size(); i++) {
            int rowY = y + 1 + i * rowH;
            boolean hover = inside(mouseX, mouseY, x, rowY, w, rowH);
            if (hover) {
                graphics.fill(x, rowY, x + w, rowY + rowH, 0xD0226D4B);
            }
            drawClipped(graphics, Component.translatable("gui.freemarket.tag_chip", state.tagLabel(suggestions.get(i))), x + 6, rowY + 5, w - 12, hover ? TEXT : MUTED);
        }
    }

    private boolean tagSuggestionClicked(double mouseX, double mouseY) {
        List<String> suggestions = tagSuggestions();
        if (suggestions.isEmpty()) {
            return false;
        }
        int sideW = Math.max(168, panelW * 22 / 100);
        int sideX = panelX + panelW - sideW;
        int x = sideX + 10;
        int w = sideW - 20;
        FilterLayout layout = filterLayout();
        int y = layout.tagY + (layout.compact ? 18 : 22) + 2;
        for (int i = 0; i < suggestions.size(); i++) {
            if (inside(mouseX, mouseY, x, y + 1 + i * 18, w, 18)) {
                searchTags.add(suggestions.get(i));
                tagQuery = "";
                if (tagBox != null) {
                    tagBox.setValue("");
                }
                resetScroll();
                scheduleQuery();
                return true;
            }
        }
        return false;
    }

    private String combinedTagQuery() {
        LinkedHashSet<String> all = new LinkedHashSet<>(searchTags);
        if (!tagQuery.isBlank()) {
            all.add(tagQuery.strip());
        }
        return String.join(",", all);
    }

    private List<MarketListing> filteredListings() {
        List<MarketListing> result = state.listings();
        visibleListings = result;
        return result;
    }

    private void renderListingGrid(GuiGraphics graphics, int x, int y, int w, int h, List<MarketListing> listings, int mouseX, int mouseY) {
        int gap = 8;
        int columns = Math.max(1, Math.min(6, (w + gap) / 132));
        int cardW = Math.max(104, (w - gap * (columns - 1)) / columns);
        int cardH = 152;
        int rows = (listings.size() + columns - 1) / columns;
        maxScroll = Math.max(0, rows * (cardH + gap) - gap - h);
        clampSmoothScroll();
        cardListings = listings;
        CyberUiFx.scissor(graphics, x, y, x + w, y + h);
        for (int i = 0; i < listings.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            int cx = x + column * (cardW + gap);
            int cy = y + row * (cardH + gap) - scroll;
            if (cy + cardH < y || cy > y + h) {
                continue;
            }
            renderListingCard(graphics, listings.get(i), cx, cy, cardW, cardH, mouseX, mouseY);
        }
        graphics.disableScissor();
        renderScrollBar(graphics, x + w - 3, y, h, rows * (cardH + gap) - gap, scroll);
        if (listings.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.freemarket.no_results"), x + w / 2, y + h / 2, MUTED);
        }
    }

    private void renderListingCard(GuiGraphics graphics, MarketListing listing, int x, int y, int w, int h, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        float hoverAmount = CyberUiFx.approach(cardHoverAmounts.getOrDefault(listing.id(), 0.0F), hover ? 1.0F : 0.0F, frameDelta, 12.0F);
        cardHoverAmounts.put(listing.id(), hoverAmount);
        int lift = Math.round(CyberUiFx.smoothstep(hoverAmount) * 3.0F);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, -lift, 0.0F);
        if (hoverAmount > 0.02F) {
            graphics.fill(x + 3, y + 5, x + w + 3, y + h + 5, CyberUiFx.alpha(0x000000, 0.28F + hoverAmount * 0.24F));
        }
        graphics.fillGradient(x, y, x + w, y + h, CyberUiFx.mix(CARD, 0xF0213B30, hoverAmount), CyberUiFx.mix(0xE80A140F, 0xF00C2017, hoverAmount));
        border(graphics, x, y, w, h, CyberUiFx.mix(0xFF28503D, GREEN, hoverAmount));
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, CyberUiFx.alpha(0xB7FFDA, 0.05F + hoverAmount * 0.19F));
        if (hoverAmount > 0.02F) {
            CyberUiFx.corners(graphics, x - 1, y - 1, w + 2, h + 2, 9, CyberUiFx.alpha(0x8BFFC6, hoverAmount * 0.78F));
            int sweep = x + Math.floorMod((int)(System.currentTimeMillis() / 8L + listing.id().hashCode()), Math.max(1, w + 28)) - 14;
            CyberUiFx.scissor(graphics, x + 1, y + 1 - lift, x + w - 1, y + h - 1 - lift);
            graphics.fill(sweep, y + 2, sweep + 7, y + h - 2, CyberUiFx.alpha(0x8BFFC6, hoverAmount * 0.065F));
            graphics.disableScissor();
        }
        graphics.fill(x, y, x + 30, y + 18, listing.auction() ? 0xE0A66B16 : 0xE0176848);
        Component typeBadge = Component.translatable(listing.auction() ? "gui.freemarket.icon.auction" : "gui.freemarket.icon.fixed").withStyle(style -> style.withFont(ICON_FONT));
        drawCenteredScaled(graphics, typeBadge, x + 15, y + 5, 1.15F, 0xFFFFFFFF);
        float heartPulse = listing.liked() ? 0.84F + 0.16F * (float)Math.sin(System.currentTimeMillis() * 0.007D) : 1.0F;
        int heartColor = listing.liked() ? CyberUiFx.alpha(RED, heartPulse) : MUTED;
        drawScaled(graphics, Component.translatable(listing.liked() ? "gui.freemarket.icon.heart_filled" : "gui.freemarket.icon.heart"), x + w - 21, y + 4, 1.7F, heartColor);
        graphics.fillGradient(x + 5, y + 18, x + w - 5, y + 68, 0xB20B1711, 0x6A163427);
        graphics.fill(x + 5, y + 67, x + w - 5, y + 68, CyberUiFx.alpha(0x35F29A, 0.2F + hoverAmount * 0.25F));
        float itemScale = 2.42F + hoverAmount * 0.12F;
        int itemX = x + w / 2 - Math.round(8.0F * itemScale);
        int itemY = y + 24 - Math.round(hoverAmount * (1.0F + 0.7F * (float)Math.sin(System.currentTimeMillis() * 0.008D)));
        renderLargeItem(graphics, listing.item(), itemX, itemY, itemScale);
        if (inside(mouseX, mouseY, x + w / 2 - 24, y + 20, 48, 46)) {
            hoveredStack = listing.item();
            hoveredItemX = mouseX;
            hoveredItemY = mouseY;
        }
        int textY = y + 73;
        drawScaledClipped(graphics, legacy(listing.name().isBlank() ? listing.item().getHoverName().getString() : listing.name()), x + 7, textY, w - 14, 1.15F, TEXT);
        graphics.drawString(font, Component.translatable(listing.auction() ? "gui.freemarket.current_price" : "gui.freemarket.price"), x + 7, textY + 16, MUTED, false);
        drawScaledClipped(graphics, money(listing.displayPrice()), x + 7, textY + 27, w - 14, 1.3F, GREEN);
        drawClipped(graphics, Component.translatable("gui.freemarket.seller_value", listing.sellerName()), x + 7, textY + 48, w - 14, MUTED);
        graphics.drawString(font, Component.translatable("gui.freemarket.likes_value", listing.likeCount()), x + 7, textY + 63, MUTED, false);
        if (listing.sold() || listing.paused()) {
            int bandY = y + 50;
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 320.0F);
            graphics.fill(x + 1, bandY, x + w - 1, bandY + 20, listing.sold() ? 0xEFCE263A : 0xE8A66B16);
            drawCenteredScaled(graphics, Component.translatable(listing.sold() ? "gui.freemarket.sold" : "gui.freemarket.hidden_band"), x + w / 2, bandY + 5, 1.25F, 0xFFFFFFFF);
            graphics.pose().popPose();
        }
        graphics.pose().popPose();
    }

    private void renderDetail(GuiGraphics graphics, int mouseX, int mouseY) {
        if (selected == null) {
            graphics.drawCenteredString(font, Component.translatable("gui.freemarket.listing_unavailable"), width / 2, panelY + panelH / 2, MUTED);
            return;
        }
        int leftX = panelX + 18;
        int leftW = Math.max(190, panelW * 25 / 100);
        int rightW = Math.max(240, panelW * 32 / 100);
        int rightX = panelX + panelW - rightW - 12;
        int middleX = leftX + leftW + 12;
        int middleW = rightX - middleX - 12;
        int top = contentTop + 4;
        graphics.fill(leftX + 4, top + 5, leftX + leftW + 4, contentBottom - 28, 0x52000000);
        graphics.fillGradient(leftX, top, leftX + leftW, contentBottom - 33, 0xEF193126, 0xEF07110C);
        border(graphics, leftX, top, leftW, contentBottom - 33 - top, 0xFF28503D);
        CyberUiFx.corners(graphics, leftX - 1, top - 1, leftW + 2, contentBottom - 31 - top, 11, 0x7735F29A);
        drawScaled(graphics, Component.translatable(selected.liked() ? "gui.freemarket.icon.heart_filled" : "gui.freemarket.icon.heart"), leftX + leftW - 30, top + 7, 1.8F, selected.liked() ? RED : MUTED);
        graphics.fillGradient(rightX, top, rightX + rightW, contentBottom - 33, 0xCF102219, 0xD006100B);
        border(graphics, rightX, top, rightW, contentBottom - 33 - top, 0xFF28503D);
        graphics.fill(leftX + 10, top + 17, leftX + leftW - 10, top + 96, 0x6A07110C);
        renderLargeItem(graphics, selected.item(), leftX + leftW / 2 - 38, top + 18, 4.75F);
        if (inside(mouseX, mouseY, leftX + leftW / 2 - 42, top + 14, 84, 86)) {
            hoveredStack = selected.item();
            hoveredItemX = mouseX;
            hoveredItemY = mouseY;
        }
        int itemBottom = top + 102;
        if (selected.sold()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 320.0F);
            graphics.fill(leftX + 1, itemBottom - 18, leftX + leftW - 1, itemBottom, 0xEFCE263A);
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.sold"), leftX + leftW / 2, itemBottom - 14, 1.15F, 0xFFFFFFFF);
            graphics.pose().popPose();
        }
        drawWrappedScaled(graphics, legacy(selected.name().isBlank() ? selected.item().getHoverName().getString() : selected.name()), leftX + 10, itemBottom + 5, leftW - 20, 2, 1.2F, TEXT);
        graphics.drawString(font, Component.translatable(selected.auction() ? "gui.freemarket.current_price" : "gui.freemarket.price"), leftX + 10, itemBottom + 34, MUTED, false);
        drawScaledClipped(graphics, money(selected.displayPrice()), leftX + 10, itemBottom + 46, leftW - 20, 1.45F, GREEN);
        drawScaled(graphics, Component.translatable(selected.auction() ? "gui.freemarket.auction_listing" : "gui.freemarket.fixed_listing"), leftX + 10, itemBottom + 68, 1.1F, selected.auction() ? 0xFFFFBD55 : GREEN);
        if (selected.auction()) {
            drawScaled(graphics, remainingTime(selected.endsAt()), leftX + 10, itemBottom + 84, 1.1F, TEXT);
        }
        int sellerRowY = itemBottom + (selected.auction() ? 98 : 82);
        boolean sellerHover = inside(mouseX, mouseY, leftX + 8, sellerRowY, leftW - 16, 26);
        graphics.fill(leftX + 8, sellerRowY, leftX + leftW - 8, sellerRowY + 26, sellerHover ? 0x8823493A : 0x4E132A20);
        border(graphics, leftX + 8, sellerRowY, leftW - 16, 26, sellerHover ? GREEN : 0x6628503D);
        renderRoundHead(graphics, leftX + 13, sellerRowY + 3, 20, playerSkin(selected.sellerId(), selected.sellerName()));
        drawScaledClipped(graphics, Component.literal(selected.sellerName()), leftX + 39, sellerRowY + 8, leftW - 74, 1.15F, TEXT);
        drawScaled(graphics, Component.translatable("gui.freemarket.icon.next"), leftX + leftW - 22, sellerRowY + 9, 1.0F, sellerHover ? GREEN : MUTED);
        graphics.drawString(font, Component.translatable("gui.freemarket.likes_value", selected.likeCount()), leftX + 10, itemBottom + (selected.auction() ? 131 : 115), MUTED, false);
        boolean mine = isMine(selected);
        if (!selected.sold()) {
            int statusY = itemBottom + (selected.auction() ? 146 : 130);
            if (selected.paused()) {
                drawScaled(graphics, Component.translatable("gui.freemarket.status_hidden"), leftX + 10, statusY, 1.1F, 0xFFFFC96B);
            } else if (mine && selected.active()) {
                drawScaled(graphics, Component.translatable("gui.freemarket.status_public"), leftX + 10, statusY, 1.1F, GREEN);
            }
        }
        if (!selected.sold() && selected.active() && !mine) {
            int ctaY = contentBottom - 60;
            graphics.fillGradient(rightX + 2, ctaY, rightX + rightW - 2, contentBottom - 2, 0xE0194A34, 0xE0081710);
            border(graphics, rightX + 2, ctaY, rightW - 4, 58, 0xAA35F29A);
            CyberUiFx.corners(graphics, rightX, ctaY - 2, rightW, 62, 9, 0x9935F29A);
            if (!selected.auction()) {
                MarketListing.MarketOffer pendingOffer = pendingOfferForCurrentUser();
                if (pendingOffer != null) {
                    renderOwnPendingOffer(graphics, rightX, rightW, pendingOffer);
                }
            }
        }
        renderDetailMiddle(graphics, middleX, top, middleW);
        renderDetailActivity(graphics, rightX, top, rightW, mouseX, mouseY);
    }

    private void renderDetailMiddle(GuiGraphics graphics, int x, int y, int w) {
        drawScaled(graphics, Component.translatable("gui.freemarket.item_lore"), x, y + 2, 1.2F, GREEN);
        int lineY = y + 20;
        List<Component> lore = detailLore();
        if (lore.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.freemarket.no_lore"), x, lineY, MUTED, false);
            lineY += 14;
        } else {
            for (Component line : lore.stream().limit(6).toList()) {
                drawClipped(graphics, line, x, lineY, w, TEXT);
                lineY += 12;
            }
        }
        graphics.fill(x, lineY + 3, x + w, lineY + 4, 0xFF244437);
        lineY += 13;
        drawScaled(graphics, Component.translatable("gui.freemarket.description"), x, lineY, 1.2F, GREEN);
        lineY += 17;
        lineY += drawWrappedScaled(graphics, Component.literal(selected.description()), x, lineY, w, 7, 1.05F, TEXT) * 12;
        lineY += 10;
        drawScaled(graphics, Component.translatable("gui.freemarket.tags"), x, lineY, 1.2F, GREEN);
        lineY += 17;
        renderTagChips(graphics, selected.tags(), x, lineY, w, false, 3);
        if (detailEditing) {
            drawScaled(graphics, Component.translatable("gui.freemarket.editing_notice"), x, contentBottom - 48, 1.1F, 0xFFFFC96B);
        }
    }

    private void renderDetailActivity(GuiGraphics graphics, int x, int y, int w, int mouseX, int mouseY) {
        int commentInputY = detailCommentInputY();
        int commentBottom = commentInputY - 6;
        graphics.fill(x + 5, y + 5, x + w - 5, commentBottom + 1, 0x6A07110C);
        border(graphics, x + 5, y + 5, w - 10, Math.max(1, commentBottom - y - 4), 0x6628503D);
        drawScaled(graphics, Component.translatable("gui.freemarket.comments", selected.comments().size()), x + 9, y + 8, 1.15F, GREEN);
        int commentY = y + 26;
        commentAuthorZones.clear();
        CyberUiFx.scissor(graphics, x + 2, commentY, x + w - 2, commentBottom);
        int shown = 0;
        for (int i = selected.comments().size() - 1; i >= 0 && shown < 8; i--, shown++) {
            if (commentY + 24 > commentBottom) {
                break;
            }
            MarketListing.MarketComment comment = selected.comments().get(i);
            int textX = x + 40;
            int textW = Math.max(20, x + w - 13 - textX);
            List<FormattedCharSequence> messageLines = font.split(Component.literal(comment.message()), textW);
            int lineCount = Mth.clamp(messageLines.size(), 1, 3);
            int rowH = 20 + lineCount * 11;
            graphics.fill(x + 8, commentY, x + w - 8, commentY + rowH, shown % 2 == 0 ? 0x6814271E : 0x500B1811);
            graphics.fill(x + 8, commentY, x + 10, commentY + rowH, 0x8835F29A);
            renderRoundHead(graphics, x + 14, commentY + 4, 20, playerSkin(comment.authorId(), comment.author()));
            Component time = relativeTime(comment.createdAt());
            int timeW = font.width(time);
            int nameW = Math.max(20, textW - timeW - 8);
            boolean authorHover = commentY + 2 >= y + 24 && inside(mouseX, mouseY, x + 12, commentY + 2, 24 + Math.min(nameW, 120), 24);
            drawScaledClipped(graphics, Component.literal(comment.author()), textX, commentY + 5, nameW, 1.05F, authorHover ? GREEN : TEXT);
            commentAuthorZones.add(new AuthorZone(x + 12, commentY + 2, 24 + Math.min(nameW, 120), 24, comment.authorId(), comment.author()));
            graphics.drawString(font, time, x + w - 12 - timeW, commentY + 6, MUTED, false);
            for (int line = 0; line < lineCount; line++) {
                graphics.drawString(font, messageLines.get(line), textX, commentY + 18 + line * 11, MUTED, false);
            }
            commentY += rowH + 7;
        }
        graphics.disableScissor();
        if (selected.sold()) {
            drawScaled(graphics, Component.translatable("gui.freemarket.comments_closed"), x + 9, commentInputY + 10, 1.05F, MUTED);
        }
        if (!selected.auction()) {
            if (isMine(selected)) {
                renderOfferRequests(graphics, x, w);
            }
            return;
        }
        int activityY = commentInputY + 44;
        graphics.fill(x + 8, activityY - 5, x + w - 8, activityY - 4, 0xFF244437);
        drawScaled(graphics, Component.translatable("gui.freemarket.bid_history", selected.bids().size()), x + 9, activityY + 3, 1.15F, GREEN);
        int bidY = activityY + 21;
        int bidBottom = contentBottom - 62;
        CyberUiFx.scissor(graphics, x + 2, bidY, x + w - 2, bidBottom);
        if (selected.bids().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.freemarket.no_bids"), x + 9, bidY, MUTED, false);
        } else {
            for (int i = selected.bids().size() - 1; i >= 0; i--) {
                MarketListing.MarketBid bid = selected.bids().get(i);
                drawClipped(graphics, Component.translatable("gui.freemarket.bid_entry", bid.bidder(), money(bid.amount()), relativeTime(bid.createdAt())), x + 9, bidY, w - 18, TEXT);
                bidY += 16;
            }
        }
        graphics.disableScissor();
    }

    private void renderOwnPendingOffer(GuiGraphics graphics, int x, int w, MarketListing.MarketOffer offer) {
        int y = contentBottom - 27;
        graphics.fillGradient(x, y, x + w, y + 20, 0xE01A2C23, 0xE00A1711);
        border(graphics, x, y, w, 20, GREEN_DIM);
        drawClipped(graphics, Component.translatable("gui.freemarket.offer_pending", money(offer.amount()), remainingTime(offer.expiresAt())), x + 8, y + 6, w - 16, TEXT);
    }

    private void renderOfferRequests(GuiGraphics graphics, int x, int w) {
        List<MarketListing.MarketOffer> offers = pendingOffers();
        int cardCount = Math.min(detailOfferCardLimit(), offers.size());
        if (cardCount <= 0) {
            return;
        }
        int sectionY = detailOfferSectionY();
        graphics.fill(x + 8, sectionY - 5, x + w - 8, sectionY - 4, 0xFF244437);
        drawScaled(graphics, Component.translatable("gui.freemarket.offer_requests", offers.size()), x + 9, sectionY + 3, 1.15F, GREEN);
        int buttonW = detailOfferButtonWidth(w);
        int rejectX = x + w - 9 - buttonW;
        int acceptX = rejectX - OFFER_CARD_GAP - buttonW;
        for (int i = 0; i < cardCount; i++) {
            MarketListing.MarketOffer offer = offers.get(i);
            int cardX = x + 8;
            int cardY = detailOfferCardsY() + i * (OFFER_CARD_HEIGHT + OFFER_CARD_GAP);
            int cardW = w - 16;
            graphics.fillGradient(cardX, cardY, cardX + cardW, cardY + OFFER_CARD_HEIGHT, 0xE41A3026, 0xE40A1711);
            border(graphics, cardX, cardY, cardW, OFFER_CARD_HEIGHT, 0xAA35F29A);
            graphics.fill(cardX, cardY, cardX + 3, cardY + OFFER_CARD_HEIGHT, GREEN_DIM);
            Component remaining = remainingTime(offer.expiresAt());
            int remainingW = Math.min(font.width(remaining), Math.max(48, cardW / 2));
            int remainingX = cardX + cardW - 8 - remainingW;
            String requester = offer.requesterName().isBlank() ? offer.requesterId() : offer.requesterName();
            drawClipped(graphics, Component.literal(requester), cardX + 9, cardY + 6, Math.max(20, remainingX - cardX - 14), TEXT);
            drawClipped(graphics, remaining, remainingX, cardY + 6, remainingW, MUTED);
            graphics.drawString(font, Component.translatable("gui.freemarket.offer_amount"), cardX + 9, cardY + 20, MUTED, false);
            drawClipped(graphics, money(offer.amount()), cardX + 9, cardY + 36, Math.max(20, acceptX - cardX - 14), GREEN);
        }
    }

    private List<MarketListing.MarketOffer> pendingOffers() {
        if (selected == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return selected.offers().stream()
                .filter(offer -> "PENDING".equalsIgnoreCase(offer.status()) && offer.expiresAt() > now && !offer.id().isBlank())
                .sorted(Comparator.comparingLong(MarketListing.MarketOffer::createdAt).reversed())
                .toList();
    }

    private MarketListing.MarketOffer pendingOfferForCurrentUser() {
        CompoundTag user = state.user();
        String id = user.getString("Id");
        String name = user.getString("Name");
        if (minecraft != null && name.isBlank()) {
            name = minecraft.getUser().getName();
        }
        String userName = name;
        return pendingOffers().stream()
                .filter(offer -> !id.isBlank() && id.equalsIgnoreCase(offer.requesterId()) || !userName.isBlank() && userName.equalsIgnoreCase(offer.requesterName()))
                .findFirst()
                .orElse(null);
    }

    private int detailOfferSectionY() {
        return detailCommentInputY() + 44;
    }

    private int detailOfferCardsY() {
        return detailOfferSectionY() + 18;
    }

    private int detailOfferCardLimit() {
        int available = contentBottom - 39 - detailOfferCardsY();
        return Mth.clamp((available + OFFER_CARD_GAP) / (OFFER_CARD_HEIGHT + OFFER_CARD_GAP), 0, 4);
    }

    private int detailOfferButtonWidth(int w) {
        return Mth.clamp((w - 36) / 4, 46, 58);
    }

    private void trackOfferExpiry(MarketListing.MarketOffer offer) {
        if (offer.expiresAt() > System.currentTimeMillis() && (offerUiRefreshAt == 0L || offer.expiresAt() < offerUiRefreshAt)) {
            offerUiRefreshAt = offer.expiresAt();
        }
    }

    private int detailCommentInputY() {
        int contentHeight = contentBottom - contentTop;
        return contentHeight < 360 ? contentTop + Math.max(96, contentHeight * 42 / 100) : contentTop + 169;
    }

    private void renderCreate(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = panelX + 24;
        int labelW = 122;
        int fieldX = left + labelW;
        int fieldW = Math.max(150, panelW / 2 - labelW - 40);
        int itemY = contentTop + 5;
        ItemStack draft = draftStack();
        boolean itemValid = !draft.isEmpty();
        boolean nameValid = !stripLegacy(createName).isBlank();
        boolean priceValid = parseLong(createPrice, 0L) > 0L;
        long duration = parseLong(createDuration, 0L);
        boolean durationValid = !createAuction || duration >= 5L && duration <= 43200L;
        boolean ready = itemValid && nameValid && priceValid && durationValid;
        graphics.fillGradient(left, itemY, fieldX + fieldW, itemY + 48, 0xE0182D24, 0xE0091510);
        border(graphics, left, itemY, labelW + fieldW, 48, itemValid ? GREEN : RED);
        CyberUiFx.corners(graphics, left - 2, itemY - 2, labelW + fieldW + 4, 52, 8, CyberUiFx.alpha(itemValid ? GREEN : RED, 0.68F));
        graphics.drawString(font, Component.translatable("gui.freemarket.item_selection"), left + 10, itemY + 16, MUTED, false);
        Component itemRequired = Component.translatable("gui.freemarket.required");
        graphics.drawString(font, itemRequired, fieldX - font.width(itemRequired) - 7, itemY + 29, itemValid ? GREEN : RED, false);
        if (draft.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.freemarket.click_to_select"), fieldX + 6, itemY + 16, GREEN, false);
        } else {
            renderLargeItem(graphics, draft, fieldX + 7, itemY + 8, 2.0F);
            drawClipped(graphics, draft.getHoverName(), fieldX + 45, itemY + 17, fieldW - 51, TEXT);
            if (inside(mouseX, mouseY, fieldX + 3, itemY + 4, fieldW - 6, 40)) {
                hoveredStack = draft;
                hoveredItemX = mouseX;
                hoveredItemY = mouseY;
            }
        }
        int y = contentTop + 63;
        String[] labels = createAuction
                ? new String[]{"gui.freemarket.item_name", "gui.freemarket.description", "gui.freemarket.starting_price", "gui.freemarket.duration_minutes", "gui.freemarket.tags"}
                : new String[]{"gui.freemarket.item_name", "gui.freemarket.description", "gui.freemarket.listing_price", "gui.freemarket.sale_type", "gui.freemarket.tags"};
        boolean[] validRows = {nameValid, true, priceValid, durationValid, true};
        int[] rowYs = {0, 31, 86, 117, 148};
        int[] rowHs = {20, 44, 20, 20, 20};
        int formBottom = y + (createAuction ? 224 : 176);
        graphics.fill(left - 7, y - 8, fieldX + fieldW + 7, formBottom, 0x7D07110C);
        graphics.fill(left - 7, y - 8, left - 5, formBottom, GREEN_DIM);
        border(graphics, left - 7, y - 8, labelW + fieldW + 14, formBottom - y + 8, 0x9928503D);
        for (int i = 0; i < labels.length; i++) {
            int rowY = y + rowYs[i];
            int labelColor = draftDirty && !validRows[i] ? RED : i == 0 || i == 2 || createAuction && i == 3 ? TEXT : MUTED;
            drawScaled(graphics, Component.translatable(labels[i]), left, rowY + 6, 1.1F, labelColor);
            if (i == 0 || i == 2 || createAuction && i == 3) {
                Component required = Component.translatable("gui.freemarket.required");
                graphics.drawString(font, required, fieldX - font.width(required) - 7, rowY + 6, validRows[i] ? GREEN_DIM : RED, false);
            }
            if (draftDirty && !validRows[i]) {
                CyberUiFx.corners(graphics, fieldX - 2, rowY - 2, fieldW + 4, rowHs[i] + 4, 6, CyberUiFx.alpha(RED, 0.9F));
            }
        }
        Component nameCount = Component.translatable("gui.freemarket.character_count", stripLegacy(createName).length(), 96);
        drawClipped(graphics, nameCount, left, y + 18, labelW - 8, MUTED);
        Component descriptionCount = Component.translatable("gui.freemarket.character_count", createDescription.length(), 512);
        drawClipped(graphics, descriptionCount, left, y + 49, labelW - 8, MUTED);
        if (fieldW >= 280 && priceValid) {
            long feeAmount = feePreview();
            long netAmount = Math.max(0L, parseLong(createPrice, 0L) - feeAmount);
            int infoX = fieldX + fieldW - 122;
            drawClipped(graphics, Component.translatable("gui.freemarket.fee_amount_short", NumberFormat.getIntegerInstance().format(feeAmount)), infoX, y + 86, 122, 0xFFFFC96B);
            drawClipped(graphics, Component.translatable("gui.freemarket.net_amount_short", NumberFormat.getIntegerInstance().format(netAmount)), infoX, y + 97, 122, GREEN);
        }
        if (!createAuction) {
            int readOnlyTypeY = y + 117;
            graphics.fillGradient(fieldX, readOnlyTypeY, fieldX + fieldW, readOnlyTypeY + 20, 0xD01B3529, 0xD00C1A13);
            border(graphics, fieldX, readOnlyTypeY, fieldW, 20, GREEN_DIM);
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.fixed_listing"), fieldX + fieldW / 2, readOnlyTypeY + 6, 1.05F, TEXT);
        } else {
            long durationMinutes = parseLong(createDuration, 0L);
            boolean durationOk = durationMinutes >= 5L && durationMinutes <= 43200L;
            Component durationInfo = durationOk
                    ? Component.translatable("gui.freemarket.duration_current", durationText(durationMinutes))
                    : Component.translatable("gui.freemarket.duration_invalid_hint");
            drawScaled(graphics, durationInfo, left, y + 201, 1.05F, durationOk ? GREEN : RED);
        }
        int typeButtonX = panelX + panelW / 2 + 22;
        int rightW = panelX + panelW - 24 - typeButtonX;
        graphics.drawString(font, Component.translatable("gui.freemarket.immutable_lore"), typeButtonX, itemY + 2, GREEN, false);
        int loreY = itemY + 18;
        graphics.fillGradient(typeButtonX, loreY, typeButtonX + rightW, loreY + 96, 0xC012271D, 0xC006100B);
        border(graphics, typeButtonX, loreY, rightW, 96, 0xFF28503D);
        CyberUiFx.corners(graphics, typeButtonX - 1, loreY - 1, rightW + 2, 98, 9, 0x9935F29A);
        List<Component> lore = draftLore();
        if (lore.isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.freemarket.no_lore"), typeButtonX + 9, loreY + 10, MUTED, false);
        } else {
            int ly = loreY + 9;
            for (Component line : lore.stream().limit(6).toList()) {
                drawClipped(graphics, line, typeButtonX + 9, ly, rightW - 18, TEXT);
                ly += 12;
            }
        }
        drawScaled(graphics, Component.translatable("gui.freemarket.sale_type"), typeButtonX, loreY + 107, 1.1F, MUTED);
        int typeY = loreY + 122;
        int half = (rightW - 6) / 2;
        drawToggle(graphics, typeButtonX, typeY, half, 24, !createAuction, Component.translatable("gui.freemarket.fixed"));
        drawToggle(graphics, typeButtonX + half + 6, typeY, half, 24, createAuction, Component.translatable("gui.freemarket.auction"));
        drawScaled(graphics, Component.translatable("gui.freemarket.available_tags"), typeButtonX, typeY + 37, 1.1F, MUTED);
        int tagsY = typeY + 53;
        int tagBottom = contentBottom - 38;
        createTagContentHeight = tagFlowHeight(state.tags(), rightW - 10);
        createTagScroll = Mth.clamp(createTagScroll, 0, Math.max(0, createTagContentHeight - Math.max(1, tagBottom - tagsY)));
        CyberUiFx.scissor(graphics, typeButtonX, tagsY, typeButtonX + rightW, tagBottom);
        renderTagFlow(graphics, state.tags(), typeButtonX, tagsY - createTagScroll, rightW - 10, tagsY, tagBottom);
        graphics.disableScissor();
        renderScrollBar(graphics, typeButtonX + rightW - 3, tagsY, Math.max(1, tagBottom - tagsY), createTagContentHeight, createTagScroll);
        long previewFee = feePreview();
        long previewNet = Math.max(0L, parseLong(createPrice, 0L) - previewFee);
        drawScaled(graphics, Component.translatable("gui.freemarket.fee_preview_full", NumberFormat.getIntegerInstance().format(previewFee), NumberFormat.getIntegerInstance().format(previewNet)), left, contentBottom - 48, 1.1F, MUTED);
        Component readiness = Component.translatable(ready ? "gui.freemarket.form_ready" : "gui.freemarket.form_incomplete");
        graphics.fill(panelX + panelW - 228, contentBottom - 34, panelX + panelW - 8, contentBottom - 2, CyberUiFx.alpha(ready ? 0x103A28 : 0x361116, 0.52F));
        border(graphics, panelX + panelW - 228, contentBottom - 34, 220, 32, CyberUiFx.alpha(ready ? GREEN : RED, 0.62F));
        drawScaled(graphics, readiness, panelX + panelW - 218 - Math.round(font.width(readiness) * 1.1F) - 10, contentBottom - 23, 1.1F, ready ? GREEN : RED);
    }

    private void renderInventory(GuiGraphics graphics, int mouseX, int mouseY) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        drawScaled(graphics, Component.translatable("gui.freemarket.inventory_help"), panelX + 24, contentTop + 6, 1.1F, MUTED);
        Inventory inventory = minecraft.player.getInventory();
        int slot = 26;
        int gridW = slot * 9;
        int startX = width / 2 - gridW / 2;
        int startY = contentTop + 36;
        graphics.fill(startX - 8, startY - 8, startX + gridW + 8, startY + slot * 4 + 8, 0xCB0C1813);
        border(graphics, startX - 8, startY - 8, gridW + 16, slot * 4 + 16, GREEN_DIM);
        for (int index = 0; index < 36; index++) {
            int displayRow = index < 9 ? 3 : (index / 9 - 1);
            int column = index % 9;
            int sx = startX + column * slot;
            int sy = startY + displayRow * slot;
            boolean hover = inside(mouseX, mouseY, sx, sy, 24, 24);
            graphics.fill(sx, sy, sx + 24, sy + 24, hover ? 0xE02B4B3C : 0xD015251E);
            border(graphics, sx, sy, 24, 24, hover ? GREEN : 0xFF355344);
            ItemStack stack = inventory.getItem(index);
            if (!stack.isEmpty()) {
                graphics.pose().pushPose();
                graphics.pose().translate(sx + 2, sy + 2, 0.0F);
                graphics.pose().scale(1.25F, 1.25F, 1.0F);
                graphics.renderItem(stack, 0, 0);
                graphics.renderItemDecorations(font, stack, 0, 0);
                graphics.pose().popPose();
                if (hover) {
                    hoveredStack = stack;
                    hoveredItemX = mouseX;
                    hoveredItemY = mouseY;
                }
            }
        }
        drawCenteredScaled(graphics, Component.translatable("gui.freemarket.inventory_select_one"), width / 2, startY + slot * 4 + 22, 1.15F, TEXT);
    }

    private void renderProfile(GuiGraphics graphics, int mouseX, int mouseY) {
        CompoundTag user = state.user();
        int cardX = panelX + 28;
        int cardY = contentTop + 15;
        int cardW = panelW - 56;
        graphics.fillGradient(cardX, cardY, cardX + cardW, cardY + 84, 0xEE1A3328, 0xEE0A1710);
        border(graphics, cardX, cardY, cardW, 84, GREEN_DIM);
        CyberUiFx.corners(graphics, cardX - 2, cardY - 2, cardW + 4, 88, 11, 0x7735F29A);
        renderRoundHead(graphics, cardX + 18, cardY + 14, 52);
        String username = user.getString("Name");
        if (username.isBlank() && minecraft != null) {
            username = minecraft.getUser().getName();
        }
        drawScaled(graphics, Component.literal(username), cardX + 84, cardY + 16, 1.5F, TEXT);
        int rating = Mth.clamp((int)Math.round(user.getDouble("Rating")), 0, 5);
        MutableComponent stars = Component.empty();
        for (int i = 0; i < 5; i++) {
            stars.append(Component.translatable(i < rating ? "gui.freemarket.icon.star_filled" : "gui.freemarket.icon.star"));
        }
        drawScaled(graphics, stars, cardX + 84, cardY + 42, 1.4F, 0xFFFFCD62);
        int reviewCount = user.contains("ReviewCount") ? user.getInt("ReviewCount") : user.getInt("RatingCount");
        drawScaled(graphics, Component.translatable("gui.freemarket.review_count", reviewCount), cardX + 84 + Math.round(font.width(stars) * 1.4F) + 10, cardY + 45, 1.1F, MUTED);
        boolean headerHover = inside(mouseX, mouseY, cardX + 10, cardY + 8, 300, 62);
        if (headerHover) {
            border(graphics, cardX + 10, cardY + 8, 300, 62, GREEN_DIM);
        }
        drawScaled(graphics, Component.translatable("gui.freemarket.view_own_listings"), cardX + 84, cardY + 64, 0.95F, headerHover ? GREEN : MUTED);
        if (state.admin()) {
            boolean adminHover = inside(mouseX, mouseY, cardX + cardW - 92, cardY + 8, 84, 32);
            graphics.fill(cardX + cardW - 92, cardY + 8, cardX + cardW - 8, cardY + 40, adminHover ? 0xA85C4219 : 0x672D2515);
            border(graphics, cardX + cardW - 92, cardY + 8, 84, 32, 0xAAFFC460);
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.admin_badge"), cardX + cardW - 50, cardY + 18, 1.2F, 0xFFFFC460);
        }
        int gridX = cardX;
        int gridY = cardY + 103;
        int gap = 10;
        int leftW = Math.max(260, cardW * 57 / 100);
        int tileW = (leftW - gap) / 2;
        int tileH = 58;
        View[] targets = {View.VIEW_HISTORY, View.LIKES, View.PURCHASE_HISTORY, View.LISTING_HISTORY, View.BLOCKED_USERS};
        String[] icons = {"gui.freemarket.icon.history", "gui.freemarket.icon.heart", "gui.freemarket.icon.purchase", "gui.freemarket.icon.listings", "gui.freemarket.icon.blocked"};
        String[] labels = {"gui.freemarket.view_history", "gui.freemarket.likes", "gui.freemarket.purchase_history", "gui.freemarket.listing_history", "gui.freemarket.blocked_users"};
        for (int i = 0; i < targets.length; i++) {
            int tx = gridX + (i % 2) * (tileW + gap);
            int ty = gridY + (i / 2) * (tileH + gap);
            boolean hover = inside(mouseX, mouseY, tx, ty, tileW, tileH);
            graphics.fillGradient(tx, ty, tx + tileW, ty + tileH, hover ? 0xE126493A : CARD, hover ? 0xE10D2118 : 0xE8091510);
            border(graphics, tx, ty, tileW, tileH, hover ? GREEN : 0xFF28503D);
            if (hover) {
                CyberUiFx.corners(graphics, tx - 1, ty - 1, tileW + 2, tileH + 2, 7, 0xAA8BFFC6);
            }
            Component icon = Component.translatable(icons[i]);
            drawScaled(graphics, icon, tx + 16, ty + (tileH - 15) / 2, 1.6F, GREEN);
            drawScaled(graphics, Component.translatable(labels[i]), tx + 16 + Math.round(font.width(icon) * 1.6F) + 12, ty + (tileH - 12) / 2, 1.3F, TEXT);
        }
        int savedX = gridX + leftW + gap;
        int savedW = cardW - leftW - gap;
        int savedH = Math.max(90, contentBottom - gridY - 7);
        graphics.fillGradient(savedX, gridY, savedX + savedW, gridY + savedH, 0xD0102118, 0xD006100B);
        border(graphics, savedX, gridY, savedW, savedH, 0xFF28503D);
        graphics.fill(savedX + 1, gridY + 1, savedX + savedW - 1, gridY + 22, 0xB31A392A);
        drawScaled(graphics, Component.translatable("gui.freemarket.saved_searches"), savedX + 10, gridY + 6, 1.15F, GREEN);
        List<CompoundTag> savedSearches = state.savedSearches();
        int listTop = gridY + 28;
        int listBottom = gridY + savedH - 6;
        int rowH = 42;
        int contentHeight = savedSearches.size() * (rowH + 5);
        maxScroll = Math.max(0, contentHeight - Math.max(1, listBottom - listTop));
        clampSmoothScroll();
        CyberUiFx.scissor(graphics, savedX + 1, listTop, savedX + savedW - 1, listBottom);
        for (int i = 0; i < savedSearches.size(); i++) {
            CompoundTag search = savedSearches.get(i);
            int rowY = listTop + i * (rowH + 5) - scroll;
            if (rowY + rowH < listTop || rowY > listBottom) {
                continue;
            }
            boolean hover = inside(mouseX, mouseY, savedX + 6, rowY, savedW - 12, rowH);
            graphics.fill(savedX + 6, rowY, savedX + savedW - 6, rowY + rowH, hover ? 0xE0204032 : 0xC00B1912);
            border(graphics, savedX + 6, rowY, savedW - 12, rowH, hover ? GREEN : 0xFF29483A);
            drawScaledClipped(graphics, Component.literal(search.getString("Name")), savedX + 13, rowY + 6, savedW - 72, 1.1F, TEXT);
            drawClipped(graphics, savedSearchSummary(search), savedX + 13, rowY + 23, savedW - 72, MUTED);
            int deleteX = savedX + savedW - 50;
            graphics.fill(deleteX, rowY + 5, deleteX + 38, rowY + rowH - 5, inside(mouseX, mouseY, deleteX, rowY + 5, 38, rowH - 10) ? 0xC8532029 : 0x8A27131A);
            border(graphics, deleteX, rowY + 5, 38, rowH - 10, RED);
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.delete"), deleteX + 19, rowY + 16, 1.05F, 0xFFFFDDE1);
            if (search.getBoolean("NotificationsEnabled")) {
                circle(graphics, savedX + savedW - 58, rowY + 10, 3, GREEN);
            }
        }
        graphics.disableScissor();
        renderScrollBar(graphics, savedX + savedW - 3, listTop, Math.max(1, listBottom - listTop), contentHeight, scroll);
        if (savedSearches.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.freemarket.no_saved_searches"), savedX + savedW / 2, listTop + 22, MUTED);
        }
        if (state.admin()) {
            int adminX = gridX + tileW + gap;
            int adminY = gridY + 2 * (tileH + gap);
            boolean hover = inside(mouseX, mouseY, adminX, adminY, tileW, tileH);
            graphics.fillGradient(adminX, adminY, adminX + tileW, adminY + tileH, hover ? 0xE15A4217 : 0xD02A2315, 0xD013110B);
            border(graphics, adminX, adminY, tileW, tileH, 0xFFFFC460);
            CyberUiFx.corners(graphics, adminX - 1, adminY - 1, tileW + 2, tileH + 2, 8, 0xAAFFC460);
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.open_admin"), adminX + tileW / 2, adminY + (tileH - 12) / 2, 1.3F, 0xFFFFD787);
        }
    }

    private Component savedSearchSummary(CompoundTag search) {
        CompoundTag savedQuery = search.getCompound("Query");
        String text = savedQuery.getString("Text");
        if (!text.isBlank()) {
            return Component.literal(text);
        }
        ListTag tags = savedQuery.getList("Tags", 8);
        if (!tags.isEmpty()) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                values.add(state.tagLabel(tags.getString(i)));
            }
            return Component.literal(String.join(", ", values));
        }
        return Component.translatable("gui.freemarket.saved_search_filters");
    }

    private void applySavedSearchFilters(CompoundTag search) {
        queryDue = 0L;
        requestedPage = 0;
        requestedLikedOnly = false;
        CompoundTag savedQuery = search.getCompound("Query");
        query = savedQuery.getString("Text");
        ListTag tags = savedQuery.getList("Tags", 8);
        searchTags.clear();
        for (int i = 0; i < tags.size(); i++) {
            searchTags.add(tags.getString(i));
        }
        tagQuery = "";
        minimumPrice = savedQuery.contains("MinimumPrice", 99) ? Long.toString(Math.max(0L, Math.round(savedQuery.getDouble("MinimumPrice")))) : "";
        maximumPrice = savedQuery.contains("MaximumPrice", 99) ? Long.toString(Math.max(0L, Math.round(savedQuery.getDouble("MaximumPrice")))) : "";
        activeOnly = savedQuery.getBoolean("AvailableOnly");
        String savedSort = savedQuery.getString("SortOrder");
        sort = savedSort.startsWith("NAME") ? Sort.NAME : savedSort.startsWith("LIKES") ? Sort.LIKES : Sort.UPDATED;
    }

    private void renderNotifications(GuiGraphics graphics, int mouseX, int mouseY) {
        List<CompoundTag> notifications = state.notifications();
        int x = panelX + 25;
        int y = contentTop + 6;
        int w = panelW - 50;
        int h = contentBottom - y - 4;
        int rowH = 58;
        maxScroll = Math.max(0, notifications.size() * (rowH + 6) - 6 - h);
        clampSmoothScroll();
        CyberUiFx.scissor(graphics, x, y, x + w, y + h);
        for (int i = 0; i < notifications.size(); i++) {
            CompoundTag notification = notifications.get(i);
            int ry = y + i * (rowH + 6) - scroll;
            if (ry + rowH < y || ry > y + h) {
                continue;
            }
            boolean read = notification.getBoolean("Read");
            graphics.fill(x, ry, x + w, ry + rowH, read ? 0xA5101B17 : 0xE0163025);
            border(graphics, x, ry, w, rowH, read ? 0xFF315043 : GREEN);
            if (!read) {
                circle(graphics, x + 12, ry + 13, 4, GREEN);
            }
            Component title = notificationText(notification, "Title", "TitleKey");
            Component message = notificationText(notification, "Message", "MessageKey");
            drawScaledClipped(graphics, title, x + 23, ry + 7, w - 82, 1.15F, TEXT);
            drawClipped(graphics, message, x + 23, ry + 25, w - 82, MUTED);
            graphics.drawString(font, relativeTime(notification.getLong("CreatedAt")), x + 23, ry + 42, MUTED, false);
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.delete_icon"), x + w - 24, ry + 20, 1.5F, RED);
        }
        graphics.disableScissor();
        renderScrollBar(graphics, x + w - 3, y, h, notifications.size() * (rowH + 6) - 6, scroll);
        if (notifications.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("gui.freemarket.no_notifications"), x + w / 2, y + h / 2, MUTED);
        }
    }

    private void renderAdmin(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(panelX + 12, contentTop - 2, panelX + panelW - 12, contentBottom, 0x68251C0B);
        border(graphics, panelX + 12, contentTop - 2, panelW - 24, contentBottom - contentTop + 2, 0x99FFC460);
        CyberUiFx.corners(graphics, panelX + 10, contentTop - 4, panelW - 20, contentBottom - contentTop + 6, 14, 0xAAFFC460);
        int splitX = panelX + panelW / 2 - 8;
        int leftX = panelX + 32;
        int leftW = Math.max(220, splitX - leftX - 16);
        int half = (leftW - 8) / 2;
        drawScaled(graphics, Component.translatable("gui.freemarket.tag_management"), leftX, contentTop + 8, 1.25F, GREEN);
        drawClipped(graphics, Component.translatable("gui.freemarket.tag_management_help"), leftX, contentTop + 27, leftW, MUTED);
        graphics.drawString(font, Component.translatable("gui.freemarket.tag_name"), leftX, contentTop + 44, MUTED, false);
        graphics.drawString(font, Component.translatable("gui.freemarket.tag_label"), leftX + half + 8, contentTop + 44, MUTED, false);
        Component tagStatus = adminSelectedTag.isBlank()
                ? Component.translatable("gui.freemarket.tag_new_hint")
                : Component.translatable("gui.freemarket.tag_editing", state.tagLabel(adminSelectedTag));
        drawClipped(graphics, tagStatus, leftX, contentTop + 110, leftW, adminSelectedTag.isBlank() ? MUTED : 0xFFFFC96B);
        int feeY = adminFeeSectionY();
        graphics.fill(leftX - 8, feeY, splitX - 8, feeY + 118, 0xAF0C1913);
        border(graphics, leftX - 8, feeY, splitX - leftX, 118, 0xFF28503D);
        drawScaled(graphics, Component.translatable("gui.freemarket.fee_management"), leftX, feeY + 8, 1.2F, GREEN);
        drawClipped(graphics, Component.translatable("gui.freemarket.fee_help"), leftX, feeY + 25, leftW, MUTED);
        CompoundTag fee = state.fee();
        double currentValue = fee.getDouble("Value");
        Component currentSetting = "PERCENT".equalsIgnoreCase(fee.getString("Mode"))
                ? Component.translatable("gui.freemarket.fee_current_percent", formatFeeValue(currentValue))
                : Component.translatable("gui.freemarket.fee_current_fixed", NumberFormat.getIntegerInstance().format(Math.max(0L, Math.round(currentValue))));
        drawClipped(graphics, currentSetting, leftX, feeY + 38, leftW, GREEN);
        int halfToggle = (leftW - 6) / 2;
        drawToggle(graphics, leftX, feeY + 52, halfToggle, 22, adminPercentFee, Component.translatable("gui.freemarket.percent"));
        drawToggle(graphics, leftX + halfToggle + 6, feeY + 52, halfToggle, 22, !adminPercentFee, Component.translatable("gui.freemarket.fixed"));
        double previewValue = 0.0D;
        try {
            previewValue = adminFeeBox == null ? 0.0D : Double.parseDouble(adminFeeBox.getValue());
        } catch (NumberFormatException ignored) {
        }
        long exampleFee = adminPercentFee ? Math.max(0L, Math.round(10000.0D * previewValue / 100.0D)) : Math.max(0L, Math.round(previewValue));
        drawClipped(graphics, Component.translatable("gui.freemarket.fee_example", NumberFormat.getIntegerInstance().format(exampleFee)), leftX, feeY + 105, leftW, MUTED);
        int listX = splitX + 16;
        int listY = contentTop + 12;
        int listW = panelX + panelW - 28 - listX;
        int listH = contentBottom - listY - 7;
        drawScaled(graphics, Component.translatable("gui.freemarket.registered_tags", state.tags().size()), listX, listY, 1.15F, GREEN);
        int rowsY = listY + 20;
        int rowsH = listH - 20;
        int rowH = 24;
        maxScroll = Math.max(0, state.tags().size() * rowH - rowsH);
        clampSmoothScroll();
        CyberUiFx.scissor(graphics, listX, rowsY, listX + listW, rowsY + rowsH);
        for (int i = 0; i < state.tags().size(); i++) {
            String tag = state.tags().get(i);
            int rowY = rowsY + i * rowH - scroll;
            if (rowY + 21 < rowsY || rowY > rowsY + rowsH) {
                continue;
            }
            boolean selectedTag = adminSelectedTag.equals(tag);
            boolean hover = inside(mouseX, mouseY, listX, rowY, listW, 21);
            graphics.fill(listX, rowY, listX + listW, rowY + 21, selectedTag ? 0xD8216D4A : hover ? 0xCC1B3329 : 0xB5102019);
            border(graphics, listX, rowY, listW, 21, selectedTag ? GREEN : 0xFF315043);
            drawScaledClipped(graphics, Component.translatable("gui.freemarket.tag_chip", state.tagLabel(tag)), listX + 8, rowY + 6, listW - 16, 1.05F, selectedTag ? TEXT : MUTED);
        }
        graphics.disableScissor();
        renderScrollBar(graphics, listX + listW - 2, rowsY, rowsH, state.tags().size() * rowH, scroll);
        int reportX = leftX - 8;
        int reportY = feeY + 126;
        int reportW = splitX - leftX;
        int reportH = contentBottom - reportY - 7;
        if (reportH >= 24) {
            graphics.fillGradient(reportX, reportY, reportX + reportW, reportY + reportH, 0xC0271711, 0xC00D0907);
            border(graphics, reportX, reportY, reportW, reportH, 0x99E06A49);
            graphics.fill(reportX + 1, reportY + 1, reportX + reportW - 1, reportY + 22, 0xA848221A);
            drawScaled(graphics, Component.translatable("gui.freemarket.pending_reports", state.pendingReportCount()), reportX + 8, reportY + 6, 1.1F, 0xFFFFA47E);
            List<CompoundTag> reports = state.reports();
            int reportRowH = 62;
            int availableRows = Math.max(0, (reportH - 28) / reportRowH);
            for (int i = 0; i < Math.min(availableRows, reports.size()); i++) {
                CompoundTag report = reports.get(i);
                int rowY = reportY + 27 + i * reportRowH;
                graphics.fill(reportX + 6, rowY, reportX + reportW - 6, rowY + reportRowH - 5, 0xB31A100D);
                border(graphics, reportX + 6, rowY, reportW - 12, reportRowH - 5, 0x886B3A2D);
                drawScaledClipped(graphics, Component.literal(report.getString("ListingName")), reportX + 12, rowY + 5, reportW - 24, 1.05F, TEXT);
                drawClipped(graphics, Component.translatable("gui.freemarket.report_meta", report.getString("Reporter"), report.getString("Reason")), reportX + 12, rowY + 19, reportW - 24, MUTED);
                int actionY = rowY + 33;
                int actionW = Math.max(44, (reportW - 30) / 2);
                drawAdminReportAction(graphics, reportX + 10, actionY, actionW, Component.translatable("gui.freemarket.resolve_report"), false, inside(mouseX, mouseY, reportX + 10, actionY, actionW, 18));
                drawAdminReportAction(graphics, reportX + reportW - 10 - actionW, actionY, actionW, Component.translatable("gui.freemarket.dismiss_report"), true, inside(mouseX, mouseY, reportX + reportW - 10 - actionW, actionY, actionW, 18));
            }
        }
    }

    private void drawAdminReportAction(GuiGraphics graphics, int x, int y, int w, Component label, boolean danger, boolean hover) {
        int accent = danger ? RED : GREEN;
        graphics.fill(x, y, x + w, y + 18, hover ? CyberUiFx.alpha(accent, 0.42F) : 0xA0101814);
        border(graphics, x, y, w, 18, CyberUiFx.alpha(accent, 0.78F));
        drawCenteredScaled(graphics, label, x + w / 2, y + 5, 1.05F, danger ? 0xFFFFC6CE : TEXT);
    }

    private void renderHistory(GuiGraphics graphics, int mouseX, int mouseY) {
        List<MarketListing> listings;
        if (view == View.LIKES) {
            listings = state.listings().stream().filter(MarketListing::liked).toList();
        } else if (view == View.VIEW_HISTORY) {
            listings = state.userListings("ViewHistory");
        } else if (view == View.PURCHASE_HISTORY) {
            listings = state.userListings("PurchaseHistory");
        } else {
            listings = state.userListings("ListingHistory");
            if (listings.isEmpty()) {
                listings = state.listings().stream().filter(this::isMine).toList();
            }
            listings = switch (listingHistoryFilter) {
                case ACTIVE -> listings.stream().filter(MarketListing::active).toList();
                case SOLD -> listings.stream().filter(MarketListing::sold).toList();
                default -> listings;
            };
            renderListingHistoryFilters(graphics, mouseX, mouseY);
        }
        int y = contentTop + (view == View.LISTING_HISTORY ? 29 : 6);
        int bottomReserve = view == View.LIKES ? 27 : 4;
        renderListingGrid(graphics, panelX + 18, y, panelW - 36, contentBottom - y - bottomReserve, listings, mouseX, mouseY);
        if (view == View.LIKES) {
            renderWidePager(graphics, mouseX, mouseY);
        }
    }

    private void renderListingHistoryFilters(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX + 20;
        for (ListingHistoryFilter filter : ListingHistoryFilter.values()) {
            int w = 82;
            boolean active = listingHistoryFilter == filter;
            graphics.fill(x, contentTop + 2, x + w, contentTop + 24, active ? 0xD0226D4B : 0xA012211B);
            border(graphics, x, contentTop + 2, w, 22, active ? GREEN : 0xFF315043);
            drawCenteredScaled(graphics, Component.translatable(filter.key), x + w / 2, contentTop + 8, 1.1F, active ? TEXT : MUTED);
            x += w + 8;
        }
    }

    private void renderWidePager(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = contentBottom - 22;
        drawPageArrow(graphics, width / 2 - 96, y, 32, state.page() > 0, true, inside(mouseX, mouseY, width / 2 - 96, y, 32, 18));
        graphics.drawCenteredString(font, Component.translatable("gui.freemarket.page", state.page() + 1, state.totalPages()), width / 2, y + 5, TEXT);
        drawPageArrow(graphics, width / 2 + 64, y, 32, state.page() + 1 < state.totalPages(), false, inside(mouseX, mouseY, width / 2 + 64, y, 32, 18));
    }

    private Component sellerStarsComponent(int rating) {
        MutableComponent stars = Component.empty();
        for (int i = 0; i < 5; i++) {
            stars.append(Component.translatable(i < rating ? "gui.freemarket.icon.star_filled" : "gui.freemarket.icon.star"));
        }
        return stars;
    }

    private void renderSeller(GuiGraphics graphics, int mouseX, int mouseY) {
        CompoundTag seller = state.sellerProfile();
        int cardX = panelX + 28;
        int cardY = contentTop + 8;
        int cardW = panelW - 56;
        int cardH = 74;
        graphics.fillGradient(cardX, cardY, cardX + cardW, cardY + cardH, 0xEE1A3328, 0xEE0A1710);
        border(graphics, cardX, cardY, cardW, cardH, GREEN_DIM);
        CyberUiFx.corners(graphics, cardX - 2, cardY - 2, cardW + 4, cardH + 4, 11, 0x7735F29A);
        renderRoundHead(graphics, cardX + 16, cardY + 11, 52, playerSkin(seller.getString("Id"), seller.getString("Name")));
        drawScaled(graphics, Component.literal(seller.getString("Name")), cardX + 82, cardY + 12, 1.5F, TEXT);
        int rating = Mth.clamp((int)Math.round(seller.getDouble("Rating")), 0, 5);
        Component stars = sellerStarsComponent(rating);
        int starsW = Math.round(font.width(stars) * 1.4F);
        boolean starsHover = inside(mouseX, mouseY, cardX + 78, cardY + 34, starsW + 148, 22);
        drawScaled(graphics, stars, cardX + 82, cardY + 38, 1.4F, 0xFFFFCD62);
        drawScaled(graphics, Component.translatable("gui.freemarket.review_count", seller.getInt("ReviewCount")), cardX + 82 + starsW + 8, cardY + 41, 1.1F, starsHover ? TEXT : MUTED);
        if (starsHover) {
            border(graphics, cardX + 78, cardY + 34, starsW + 148, 22, GREEN_DIM);
        }
        drawScaled(graphics, Component.translatable("gui.freemarket.view_reviews_hint"), cardX + 82, cardY + 60, 0.95F, MUTED);
        drawScaled(graphics, Component.translatable("gui.freemarket.seller_listings"), cardX, cardY + cardH + 10, 1.15F, GREEN);
        int gridY = cardY + cardH + 28;
        renderListingGrid(graphics, panelX + 18, gridY, panelW - 36, contentBottom - gridY - 4, state.sellerListings(), mouseX, mouseY);
    }

    private boolean sellerClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        CompoundTag seller = state.sellerProfile();
        int cardX = panelX + 28;
        int cardY = contentTop + 8;
        int rating = Mth.clamp((int)Math.round(seller.getDouble("Rating")), 0, 5);
        int starsW = Math.round(font.width(sellerStarsComponent(rating)) * 1.4F);
        if (inside(mouseX, mouseY, cardX + 78, cardY + 34, starsW + 148, 22)) {
            navigate(View.SELLER_REVIEWS);
            return true;
        }
        int gridY = cardY + 74 + 28;
        MarketListing hit = listingAt(mouseX, mouseY, panelX + 18, gridY, panelW - 36, contentBottom - gridY - 4, cardListings);
        if (hit != null) {
            int[] rect = listingRect(hit, panelX + 18, gridY, panelW - 36, cardListings);
            if (rect != null && inside(mouseX, mouseY, rect[0] + rect[2] - 24, rect[1], 24, 24)) {
                toggleLike(hit);
            } else {
                openDetail(hit);
            }
            return true;
        }
        return false;
    }

    private void renderSellerReviews(GuiGraphics graphics, int mouseX, int mouseY) {
        CompoundTag seller = state.sellerProfile();
        List<CompoundTag> reviews = state.sellerReviews();
        int x = panelX + 25;
        int y = contentTop + 6;
        int w = panelW - 50;
        int h = contentBottom - y - 4;
        drawScaled(graphics, Component.translatable("gui.freemarket.reviews_header", seller.getString("Name"), reviews.size()), x, y, 1.2F, GREEN);
        int listTop = y + 24;
        int textX = x + 40;
        int textW = Math.max(20, x + w - 13 - textX);
        int[] heights = new int[reviews.size()];
        List<List<FormattedCharSequence>> allLines = new ArrayList<>();
        int totalH = 0;
        for (int i = 0; i < reviews.size(); i++) {
            String comment = reviews.get(i).getString("Comment");
            List<FormattedCharSequence> lines = comment.isBlank() ? List.of() : font.split(Component.literal(comment), textW);
            allLines.add(lines);
            int lineCount = Math.min(4, lines.size());
            heights[i] = lineCount == 0 ? 28 : 22 + lineCount * 11 + 4;
            totalH += heights[i] + 7;
        }
        maxScroll = Math.max(0, totalH - Math.max(1, h - 24));
        clampSmoothScroll();
        commentAuthorZones.clear();
        reviewAuthorZones.clear();
        CyberUiFx.scissor(graphics, x, listTop, x + w, y + h);
        int rowY = listTop - scroll;
        for (int i = 0; i < reviews.size(); i++) {
            int rowH = heights[i];
            if (rowY + rowH >= listTop && rowY <= y + h) {
                CompoundTag review = reviews.get(i);
                graphics.fill(x + 2, rowY, x + w - 2, rowY + rowH, i % 2 == 0 ? 0x6814271E : 0x500B1811);
                graphics.fill(x + 2, rowY, x + 4, rowY + rowH, 0x8835F29A);
                renderRoundHead(graphics, x + 12, rowY + 4, 20, playerSkin(review.getString("ReviewerId"), review.getString("Reviewer")));
                Component time = relativeTime(review.getLong("CreatedAt"));
                int timeW = font.width(time);
                Component starRow = sellerStarsComponent(Mth.clamp(review.getInt("Stars"), 0, 5));
                int starRowW = Math.round(font.width(starRow) * 1.2F);
                int nameW = Math.max(20, textW - timeW - starRowW - 24);
                boolean authorHover = rowY + 2 >= listTop && inside(mouseX, mouseY, x + 10, rowY + 2, 26 + Math.min(nameW, 120), 24);
                drawScaledClipped(graphics, Component.literal(review.getString("Reviewer")), textX, rowY + 5, nameW, 1.05F, authorHover ? GREEN : TEXT);
                reviewAuthorZones.add(new AuthorZone(x + 10, rowY + 2, 26 + Math.min(nameW, 120), 24, review.getString("ReviewerId"), review.getString("Reviewer")));
                graphics.drawString(font, time, x + w - 12 - timeW, rowY + 6, MUTED, false);
                drawScaled(graphics, starRow, x + w - 12 - timeW - starRowW - 8, rowY + 4, 1.2F, 0xFFFFCD62);
                List<FormattedCharSequence> lines = allLines.get(i);
                int lineCount = Math.min(4, lines.size());
                for (int line = 0; line < lineCount; line++) {
                    graphics.drawString(font, lines.get(line), textX, rowY + 20 + line * 11, TEXT, false);
                }
            }
            rowY += rowH + 7;
        }
        graphics.disableScissor();
        renderScrollBar(graphics, x + w - 3, listTop, Math.max(1, h - 24), totalH, scroll);
        if (reviews.isEmpty()) {
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.no_reviews"), x + w / 2, y + h / 2, 1.1F, MUTED);
        }
    }

    private boolean sellerReviewsClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        for (AuthorZone zone : reviewAuthorZones) {
            if (inside(mouseX, mouseY, zone.x(), zone.y(), zone.w(), zone.h())) {
                if (!zone.id().isBlank()) {
                    requestSellerProfile(zone.id(), zone.name());
                }
                return true;
            }
        }
        return false;
    }

    private void renderBlockedUsers(GuiGraphics graphics, int mouseX, int mouseY) {
        List<CompoundTag> blocked = state.blockedUserEntries();
        int x = panelX + 25;
        int y = contentTop + 6;
        int w = panelW - 50;
        int h = contentBottom - y - 4;
        drawScaled(graphics, Component.translatable("gui.freemarket.unblock_hint"), x, y, 1.05F, MUTED);
        int listTop = y + 20;
        int rowH = 34;
        maxScroll = Math.max(0, blocked.size() * (rowH + 6) - 6 - Math.max(1, h - 20));
        clampSmoothScroll();
        CyberUiFx.scissor(graphics, x, listTop, x + w, y + h);
        Component unblockLabel = Component.translatable("gui.freemarket.unblock_seller");
        int unblockW = font.width(unblockLabel);
        for (int i = 0; i < blocked.size(); i++) {
            int rowY = listTop + i * (rowH + 6) - scroll;
            if (rowY + rowH < listTop || rowY > y + h) {
                continue;
            }
            CompoundTag entry = blocked.get(i);
            boolean hover = inside(mouseX, mouseY, x, rowY, w, rowH);
            graphics.fill(x, rowY, x + w, rowY + rowH, hover ? 0xE0204032 : 0xC00B1912);
            border(graphics, x, rowY, w, rowH, hover ? GREEN : 0xFF29483A);
            renderRoundHead(graphics, x + 8, rowY + 7, 20, playerSkin(entry.getString("Id"), entry.getString("Name")));
            drawScaledClipped(graphics, Component.literal(entry.getString("Name")), x + 36, rowY + 12, Math.max(20, w - unblockW - 60), 1.15F, TEXT);
            graphics.drawString(font, unblockLabel, x + w - unblockW - 14, rowY + 13, hover ? GREEN : MUTED, false);
        }
        graphics.disableScissor();
        renderScrollBar(graphics, x + w - 3, listTop, Math.max(1, h - 20), blocked.size() * (rowH + 6) - 6, scroll);
        if (blocked.isEmpty()) {
            drawCenteredScaled(graphics, Component.translatable("gui.freemarket.no_blocked_users"), x + w / 2, y + h / 2, 1.1F, MUTED);
        }
    }

    private boolean blockedUsersClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        List<CompoundTag> blocked = state.blockedUserEntries();
        int x = panelX + 25;
        int y = contentTop + 6;
        int w = panelW - 50;
        int h = contentBottom - y - 4;
        int listTop = y + 20;
        int rowH = 34;
        for (int i = 0; i < blocked.size(); i++) {
            int rowY = listTop + i * (rowH + 6) - scroll;
            if (rowY + rowH < listTop || rowY > y + h) {
                continue;
            }
            if (inside(mouseX, mouseY, x, rowY, w, rowH)) {
                String id = blocked.get(i).getString("Id");
                if (!id.isBlank()) {
                    pendingUnblockUserId = id;
                    setModal(Modal.UNBLOCK_USER);
                }
                return true;
            }
        }
        return false;
    }

    private void renderBottomBar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return;
        }
        int y = panelY + panelH - 33;
        graphics.fillGradient(panelX + 1, y, panelX + panelW - 1, panelY + panelH - 1, 0xF00E2118, 0xF0040B07);
        graphics.fill(panelX + 1, y, panelX + panelW - 1, y + 1, GREEN_DIM);
        int center = width / 2;
        int tabW = Math.min(150, panelW / 5);
        int start = center - tabW * 3 / 2;
        View[] targets = {View.HOME, View.LIKES, View.CREATE};
        String[] icons = {"gui.freemarket.icon.home", "gui.freemarket.icon.heart", "gui.freemarket.icon.create"};
        String[] labels = {"gui.freemarket.home", "gui.freemarket.likes", "gui.freemarket.create"};
        for (int i = 0; i < 3; i++) {
            int x = start + i * tabW;
            boolean active = view == targets[i] || i == 1 && view == View.LIKES || i == 2 && (view == View.CREATE || view == View.INVENTORY);
            boolean hover = inside(mouseX, mouseY, x, y + 1, tabW, 31);
            bottomTabAmounts[i] = CyberUiFx.approach(bottomTabAmounts[i], active ? 1.0F : hover ? 0.62F : 0.0F, frameDelta, 14.0F);
            float amount = bottomTabAmounts[i];
            if (amount > 0.01F) {
                graphics.fillGradient(x, y + 1, x + tabW, y + 31, CyberUiFx.alpha(0x2A8A60, amount * 0.62F), CyberUiFx.alpha(0x10291E, amount * 0.46F));
            }
            if (active) {
                int indicatorW = Math.max(16, (int)((tabW - 18) * CyberUiFx.smoothstep(amount)));
                graphics.fill(x + (tabW - indicatorW) / 2, y + 1, x + (tabW + indicatorW) / 2, y + 3, GREEN);
            }
            int lift = Math.round(amount * 1.5F);
            int color = active ? TEXT : MUTED;
            Component icon = Component.translatable(icons[i]);
            Component label = Component.translatable(labels[i]);
            int iconW = Math.round(font.width(icon) * 1.5F);
            int labelW = Math.round(font.width(label) * 1.2F);
            int startX = x + tabW / 2 - (iconW + 6 + labelW) / 2;
            drawScaled(graphics, icon, startX, y + 9 - lift, 1.5F, color);
            drawScaled(graphics, label, startX + iconW + 6, y + 11 - lift, 1.2F, color);
        }
    }

    private void renderToast(GuiGraphics graphics, long now) {
        int w = Math.min(panelW - 40, Math.max(200, Math.round(font.width(toast) * 1.1F) + 40));
        int x = width / 2 - w / 2;
        float fadeIn = CyberUiFx.smoothstep((now - toastStartedAt) / 210.0F);
        float fadeOut = CyberUiFx.smoothstep((toastUntil - now) / 320.0F);
        float alpha = Math.min(fadeIn, fadeOut);
        int y = panelY + 48 - (int)((1.0F - fadeIn) * 12.0F);
        graphics.fill(x + 3, y + 4, x + w + 3, y + 33, CyberUiFx.alpha(0x000000, alpha * 0.42F));
        graphics.fillGradient(x, y, x + w, y + 30, CyberUiFx.alpha(0x193A2D, alpha * 0.97F), CyberUiFx.alpha(0x09150F, alpha * 0.97F));
        border(graphics, x, y, w, 30, CyberUiFx.alpha(0x35F29A, alpha));
        CyberUiFx.corners(graphics, x - 2, y - 2, w + 4, 34, 7, CyberUiFx.alpha(0x8BFFC6, alpha * 0.82F));
        graphics.fill(x + 5, y + 5, x + 7, y + 25, CyberUiFx.alpha(0x35F29A, alpha));
        drawCenteredScaled(graphics, toast, width / 2 + 1, y + 11, 1.1F, CyberUiFx.alpha(0x001109, alpha * 0.8F));
        drawCenteredScaled(graphics, toast, width / 2, y + 10, 1.1F, CyberUiFx.alpha(0xE8FFF4, alpha));
    }

    private void renderModal(GuiGraphics graphics, int mouseX, int mouseY, long now, float partialTick) {
        float raw = clamp((now - modalOpenedAt) / 220.0F);
        float fade = CyberUiFx.smoothstep(raw);
        float scale = Math.max(0.04F, CyberUiFx.easeOutBack(raw));
        graphics.fill(0, 0, width, height, CyberUiFx.alpha(0x000000, fade * 0.74F));
        int w = Math.min(410, panelW - 50);
        int h = modalHeight();
        int x = width / 2 - w / 2;
        int y = height / 2 - h / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(width / 2.0F, height / 2.0F, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.pose().translate(-width / 2.0F, -height / 2.0F, 0.0F);
        graphics.fill(x + 5, y + 7, x + w + 5, y + h + 7, CyberUiFx.alpha(0x000000, 0.58F * fade));
        graphics.fillGradient(x, y, x + w, y + h, CyberUiFx.alpha(modal.danger ? 0x241116 : 0x0E2118, 0.99F), 0xFC050C08);
        border(graphics, x, y, w, h, modal.danger ? RED : GREEN);
        CyberUiFx.corners(graphics, x - 3, y - 3, w + 6, h + 6, 13, CyberUiFx.alpha(modal.danger ? 0xFF8792 : 0x8BFFC6, 0.92F));
        graphics.fill(x + 1, y + 1, x + w - 1, y + 3, CyberUiFx.alpha(modal.danger ? 0xD74656 : 0x35F29A, 0.42F));
        drawCenteredScaled(graphics, Component.translatable(modal.titleKey), width / 2, y + 15, 1.25F, modal.danger ? RED : GREEN);
        drawWrappedCenteredScaled(graphics, modalMessageComponent(), width / 2, y + 41, w - 40, modal == Modal.REPORT_LISTING || modal == Modal.REVIEW ? 2 : 3, 1.05F, TEXT);
        if (modal == Modal.REPORT_LISTING && reportDetailBox != null) {
            reportDetailBox.setX(x + 20);
            reportDetailBox.setY(y + 72);
            reportDetailBox.render(graphics, mouseX, mouseY, partialTick);
            if (now < reportValidationUntil) {
                float warning = 0.55F + 0.45F * (float)Math.sin(now * 0.035D);
                CyberUiFx.corners(graphics, reportDetailBox.getX() - 2, reportDetailBox.getY() - 2, reportDetailBox.getWidth() + 4, reportDetailBox.getHeight() + 4, 7, CyberUiFx.alpha(RED, warning));
            }
        }
        if (modal == Modal.REVIEW) {
            int hoverStars = reviewStarsHover(mouseX, mouseY);
            int displayStars = hoverStars > 0 ? hoverStars : reviewStars;
            for (int i = 0; i < 5; i++) {
                boolean filled = i < displayStars;
                drawScaled(graphics, Component.translatable(filled ? "gui.freemarket.icon.star_filled" : "gui.freemarket.icon.star"), width / 2 - 58 + i * 25, y + 74, 2.0F, filled ? 0xFFFFCD62 : MUTED);
            }
            if (now < reviewValidationUntil) {
                float warning = 0.55F + 0.45F * (float)Math.sin(now * 0.035D);
                CyberUiFx.corners(graphics, width / 2 - 64, y + 68, 130, 30, 7, CyberUiFx.alpha(RED, warning));
            }
            if (reviewCommentBox != null) {
                reviewCommentBox.setX(x + 20);
                reviewCommentBox.setY(y + 112);
                reviewCommentBox.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        int buttonY = y + h - 34;
        int buttonW = 104;
        int noX = width / 2 - buttonW - 5;
        int yesX = width / 2 + 5;
        boolean confirmEnabled = modal == Modal.REPORT_LISTING ? !reportDetail.trim().isBlank() : modal != Modal.REVIEW || reviewStars >= 1;
        Component noLabel = Component.translatable(modal == Modal.REVIEW ? "gui.freemarket.close" : "gui.freemarket.no");
        Component yesLabel = Component.translatable(modal == Modal.REVIEW ? "gui.freemarket.review_submit" : "gui.freemarket.yes");
        drawModalButton(graphics, noX, buttonY, buttonW, 22, noLabel, false, true, inside(mouseX, mouseY, noX, buttonY, buttonW, 22));
        drawModalButton(graphics, yesX, buttonY, buttonW, 22, yesLabel, modal.danger, confirmEnabled, confirmEnabled && inside(mouseX, mouseY, yesX, buttonY, buttonW, 22));
        CyberUiFx.scanlines(graphics, x + 1, y + 1, w - 2, h - 2, now, 0.72F);
        CyberUiFx.glitch(graphics, x + 2, y + 2, w - 4, h - 4, now + 1100L, 0.78F);
        graphics.pose().popPose();
    }

    private Component modalMessageComponent() {
        if (modal == Modal.REVIEW) {
            return Component.translatable(modal.messageKey, reviewSellerName);
        }
        if (modal == Modal.CONFIRM_BUY) {
            MarketListing target = pendingListing != null ? pendingListing : selected;
            if (target != null) {
                Component itemName = legacy(target.name().isBlank() ? target.item().getHoverName().getString() : target.name());
                return Component.translatable(modal.messageKey, itemName, money(target.displayPrice()));
            }
        }
        if (modal == Modal.CONFIRM_BID) {
            return Component.translatable(modal.messageKey, money(pendingBidAmount));
        }
        return Component.translatable(modal.messageKey);
    }

    private void drawModalButton(GuiGraphics graphics, int x, int y, int w, int h, Component label, boolean danger, boolean enabled, boolean hover) {
        int accent = enabled ? danger ? RED : GREEN : 0xFF3C4B44;
        graphics.fillGradient(x, y, x + w, y + h, enabled ? hover ? 0xEF315144 : 0xE014211C : 0xD0101512, enabled ? hover ? 0xEF12271D : 0xE008120E : 0xD0080C0A);
        border(graphics, x, y, w, h, accent);
        if (hover) {
            CyberUiFx.corners(graphics, x - 1, y - 1, w + 2, h + 2, 6, CyberUiFx.alpha(accent, 0.8F));
            graphics.fill(x + 2, y + 2, x + 4, y + h - 2, accent);
        }
        drawCenteredScaled(graphics, label, x + w / 2, y + (h - 10) / 2, 1.1F, enabled ? TEXT : MUTED);
    }

    private void renderItemTooltip(GuiGraphics graphics, ItemStack stack, int x, int y) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : getTooltipFromItem(minecraft, stack)) {
            lines.add(line.getVisualOrderText());
        }
        graphics.renderTooltip(font, lines, (screenWidth, screenHeight, mx, my, tooltipWidth, tooltipHeight) -> {
            int tx = mx + 12;
            int ty = my - 12;
            if (tx + tooltipWidth > width - 6) {
                tx = Math.max(6, mx - 16 - tooltipWidth);
            }
            ty = Mth.clamp(ty, 6, Math.max(6, height - tooltipHeight - 6));
            return new Vector2i(tx, ty);
        }, x, y);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX / uiScale, mouseY / uiScale);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button, dragX / uiScale, dragY / uiScale);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX /= uiScale;
        mouseY /= uiScale;
        if (closing) {
            return true;
        }
        if (animateOpening && System.currentTimeMillis() - openedAt < LOAD_DELAY + LOAD_DURATION + LOAD_FADE) {
            return true;
        }
        if (modal != Modal.NONE) {
            return modalClicked(mouseX, mouseY, button);
        }
        if (transitionStarted != 0L && System.currentTimeMillis() - transitionStarted < TRANSITION_DURATION) {
            return true;
        }
        if (view == View.HOME && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && tagSuggestionClicked(mouseX, mouseY)) {
            manualFeedback(mouseX, mouseY, button);
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            return true;
        }
        if (bottomClicked(mouseX, mouseY, button)) {
            manualFeedback(mouseX, mouseY, button);
            return true;
        }
        boolean handled;
        if (view == View.HOME) {
            handled = homeClicked(mouseX, mouseY, button);
        } else if (view == View.DETAIL) {
            handled = detailClicked(mouseX, mouseY, button);
        } else if (view == View.CREATE) {
            handled = createClicked(mouseX, mouseY, button);
        } else if (view == View.INVENTORY) {
            handled = inventoryClicked(mouseX, mouseY, button);
        } else if (view == View.PROFILE) {
            handled = profileClicked(mouseX, mouseY, button);
        } else if (view == View.NOTIFICATIONS) {
            handled = notificationClicked(mouseX, mouseY, button);
        } else if (view == View.ADMIN) {
            handled = adminClicked(mouseX, mouseY, button);
        } else if (view == View.SELLER) {
            handled = sellerClicked(mouseX, mouseY, button);
        } else if (view == View.SELLER_REVIEWS) {
            handled = sellerReviewsClicked(mouseX, mouseY, button);
        } else if (view == View.BLOCKED_USERS) {
            handled = blockedUsersClicked(mouseX, mouseY, button);
        } else {
            handled = historyClicked(mouseX, mouseY, button);
        }
        if (handled) {
            manualFeedback(mouseX, mouseY, button);
        }
        return handled;
    }

    private boolean modalClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }
        if (System.currentTimeMillis() - modalOpenedAt < 180L) {
            return true;
        }
        int w = Math.min(410, panelW - 50);
        int h = modalHeight();
        int x = width / 2 - w / 2;
        int y = height / 2 - h / 2;
        int buttonY = y + h - 34;
        int buttonW = 104;
        if (modal == Modal.REPORT_LISTING && reportDetailBox != null && inside(mouseX, mouseY, reportDetailBox.getX(), reportDetailBox.getY(), reportDetailBox.getWidth(), reportDetailBox.getHeight())) {
            reportDetailBox.mouseClicked(mouseX, mouseY, button);
            setFocused(reportDetailBox);
            return true;
        }
        if (modal == Modal.REVIEW) {
            int hoverStars = reviewStarsHover(mouseX, mouseY);
            if (hoverStars > 0) {
                reviewStars = hoverStars;
                manualFeedback(mouseX, mouseY, button);
                return true;
            }
            if (reviewCommentBox != null && inside(mouseX, mouseY, reviewCommentBox.getX(), reviewCommentBox.getY(), reviewCommentBox.getWidth(), reviewCommentBox.getHeight())) {
                reviewCommentBox.mouseClicked(mouseX, mouseY, button);
                setFocused(reviewCommentBox);
                return true;
            }
        }
        if (inside(mouseX, mouseY, width / 2 - buttonW - 5, buttonY, buttonW, 22)) {
            manualFeedback(mouseX, mouseY, button);
            if (modal == Modal.REVIEW) {
                setModal(Modal.REVIEW_DISCARD);
                return true;
            }
            if (modal == Modal.REVIEW_DISCARD) {
                setModal(Modal.REVIEW);
                return true;
            }
            setModal(Modal.NONE);
            pendingListing = null;
            pendingView = null;
            pendingClose = false;
            return true;
        }
        if (inside(mouseX, mouseY, width / 2 + 5, buttonY, buttonW, 22)) {
            if (modal == Modal.REPORT_LISTING && reportDetail.trim().isBlank()) {
                reportValidationUntil = System.currentTimeMillis() + 900L;
                CyberUiFx.play("minecraft:block.note_block.didgeridoo", 0.58F);
                return true;
            }
            if (modal == Modal.REVIEW) {
                if (reviewStars < 1) {
                    reviewValidationUntil = System.currentTimeMillis() + 900L;
                    showLocalMessage("gui.freemarket.review_stars_required");
                    return true;
                }
                manualFeedback(mouseX, mouseY, button);
                submitReview();
                return true;
            }
            manualFeedback(mouseX, mouseY, button);
            confirmModal();
            return true;
        }
        return true;
    }

    private void confirmModal() {
        Modal confirmed = modal;
        String submittedReportDetail = reportDetail;
        setModal(Modal.NONE);
        if (confirmed == Modal.REVIEW_DISCARD) {
            clearReviewState();
            return;
        }
        if (confirmed == Modal.UNBLOCK_USER) {
            CompoundTag data = new CompoundTag();
            data.putString("TargetId", pendingUnblockUserId);
            pendingUnblockUserId = "";
            send("unblock_user", data);
            return;
        }
        if (confirmed == Modal.LEAVE_DRAFT) {
            send("cancel_draft", new CompoundTag());
            state.clearDraft();
            draftDirty = false;
            if (pendingClose) {
                closeNow();
            } else if (pendingView != null) {
                View target = pendingView;
                pendingView = null;
                navigateRoot(target);
            } else {
                backNow();
            }
            pendingClose = false;
            return;
        }
        if (confirmed == Modal.DELETE_SAVED_SEARCH) {
            CompoundTag data = new CompoundTag();
            data.putString("Id", pendingSavedSearchId);
            pendingSavedSearchId = "";
            send("delete_saved_search", data);
            return;
        }
        if (confirmed == Modal.RESOLVE_REPORT || confirmed == Modal.DISMISS_REPORT) {
            CompoundTag data = new CompoundTag();
            data.putString("ReportId", pendingReportId);
            data.putString("Status", confirmed == Modal.RESOLVE_REPORT ? "RESOLVED" : "DISMISSED");
            data.putString("Resolution", "");
            pendingReportId = "";
            send("admin_report_review", data);
            return;
        }
        MarketListing listing = pendingListing == null ? selected : pendingListing;
        pendingListing = null;
        if (listing == null) {
            return;
        }
        CompoundTag data = listingId(listing);
        if (confirmed == Modal.CONFIRM_BUY) {
            send("buy", data);
        } else if (confirmed == Modal.CONFIRM_BID) {
            data.putLong("Amount", pendingBidAmount);
            send("bid", data);
            if (bidBox != null) {
                bidBox.setValue("");
            }
        } else if (confirmed == Modal.ADMIN_DELETE) {
            send("admin_delete_listing", data);
        } else if (confirmed == Modal.CANCEL_LISTING) {
            send("cancel_listing", data);
        } else if (confirmed == Modal.BLOCK_SELLER) {
            send("block_seller", data);
        } else if (confirmed == Modal.UNBLOCK_SELLER) {
            send("unblock_seller", data);
        } else if (confirmed == Modal.REPORT_LISTING) {
            data.putString("Reason", "OTHER");
            data.putString("Detail", submittedReportDetail);
            send("report_listing", data);
        }
    }

    private boolean bottomClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int y = panelY + panelH - 33;
        int tabW = Math.min(150, panelW / 5);
        int start = width / 2 - tabW * 3 / 2;
        if (!inside(mouseX, mouseY, start, y, tabW * 3, 33)) {
            return false;
        }
        int index = Mth.clamp(((int)mouseX - start) / tabW, 0, 2);
        View target = index == 0 ? View.HOME : index == 1 ? View.LIKES : View.CREATE;
        if ((view == View.CREATE || view == View.INVENTORY) && target != View.CREATE && hasDraftWork()) {
            pendingView = target;
            setModal(Modal.LEAVE_DRAFT);
        } else {
            navigateRoot(target);
        }
        return true;
    }

    private boolean homeClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && inside(mouseX, mouseY, panelX + 8, panelY + 5, 150, 34)) {
            navigate(View.PROFILE);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && inside(mouseX, mouseY, panelX + 166, panelY + 6, 32, 32)) {
            navigate(View.NOTIFICATIONS);
            return true;
        }
        int sideW = Math.max(168, panelW * 22 / 100);
        int sideX = panelX + panelW - sideW;
        int sortX = sortBarButtonsX();
        int sortY = contentTop - 6;
        for (Sort option : Sort.values()) {
            if (inside(mouseX, mouseY, sortX, sortY, 78, 22)) {
                sort = option;
                resetScroll();
                scheduleQuery();
                return true;
            }
            sortX += 84;
            if (sortX + 78 >= sideX) {
                break;
            }
        }
        FilterLayout layout = filterLayout();
        String removedTag = searchTagChipAt(mouseX, mouseY, sideX + 10, layout.chipsY, sideW - 20, layout.compact ? 1 : 2);
        if (removedTag != null) {
            searchTags.remove(removedTag);
            resetScroll();
            scheduleQuery();
            return true;
        }
        if (inside(mouseX, mouseY, sideX + 10, layout.activeY, sideW - 20, 16)) {
            activeOnly = !activeOnly;
            resetScroll();
            scheduleQuery();
            return true;
        }
        int pageY = contentBottom - 53;
        int filterX = sideX + 10;
        int filterW = sideW - 20;
        if (inside(mouseX, mouseY, filterX, pageY, 32, 18) && state.page() > 0) {
            sendQuery(state.page() - 1);
            return true;
        }
        if (inside(mouseX, mouseY, filterX + filterW - 32, pageY, 32, 18) && state.page() + 1 < state.totalPages()) {
            sendQuery(state.page() + 1);
            return true;
        }
        MarketListing hit = listingAt(mouseX, mouseY, panelX + 12, contentTop + 23, sideX - panelX - 20, contentBottom - contentTop - 27, cardListings);
        if (hit != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && state.admin()) {
                pendingListing = hit;
                setModal(Modal.ADMIN_DELETE);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                int[] rect = listingRect(hit, panelX + 12, contentTop + 23, sideX - panelX - 20, cardListings);
                if (rect != null && inside(mouseX, mouseY, rect[0] + rect[2] - 24, rect[1], 24, 24)) {
                    toggleLike(hit);
                } else {
                    openDetail(hit);
                }
                return true;
            }
        }
        return false;
    }

    private boolean detailClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || selected == null) {
            return false;
        }
        int leftX = panelX + 18;
        int leftW = Math.max(190, panelW * 25 / 100);
        int top = contentTop + 4;
        if (inside(mouseX, mouseY, leftX + leftW - 32, top + 4, 26, 24)) {
            toggleLike(selected);
            return true;
        }
        int itemBottom = top + 102;
        int sellerRowY = itemBottom + (selected.auction() ? 98 : 82);
        if (inside(mouseX, mouseY, leftX + 8, sellerRowY, leftW - 16, 26)) {
            requestSellerProfile(selected.sellerId(), selected.sellerName());
            return true;
        }
        for (AuthorZone zone : commentAuthorZones) {
            if (inside(mouseX, mouseY, zone.x(), zone.y(), zone.w(), zone.h())) {
                if (!zone.id().isBlank()) {
                    requestSellerProfile(zone.id(), zone.name());
                }
                return true;
            }
        }
        return false;
    }

    private void requestSellerProfile(String sellerId, String sellerName) {
        if (sellerId == null || sellerId.isBlank()) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("SellerId", sellerId);
        data.putString("SellerName", sellerName == null ? "" : sellerName);
        send("seller_profile", data);
    }

    private boolean createClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int left = panelX + 24;
        int labelW = 122;
        int fieldX = left + labelW;
        int fieldW = Math.max(150, panelW / 2 - labelW - 40);
        int itemY = contentTop + 5;
        if (inside(mouseX, mouseY, left, itemY, labelW + fieldW, 48)) {
            navigate(View.INVENTORY);
            return true;
        }
        int typeX = panelX + panelW / 2 + 22;
        int rightW = panelX + panelW - 24 - typeX;
        int half = (rightW - 6) / 2;
        int typeY = itemY + 140;
        if (inside(mouseX, mouseY, typeX, typeY, half, 24)) {
            createAuction = false;
            draftDirty = true;
            buildWidgets();
            return true;
        }
        if (inside(mouseX, mouseY, typeX + half + 6, typeY, half, 24)) {
            createAuction = true;
            draftDirty = true;
            buildWidgets();
            return true;
        }
        int tagsY = typeY + 53;
        int tagBottom = contentBottom - 38;
        String tag = tagFlowAt(mouseX, mouseY, state.tags(), typeX, tagsY - createTagScroll, rightW - 10, tagsY, tagBottom);
        if (tag != null) {
            if (!createTags.add(tag)) {
                createTags.remove(tag);
            }
            draftDirty = true;
            return true;
        }
        return false;
    }

    private boolean inventoryClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || minecraft == null || minecraft.player == null) {
            return false;
        }
        int slotSize = 26;
        int startX = width / 2 - slotSize * 9 / 2;
        int startY = contentTop + 36;
        Inventory inventory = minecraft.player.getInventory();
        for (int index = 0; index < 36; index++) {
            int row = index < 9 ? 3 : index / 9 - 1;
            int column = index % 9;
            int sx = startX + column * slotSize;
            int sy = startY + row * slotSize;
            if (inside(mouseX, mouseY, sx, sy, 24, 24) && !inventory.getItem(index).isEmpty()) {
                CompoundTag data = new CompoundTag();
                data.putInt("Slot", index);
                send("select_item", data);
                ItemStack preview = inventory.getItem(index).copy();
                preview.setCount(1);
                state.setDraft(preview);
                if (createName.isBlank()) {
                    createName = preview.getHoverName().getString();
                }
                draftDirty = true;
                backNow();
                return true;
            }
        }
        return false;
    }

    private boolean profileClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int cardX = panelX + 28;
        int cardY = contentTop + 15;
        int cardW = panelW - 56;
        if (state.admin() && inside(mouseX, mouseY, cardX + cardW - 92, cardY + 8, 84, 32)) {
            navigate(View.ADMIN);
            return true;
        }
        if (inside(mouseX, mouseY, cardX + 10, cardY + 8, 300, 62)) {
            CompoundTag user = state.user();
            String name = user.getString("Name");
            if (name.isBlank() && minecraft != null) {
                name = minecraft.getUser().getName();
            }
            requestSellerProfile(user.getString("Id"), name);
            return true;
        }
        int gridY = cardY + 103;
        int gap = 10;
        int leftW = Math.max(260, cardW * 57 / 100);
        int tileW = (leftW - gap) / 2;
        int tileH = 58;
        View[] targets = {View.VIEW_HISTORY, View.LIKES, View.PURCHASE_HISTORY, View.LISTING_HISTORY, View.BLOCKED_USERS};
        for (int i = 0; i < targets.length; i++) {
            int x = cardX + (i % 2) * (tileW + gap);
            int y = gridY + (i / 2) * (tileH + gap);
            if (inside(mouseX, mouseY, x, y, tileW, tileH)) {
                navigate(targets[i]);
                return true;
            }
        }
        int savedX = cardX + leftW + gap;
        int savedW = cardW - leftW - gap;
        int savedH = Math.max(90, contentBottom - gridY - 7);
        int listTop = gridY + 28;
        int rowH = 42;
        List<CompoundTag> savedSearches = state.savedSearches();
        for (int i = 0; i < savedSearches.size(); i++) {
            int rowY = listTop + i * (rowH + 5) - scroll;
            if (!inside(mouseX, mouseY, savedX + 6, rowY, savedW - 12, rowH) || rowY < listTop || rowY + rowH > gridY + savedH - 6) {
                continue;
            }
            CompoundTag search = savedSearches.get(i);
            String id = search.getString("Id");
            if (inside(mouseX, mouseY, savedX + savedW - 50, rowY + 5, 38, rowH - 10)) {
                pendingSavedSearchId = id;
                setModal(Modal.DELETE_SAVED_SEARCH);
            } else {
                CompoundTag data = new CompoundTag();
                data.putString("Id", id);
                send("run_saved_search", data);
                applySavedSearchFilters(search);
                history.clear();
                view = View.HOME;
                resetScroll();
                transitionDirection = -1;
                transitionStarted = System.currentTimeMillis();
                buildWidgets();
            }
            return true;
        }
        if (state.admin() && inside(mouseX, mouseY, cardX + tileW + gap, gridY + 2 * (tileH + gap), tileW, tileH)) {
            navigate(View.ADMIN);
            return true;
        }
        return false;
    }

    private boolean notificationClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        List<CompoundTag> notifications = state.notifications();
        int x = panelX + 25;
        int y = contentTop + 6;
        int w = panelW - 50;
        int rowH = 58;
        for (int i = 0; i < notifications.size(); i++) {
            int ry = y + i * (rowH + 6) - scroll;
            if (!inside(mouseX, mouseY, x, ry, w, rowH)) {
                continue;
            }
            CompoundTag notification = notifications.get(i);
            CompoundTag data = new CompoundTag();
            data.putString("Id", notification.getString("Id"));
            if (inside(mouseX, mouseY, x + w - 48, ry, 48, rowH)) {
                send("notification_delete", data);
            } else if (!notification.getBoolean("Read")) {
                send("notification_read", data);
            }
            return true;
        }
        return false;
    }

    private boolean adminClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int splitX = panelX + panelW / 2 - 8;
        int leftX = panelX + 32;
        int leftW = Math.max(220, splitX - leftX - 16);
        int listX = splitX + 16;
        int rowsY = contentTop + 32;
        int listW = panelX + panelW - 28 - listX;
        int rowsH = contentBottom - rowsY - 7;
        if (inside(mouseX, mouseY, listX, rowsY, listW, rowsH)) {
            int index = ((int)mouseY - rowsY + scroll) / 24;
            if (index < 0 || index >= state.tags().size()) {
                return false;
            }
            adminSelectedTag = state.tags().get(index);
            if (adminTagBox != null) {
                adminTagBox.setValue(adminSelectedTag);
            }
            buildWidgets();
            return true;
        }
        int feeY = adminFeeSectionY();
        int halfToggle = (leftW - 6) / 2;
        if (inside(mouseX, mouseY, leftX, feeY + 52, halfToggle, 22)) {
            if (!adminPercentFee) {
                adminPercentFee = true;
                adminFeeDirty = true;
                buildWidgets();
            }
            return true;
        }
        if (inside(mouseX, mouseY, leftX + halfToggle + 6, feeY + 52, halfToggle, 22)) {
            if (adminPercentFee) {
                adminPercentFee = false;
                adminFeeDirty = true;
                buildWidgets();
            }
            return true;
        }
        int reportX = leftX - 8;
        int reportY = feeY + 126;
        int reportW = splitX - leftX;
        int reportH = contentBottom - reportY - 7;
        int reportRowH = 62;
        int availableRows = Math.max(0, (reportH - 28) / reportRowH);
        List<CompoundTag> reports = state.reports();
        for (int i = 0; i < Math.min(availableRows, reports.size()); i++) {
            int rowY = reportY + 27 + i * reportRowH;
            int actionY = rowY + 33;
            int actionW = Math.max(44, (reportW - 30) / 2);
            Modal target = null;
            if (inside(mouseX, mouseY, reportX + 10, actionY, actionW, 18)) {
                target = Modal.RESOLVE_REPORT;
            } else if (inside(mouseX, mouseY, reportX + reportW - 10 - actionW, actionY, actionW, 18)) {
                target = Modal.DISMISS_REPORT;
            }
            if (target != null) {
                pendingReportId = reports.get(i).getString("Id");
                setModal(target);
                return true;
            }
        }
        return false;
    }

    private boolean historyClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (view == View.LISTING_HISTORY) {
            int x = panelX + 20;
            for (ListingHistoryFilter filter : ListingHistoryFilter.values()) {
                if (inside(mouseX, mouseY, x, contentTop + 2, 82, 22)) {
                    listingHistoryFilter = filter;
                    resetScroll();
                    return true;
                }
                x += 90;
            }
        }
        if (view == View.LIKES) {
            int pagerY = contentBottom - 22;
            if (inside(mouseX, mouseY, width / 2 - 96, pagerY, 32, 18) && state.page() > 0) {
                sendLikedQuery(state.page() - 1);
                return true;
            }
            if (inside(mouseX, mouseY, width / 2 + 64, pagerY, 32, 18) && state.page() + 1 < state.totalPages()) {
                sendLikedQuery(state.page() + 1);
                return true;
            }
        }
        int y = contentTop + (view == View.LISTING_HISTORY ? 29 : 6);
        int bottomReserve = view == View.LIKES ? 27 : 4;
        MarketListing hit = listingAt(mouseX, mouseY, panelX + 18, y, panelW - 36, contentBottom - y - bottomReserve, cardListings);
        if (hit != null) {
            int[] rect = listingRect(hit, panelX + 18, y, panelW - 36, cardListings);
            if (rect != null && inside(mouseX, mouseY, rect[0] + rect[2] - 24, rect[1], 24, 24)) {
                toggleLike(hit);
            } else {
                openDetail(hit);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        mouseX /= uiScale;
        mouseY /= uiScale;
        if (closing) {
            return true;
        }
        if (modal != Modal.NONE) {
            if (modal == Modal.REPORT_LISTING && reportDetailBox != null) {
                reportDetailBox.mouseScrolled(mouseX, mouseY, delta);
            }
            if (modal == Modal.REVIEW && reviewCommentBox != null) {
                reviewCommentBox.mouseScrolled(mouseX, mouseY, delta);
            }
            return true;
        }
        if (view == View.CREATE) {
            int typeButtonX = panelX + panelW / 2 + 22;
            int rightW = panelX + panelW - 24 - typeButtonX;
            int tagsY = contentTop + 198;
            int tagBottom = contentBottom - 38;
            if (inside(mouseX, mouseY, typeButtonX, tagsY, rightW, Math.max(1, tagBottom - tagsY))) {
                createTagScroll = Mth.clamp(createTagScroll - (int)Math.round(delta * 44.0D), 0, Math.max(0, createTagContentHeight - Math.max(1, tagBottom - tagsY)));
                return true;
            }
        }
        if (view == View.HOME || view == View.PROFILE || view == View.LIKES || view == View.VIEW_HISTORY || view == View.PURCHASE_HISTORY || view == View.LISTING_HISTORY || view == View.NOTIFICATIONS || view == View.ADMIN || view == View.SELLER || view == View.SELLER_REVIEWS || view == View.BLOCKED_USERS) {
            if (!scrollAnimating) {
                scrollPosition = scroll;
                scrollTarget = scroll;
            }
            scrollTarget = Mth.clamp(scrollTarget - (int)Math.round(delta * 64.0D), 0, maxScroll);
            scrollAnimating = true;
            long now = System.currentTimeMillis();
            if (now - scrollSoundAt > 75L) {
                scrollSoundAt = now;
                CyberUiFx.play("minecraft:block.note_block.hat", delta > 0.0D ? 1.72F : 1.42F);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closing) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (modal != Modal.NONE) {
                if (modal == Modal.REVIEW) {
                    setModal(Modal.REVIEW_DISCARD);
                } else if (modal == Modal.REVIEW_DISCARD) {
                    setModal(Modal.REVIEW);
                } else {
                    setModal(Modal.NONE);
                    pendingListing = null;
                    pendingView = null;
                    pendingClose = false;
                }
                return true;
            }
            if (view == View.HOME) {
                onClose();
            } else if ((view == View.CREATE || view == View.INVENTORY) && hasDraftWork()) {
                setModal(Modal.LEAVE_DRAFT);
            } else {
                back();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if ((view == View.CREATE || view == View.INVENTORY) && hasDraftWork()) {
            pendingClose = true;
            setModal(Modal.LEAVE_DRAFT);
            return;
        }
        closeNow();
    }

    private void closeNow() {
        if (closing) {
            return;
        }
        closing = true;
        closingAt = System.currentTimeMillis();
        CyberUiFx.play("minecraft:block.beacon.deactivate", 0.74F);
    }

    private void completeClose() {
        send("close", new CompoundTag());
        if (minecraft != null) {
            minecraft.popGuiLayer();
        }
    }

    private void buildEditFields() {
        if (selected == null) {
            return;
        }
        int leftX = panelX + 18;
        int leftW = Math.max(190, panelW * 25 / 100);
        int middleX = leftX + leftW + 12;
        int rightW = Math.max(240, panelW * 32 / 100);
        int rightX = panelX + panelW - rightW - 12;
        int middleW = rightX - middleX - 12;
        editNameBox = addBox(middleX, contentTop + 173, middleW, 20, "gui.freemarket.item_name", selected.name(), value -> {
        });
        editPriceBox = addBox(middleX, contentTop + 198, middleW, 20, "gui.freemarket.listing_price", Long.toString(selected.displayPrice()), value -> {
            String number = numeric(value);
            if (!number.equals(value)) {
                editPriceBox.setValue(number);
            }
        });
        editPriceBox.setEditable(!selected.auction());
        editDescriptionBox = addArea(middleX, contentTop + 223, middleW, 44, "gui.freemarket.description", selected.description(), value -> {
        });
        addRenderableWidget(new CyberButton(middleX, contentTop + 273, Math.min(120, middleW), 20, Component.translatable("gui.freemarket.save_changes"), this::saveListingEdit));
    }

    private void toggleEdit() {
        detailEditing = !detailEditing;
        buildWidgets();
    }

    private void saveListingEdit() {
        if (selected == null || editNameBox == null || editPriceBox == null || editDescriptionBox == null) {
            return;
        }
        CompoundTag data = listingId(selected);
        data.putString("Name", editNameBox.getValue());
        if (!selected.auction()) {
            data.putLong("Price", parseLong(editPriceBox.getValue(), selected.displayPrice()));
        }
        data.putString("Description", editDescriptionBox.getValue());
        send("edit_listing", data);
        detailEditing = false;
        buildWidgets();
    }

    private void togglePause() {
        if (selected == null) {
            return;
        }
        CompoundTag data = listingId(selected);
        data.putBoolean("Paused", selected.active());
        send("set_listing_paused", data);
    }

    private void sendBuy() {
        if (selected == null) {
            return;
        }
        pendingListing = selected;
        setModal(Modal.CONFIRM_BUY);
    }

    private void sendBid() {
        if (selected == null || bidBox == null || bidBox.getValue().isBlank()) {
            showLocalMessage("gui.freemarket.enter_bid");
            return;
        }
        long amount = parseLong(bidBox.getValue(), 0L);
        if (amount <= selected.displayPrice()) {
            showLocalMessage("message.freemarket.invalid_bid");
            return;
        }
        pendingBidAmount = amount;
        pendingListing = selected;
        setModal(Modal.CONFIRM_BID);
    }

    private void sendOffer() {
        if (selected == null || offerBox == null || offerBox.getValue().isBlank()) {
            showLocalMessage("gui.freemarket.enter_offer");
            return;
        }
        CompoundTag data = listingId(selected);
        data.putLong("Amount", parseLong(offerBox.getValue(), 0L));
        send("offer", data);
        offerBox.setValue("");
    }

    private void respondToOffer(String action, MarketListing.MarketOffer offer) {
        if (selected == null || offer == null || offer.id().isBlank()) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("ListingId", selected.id());
        data.putString("OfferId", offer.id());
        send(action, data);
    }

    private void sendComment() {
        if (selected == null || commentBox == null || commentBox.getValue().isBlank()) {
            return;
        }
        CompoundTag data = listingId(selected);
        data.putString("Message", commentBox.getValue());
        send("comment", data);
        commentBox.setValue("");
    }

    private void publish() {
        if (publishing) {
            return;
        }
        ItemStack draft = state.draft();
        if (draft.isEmpty()) {
            showLocalMessage("gui.freemarket.select_item_required");
            return;
        }
        if (createName.isBlank() || createPrice.isBlank() || parseLong(createPrice, 0L) <= 0L) {
            showLocalMessage("gui.freemarket.invalid_listing_fields");
            return;
        }
        if (createAuction && parseLong(createDuration, 0L) <= 0L) {
            showLocalMessage("gui.freemarket.invalid_duration");
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("Name", createName);
        data.putString("Type", createAuction ? "AUCTION" : "FIXED");
        data.putLong("Price", parseLong(createPrice, 0L));
        data.putLong("Duration", parseLong(createDuration, 60L));
        data.putString("Description", createDescription);
        Set<String> merged = new LinkedHashSet<>(createTags);
        for (String value : createTagsText.split(",")) {
            if (!value.isBlank()) {
                merged.add(value.strip());
            }
        }
        ListTag tags = new ListTag();
        for (String value : merged) {
            tags.add(StringTag.valueOf(value));
        }
        data.put("Tags", tags);
        send("create_listing", data);
        publishing = true;
    }

    private void clearCreateForm() {
        createName = "";
        createPrice = "";
        createDuration = "60";
        createDescription = "";
        createTagsText = "";
        createTags.clear();
        createAuction = false;
    }

    private void requestLeaveDraft() {
        if (hasDraftWork()) {
            setModal(Modal.LEAVE_DRAFT);
        } else {
            back();
        }
    }

    private boolean hasDraftWork() {
        return draftDirty || !state.draft().isEmpty();
    }

    private void fillDraftDefaults() {
        ItemStack draft = state.draft();
        if (!draft.isEmpty() && createName.isBlank()) {
            createName = draft.getHoverName().getString();
        }
    }

    private void saveAdminTag() {
        if (adminTagBox == null || adminTagBox.getValue().isBlank()) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("Name", adminTagBox.getValue().strip());
        if (adminTagLabelBox != null) {
            data.putString("Label", adminTagLabelBox.getValue().strip());
        }
        if (adminSelectedTag.isBlank()) {
            send("admin_tag_add", data);
        } else {
            data.putString("OldName", adminSelectedTag);
            send("admin_tag_edit", data);
        }
        adminSelectedTag = "";
        adminTagBox.setValue("");
        if (adminTagLabelBox != null) {
            adminTagLabelBox.setValue("");
        }
    }

    private void deleteAdminTag() {
        String value = adminSelectedTag;
        if (value.isBlank() && adminTagBox != null) {
            value = adminTagBox.getValue().strip();
        }
        if (value.isBlank()) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("Name", value);
        send("admin_tag_delete", data);
        adminSelectedTag = "";
        if (adminTagBox != null) {
            adminTagBox.setValue("");
        }
        if (adminTagLabelBox != null) {
            adminTagLabelBox.setValue("");
        }
    }

    private void saveFee() {
        if (adminFeeBox == null) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(adminFeeBox.getValue());
        } catch (NumberFormatException exception) {
            showLocalMessage("gui.freemarket.invalid_fee");
            return;
        }
        if (value < 0.0D || adminPercentFee && value > 100.0D) {
            showLocalMessage("gui.freemarket.invalid_fee");
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("Mode", adminPercentFee ? "PERCENT" : "FIXED");
        data.putDouble("Value", value);
        send("admin_fee_set", data);
        adminFeeDirty = false;
    }

    private Component durationText(long minutes) {
        long days = minutes / 1440L;
        long hours = minutes % 1440L / 60L;
        long mins = minutes % 60L;
        if (days > 0L) {
            return Component.translatable("gui.freemarket.duration_days", days, hours);
        }
        if (hours > 0L) {
            return Component.translatable("gui.freemarket.duration_hours", hours, mins);
        }
        return Component.translatable("gui.freemarket.duration_minutes_only", mins);
    }

    private long feePreview() {
        long price = parseLong(createPrice, 0L);
        CompoundTag fee = state.fee();
        if ("PERCENT".equalsIgnoreCase(fee.getString("Mode"))) {
            return Math.max(0L, Math.round(price * fee.getDouble("Value") / 100.0D));
        }
        return Math.max(0L, fee.getLong("Value"));
    }

    private void toggleLike(MarketListing listing) {
        CompoundTag data = listingId(listing);
        data.putBoolean("Liked", !listing.liked());
        send("like", data);
    }

    private void openDetail(MarketListing listing) {
        selected = listing;
        CompoundTag data = listingId(listing);
        send("view", data);
        navigate(View.DETAIL);
    }

    private void openListingModal(Modal type) {
        pendingListing = selected;
        setModal(type);
    }

    private void openSellerBlockModal(boolean blocked) {
        pendingListing = selected;
        setModal(blocked ? Modal.UNBLOCK_SELLER : Modal.BLOCK_SELLER);
    }

    private void openReportModal() {
        pendingListing = selected;
        reportDetail = "";
        setModal(Modal.REPORT_LISTING);
    }

    private void navigate(View target) {
        if (target == view) {
            return;
        }
        history.push(view);
        view = target;
        resetScroll();
        transitionDirection = 1;
        transitionStarted = System.currentTimeMillis();
        if (target == View.CREATE) {
            fillDraftDefaults();
        }
        if (target == View.LIKES) {
            scheduleLikedQuery(0);
        } else if (target == View.HOME) {
            scheduleQuery(0);
        }
        buildWidgets();
    }

    private void navigateRoot(View target) {
        View previous = view;
        history.clear();
        view = target;
        resetScroll();
        transitionDirection = rootIndex(target) < rootIndex(previous) ? -1 : 1;
        transitionStarted = System.currentTimeMillis();
        if (target == View.CREATE) {
            fillDraftDefaults();
        }
        if (target == View.LIKES) {
            scheduleLikedQuery(0);
        } else if (target == View.HOME) {
            scheduleQuery(0);
        }
        buildWidgets();
    }

    private void back() {
        if ((view == View.CREATE || view == View.INVENTORY) && hasDraftWork() && (history.isEmpty() || history.peek() != View.CREATE)) {
            setModal(Modal.LEAVE_DRAFT);
            return;
        }
        backNow();
    }

    private void backNow() {
        if (history.isEmpty()) {
            view = View.HOME;
        } else {
            view = history.pop();
        }
        resetScroll();
        transitionDirection = -1;
        transitionStarted = System.currentTimeMillis();
        if (view == View.HOME) {
            scheduleQuery(0);
        } else if (view == View.LIKES) {
            scheduleLikedQuery(state.page());
        }
        buildWidgets();
    }

    private void scheduleQuery() {
        scheduleQuery(0);
    }

    private void scheduleQuery(int page) {
        requestedPage = Math.max(0, page);
        requestedLikedOnly = false;
        queryDue = System.currentTimeMillis() + 220L;
    }

    private void scheduleLikedQuery(int page) {
        requestedPage = Math.max(0, page);
        requestedLikedOnly = true;
        queryDue = System.currentTimeMillis() + 220L;
    }

    private void sendQuery(int page) {
        if (Math.max(0, page) != state.page()) {
            resetScroll();
        }
        requestedLikedOnly = false;
        requestedPage = Math.max(0, page);
        CompoundTag data = new CompoundTag();
        data.putString("Text", query);
        data.putString("Seller", "");
        data.putString("Item", "");
        data.putString("Tag", combinedTagQuery());
        if (!minimumPrice.isBlank()) {
            data.putLong("MinPrice", parseLong(minimumPrice, 0L));
        }
        if (!maximumPrice.isBlank()) {
            data.putLong("MaxPrice", parseLong(maximumPrice, Long.MAX_VALUE));
        }
        data.putBoolean("AvailableOnly", activeOnly);
        data.putString("Sort", sort.networkValue);
        data.putInt("Page", requestedPage);
        send("query", data);
    }

    private void saveCurrentSearch() {
        CompoundTag data = new CompoundTag();
        String combinedTags = combinedTagQuery();
        String name = !query.isBlank() ? query.trim() : !combinedTags.isBlank() ? combinedTags : Component.translatable("gui.freemarket.saved_search_default_name").getString();
        data.putString("Name", name);
        data.putString("Text", query);
        data.putString("Seller", "");
        data.putString("Item", "");
        data.putString("Tag", combinedTags);
        if (!minimumPrice.isBlank()) {
            data.putLong("MinPrice", parseLong(minimumPrice, 0L));
        }
        if (!maximumPrice.isBlank()) {
            data.putLong("MaxPrice", parseLong(maximumPrice, Long.MAX_VALUE));
        }
        data.putBoolean("AvailableOnly", activeOnly);
        data.putString("Sort", sort.networkValue);
        data.putBoolean("NotificationsEnabled", true);
        send("save_search", data);
    }

    private void sendLikedQuery(int page) {
        if (Math.max(0, page) != state.page()) {
            resetScroll();
        }
        requestedLikedOnly = true;
        requestedPage = Math.max(0, page);
        CompoundTag data = new CompoundTag();
        data.putBoolean("LikedOnly", true);
        data.putBoolean("AvailableOnly", false);
        data.putString("Sort", sort.networkValue);
        data.putInt("Page", requestedPage);
        send("query", data);
    }

    private void send(String action, CompoundTag data) {
        NetworkHandler.sendToServer(action, data);
    }

    private CompoundTag listingId(MarketListing listing) {
        CompoundTag data = new CompoundTag();
        data.putString("Id", listing.id());
        return data;
    }

    private void showLocalMessage(String key) {
        showToast(Component.translatable(key), 2800L);
    }

    private void showToast(Component message, long duration) {
        toast = message;
        toastStartedAt = System.currentTimeMillis();
        toastUntil = toastStartedAt + Math.max(600L, duration);
        CyberUiFx.play("minecraft:block.note_block.chime", 1.58F);
    }

    private void setModal(Modal next) {
        if (modal == next) {
            return;
        }
        if (reportDetailBox != null) {
            setFocused(null);
            removeWidget(reportDetailBox);
            reportDetailBox = null;
        }
        if (reviewCommentBox != null) {
            setFocused(null);
            removeWidget(reviewCommentBox);
            reviewCommentBox = null;
        }
        modal = next;
        if (next != Modal.NONE) {
            setFocused(null);
            modalOpenedAt = System.currentTimeMillis();
            CyberUiFx.play(next.danger ? "minecraft:block.note_block.didgeridoo" : "minecraft:block.note_block.hat", next.danger ? 0.68F : 0.92F);
            if (next == Modal.REPORT_LISTING) {
                int modalW = Math.min(410, panelW - 50);
                int modalX = width / 2 - modalW / 2;
                int modalY = height / 2 - modalHeight() / 2;
                reportDetailBox = new CyberTextArea(font, modalX + 20, modalY + 72, modalW - 40, 64, Component.translatable("gui.freemarket.report_detail"));
                reportDetailBox.setCharacterLimit(512);
                reportDetailBox.setValue(reportDetail);
                reportDetailBox.setValueListener(value -> reportDetail = value);
                addRenderableWidget(reportDetailBox);
                setFocused(reportDetailBox);
            }
            if (next == Modal.REVIEW) {
                int modalW = Math.min(410, panelW - 50);
                int modalX = width / 2 - modalW / 2;
                int modalY = height / 2 - modalHeight() / 2;
                reviewCommentBox = new CyberTextArea(font, modalX + 20, modalY + 112, modalW - 40, 56, Component.translatable("gui.freemarket.review_comment_hint"));
                reviewCommentBox.setCharacterLimit(256);
                reviewCommentBox.setValue(reviewComment);
                reviewCommentBox.setValueListener(value -> reviewComment = value);
                addRenderableWidget(reviewCommentBox);
            }
        }
    }

    private void submitReview() {
        CompoundTag data = new CompoundTag();
        data.putString("Id", reviewListingId);
        data.putInt("Stars", reviewStars);
        data.putString("Comment", reviewComment);
        send("review", data);
        clearReviewState();
        setModal(Modal.NONE);
    }

    private void clearReviewState() {
        reviewListingId = "";
        reviewSellerName = "";
        reviewStars = 0;
        reviewComment = "";
    }

    private int reviewStarsHover(double mouseX, double mouseY) {
        int y = height / 2 - modalHeight() / 2;
        for (int i = 4; i >= 0; i--) {
            if (inside(mouseX, mouseY, width / 2 - 62 + i * 25, y + 70, 25, 26)) {
                return i + 1;
            }
        }
        return 0;
    }

    private int modalHeight() {
        if (modal == Modal.REVIEW) {
            return 244;
        }
        return modal == Modal.REPORT_LISTING ? 210 : 132;
    }

    private void manualFeedback(double mouseX, double mouseY, int button) {
        feedbackX = Mth.clamp((int)Math.round(mouseX), 0, width);
        feedbackY = Mth.clamp((int)Math.round(mouseY), 0, height);
        feedbackStartedAt = System.currentTimeMillis();
        CyberUiFx.play("minecraft:ui.button.click", button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? 0.74F : 1.36F);
    }

    private int rootIndex(View value) {
        if (value == View.HOME) {
            return 0;
        }
        if (value == View.CREATE || value == View.INVENTORY) {
            return 2;
        }
        return 1;
    }

    private FilterLayout filterLayout() {
        boolean compact = contentBottom - contentTop < 390;
        if (compact) {
            return new FilterLayout(contentTop + 16, contentTop + 48, contentTop + 70, contentTop + 100, contentTop + 132, contentTop + 158, contentTop + 176, Math.min(contentTop + 194, contentBottom - 80), true);
        }
        return new FilterLayout(contentTop + 30, contentTop + 80, contentTop + 106, contentTop + 152, contentTop + 202, contentTop + 236, contentTop + 264, Math.min(contentTop + 292, contentBottom - 80), false);
    }

    private boolean isMine(MarketListing listing) {
        CompoundTag user = state.user();
        String id = user.getString("Id");
        String name = user.getString("Name");
        if (minecraft != null && name.isBlank()) {
            name = minecraft.getUser().getName();
        }
        return !id.isBlank() && id.equals(listing.sellerId()) || !name.isBlank() && name.equalsIgnoreCase(listing.sellerName());
    }

    private int unreadCount() {
        int count = 0;
        for (CompoundTag notification : state.notifications()) {
            if (!notification.getBoolean("Read")) {
                count++;
            }
        }
        return count;
    }

    private Component notificationText(CompoundTag tag, String textKey, String translationKey) {
        String key = tag.getString(translationKey);
        return key.isBlank() ? Component.literal(tag.getString(textKey)) : Component.translatable(key);
    }

    private void renderTagChips(GuiGraphics graphics, List<String> tags, int x, int y, int w, boolean selectable, int maxRows) {
        ensureTagCache();
        int cx = x;
        int cy = y;
        int rows = 1;
        for (String tag : tags) {
            int fullW = tagChipWidth(tag);
            int chipW = Math.min(w, fullW);
            if (cx + chipW > x + w) {
                cx = x;
                cy += 22;
                rows++;
            }
            if (rows > maxRows) {
                break;
            }
            boolean selectedTag = selectable && createTags.contains(tag) || !selectable && adminSelectedTag.equals(tag);
            Component label = tagChipLabel(tag);
            graphics.fill(cx, cy, cx + chipW, cy + 18, selectedTag ? 0xDB216D4A : 0xC013261D);
            border(graphics, cx, cy, chipW, 18, selectedTag ? GREEN : 0xFF315043);
            if (chipW < fullW) {
                drawScaledClipped(graphics, label, cx + 8, cy + 5, chipW - 16, 1.05F, selectedTag ? TEXT : MUTED);
            } else {
                drawScaled(graphics, label, cx + 8, cy + 5, 1.05F, selectedTag ? TEXT : MUTED);
            }
            cx += chipW + 5;
        }
    }

    private void ensureTagCache() {
        if (tagCacheRevision != state.revision()) {
            tagCacheRevision = state.revision();
            tagChipLabels.clear();
            tagChipWidths.clear();
        }
    }

    private Component tagChipLabel(String tag) {
        return tagChipLabels.computeIfAbsent(tag, key -> Component.translatable("gui.freemarket.tag_chip", state.tagLabel(key)));
    }

    private int tagChipWidth(String tag) {
        return tagChipWidths.computeIfAbsent(tag, key -> Math.round(font.width(tagChipLabel(key)) * 1.05F) + 16);
    }

    private void renderTagFlow(GuiGraphics graphics, List<String> tags, int x, int y, int w, int areaTop, int areaBottom) {
        ensureTagCache();
        int cx = x;
        int cy = y;
        for (String tag : tags) {
            int fullW = tagChipWidth(tag);
            int chipW = Math.min(w, fullW);
            if (cx + chipW > x + w) {
                cx = x;
                cy += 22;
            }
            if (cy > areaBottom) {
                break;
            }
            if (cy + 18 >= areaTop) {
                boolean selectedTag = createTags.contains(tag);
                Component label = tagChipLabel(tag);
                graphics.fill(cx, cy, cx + chipW, cy + 18, selectedTag ? 0xDB216D4A : 0xC013261D);
                border(graphics, cx, cy, chipW, 18, selectedTag ? GREEN : 0xFF315043);
                if (chipW < fullW) {
                    drawScaledClipped(graphics, label, cx + 8, cy + 5, chipW - 16, 1.05F, selectedTag ? TEXT : MUTED);
                } else {
                    drawScaled(graphics, label, cx + 8, cy + 5, 1.05F, selectedTag ? TEXT : MUTED);
                }
            }
            cx += chipW + 5;
        }
    }

    private int tagFlowHeight(List<String> tags, int w) {
        if (tags.isEmpty()) {
            return 0;
        }
        ensureTagCache();
        int cx = 0;
        int rows = 1;
        for (String tag : tags) {
            int chipW = Math.min(w, tagChipWidth(tag));
            if (cx + chipW > w) {
                cx = 0;
                rows++;
            }
            cx += chipW + 5;
        }
        return rows * 22;
    }

    private String tagFlowAt(double mouseX, double mouseY, List<String> tags, int x, int y, int w, int areaTop, int areaBottom) {
        if (mouseY < areaTop || mouseY >= areaBottom) {
            return null;
        }
        ensureTagCache();
        int cx = x;
        int cy = y;
        for (String tag : tags) {
            int chipW = Math.min(w, tagChipWidth(tag));
            if (cx + chipW > x + w) {
                cx = x;
                cy += 22;
            }
            if (inside(mouseX, mouseY, cx, cy, chipW, 18)) {
                return tag;
            }
            cx += chipW + 5;
        }
        return null;
    }

    private MarketListing listingAt(double mouseX, double mouseY, int x, int y, int w, int h, List<MarketListing> listings) {
        if (!inside(mouseX, mouseY, x, y, w, h)) {
            return null;
        }
        for (MarketListing listing : listings) {
            int[] rect = listingRect(listing, x, y, w, listings);
            if (rect != null && inside(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3])) {
                return listing;
            }
        }
        return null;
    }

    private int[] listingRect(MarketListing listing, int x, int y, int w, List<MarketListing> listings) {
        int index = listings.indexOf(listing);
        if (index < 0) {
            return null;
        }
        int gap = 8;
        int columns = Math.max(1, Math.min(6, (w + gap) / 132));
        int cardW = Math.max(104, (w - gap * (columns - 1)) / columns);
        int cardH = 152;
        int cx = x + index % columns * (cardW + gap);
        int cy = y + index / columns * (cardH + gap) - scroll;
        return new int[]{cx, cy, cardW, cardH};
    }

    private void renderScrollBar(GuiGraphics graphics, int x, int y, int h, int contentH, int currentScroll) {
        if (contentH <= h || h <= 0) {
            return;
        }
        graphics.fill(x, y, x + 2, y + h, 0x6635F29A);
        int thumbH = Math.max(18, h * h / contentH);
        int travel = h - thumbH;
        int thumbY = y + (int)(travel * (currentScroll / (double)Math.max(1, contentH - h)));
        graphics.fill(x - 1, thumbY, x + 3, thumbY + thumbH, GREEN);
    }

    private void renderLargeItem(GuiGraphics graphics, ItemStack stack, int x, int y, float scale) {
        if (stack.isEmpty()) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    private ItemStack draftStack() {
        if (draftCacheRevision != state.revision()) {
            draftCacheRevision = state.revision();
            cachedDraft = state.draft();
            cachedDraftLore = itemLore(cachedDraft);
        }
        return cachedDraft;
    }

    private List<Component> draftLore() {
        draftStack();
        return cachedDraftLore;
    }

    private List<Component> detailLore() {
        if (selected == null) {
            return List.of();
        }
        if (!selected.id().equals(detailLoreId) || detailLoreRevision != state.revision()) {
            detailLoreId = selected.id();
            detailLoreRevision = state.revision();
            detailLoreCache = itemLore(selected.item());
        }
        return detailLoreCache;
    }

    private List<Component> itemLore(ItemStack stack) {
        if (stack.isEmpty() || stack.getTag() == null || !stack.getTag().contains("display", 10)) {
            return List.of();
        }
        CompoundTag display = stack.getTag().getCompound("display");
        ListTag loreTag = display.getList("Lore", 8);
        List<Component> result = new ArrayList<>();
        for (int i = 0; i < loreTag.size(); i++) {
            Component component = Component.Serializer.fromJson(loreTag.getString(i));
            if (component != null) {
                result.add(component);
            }
        }
        return result;
    }

    private Component money(long amount) {
        return Component.translatable("gui.freemarket.money", NumberFormat.getIntegerInstance().format(amount));
    }

    private Component remainingTime(long timestamp) {
        long remaining = timestamp - System.currentTimeMillis();
        if (remaining <= 0L) {
            return Component.translatable("gui.freemarket.ended");
        }
        long minutes = remaining / 60000L;
        long days = minutes / 1440L;
        long hours = minutes % 1440L / 60L;
        long mins = minutes % 60L;
        return days > 0L
                ? Component.translatable("gui.freemarket.remaining_days", days, hours, mins)
                : Component.translatable("gui.freemarket.remaining_hours", hours, mins);
    }

    private Component relativeTime(long timestamp) {
        long delta = Math.max(0L, System.currentTimeMillis() - timestamp);
        if (delta < 3600000L) {
            return Component.translatable("gui.freemarket.minutes_ago", Math.max(1L, delta / 60000L));
        }
        String formatted = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp));
        return Component.translatable("gui.freemarket.date_time", formatted);
    }

    private Component legacy(String text) {
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value == '&' || value == '\u00A7') && i + 1 < text.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(text.charAt(i + 1));
                if (formatting != null) {
                    if (!part.isEmpty()) {
                        result.append(Component.literal(part.toString()).setStyle(style));
                        part.setLength(0);
                    }
                    style = formatting == ChatFormatting.RESET ? Style.EMPTY : style.applyLegacyFormat(formatting);
                    i++;
                    continue;
                }
            }
            part.append(value);
        }
        if (!part.isEmpty()) {
            result.append(Component.literal(part.toString()).setStyle(style));
        }
        return result;
    }

    private String stripLegacy(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if ((text.charAt(i) == '&' || text.charAt(i) == '\u00A7') && i + 1 < text.length() && ChatFormatting.getByCode(text.charAt(i + 1)) != null) {
                i++;
            } else {
                result.append(text.charAt(i));
            }
        }
        return result.toString();
    }

    private void drawClipped(GuiGraphics graphics, Component text, int x, int y, int w, int color) {
        if (w <= 0) {
            return;
        }
        CyberUiFx.scissor(graphics, x, y - 1, x + w, y + 11);
        graphics.drawString(font, text, x, y, color, false);
        graphics.disableScissor();
    }

    private void drawScaledClipped(GuiGraphics graphics, Component text, int x, int y, int w, float scale, int color) {
        if (w <= 0 || scale <= 0.0F) {
            return;
        }
        CyberUiFx.scissor(graphics, x, y - 1, x + w, y + Math.max(11, (int)Math.ceil(11.0F * scale)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int w, int maxLines, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, w));
        int count = Math.min(maxLines, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawString(font, lines.get(i), x, y + i * 11, color, false);
        }
        return count;
    }

    private void drawWrappedCentered(GuiGraphics graphics, Component text, int centerX, int y, int w, int maxLines, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, w));
        int count = Math.min(maxLines, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawCenteredString(font, lines.get(i), centerX, y + i * 11, color);
        }
    }

    private void drawScaled(GuiGraphics graphics, Component text, int x, int y, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private void drawCenteredScaled(GuiGraphics graphics, Component text, int centerX, int y, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawCenteredString(font, text, 0, 0, color);
        graphics.pose().popPose();
    }

    private int drawWrappedScaled(GuiGraphics graphics, Component text, int x, int y, int w, int maxLines, float scale, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, (int)(w / scale)));
        int count = Math.min(maxLines, lines.size());
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        for (int i = 0; i < count; i++) {
            graphics.drawString(font, lines.get(i), 0, i * 11, color, false);
        }
        graphics.pose().popPose();
        return count;
    }

    private void drawWrappedCenteredScaled(GuiGraphics graphics, Component text, int centerX, int y, int w, int maxLines, float scale, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, (int)(w / scale)));
        int count = Math.min(maxLines, lines.size());
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        for (int i = 0; i < count; i++) {
            graphics.drawCenteredString(font, lines.get(i), 0, i * 11, color);
        }
        graphics.pose().popPose();
    }

    private void drawToggle(GuiGraphics graphics, int x, int y, int w, int h, boolean selected, Component text) {
        graphics.fill(x, y, x + w, y + h, selected ? 0xDA216D4A : 0xB211211A);
        border(graphics, x, y, w, h, selected ? GREEN : 0xFF315043);
        drawCenteredScaled(graphics, text, x + w / 2, y + (h - 10) / 2, 1.1F, selected ? TEXT : MUTED);
    }

    private void drawPageArrow(GuiGraphics graphics, int x, int y, int w, boolean enabled, boolean previous, boolean hover) {
        int color = enabled ? GREEN : 0xFF34443C;
        graphics.fill(x, y, x + w, y + 18, enabled && hover ? 0xD0226D4B : 0xB012211B);
        border(graphics, x, y, w, 18, color);
        drawCenteredScaled(graphics, Component.translatable(previous ? "gui.freemarket.icon.previous" : "gui.freemarket.icon.next"), x + w / 2, y + 4, 1.2F, enabled ? TEXT : MUTED);
    }

    private void renderRoundHead(GuiGraphics graphics, int x, int y, int size) {
        ResourceLocation skin = minecraft != null && minecraft.player != null ? minecraft.player.getSkinTextureLocation() : null;
        renderRoundHead(graphics, x, y, size, skin);
    }

    private void renderRoundHead(GuiGraphics graphics, int x, int y, int size, ResourceLocation skin) {
        circle(graphics, x + size / 2, y + size / 2, size / 2 + 1, GREEN_DIM);
        if (skin == null) {
            circle(graphics, x + size / 2, y + size / 2, size / 2 - 1, 0xFF1A3026);
            return;
        }
        for (int row = 0; row < size; row++) {
            double normalized = (row + 0.5D - size / 2.0D) / (size / 2.0D);
            int half = Math.max(0, (int)(Math.sqrt(Math.max(0.0D, 1.0D - normalized * normalized)) * size / 2.0D));
            int left = size / 2 - half;
            int right = size / 2 + half;
            if (right <= left) {
                continue;
            }
            CyberUiFx.scissor(graphics, x + left, y + row, x + right, y + row + 1);
            graphics.blit(skin, x, y, size, size, 8.0F, 8.0F, 8, 8, 64, 64);
            RenderSystem.enableBlend();
            graphics.blit(skin, x, y, size, size, 40.0F, 8.0F, 8, 8, 64, 64);
            RenderSystem.disableBlend();
            graphics.disableScissor();
        }
    }

    private ResourceLocation playerSkin(String uuidString, String name) {
        UUID uuid = null;
        try {
            if (uuidString != null && !uuidString.isBlank()) {
                uuid = UUID.fromString(uuidString);
            }
        } catch (IllegalArgumentException ignored) {
        }
        if (minecraft != null && minecraft.getConnection() != null) {
            PlayerInfo info = uuid != null ? minecraft.getConnection().getPlayerInfo(uuid) : null;
            if (info == null && name != null && !name.isBlank()) {
                info = minecraft.getConnection().getPlayerInfo(name);
            }
            if (info != null) {
                return info.getSkinLocation();
            }
        }
        if (minecraft != null && minecraft.player != null && name != null && name.equalsIgnoreCase(minecraft.player.getGameProfile().getName())) {
            return minecraft.player.getSkinTextureLocation();
        }
        if (uuid != null) {
            ResourceLocation cached = SKIN_CACHE.get(uuid);
            if (cached != null) {
                return cached;
            }
            if (minecraft != null && SKIN_REQUESTED.add(uuid)) {
                UUID target = uuid;
                GameProfile profile = new GameProfile(target, name == null || name.isBlank() ? null : name);
                minecraft.getSkinManager().registerSkins(profile, (type, location, texture) -> {
                    if (type == MinecraftProfileTexture.Type.SKIN) {
                        SKIN_CACHE.put(target, location);
                    }
                }, false);
            }
            return DefaultPlayerSkin.getDefaultSkin(uuid);
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }

    private void drawBell(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 5, y, x + 10, y + 2, color);
        graphics.fill(x + 3, y + 2, x + 12, y + 10, color);
        graphics.fill(x + 1, y + 10, x + 14, y + 12, color);
        graphics.fill(x + 6, y + 13, x + 9, y + 15, color);
        graphics.fill(x + 5, y + 3, x + 10, y + 10, PANEL);
    }

    private void circle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int)Math.sqrt(Math.max(0, radius * radius - dy * dy));
            graphics.fill(centerX - half, centerY + dy, centerX + half + 1, centerY + dy + 1, color);
        }
    }

    private void border(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String numeric(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
    }

    private float clamp(float value) {
        return Mth.clamp(value, 0.0F, 1.0F);
    }

    private float easeOut(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private float easeInOut(float value) {
        return value < 0.5F ? 4.0F * value * value * value : 1.0F - (float)Math.pow(-2.0F * value + 2.0F, 3.0D) / 2.0F;
    }

    private record FilterLayout(int queryY, int tagY, int chipsY, int minimumY, int maximumY, int activeY, int resultsY, int saveY, boolean compact) {
    }

    private enum View {
        HOME,
        DETAIL,
        CREATE,
        INVENTORY,
        PROFILE,
        LIKES,
        VIEW_HISTORY,
        PURCHASE_HISTORY,
        LISTING_HISTORY,
        NOTIFICATIONS,
        ADMIN,
        SELLER,
        SELLER_REVIEWS,
        BLOCKED_USERS
    }

    private enum Sort {
        NAME("gui.freemarket.sort_name", "NAME"),
        UPDATED("gui.freemarket.sort_updated", "UPDATED"),
        LIKES("gui.freemarket.sort_likes", "LIKES");

        private final String key;
        private final String networkValue;

        Sort(String key, String networkValue) {
            this.key = key;
            this.networkValue = networkValue;
        }
    }

    private enum ListingHistoryFilter {
        ALL("gui.freemarket.all"),
        ACTIVE("gui.freemarket.active"),
        SOLD("gui.freemarket.sold_filter");

        private final String key;

        ListingHistoryFilter(String key) {
            this.key = key;
        }
    }

    private enum Modal {
        NONE("", "", false),
        LEAVE_DRAFT("gui.freemarket.unsaved_title", "gui.freemarket.unsaved_message", true),
        ADMIN_DELETE("gui.freemarket.delete_listing_title", "gui.freemarket.delete_listing_message", true),
        CANCEL_LISTING("gui.freemarket.cancel_listing_title", "gui.freemarket.cancel_listing_message", true),
        DELETE_SAVED_SEARCH("gui.freemarket.delete_saved_search_title", "gui.freemarket.delete_saved_search_message", true),
        BLOCK_SELLER("gui.freemarket.block_seller_title", "gui.freemarket.block_seller_message", true),
        UNBLOCK_SELLER("gui.freemarket.unblock_seller_title", "gui.freemarket.unblock_seller_message", true),
        REPORT_LISTING("gui.freemarket.report_listing_title", "gui.freemarket.report_listing_message", true),
        RESOLVE_REPORT("gui.freemarket.resolve_report_title", "gui.freemarket.resolve_report_message", false),
        DISMISS_REPORT("gui.freemarket.dismiss_report_title", "gui.freemarket.dismiss_report_message", true),
        REVIEW("gui.freemarket.review_title", "gui.freemarket.review_message", false),
        REVIEW_DISCARD("gui.freemarket.review_discard_title", "gui.freemarket.review_discard_message", true),
        CONFIRM_BUY("gui.freemarket.confirm_buy_title", "gui.freemarket.confirm_buy_message", false),
        CONFIRM_BID("gui.freemarket.confirm_bid_title", "gui.freemarket.confirm_bid_message", false),
        UNBLOCK_USER("gui.freemarket.unblock_seller_title", "gui.freemarket.unblock_seller_message", true);

        private final String titleKey;
        private final String messageKey;
        private final boolean danger;

        Modal(String titleKey, String messageKey, boolean danger) {
            this.titleKey = titleKey;
            this.messageKey = messageKey;
            this.danger = danger;
        }
    }
}
