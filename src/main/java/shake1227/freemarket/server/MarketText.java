package shake1227.freemarket.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;

public final class MarketText {
    private MarketText() {
    }

    public static Component parseDisplayName(String input, Component fallback, int limit) {
        String value = clean(input, limit);
        if (value.isBlank()) {
            return fallback.copy();
        }
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder segment = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '&' && index + 1 < value.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(value.charAt(index + 1));
                if (formatting != null) {
                    if (!segment.isEmpty()) {
                        result.append(Component.literal(segment.toString()).setStyle(style));
                        segment.setLength(0);
                    }
                    style = formatting == ChatFormatting.RESET ? Style.EMPTY : style.applyFormat(formatting);
                    index++;
                    continue;
                }
            }
            if (current != '§') {
                segment.append(current);
            }
        }
        if (!segment.isEmpty()) {
            result.append(Component.literal(segment.toString()).setStyle(style));
        }
        return result.getString().isBlank() ? fallback.copy() : result;
    }

    public static String clean(String input, int limit) {
        if (input == null || limit <= 0) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(input.length(), limit));
        for (int index = 0; index < input.length() && result.length() < limit; index++) {
            char value = input.charAt(index);
            if (value == '\u0000' || value == '§' || Character.isISOControl(value) && value != '\n' && value != '\t') {
                continue;
            }
            result.append(value);
        }
        return result.toString().strip();
    }

    public static String cleanTag(String input) {
        String value = clean(input, 64).toLowerCase(Locale.ROOT);
        return value.replaceAll("[^a-z0-9_.-]", "");
    }
}

