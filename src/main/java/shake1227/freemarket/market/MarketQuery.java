package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MarketQuery {
    public enum TagMatch {
        ANY,
        ALL
    }

    public enum SortOrder {
        NAME_ASC,
        UPDATED_DESC,
        LIKES_DESC,
        PRICE_ASC,
        PRICE_DESC
    }

    private final String text;
    private final String sellerText;
    private final String itemText;
    private final UUID sellerId;
    private final UUID likedBy;
    private final Set<String> tags;
    private final TagMatch tagMatch;
    private final Double minimumPrice;
    private final Double maximumPrice;
    private final Set<Listing.SaleType> saleTypes;
    private final Set<Listing.Status> statuses;
    private final boolean availableOnly;
    private final SortOrder sortOrder;
    private final int page;
    private final int pageSize;

    private MarketQuery(Builder builder) {
        text = MarketLimits.bounded(builder.text, MarketLimits.MAX_QUERY_LENGTH);
        sellerText = MarketLimits.bounded(builder.sellerText, MarketLimits.MAX_QUERY_LENGTH);
        itemText = MarketLimits.bounded(builder.itemText, MarketLimits.MAX_QUERY_LENGTH);
        sellerId = builder.sellerId;
        likedBy = builder.likedBy;
        tags = checkedTags(builder.tags);
        tagMatch = Objects.requireNonNull(builder.tagMatch, "tagMatch");
        minimumPrice = checkedOptionalPrice(builder.minimumPrice);
        maximumPrice = checkedOptionalPrice(builder.maximumPrice);
        if (minimumPrice != null && maximumPrice != null && minimumPrice > maximumPrice) {
            throw new IllegalArgumentException("price range");
        }
        saleTypes = builder.saleTypes.isEmpty() ? Set.of() : Set.copyOf(builder.saleTypes);
        statuses = builder.statuses.isEmpty() ? Set.of() : Set.copyOf(builder.statuses);
        availableOnly = builder.availableOnly;
        sortOrder = Objects.requireNonNull(builder.sortOrder, "sortOrder");
        page = Math.max(0, Math.min(1_000_000, builder.page));
        pageSize = Math.max(1, Math.min(MarketLimits.MAX_PAGE_SIZE, builder.pageSize));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getText() {
        return text;
    }

    public String getSellerText() {
        return sellerText;
    }

    public String getItemText() {
        return itemText;
    }

    public Optional<UUID> getSellerId() {
        return Optional.ofNullable(sellerId);
    }

    public Optional<UUID> getLikedBy() {
        return Optional.ofNullable(likedBy);
    }

    public Set<String> getTags() {
        return tags;
    }

    public TagMatch getTagMatch() {
        return tagMatch;
    }

    public Optional<Double> getMinimumPrice() {
        return Optional.ofNullable(minimumPrice);
    }

    public Optional<Double> getMaximumPrice() {
        return Optional.ofNullable(maximumPrice);
    }

    public Set<Listing.SaleType> getSaleTypes() {
        return saleTypes;
    }

    public Set<Listing.Status> getStatuses() {
        return statuses;
    }

    public boolean isAvailableOnly() {
        return availableOnly;
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public Builder toBuilder() {
        return builder()
                .text(text)
                .sellerText(sellerText)
                .itemText(itemText)
                .sellerId(sellerId)
                .likedBy(likedBy)
                .tags(tags)
                .tagMatch(tagMatch)
                .minimumPrice(minimumPrice)
                .maximumPrice(maximumPrice)
                .saleTypes(saleTypes)
                .statuses(statuses)
                .availableOnly(availableOnly)
                .sortOrder(sortOrder)
                .page(page)
                .pageSize(pageSize);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Text", text);
        tag.putString("SellerText", sellerText);
        tag.putString("ItemText", itemText);
        if (sellerId != null) {
            tag.putUUID("SellerId", sellerId);
        }
        if (likedBy != null) {
            tag.putUUID("LikedBy", likedBy);
        }
        tag.put("Tags", stringList(tags));
        tag.putString("TagMatch", tagMatch.name());
        if (minimumPrice != null) {
            tag.putDouble("MinimumPrice", minimumPrice);
        }
        if (maximumPrice != null) {
            tag.putDouble("MaximumPrice", maximumPrice);
        }
        tag.put("SaleTypes", enumList(saleTypes));
        tag.put("Statuses", enumList(statuses));
        tag.putBoolean("AvailableOnly", availableOnly);
        tag.putString("SortOrder", sortOrder.name());
        tag.putInt("Page", page);
        tag.putInt("PageSize", pageSize);
        return tag;
    }

    public static MarketQuery fromTag(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        Builder builder = builder()
                .text(tag.getString("Text"))
                .sellerText(tag.getString("SellerText"))
                .itemText(tag.getString("ItemText"))
                .availableOnly(tag.getBoolean("AvailableOnly"))
                .page(tag.getInt("Page"));
        if (tag.hasUUID("SellerId")) {
            builder.sellerId(tag.getUUID("SellerId"));
        }
        if (tag.hasUUID("LikedBy")) {
            builder.likedBy(tag.getUUID("LikedBy"));
        }
        if (tag.contains("Tags", Tag.TAG_LIST)) {
            builder.tags(readStrings(tag.getList("Tags", Tag.TAG_STRING), MarketLimits.MAX_TAGS_PER_LISTING));
        }
        builder.tagMatch(parseEnum(TagMatch.class, tag.getString("TagMatch"), TagMatch.ALL));
        if (tag.contains("MinimumPrice", Tag.TAG_ANY_NUMERIC)) {
            builder.minimumPrice(tag.getDouble("MinimumPrice"));
        }
        if (tag.contains("MaximumPrice", Tag.TAG_ANY_NUMERIC)) {
            builder.maximumPrice(tag.getDouble("MaximumPrice"));
        }
        if (tag.contains("SaleTypes", Tag.TAG_LIST)) {
            builder.saleTypes(readEnums(tag.getList("SaleTypes", Tag.TAG_STRING), Listing.SaleType.class));
        }
        if (tag.contains("Statuses", Tag.TAG_LIST)) {
            builder.statuses(readEnums(tag.getList("Statuses", Tag.TAG_STRING), Listing.Status.class));
        }
        builder.sortOrder(parseEnum(SortOrder.class, tag.getString("SortOrder"), SortOrder.UPDATED_DESC));
        if (tag.contains("PageSize", Tag.TAG_ANY_NUMERIC)) {
            builder.pageSize(tag.getInt("PageSize"));
        }
        return builder.build();
    }

    public static final class Builder {
        private String text = "";
        private String sellerText = "";
        private String itemText = "";
        private UUID sellerId;
        private UUID likedBy;
        private Collection<String> tags = Set.of();
        private TagMatch tagMatch = TagMatch.ALL;
        private Double minimumPrice;
        private Double maximumPrice;
        private EnumSet<Listing.SaleType> saleTypes = EnumSet.allOf(Listing.SaleType.class);
        private EnumSet<Listing.Status> statuses = EnumSet.of(Listing.Status.ACTIVE, Listing.Status.SOLD);
        private boolean availableOnly;
        private SortOrder sortOrder = SortOrder.UPDATED_DESC;
        private int page;
        private int pageSize = 30;

        private Builder() {
        }

        public Builder text(String value) {
            text = value;
            return this;
        }

        public Builder sellerText(String value) {
            sellerText = value;
            return this;
        }

        public Builder itemText(String value) {
            itemText = value;
            return this;
        }

        public Builder sellerId(UUID value) {
            sellerId = value;
            return this;
        }

        public Builder likedBy(UUID value) {
            likedBy = value;
            return this;
        }

        public Builder tags(Collection<String> values) {
            tags = values == null ? Set.of() : values;
            return this;
        }

        public Builder tagMatch(TagMatch value) {
            tagMatch = value;
            return this;
        }

        public Builder minimumPrice(Double value) {
            minimumPrice = value;
            return this;
        }

        public Builder maximumPrice(Double value) {
            maximumPrice = value;
            return this;
        }

        public Builder saleTypes(Collection<Listing.SaleType> values) {
            saleTypes = values == null || values.isEmpty() ? EnumSet.noneOf(Listing.SaleType.class) : EnumSet.copyOf(values);
            return this;
        }

        public Builder statuses(Collection<Listing.Status> values) {
            statuses = values == null || values.isEmpty() ? EnumSet.noneOf(Listing.Status.class) : EnumSet.copyOf(values);
            return this;
        }

        public Builder availableOnly(boolean value) {
            availableOnly = value;
            return this;
        }

        public Builder sortOrder(SortOrder value) {
            sortOrder = value;
            return this;
        }

        public Builder page(int value) {
            page = value;
            return this;
        }

        public Builder pageSize(int value) {
            pageSize = value;
            return this;
        }

        public MarketQuery build() {
            return new MarketQuery(this);
        }
    }

    private static Set<String> checkedTags(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = MarketTag.normalizeId(value);
                if (!normalized.isBlank()) {
                    result.add(normalized);
                }
                if (result.size() >= MarketLimits.MAX_TAGS_PER_LISTING) {
                    break;
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Double checkedOptionalPrice(Double value) {
        if (value == null) {
            return null;
        }
        return MarketLimits.requirePrice(value);
    }

    private static ListTag stringList(Collection<String> values) {
        ListTag list = new ListTag();
        values.forEach(value -> list.add(StringTag.valueOf(value)));
        return list;
    }

    private static ListTag enumList(Collection<? extends Enum<?>> values) {
        ListTag list = new ListTag();
        values.forEach(value -> list.add(StringTag.valueOf(value.name())));
        return list;
    }

    private static Collection<String> readStrings(ListTag list, int limit) {
        ArrayList<String> values = new ArrayList<>();
        for (int i = 0; i < Math.min(list.size(), limit); i++) {
            values.add(list.getString(i));
        }
        return values;
    }

    private static <E extends Enum<E>> Collection<E> readEnums(ListTag list, Class<E> type) {
        EnumSet<E> values = EnumSet.noneOf(type);
        for (int i = 0; i < Math.min(list.size(), type.getEnumConstants().length); i++) {
            try {
                values.add(Enum.valueOf(type, list.getString(i).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return values;
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
}
