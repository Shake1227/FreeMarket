package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MarketTag {
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    private final String id;
    private final String translationKey;
    private final String fallbackLabel;
    private final String parentId;
    private final int sortOrder;
    private final boolean enabled;

    public MarketTag(String id, String translationKey, String fallbackLabel, String parentId, int sortOrder, boolean enabled) {
        String checkedId = normalizeId(id);
        if (!VALID_ID.matcher(checkedId).matches()) {
            throw new IllegalArgumentException("id");
        }
        String checkedParent = normalizeId(parentId);
        if (!checkedParent.isEmpty() && (!VALID_ID.matcher(checkedParent).matches() || checkedParent.equals(checkedId))) {
            throw new IllegalArgumentException("parentId");
        }
        this.id = checkedId;
        this.translationKey = MarketLimits.bounded(translationKey, MarketLimits.MAX_TRANSLATION_KEY_LENGTH);
        this.fallbackLabel = MarketLimits.bounded(fallbackLabel, MarketLimits.MAX_TAG_LABEL_LENGTH);
        this.parentId = checkedParent;
        this.sortOrder = Math.max(-100_000, Math.min(100_000, sortOrder));
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public String getFallbackLabel() {
        return fallbackLabel;
    }

    public String getParentId() {
        return parentId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("TranslationKey", translationKey);
        tag.putString("FallbackLabel", fallbackLabel);
        tag.putString("ParentId", parentId);
        tag.putInt("SortOrder", sortOrder);
        tag.putBoolean("Enabled", enabled);
        return tag;
    }

    public static MarketTag fromTag(CompoundTag tag) {
        String id = tag.getString("Id");
        String translationKey = tag.getString("TranslationKey");
        String fallbackLabel = tag.getString("FallbackLabel");
        String parentId = tag.getString("ParentId");
        int sortOrder = tag.contains("SortOrder", Tag.TAG_ANY_NUMERIC) ? tag.getInt("SortOrder") : 0;
        boolean enabled = !tag.contains("Enabled", Tag.TAG_BYTE) || tag.getBoolean("Enabled");
        return new MarketTag(id, translationKey, fallbackLabel, parentId, sortOrder, enabled);
    }

    public static String normalizeId(String value) {
        return MarketLimits.bounded(value, MarketLimits.MAX_TAG_ID_LENGTH).toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof MarketTag other)) {
            return false;
        }
        return sortOrder == other.sortOrder && enabled == other.enabled && id.equals(other.id) && translationKey.equals(other.translationKey) && fallbackLabel.equals(other.fallbackLabel) && parentId.equals(other.parentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, translationKey, fallbackLabel, parentId, sortOrder, enabled);
    }
}
