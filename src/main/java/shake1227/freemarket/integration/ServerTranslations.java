package shake1227.freemarket.integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import shake1227.freemarket.FreeMarket;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerTranslations {
    private static final Gson GSON = new Gson();
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private ServerTranslations() {
    }

    public static String translate(ServerPlayer player, String key, Object... arguments) {
        String language = player.getLanguage();
        String normalized = language == null ? "en_us" : language.toLowerCase(Locale.ROOT);
        Map<String, String> selected = CACHE.computeIfAbsent(normalized, ServerTranslations::load);
        String pattern = selected.get(key);
        if (pattern == null) {
            pattern = CACHE.computeIfAbsent("en_us", ServerTranslations::load).getOrDefault(key, key);
        }
        try {
            return String.format(Locale.ROOT, pattern.replace("%s", "%s"), arguments);
        } catch (RuntimeException ignored) {
            return pattern;
        }
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/" + FreeMarket.MODID + "/lang/" + language + ".json";
        try (InputStream stream = ServerTranslations.class.getResourceAsStream(path)) {
            if (stream == null) {
                return Collections.emptyMap();
            }
            Map<String, String> loaded = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() { }.getType());
            return loaded == null ? Collections.emptyMap() : Map.copyOf(loaded);
        } catch (Exception exception) {
            FreeMarket.LOGGER.warn("Unable to load language resource {}", language, exception);
            return Collections.emptyMap();
        }
    }
}

