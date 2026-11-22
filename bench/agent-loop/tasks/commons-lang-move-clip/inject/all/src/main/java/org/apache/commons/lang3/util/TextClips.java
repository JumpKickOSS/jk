package org.apache.commons.lang3.util;

/** Helpers for text cut to a width. */
public final class TextClips {

    /**
     * Tests whether a string fits in a width without being cut.
     *
     * @param str the string, may be null.
     * @param maxWidth the width.
     * @return whether it fits; null always fits.
     */
    public static boolean fits(final String str, final int maxWidth) {
        return str == null || str.length() <= maxWidth;
    }

    private TextClips() {
    }

}
