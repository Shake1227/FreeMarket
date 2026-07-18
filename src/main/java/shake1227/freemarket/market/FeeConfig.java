package shake1227.freemarket.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Locale;
import java.util.Objects;

public final class FeeConfig {
    public enum Mode {
        FIXED,
        PERCENT
    }

    private final Mode mode;
    private final double amount;

    public FeeConfig(Mode mode, double amount) {
        this.mode = Objects.requireNonNull(mode, "mode");
        double checked = MarketLimits.requirePrice(amount);
        if (mode == Mode.PERCENT && checked > 100D) {
            throw new IllegalArgumentException("percent");
        }
        this.amount = checked;
    }

    public static FeeConfig none() {
        return new FeeConfig(Mode.PERCENT, 0D);
    }

    public Mode getMode() {
        return mode;
    }

    public double getAmount() {
        return amount;
    }

    public double calculate(double grossAmount) {
        double gross = MarketLimits.requirePrice(grossAmount);
        double fee = mode == Mode.FIXED ? amount : gross * amount / 100D;
        if (!Double.isFinite(fee)) {
            return gross;
        }
        return Math.min(gross, Math.max(0D, fee));
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", mode.name());
        tag.putDouble("Amount", amount);
        return tag;
    }

    public static FeeConfig fromTag(CompoundTag tag) {
        Mode mode = Mode.PERCENT;
        if (tag.contains("Mode", Tag.TAG_STRING)) {
            try {
                mode = Mode.valueOf(tag.getString("Mode").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                mode = Mode.PERCENT;
            }
        }
        double amount = tag.contains("Amount", Tag.TAG_ANY_NUMERIC) ? tag.getDouble("Amount") : 0D;
        if (!Double.isFinite(amount) || amount < 0D) {
            amount = 0D;
        }
        if (mode == Mode.PERCENT) {
            amount = Math.min(100D, amount);
        } else {
            amount = Math.min(MarketLimits.MAX_PRICE, amount);
        }
        return new FeeConfig(mode, amount);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof FeeConfig other)) {
            return false;
        }
        return mode == other.mode && Double.compare(amount, other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, amount);
    }
}
