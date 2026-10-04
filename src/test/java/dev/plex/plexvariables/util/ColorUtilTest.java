package dev.plex.plexvariables.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorUtilTest {
    @Test
    void supportsLegacyAndBothHexFormats() {
        assertEquals(Component.text("Green", NamedTextColor.GREEN), ColorUtil.component("&aGreen"));
        assertEquals(Component.text("Hex", TextColor.color(0x12abef)), ColorUtil.component("&#12abefHex"));
        assertEquals(Component.text("Hex", TextColor.color(0x12abef)), ColorUtil.component("&x&1&2&a&b&e&fHex"));
    }

    @Test
    void preservesExistingSectionCodesAndLiteralAmpersands() {
        var result = ColorUtil.component("§aGreen & plain");
        assertEquals("Green & plain", PlainTextComponentSerializer.plainText().serialize(result));
        assertEquals("§aGreen & plain", ColorUtil.legacy("§aGreen & plain"));
    }

    @Test
    void serializesHexForLegacyPlaceholderConsumers() {
        assertEquals("§x§1§2§a§b§e§fHex", ColorUtil.legacy("&#12abefHex"));
    }

    @Test
    void preservesStandaloneAndTrailingFormattingForPlaceholderComposition() {
        assertEquals("§a", ColorUtil.legacy("&a"));
        assertEquals("§7[§aTest§7] §f", ColorUtil.legacy("&7[&aTest&7] &f"));
        assertEquals("text§r", ColorUtil.legacy("text&r"));
        assertEquals("§x§1§2§a§b§e§f", ColorUtil.legacy("&#12abef"));
        assertEquals("§x§1§2§a§b§e§f", ColorUtil.legacy("&x&1&2&a&b&e&f"));
    }
}
