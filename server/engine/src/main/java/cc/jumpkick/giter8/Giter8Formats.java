// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.stringtemplate.v4.AttributeRenderer;

/** Giter8 {@code $name;format="Camel,lower"$} formatters. */
final class Giter8Formats implements AttributeRenderer<Object> {

    private static final char[] RAND = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    @Override
    public String toString(@Nullable Object value, String formatString, Locale locale) {
        String s = value == null ? "" : value.toString();
        if (formatString == null || formatString.isBlank()) return s;
        for (String part : formatString.split(",")) {
            s = one(s, part.strip());
        }
        return s;
    }

    static String one(String s, String format) {
        if (format.isEmpty()) return s;
        if (format.equals("Camel") || format.equalsIgnoreCase("upper-camel")) {
            return one(startCase(s), "word");
        }
        if (format.equals("camel") || format.equalsIgnoreCase("lower-camel")) {
            return decap(one(s, "Camel"));
        }
        String f = format.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "upper", "uppercase" -> s.toUpperCase(Locale.ROOT);
            case "lower", "lowercase" -> s.toLowerCase(Locale.ROOT);
            case "cap", "capitalize" -> cap(s);
            case "decap", "decapitalize" -> decap(s);
            case "start", "start-case" -> startCase(s);
            case "word", "word-only" -> s.replaceAll("[^A-Za-z0-9_]", "");
            case "space", "word-space" -> s.replaceAll("[^A-Za-z0-9]+", " ").strip();
            case "hyphen", "hyphenate" -> s.replace(' ', '-');
            case "norm", "normalize" -> normalize(s);
            case "snake", "snake-case" -> s.replace(' ', '_').replace('.', '_');
            case "dotreverse", "dot-reverse" -> dotReverse(s);
            case "package", "package-naming" -> s.replace(' ', '.');
            case "packaged", "package-dir" -> s.replace('.', '/');
            case "random", "generate-random" -> s + randomSuffix();
            default -> s;
        };
    }

    /** Lowercase, non-alphanumerics collapse to {@code -}, no leading/trailing dash. */
    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String cap(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decap(String s) {
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String startCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean up = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            } else {
                sb.append(c);
                up = true;
            }
        }
        return sb.toString();
    }

    private static String dotReverse(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length <= 1) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = parts.length - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String randomSuffix() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        char[] out = new char[6];
        for (int i = 0; i < out.length; i++) out[i] = RAND[r.nextInt(RAND.length)];
        return new String(out);
    }
}
