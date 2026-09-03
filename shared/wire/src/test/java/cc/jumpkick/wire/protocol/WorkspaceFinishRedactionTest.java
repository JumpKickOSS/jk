// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Redacted;
import cc.jumpkick.config.SecretRedactor;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace terminal carries raw worker output to the user's terminal, the dashboard and the
 * on-disk journal, and {@code .env} values are secret by source. Three of the four engine verbs
 * that emit this event shipped it unmasked, because a {@code List<String>} parameter cannot tell a
 * caller that redaction is mandatory.
 *
 * <p>So the parameter is {@link Redacted}, and this class proves the two halves of that claim
 * <em>by compiling code</em>: raw text is rejected by {@code javac}, and {@link Redacted} has no
 * mint outside {@link SecretRedactor}. A comment asking the next editor to remember would not
 * survive the next editor.
 */
class WorkspaceFinishRedactionTest {

    private static final String SECRET = "jk-2387-must-not-leak-s3cret-token";

    /** javac's verdict plus everything it said, so a failing case can be shown to be the right failure. */
    private record Compilation(boolean ok, String diagnostics) {}

    private static Compilation compile(Path dir, String body) throws Exception {
        Path src = dir.resolve("Probe.java");
        Files.writeString(src, """
                import cc.jumpkick.config.Redacted;
                import cc.jumpkick.config.SecretRedactor;
                import cc.jumpkick.wire.protocol.ProtoEvents;
                import java.util.List;

                class Probe {
                    static String go() {
                        %s
                    }
                }
                """.formatted(body));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        StringWriter out = new StringWriter();
        Path classes = Files.createDirectories(dir.resolve("classes"));
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(
                            out,
                            files,
                            diagnostics,
                            List.of(
                                    "-classpath",
                                    System.getProperty("java.class.path"),
                                    "-d",
                                    classes.toString(),
                                    "-proc:none"),
                            null,
                            files.getJavaFileObjects(src))
                    .call();
            StringBuilder said = new StringBuilder(out.toString());
            diagnostics.getDiagnostics().forEach(d -> said.append(d.getMessage(null))
                    .append('\n'));
            return new Compilation(ok, said.toString());
        }
    }

    /**
     * Guards every assertion below: if the probe's classpath were broken, "does not compile" would
     * pass for the wrong reason. This is the same call the engine verbs make, and it must build.
     */
    @Test
    void the_redacted_form_compiles(@TempDir Path dir) throws Exception {
        Compilation c = compile(dir, """
                List<Redacted> errors = SecretRedactor.of(List.of("s3cret-value")).redactAll(List.of("boom"));
                return ProtoEvents.workspaceFinish(false, 1, errors, false);
                """);
        assertThat(c.ok())
                .as("control must compile, else the negative cases prove nothing: %s", c.diagnostics())
                .isTrue();
    }

    @Test
    void a_verb_that_forgets_to_redact_does_not_compile(@TempDir Path dir) throws Exception {
        Compilation c = compile(dir, """
                return ProtoEvents.workspaceFinish(false, 1, List.of("boom " + "%s"), false);
                """.formatted(SECRET));
        assertThat(c.ok())
                .as("raw worker text must not reach the workspace terminal")
                .isFalse();
        assertThat(c.diagnostics()).contains("Redacted").contains("incompatible types");
    }

    /** The mint is package-private, so the type cannot be forged to get past the parameter. */
    @Test
    void redacted_cannot_be_minted_outside_its_own_package(@TempDir Path dir) throws Exception {
        Compilation c = compile(dir, """
                return ProtoEvents.workspaceFinish(false, 1, List.of(new Redacted("boom")), false);
                """);
        assertThat(c.ok()).as("Redacted must have no reachable constructor").isFalse();
        // …and for the right reason: accessibility, not some unrelated slip in the probe.
        assertThat(c.diagnostics()).contains("Redacted").contains("cannot be accessed");
    }

    /** And the type is not merely decorative: what it carries really is masked. */
    @Test
    void the_emitted_line_carries_the_mask_and_not_the_secret() {
        List<Redacted> errors =
                SecretRedactor.of(List.of(SECRET)).redactAll(List.of("auth failed for " + SECRET, "and again"));

        String line = ProtoEvents.workspaceFinish(false, 1, errors, false);

        assertThat(line).doesNotContain(SECRET).contains(SecretRedactor.MASK);
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.WORKSPACE_FINISH);
    }
}
