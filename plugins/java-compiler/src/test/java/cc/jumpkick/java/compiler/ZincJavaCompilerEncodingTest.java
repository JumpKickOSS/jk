// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sbt.internal.inc.FileAnalysisStore;
import xsbti.compile.AnalysisContents;

/**
 * The charset a build decodes its sources with has to be jk's choice, not the host's: javac falls
 * back to {@link Charset#defaultCharset()}, and nothing downstream can tell UTF-8-decoded bytecode
 * from Latin-1-decoded bytecode. Two compilers reach javac from this worker, Zinc's local one and
 * {@code ProvenanceJavac} when a processor path is present, and each pins the charset for itself; a
 * fork is the only way to watch them, since a JVM's default charset is fixed at startup.
 *
 * <p>Host-independence already held before the pin was spelled out — Zinc's own file objects and
 * {@code ProvenanceJavac}'s file manager each reached UTF-8 through their library's default — so
 * {@link #class_bytes_do_not_depend_on_the_hosts_default_charset} guards a property rather than
 * proving a repair. It has teeth all the same: point either pin at the default charset and it goes
 * red. {@link #the_recorded_javac_invocation_pins_utf8} is the one that covers the new behaviour.
 */
class ZincJavaCompilerEncodingTest {

    /**
     * Written as unicode escapes so this file stays ASCII and its own compile encoding cannot
     * skew the fixture. javac resolves the escapes before lexing, so the string that reaches disk
     * holds the real characters, and its UTF-8 bytes are what the compile under test must decode.
     */
    private static final String SOURCE = "package a;\n"
            + "public class A { public static final String S = \"caf\u00e9 \u00fcber \u4e2d\u6587\"; }\n";

    @Test
    void the_recorded_javac_invocation_pins_utf8(@TempDir Path dir) throws Exception {
        Path workdir = dir.resolve("zinc-work");
        compileFixture(dir.resolve("src"), dir.resolve("classes"), workdir, List.of());

        // Zinc persists the exact option array it handed javac; read that back rather than trust
        // the builder, since this is what the next compile diffs against.
        Optional<AnalysisContents> stored =
                FileAnalysisStore.binary(workdir.resolve("zinc").toFile()).get();
        assertThat(stored).isPresent();
        assertThat(stored.get().getMiniSetup().options().javacOptions()).containsSequence("-encoding", "UTF-8");
    }

    @Test
    void class_bytes_do_not_depend_on_the_hosts_default_charset(@TempDir Path dir) throws Exception {
        // A fixture that decodes the same either way would make every comparison below vacuous —
        // which is exactly what happens if the escapes in SOURCE ever reach javac unresolved.
        assertThat(new String(SOURCE.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1))
                .as("fixture must be charset-sensitive")
                .isNotEqualTo(SOURCE);

        Path procDir = writeNoopProcessor(dir.resolve("proc"));
        Run utf8 = compileInJvmWhoseDefaultCharsetIs("UTF-8", dir.resolve("utf8"), procDir);
        Run latin1 = compileInJvmWhoseDefaultCharsetIs("ISO-8859-1", dir.resolve("latin1"), procDir);

        // Without this the comparison below is vacuous: if -Dfile.encoding ever stopped moving
        // Charset.defaultCharset() the two JVMs would be the same host and would agree for free.
        assertThat(utf8.defaultCharset()).isEqualTo("UTF-8");
        assertThat(latin1.defaultCharset()).isEqualTo("ISO-8859-1");

        assertThat(latin1.plain()).as("Zinc's local javac is host-independent").isEqualTo(utf8.plain());
        assertThat(latin1.withProcessors())
                .as("the annotation-processing javac is host-independent")
                .isEqualTo(utf8.withProcessors());
        assertThat(utf8.withProcessors())
                .as("both compile paths decode the source the same way")
                .isEqualTo(utf8.plain());
    }

    /** What one forked JVM produced: its default charset, and the class file from each path. */
    private record Run(String defaultCharset, byte[] plain, byte[] withProcessors) {}

    private static Run compileInJvmWhoseDefaultCharsetIs(String charset, Path dir, Path procDir) throws Exception {
        Files.createDirectories(dir);
        List<String> cmd = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=" + charset,
                "-cp",
                System.getProperty("java.class.path"),
                Fork.class.getName(),
                dir.toString(),
                procDir.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(5, TimeUnit.MINUTES))
                .as("fork did not exit:%n%s", out)
                .isTrue();
        assertThat(p.exitValue()).as("fork failed:%n%s", out).isZero();

        String observed = null;
        for (String line : out.split("\n")) {
            if (line.startsWith(Fork.CHARSET_LINE))
                observed = line.substring(Fork.CHARSET_LINE.length()).trim();
        }
        String reported = Objects.requireNonNull(observed, () -> "fork never reported its charset:\n" + out);
        return new Run(
                reported,
                Files.readAllBytes(dir.resolve("plain/a/A.class")),
                Files.readAllBytes(dir.resolve("ap/a/A.class")));
    }

    private static void compileFixture(Path srcDir, Path classes, Path workdir, List<Path> processorPath)
            throws IOException {
        Path src = srcDir.resolve("a/A.java");
        Files.createDirectories(src.getParent());
        Files.write(src, SOURCE.getBytes(StandardCharsets.UTF_8));
        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(
                new JavaCompileJob(List.of(src), List.of(), classes, workdir, null, 25, List.of(), processorPath));
        if (!r.success()) throw new IllegalStateException("fixture compile failed: " + r.diagnostics());
    }

    /** A do-nothing processor, ServiceLoader-registered in {@code procDir}, to force the AP path. */
    private static Path writeNoopProcessor(Path procDir) throws Exception {
        Files.createDirectories(procDir);
        Path src = procDir.resolve("noop/NoopProc.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package noop;
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import java.util.Set;
                @SupportedAnnotationTypes("noop.None")
                public class NoopProc extends AbstractProcessor {
                    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) { return false; }
                }
                """);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", procDir.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("fixture javac failed, rc=" + rc);
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "noop.NoopProc\n");
        return procDir;
    }

    /**
     * Runs in a JVM whose {@code -Dfile.encoding} the parent chose — the only way to observe what a
     * differently-configured host would produce, since {@link Charset#defaultCharset()} is fixed at
     * startup. Compiles the fixture down both paths and reports the charset it actually ran under.
     */
    public static final class Fork {

        static final String CHARSET_LINE = "JK-DEFAULT-CHARSET=";

        private Fork() {}

        public static void main(String[] args) throws Exception {
            Path dir = Path.of(args[0]);
            compileFixture(dir.resolve("src"), dir.resolve("plain"), dir.resolve("plain-work"), List.of());
            compileFixture(dir.resolve("src"), dir.resolve("ap"), dir.resolve("ap-work"), List.of(Path.of(args[1])));
            System.out.println(CHARSET_LINE + Charset.defaultCharset().name());
        }
    }
}
