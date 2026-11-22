package org.apache.commons.lang3;

import static org.apache.commons.lang3.StringUtils.CLIP_MARKER;
import static org.apache.commons.lang3.StringUtils.clip;

/** One report line, clipped with {@link StringUtils#clip(String, int)}. */
final class ClipReport {

    private ClipReport() {
    }

    static String line(final String text, final int width) {
        final String clipped = clip(text, width);
        return clipped != null && clipped.endsWith(CLIP_MARKER) ? clipped + " (clipped)" : clipped;
    }

}
