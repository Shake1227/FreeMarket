package shake1227.freemarket.integration;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import shake1227.freemarket.FreeMarket;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ModernNotificationBridge {
    public enum Placement {
        TOP,
        LEFT
    }

    public enum Category {
        SUCCESS,
        WARNING,
        FAILURE,
        SYSTEM
    }

    private static final Pattern LEGACY_CONTROL = Pattern.compile("(?i)&[0-9a-fk-oru]");
    private static volatile ReflectionApi api;
    private static volatile boolean resolutionAttempted;

    private ModernNotificationBridge() {
    }

    public static void notify(ServerPlayer player, Placement placement, Category category, String titleKey, String messageKey, Object... rawArguments) {
        Object[] arguments = sanitizeArguments(rawArguments);
        if (ModList.get().isLoaded("modernnotification") && invoke(player, placement, category, titleKey, messageKey, arguments)) {
            return;
        }
        ChatFormatting accent = switch (category) {
            case SUCCESS -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.GOLD;
            case FAILURE -> ChatFormatting.RED;
            case SYSTEM -> ChatFormatting.AQUA;
        };
        String title = ServerTranslations.translate(player, titleKey).replace("&u", "\n");
        String message = ServerTranslations.translate(player, messageKey, arguments).replace("&u", "\n");
        player.sendSystemMessage(Component.literal("╔═[ ").withStyle(ChatFormatting.DARK_GREEN).append(Component.literal("FreeMarket :: TRADE").withStyle(accent, ChatFormatting.BOLD)).append(Component.literal(" ]════════").withStyle(ChatFormatting.DARK_GREEN)));
        MutableComponent body = Component.literal("╠ ").withStyle(ChatFormatting.DARK_GREEN).append(Component.literal(title).withStyle(accent, ChatFormatting.BOLD)).append(Component.literal("  »  ").withStyle(ChatFormatting.DARK_GRAY)).append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(body);
        player.sendSystemMessage(Component.literal("╚════════════════════════════").withStyle(ChatFormatting.DARK_GREEN));
    }

    private static boolean invoke(ServerPlayer player, Placement placement, Category category, String titleKey, String messageKey, Object[] arguments) {
        try {
            ReflectionApi resolved = resolve();
            if (resolved == null) {
                return false;
            }
            String title = wrap(ServerTranslations.translate(player, titleKey), 25);
            String message = wrap(ServerTranslations.translate(player, messageKey, arguments), 25);
            Object categoryValue = Enum.valueOf(resolved.categoryClass.asSubclass(Enum.class), category.name());
            if (placement == Placement.TOP) {
                resolved.top.invoke(null, player, categoryValue, title, message, 6);
            } else {
                resolved.left.invoke(null, player, categoryValue, message, 5);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            FreeMarket.LOGGER.warn("ModernNotification integration failed", exception);
            api = null;
            resolutionAttempted = true;
            return false;
        }
    }

    private static ReflectionApi resolve() throws ReflectiveOperationException {
        ReflectionApi resolved = api;
        if (resolved != null) {
            return resolved;
        }
        if (resolutionAttempted) {
            return null;
        }
        synchronized (ModernNotificationBridge.class) {
            if (api != null) {
                return api;
            }
            resolutionAttempted = true;
            Class<?> apiClass = Class.forName("shake1227.modernnotification.api.ModernNotificationAPI");
            Class<?> categoryClass = Class.forName("shake1227.modernnotification.core.NotificationCategory");
            Method top = apiClass.getMethod("sendTopRightNotification", ServerPlayer.class, categoryClass, String.class, String.class, int.class);
            Method left = apiClass.getMethod("sendLeftNotification", ServerPlayer.class, categoryClass, String.class, int.class);
            api = new ReflectionApi(categoryClass, top, left);
            return api;
        }
    }

    private static Object[] sanitizeArguments(Object[] rawArguments) {
        if (rawArguments == null || rawArguments.length == 0) {
            return new Object[0];
        }
        Object[] safe = new Object[rawArguments.length];
        for (int index = 0; index < rawArguments.length; index++) {
            Object value = rawArguments[index];
            safe[index] = value instanceof String text ? LEGACY_CONTROL.matcher(text).replaceAll("") : value;
        }
        return safe;
    }

    static String wrap(String input, int limit) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int visible = 0;
        int preferredBreak = -1;
        for (int index = 0; index < input.length();) {
            char current = input.charAt(index);
            if (current == '&' && index + 1 < input.length() && isLegacyCode(input.charAt(index + 1))) {
                line.append(current).append(input.charAt(index + 1));
                index += 2;
                continue;
            }
            line.append(current);
            visible++;
            if (Character.isWhitespace(current) || "、。,.!?！？・/".indexOf(current) >= 0) {
                preferredBreak = line.length();
            }
            index++;
            if (visible >= limit && index < input.length()) {
                int splitAt = preferredBreak > 0 && visible >= limit - 7 ? preferredBreak : line.length();
                String head = line.substring(0, splitAt).stripTrailing();
                parts.add(head);
                String tail = line.substring(splitAt).stripLeading();
                line.setLength(0);
                line.append(tail);
                visible = visibleLength(tail);
                preferredBreak = -1;
            }
        }
        if (!line.isEmpty()) {
            parts.add(line.toString());
        }
        return String.join("&u", parts);
    }

    private static int visibleLength(String text) {
        return LEGACY_CONTROL.matcher(text).replaceAll("").length();
    }

    private static boolean isLegacyCode(char code) {
        char normalized = Character.toLowerCase(code);
        return (normalized >= '0' && normalized <= '9') || (normalized >= 'a' && normalized <= 'f') || "klmnoru".indexOf(normalized) >= 0;
    }

    private record ReflectionApi(Class<?> categoryClass, Method top, Method left) {
    }
}
