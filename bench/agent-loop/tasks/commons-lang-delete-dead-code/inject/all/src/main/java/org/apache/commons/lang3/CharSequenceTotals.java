package org.apache.commons.lang3;

import java.util.List;

/** The total length of several character sequences, a null one counting as empty. */
final class CharSequenceTotals {

    private CharSequenceTotals() {
    }

    static int total(final List<? extends CharSequence> parts) {
        return parts.stream().mapToInt(CharSequenceUtils::length).sum();
    }

}
