// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.Objects;

/** Outcome of a Groovy compilation. {@code output} is the worker's collected diagnostics. */
public record GroovycResult(boolean success, String output) {

    public GroovycResult {
        Objects.requireNonNull(output, "output");
    }
}
