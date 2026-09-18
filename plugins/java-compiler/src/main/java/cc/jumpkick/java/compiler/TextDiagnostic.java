// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.jspecify.annotations.Nullable;

/**
 * A diagnostic made from text javac wrote to its output writer rather than reported: no source, no
 * position, no key. What a compile that failed without a diagnostic has to show for itself.
 */
record TextDiagnostic(Kind kind, String message) implements Diagnostic<JavaFileObject> {

    @Override
    public Kind getKind() {
        return kind;
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
    public @Nullable String getCode() {
        return null;
    }

    @Override
    public String getMessage(@Nullable Locale locale) {
        return message;
    }
}
