package dev.plex.plexvariables.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageUtilTest {
    @Test
    void prefixIsExplicitAndMessagesCanHaveMultipleLines() {
        var config = new YamlConfiguration();
        config.set("prefix", "&dBrand ");
        config.set("messages.test", List.of("Header", "%prefix%Value %result%"));
        var messages = MessageUtil.from(config);
        var lines = messages.render("test", Map.of("result", "42"));
        assertEquals(List.of("Header", "Brand Value 42"), lines.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }

    @Test
    void parameterValuesAreNeverInterpretedAsOtherParameters() {
        var config = new YamlConfiguration();
        config.set("prefix", "Brand ");
        config.set("messages.test", "%result% / %player% / %unknown%");
        var message = MessageUtil.from(config).render("test",
                Map.of("result", "%player% %prefix%", "player", "Alex")).getFirst();
        assertEquals("%player% %prefix% / Alex / %unknown%",
                PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Test
    void missingKeysUseBundledDefaultsAndBlankMessagesCanBeSuppressed() {
        var config = new YamlConfiguration();
        config.set("messages.no-permission", "");
        var messages = MessageUtil.from(config);
        assertTrue(messages.render("no-permission", Map.of()).isEmpty());
        assertFalse(messages.render("help", Map.of()).isEmpty());
    }

    @Test
    void rejectsMalformedTemplatesInsteadOfSilentlyDiscardingThem() {
        var config = new YamlConfiguration();
        config.set("messages.help", List.of("ok", 42));
        assertThrows(IllegalArgumentException.class, () -> MessageUtil.from(config));
        config.set("messages.help", Map.of("invalid", "structure"));
        assertThrows(IllegalArgumentException.class, () -> MessageUtil.from(config));
    }

    @Test
    void templatesAreDetachedFromMutableConfiguration() {
        var config = new YamlConfiguration();
        config.set("messages.test", "original");
        var messages = MessageUtil.from(config);
        config.set("messages.test", "changed");
        assertEquals("original", PlainTextComponentSerializer.plainText()
                .serialize(messages.render("test", Map.of()).getFirst()));
    }
}
