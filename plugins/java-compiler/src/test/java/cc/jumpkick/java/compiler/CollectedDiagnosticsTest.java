// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The collector's list is live while it is drained: handing a diagnostic on can report another one
 * into the same collector, on the same thread, under the drain. Every diagnostic must reach the
 * sink, the late ones included, however many arrive and whichever delivery raises them.
 */
class CollectedDiagnosticsTest {

    /** An append lands under every delivery, the late diagnostics' own included. */
    @Test
    void a_diagnostic_reported_under_every_delivery_is_drained_in_turn() {
        CollectedDiagnostics diags = new CollectedDiagnostics();
        diags.report(diagnostic("first", m -> {}));
        diags.report(diagnostic("second", m -> {}));
        List<String> drained = new ArrayList<>();
        int[] late = {0};

        diags.drainTo(d -> {
            drained.add(d.getMessage(Locale.ROOT));
            if (late[0] < 3) diags.report(diagnostic("late-" + ++late[0], m -> {}));
        });

        assertThat(drained).containsExactly("first", "second", "late-1", "late-2", "late-3");
    }

    /**
     * The worker's path: Zinc's bridge renders each diagnostic, and rendering one reports another
     * (a source read back through the held file manager, a class symbol completed for the message).
     * The report happens inside the diagnostic's own message call, which is where the bridge is.
     */
    @Test
    void a_diagnostic_reported_while_the_keyed_reporter_renders_one_is_kept_with_its_key() {
        CollectedDiagnostics diags = new CollectedDiagnostics();
        diags.report(diagnostic("first", m -> diags.report(diagnostic("raised by first", m2 -> {}))));
        diags.report(diagnostic("second", m -> {}));
        CollectingReporter keyed = new CollectingReporter();

        diags.drainTo(keyed::report);

        assertThat(keyed.diagnostics())
                .extracting(ZincJavaCompiler.Diag::message)
                .containsExactly("first", "second", "raised by first");
        assertThat(keyed.diagnostics()).extracting(ZincJavaCompiler.Diag::key).containsOnly("compiler.err.fixture");
    }

    /** A fixture error diagnostic; {@code onMessage} runs every time its message is rendered. */
    private static Diagnostic<JavaFileObject> diagnostic(String message, Consumer<String> onMessage) {
        return new Diagnostic<>() {
            @Override
            public Kind getKind() {
                return Kind.ERROR;
            }

            @Override
            public @Nullable JavaFileObject getSource() {
                return null;
            }

            @Override
            public long getPosition() {
                return NOPOS;
            }

            @Override
            public long getStartPosition() {
                return NOPOS;
            }

            @Override
            public long getEndPosition() {
                return NOPOS;
            }

            @Override
            public long getLineNumber() {
                return NOPOS;
            }

            @Override
            public long getColumnNumber() {
                return NOPOS;
            }

            @Override
            public String getCode() {
                return "compiler.err.fixture";
            }

            @Override
            public String getMessage(Locale locale) {
                onMessage.accept(message);
                return message;
            }
        };
    }
}
