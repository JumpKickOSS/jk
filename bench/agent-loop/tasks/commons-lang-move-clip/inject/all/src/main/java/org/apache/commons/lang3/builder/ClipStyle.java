package org.apache.commons.lang3.builder;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

/** Field values clipped for a one-line summary. */
final class ClipStyle {

    static final int WIDTH = 12;

    private static final BiFunction<String, Integer, String> CLIPPER = StringUtils::clip;

    private ClipStyle() {
    }

    static String value(final String value) {
        return StringUtils.clip(value, WIDTH);
    }

    static List<String> values(final List<String> values) {
        return values.stream().map(v -> CLIPPER.apply(v, WIDTH)).collect(Collectors.toList());
    }

}
