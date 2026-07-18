package shake1227.freemarket.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MarketClientState {
    public static final MarketClientState INSTANCE = new MarketClientState();

    private List<MarketListing> listings = List.of();
    private List<String> tags = List.of();
    private List<CompoundTag> tagDefinitions = List.of();
    private Map<String, CompoundTag> tagDefinitionIndex = Map.of();
    private List<CompoundTag> notifications = List.of();
    private List<CompoundTag> savedSearches = List.of();
    private List<CompoundTag> reports = List.of();
    private Set<String> blockedUsers = Set.of();
    private List<CompoundTag> blockedUserEntries = List.of();
    private CompoundTag user = new CompoundTag();
    private CompoundTag fee = new CompoundTag();
    private ItemStack draft = ItemStack.EMPTY;
    private boolean admin;
    private int totalCount;
    private int page;
    private int totalPages = 1;
    private MarketListing detail;
    private long revision;
    private String messageKey = "";
    private boolean detailSellerBlocked;
    private int pendingReportCount;
    private CompoundTag sellerProfile = new CompoundTag();
    private List<MarketListing> sellerListings = List.of();

    private MarketClientState() {
    }

    public synchronized void apply(CompoundTag root) {
        if (root.contains("Listings", 9)) {
            List<MarketListing> next = new ArrayList<>();
            ListTag list = root.getList("Listings", 10);
            for (int i = 0; i < list.size(); i++) {
                next.add(MarketListing.fromTag(list.getCompound(i)));
            }
            listings = List.copyOf(next);
        }
        if (root.contains("Tags", 9)) {
            List<String> next = new ArrayList<>();
            ListTag list = root.getList("Tags", 8);
            for (int i = 0; i < list.size(); i++) {
                next.add(list.getString(i));
            }
            tags = List.copyOf(next);
        }
        if (root.contains("TagDefinitions", 9)) {
            tagDefinitions = copyCompounds(root.getList("TagDefinitions", 10));
            LinkedHashMap<String, CompoundTag> index = new LinkedHashMap<>();
            for (CompoundTag definition : tagDefinitions) {
                index.putIfAbsent(definition.getString("Id"), definition);
            }
            tagDefinitionIndex = Map.copyOf(index);
        }
        if (root.contains("Notifications", 9)) {
            List<CompoundTag> next = new ArrayList<>();
            ListTag list = root.getList("Notifications", 10);
            for (int i = 0; i < list.size(); i++) {
                next.add(list.getCompound(i).copy());
            }
            notifications = List.copyOf(next);
        }
        if (root.contains("SavedSearches", 9)) {
            savedSearches = copyCompounds(root.getList("SavedSearches", 10));
        }
        if (root.contains("Reports", 9)) {
            reports = copyCompounds(root.getList("Reports", 10));
        }
        if (root.contains("PendingReportCount")) {
            pendingReportCount = Math.max(0, root.getInt("PendingReportCount"));
        }
        if (root.contains("BlockedUsers", 9)) {
            blockedUsers = readIdentifiers(root, "BlockedUsers");
            blockedUserEntries = copyCompounds(root.getList("BlockedUsers", 10));
        }
        if (root.contains("User", 10)) {
            CompoundTag merged = user.copy();
            CompoundTag incomingUser = root.getCompound("User");
            merged.merge(incomingUser);
            user = merged;
            if (incomingUser.contains("SavedSearches", 9)) {
                savedSearches = copyCompounds(incomingUser.getList("SavedSearches", 10));
            }
            if (incomingUser.contains("BlockedUsers", 9)) {
                blockedUsers = readIdentifiers(incomingUser, "BlockedUsers");
                blockedUserEntries = copyCompounds(incomingUser.getList("BlockedUsers", 10));
            }
        }
        if (root.contains("Fee", 10)) {
            fee = root.getCompound("Fee").copy();
        }
        if (root.contains("IsAdmin")) {
            admin = root.getBoolean("IsAdmin");
        }
        if (root.contains("TotalCount")) {
            totalCount = root.getInt("TotalCount");
        } else if (root.contains("Listings", 9)) {
            totalCount = listings.size();
        }
        if (root.contains("Page")) {
            page = Math.max(0, root.getInt("Page"));
        }
        if (root.contains("TotalPages")) {
            totalPages = Math.max(1, root.getInt("TotalPages"));
        }
        if (root.contains("Listing", 10)) {
            detail = MarketListing.fromTag(root.getCompound("Listing"));
            if (root.getCompound("Listing").contains("SellerBlocked")) {
                detailSellerBlocked = root.getCompound("Listing").getBoolean("SellerBlocked");
            }
        } else if (root.contains("Detail", 10)) {
            detail = MarketListing.fromTag(root.getCompound("Detail"));
            if (root.getCompound("Detail").contains("SellerBlocked")) {
                detailSellerBlocked = root.getCompound("Detail").getBoolean("SellerBlocked");
            }
        }
        if (root.contains("SellerBlocked")) {
            detailSellerBlocked = root.getBoolean("SellerBlocked");
        }
        if (root.contains("Draft", 10)) {
            draft = ItemStack.of(root.getCompound("Draft"));
        } else if (root.getBoolean("ClearDraft")) {
            draft = ItemStack.EMPTY;
        }
        if (root.contains("MessageKey", 8)) {
            messageKey = root.getString("MessageKey");
        }
        if (root.contains("Seller", 10)) {
            sellerProfile = root.getCompound("Seller").copy();
        }
        if (root.contains("SellerListings", 9)) {
            List<MarketListing> next = new ArrayList<>();
            ListTag list = root.getList("SellerListings", 10);
            for (int i = 0; i < list.size(); i++) {
                next.add(MarketListing.fromTag(list.getCompound(i)));
            }
            sellerListings = List.copyOf(next);
        }
        revision++;
    }

    public synchronized CompoundTag sellerProfile() {
        return sellerProfile.copy();
    }

    public synchronized List<MarketListing> sellerListings() {
        return sellerListings;
    }

    public synchronized List<CompoundTag> sellerReviews() {
        List<CompoundTag> copy = new ArrayList<>();
        ListTag list = sellerProfile.getList("Reviews", 10);
        for (int i = 0; i < list.size(); i++) {
            copy.add(list.getCompound(i).copy());
        }
        return List.copyOf(copy);
    }

    public synchronized void setDraft(ItemStack stack) {
        draft = stack.copy();
        revision++;
    }

    public synchronized void clearDraft() {
        draft = ItemStack.EMPTY;
        revision++;
    }

    public synchronized List<MarketListing> listings() {
        return listings;
    }

    public synchronized List<String> tags() {
        return tags;
    }

    public synchronized String tagLabel(String id) {
        CompoundTag definition = tagDefinitionIndex.get(id);
        if (definition == null) {
            return id;
        }
        String translationKey = definition.getString("TranslationKey");
        if (!translationKey.isBlank() && I18n.exists(translationKey)) {
            return I18n.get(translationKey);
        }
        String fallback = definition.getString("FallbackLabel");
        return fallback.isBlank() ? id : fallback;
    }

    public synchronized String tagFallbackLabel(String id) {
        CompoundTag definition = tagDefinitionIndex.get(id);
        if (definition == null) {
            return id;
        }
        String fallback = definition.getString("FallbackLabel");
        return fallback.isBlank() ? id : fallback;
    }

    public synchronized List<CompoundTag> notifications() {
        List<CompoundTag> copy = new ArrayList<>();
        for (CompoundTag notification : notifications) {
            copy.add(notification.copy());
        }
        return List.copyOf(copy);
    }

    public synchronized List<CompoundTag> savedSearches() {
        List<CompoundTag> copy = new ArrayList<>();
        for (CompoundTag search : savedSearches) {
            copy.add(search.copy());
        }
        return List.copyOf(copy);
    }

    public synchronized boolean detailSellerBlocked(String sellerId) {
        return detailSellerBlocked || blockedUsers.contains(sellerId);
    }

    public synchronized List<CompoundTag> blockedUserEntries() {
        List<CompoundTag> copy = new ArrayList<>();
        for (CompoundTag entry : blockedUserEntries) {
            copy.add(entry.copy());
        }
        return List.copyOf(copy);
    }

    public synchronized List<CompoundTag> reports() {
        List<CompoundTag> copy = new ArrayList<>();
        for (CompoundTag report : reports) {
            copy.add(report.copy());
        }
        return List.copyOf(copy);
    }

    public synchronized int pendingReportCount() {
        return pendingReportCount;
    }

    public synchronized CompoundTag user() {
        return user.copy();
    }

    public synchronized CompoundTag fee() {
        return fee.copy();
    }

    public synchronized ItemStack draft() {
        return draft.copy();
    }

    public synchronized boolean admin() {
        return admin;
    }

    public synchronized int totalCount() {
        return totalCount;
    }

    public synchronized int page() {
        return page;
    }

    public synchronized int totalPages() {
        return totalPages;
    }

    public synchronized MarketListing detail() {
        return detail;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized String takeMessageKey() {
        String value = messageKey;
        messageKey = "";
        return value;
    }

    public synchronized List<MarketListing> userListings(String key) {
        if (!user.contains(key, 9)) {
            return List.of();
        }
        List<MarketListing> result = new ArrayList<>();
        ListTag list = user.getList(key, 10);
        for (int i = 0; i < list.size(); i++) {
            result.add(MarketListing.fromTag(list.getCompound(i)));
        }
        return List.copyOf(result);
    }

    public synchronized List<String> selectedDraftTags() {
        if (!user.contains("DraftTags", 9)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        ListTag list = user.getList("DraftTags", StringTag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            result.add(list.getString(i));
        }
        return List.copyOf(result);
    }

    private static List<CompoundTag> copyCompounds(ListTag source) {
        List<CompoundTag> copy = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            copy.add(source.getCompound(i).copy());
        }
        return List.copyOf(copy);
    }

    private static Set<String> readIdentifiers(CompoundTag source, String key) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        ListTag strings = source.getList(key, StringTag.TAG_STRING);
        for (int i = 0; i < strings.size(); i++) {
            String value = strings.getString(i);
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        ListTag compounds = source.getList(key, 10);
        for (int i = 0; i < compounds.size(); i++) {
            CompoundTag entry = compounds.getCompound(i);
            String value = entry.hasUUID("Id") ? entry.getUUID("Id").toString() : entry.getString("Id");
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return Set.copyOf(result);
    }
}
