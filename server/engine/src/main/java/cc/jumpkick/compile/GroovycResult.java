// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.List;
import java.util.stream.Collectors;

/** Outcome of a Groovy compilation, one entry per worker diagnostic. */
public record GroovycResult(boolean success, List<CompileResult.Diagnostic> diagnostics) {

    public GroovycResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** Joined form for logs and exception messages. */
    public String output() {
        return diagnostics.stream().map(CompileResult.Diagnostic::describe).collect(Collectors.joining("\n"));
    }
}
