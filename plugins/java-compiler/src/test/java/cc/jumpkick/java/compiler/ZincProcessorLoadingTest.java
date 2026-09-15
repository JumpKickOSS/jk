// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Processor discovery: per-cycle instance freshness, and broken services entries fail cleanly. */
class ZincProcessorLoadingTest {

    @Test
    void two_loads_share_nothing(@TempDir Path tmp) throws Exception {
        Path procDir = writeNoopProcessor(tmp.resolve("proc"));
        try (URLClassLoader loader =
                ZincJavaCompiler.processorClassLoader(new URL[] {procDir.toUri().toURL()})) {
            List<Processor> first = ZincJavaCompiler.freshProcessors(loader);
            List<Processor> second = ZincJavaCompiler.freshProcessors(loader);
            assertThat(first).extracting(p -> p.getClass().getName()).containsExactly("noop.NoopProc");
            assertThat(second).extracting(p -> p.getClass().getName()).containsExactly("noop.NoopProc");
            // Defined by this loader — not a Processor SPI the worker classpath happens to carry.
            assertThat(first.getFirst().getClass().getClassLoader()).isSameAs(loader);
            // Same class (one loader for the whole compile), distinct instances (init is
            // single-shot, so each Zinc cycle's JavacTask needs its own set).
            assertThat(first.getFirst().getClass()).isSameAs(second.getFirst().getClass());
            assertThat(first.getFirst()).isNotSameAs(second.getFirst());
        }
    }

    @Test
    void a_broken_non_first_services_entry_fails_as_a_runtime_exception(@TempDir Path tmp) throws Exception {
        Path procDir = writeNoopProcessor(tmp.resolve("proc"));
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        // hasNext() would validate only noop.NoopProc; the missing second entry used to escape
        // mid-compile as a raw ServiceConfigurationError (an Error, past every catch).
        Files.writeString(services, "noop.NoopProc\nmissing.NoSuchProc\n");
        try (URLClassLoader loader =
                ZincJavaCompiler.processorClassLoader(new URL[] {procDir.toUri().toURL()})) {
            assertThatThrownBy(() -> ZincJavaCompiler.freshProcessors(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("annotation processor registration");
        }
    }

    /** A do-nothing processor, ServiceLoader-registered in {@code procDir}. */
    private static Path writeNoopProcessor(Path procDir) throws Exception {
        Files.createDirectories(procDir);
        Path src = procDir.resolve("noop/NoopProc.java");
        Files.createDirectories(src.getParent());
        for (Map.Entry<String, String> e : Map.of("noop/NoopProc.java", """
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
                """).entrySet()) {
            Files.writeString(procDir.resolve(e.getKey()), e.getValue());
        }
        FixtureJavac.compile(procDir, src);
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "noop.NoopProc\n");
        return procDir;
    }
}
