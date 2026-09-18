// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

/**
 * The diagnostics of one compile, collected as javac and the held file manager report them and
 * handed on afterwards through {@link #drainTo}.
 *
 * <p>The list stays live while it is drained: the sink formats each diagnostic, and formatting one
 * can report another into this same collector — reading a source back through the held file
 * manager, or completing a class symbol the message names, goes through the compile's own
 * {@code Log}, whose listener is this. That arrival lands on the same thread, under the drain, so
 * the walk is by index and takes the late diagnostics in turn; an iterator would fail on the growth
 * and lose the whole compile to a bare exception.
 */
final class CollectedDiagnostics implements DiagnosticListener<JavaFileObject> {

    private final List<Diagnostic<? extends JavaFileObject>> collected = new ArrayList<>();

    @Override
    public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        collected.add(diagnostic);
    }

    /** True when any collected diagnostic is an error. */
    boolean hasErrors() {
        for (Diagnostic<? extends JavaFileObject> d : collected) {
            if (d.getKind() == Diagnostic.Kind.ERROR) return true;
        }
        return false;
    }

    /** Hand every collected diagnostic to {@code sink}, the ones reported while it works included. */
    void drainTo(DiagnosticListener<JavaFileObject> sink) {
        for (int i = 0; i < collected.size(); i++) sink.report(collected.get(i));
    }
}
