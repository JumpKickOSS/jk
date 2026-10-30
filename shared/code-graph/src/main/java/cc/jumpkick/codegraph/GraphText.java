// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text folding shared by ids and search, so both tokenize alike. */
final class GraphText {

    private GraphText() {}

    /** NFKD with combining marks removed. */
    static String stripDiacritics(String text) {
        String nfkd = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(nfkd.length());
        nfkd.codePoints().filter(cp -> !isCombining(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Diacritics stripped, lower-cased: a node's {@code norm_label}. */
    static String fold(String text) {
        return stripDiacritics(text).toLowerCase(Locale.ROOT);
    }

    /** Python's {@code str.casefold} for everything but the Turkic dotless i. */
    static String casefold(String text) {
        return text.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /** A Python {@code \w} code point: letter, digit, other numeric, or underscore. */
    static boolean isWord(int cp) {
        if (cp == '_' || Character.isLetterOrDigit(cp)) return true;
        int type = Character.getType(cp);
        return type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    /** Folded word tokens; {@code _} separates like any punctuation. */
    static List<String> searchTokens(String text) {
        String folded = fold(text);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        folded.codePoints().forEach(cp -> {
            if (cp != '_' && isWord(cp)) {
                cur.appendCodePoint(cp);
            } else if (!cur.isEmpty()) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        });
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static boolean isCombining(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}
