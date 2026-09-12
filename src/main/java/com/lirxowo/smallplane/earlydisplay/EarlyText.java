package com.lirxowo.smallplane.earlydisplay;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EarlyText {
    static final String KEY_PRESS_ENTER = "smallplane.early.press_enter";
    static final String KEY_WINDOW_TITLE = "smallplane.early.window_title";
    static final String KEY_INITIALIZING = "smallplane.early.initializing";

    private static final Logger LOGGER = LoggerFactory.getLogger(EarlyText.class);
    private static final String RESOURCE_PATTERN = "/smallplane/lang/early_%s.properties";
    private static final String FALLBACK_LOCALE = "en_us";

    private final Properties entries;

    private EarlyText(Properties entries) {
        this.entries = entries;
    }

    static EarlyText forLocale(@Nullable String locale) {
        Properties entries = read(FALLBACK_LOCALE);
        if (locale != null && !locale.equalsIgnoreCase(FALLBACK_LOCALE)) {
            entries.putAll(read(locale));
        }
        return new EarlyText(entries);
    }

    String get(String key) {
        return entries.getProperty(key, key);
    }

    private static Properties read(String locale) {
        Properties properties = new Properties();
        String resource = RESOURCE_PATTERN.formatted(locale);
        try (InputStream stream = EarlyText.class.getResourceAsStream(resource)) {
            if (stream != null) {
                properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}", resource, e);
        }
        return properties;
    }
}
