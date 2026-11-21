// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compile that turns processing on with {@code -proc:full} and names no processor path runs the
 * processors javac finds on its compile classpath. What they generate is recorded, and a change to
 * them is seen, as for processors on a processor path.
 */
class ClasspathProcessorTest {

    /**
     * The sweep before an incremental compile keeps only the classes the analysis names and those
     * the generated-file ledger accounts for; an unrecorded generated class would go, and no
     * processor runs over the untouched source that would write it back.
     */
    @Test
    void a_class_a_classpath_processor_generated_survives_an_edit_to_another_source(@TempDir Path dir)
            throws Exception {
        Path procDir = dir.resolve("proc");
        JavaIncrementalCompilerTest.writeGenProcessor(procDir, "");
        Path widget = dir.resolve("src/app/Widget.java");
        Path other = dir.resolve("src/app/Other.java");
        Files.createDirectories(widget.getParent());
        Files.writeString(widget, "package app; @gen.Gen public class Widget {}");
        Files.writeString(other, "package app; public class Other {}");
        Path classOut = dir.resolve("target/classes");
        Path genOut = dir.resolve("target/generated-sources/annotations");
        Path workdir = dir.resolve("zinc-work");

        assertThat(compile(dir, procDir, classOut, genOut, workdir, widget, other))
                .contains("\"t\":\"result\",\"status\":\"OK\"");
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
        assertThat(GeneratedProvenance.ledgerFor(classOut)).content().contains("WidgetGen.java");

        Files.writeString(other, "package app; public class Other { int n() { return 1; } }");
        String out = compile(dir, procDir, classOut, genOut, workdir, widget, other);
        assertThat(out).contains("\"t\":\"result\",\"status\":\"OK\"").doesNotContain("Widget.java");
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
    }

    /**
     * The processor sits on the classpath as a sibling's classes directory and is rebuilt in place:
     * nothing the consumer's sources depend on moved, so only the processors' own digest tells the
     * next compile to run them again.
     */
    @Test
    void an_edit_to_a_classpath_processor_regenerates_its_consumers(@TempDir Path dir) throws Exception {
        Path procDir = dir.resolve("proc");
        JavaIncrementalCompilerTest.writeGenProcessor(procDir, "public int version() { return 1; }");
        Path widget = dir.resolve("src/app/Widget.java");
        Files.createDirectories(widget.getParent());
        Files.writeString(widget, "package app; @gen.Gen public class Widget {}");
        Path classOut = dir.resolve("target/classes");
        Path genOut = dir.resolve("target/generated-sources/annotations");
        Path workdir = dir.resolve("zinc-work");
        assertThat(compile(dir, procDir, classOut, genOut, workdir, widget))
                .contains("\"t\":\"result\",\"status\":\"OK\"");
        Path generated = genOut.resolve("app/WidgetGen.java");
        assertThat(generated).content().contains("return 1;");

        JavaIncrementalCompilerTest.writeGenProcessor(procDir, "public int version() { return 2; }");

        assertThat(compile(dir, procDir, classOut, genOut, workdir, widget))
                .contains("\"t\":\"result\",\"status\":\"OK\"");
        assertThat(generated).content().contains("return 2;");
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
    }

    /** One compile with {@code procDir} on the compile classpath only and {@code -proc:full}; its JSONL. */
    private static String compile(Path dir, Path procDir, Path classOut, Path genOut, Path workdir, Path... sources)
            throws Exception {
        SpecWriter spec = new SpecWriter()
                .op(PluginProtocol.OP_COMPILE, null, "jk-java-compiler")
                .configInt("release", 21)
                .layout(Map.of("classesDir", classOut, "sourceOutput", genOut, "workdir", workdir));
        for (Path s : sources) spec = spec.source(s.toAbsolutePath());
        spec = spec.cp(procDir, PluginProtocol.ROLE_COMPILE).arg("-proc:full");
        Path specFile = Files.createTempFile(dir, "spec", ".txt");
        Files.write(specFile, spec.lines());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JavaIncrementalCompiler.compileSpec(
                specFile, new ProtocolWriter(new PrintStream(buf, true, StandardCharsets.UTF_8), "##JKJC:"));
        return buf.toString(StandardCharsets.UTF_8);
    }
}
