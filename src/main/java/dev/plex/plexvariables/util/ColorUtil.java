package dev.plex.plexvariables.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ColorUtil {
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private ColorUtil() {
    }

    public static Component component(String text) {
        return SECTION.deserialize(legacy(text));
    }

    public static String legacy(String text) {
        if (text.indexOf('&') < 0 && text.indexOf('§') < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if ((current == '&' || current == '§') && index + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(index + 1));
                if (code == '#' && index + 7 < text.length() && isHex(text, index + 2, 6)) {
                    result.append("§x");
                    for (int digit = index + 2; digit < index + 8; digit++) {
                        result.append('§').append(Character.toLowerCase(text.charAt(digit)));
                    }
                    index += 7;
                    continue;
                }
                if (code == 'x' && hasExpandedHex(text, index)) {
                    result.append("§x");
                    for (int digit = index + 3; digit < index + 14; digit += 2) {
                        result.append('§').append(Character.toLowerCase(text.charAt(digit)));
                    }
                    index += 13;
                    continue;
                }
                if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
                    result.append('§').append(code);
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private static boolean isHex(String text, int start, int length) {
        for (int index = start; index < start + length; index++) {
            char character = Character.toLowerCase(text.charAt(index));
            if ("0123456789abcdef".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasExpandedHex(String text, int start) {
        if (start + 13 >= text.length()) {
            return false;
        }
        for (int index = start + 2; index < start + 14; index += 2) {
            char marker = text.charAt(index);
            if ((marker != '&' && marker != '§') || !isHex(text, index + 1, 1)) {
                return false;
            }
        }
        return true;
    }
}
