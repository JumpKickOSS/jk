package org.apache.commons.lang3;

/** A character sequence quoted as the old reports printed it. */
final class CharSequenceLegacyQuoting {

    private CharSequenceLegacyQuoting() {
    }

    static String quote(final CharSequence cs) {
        return CharSequenceUtils.legacyQuote(cs);
    }

}
