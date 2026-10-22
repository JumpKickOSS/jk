// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.SymbolFinder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryApiTest {

    /** A small library compiled with parameter names, packed into a jar. */
    private static Path jar(Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src/org/lib"));
        Files.writeString(src.resolve("Mapper.java"), """
                package org.lib;

                import java.io.IOException;
                import java.util.List;
                import java.util.Map;

                public class Mapper implements java.io.Serializable {
                    public static final int VERSION = 3;

                    public Mapper() {}

                    public <T> T readValue(String json, Class<T> type) throws IOException {
                        return null;
                    }

                    public <T> T readValue(byte[] json, Class<T> type) throws IOException {
                        return null;
                    }

                    public Map<String, List<? extends Number>> tree(String... paths) {
                        return null;
                    }

                    protected void hook(long millis, int times) {}

                    void hidden() {}

                    public interface Feature {
                        boolean enabledByDefault();
                    }

                    private static class Secret {}
                }
                """);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-parameters", "-d", classes.toString()));
        args.add(src.resolve("Mapper.java").toString());
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).isZero();
        Path jar = dir.resolve("lib-1.0.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream j = new JarOutputStream(out);
                Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                j.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                j.write(Files.readAllBytes(f));
                j.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void aLockedTypeReadsAsItsPublicApiWithGenericsAndParameterNames(@TempDir Path dir) throws IOException {
        LibraryApi api = new LibraryApi(List.of(new LibraryApi.Jar("org.lib:lib:1.0", jar(dir))), dir.resolve("cache"));
        String out = api.describe("Mapper");
        assertThat(out)
                .startsWith(
                        "== org.lib.Mapper  (org.lib:lib:1.0, library)\npublic class Mapper implements Serializable")
                .contains("  public static final int VERSION")
                .contains("  public Mapper()")
                .contains("  public <T> T readValue(String json, Class<T> type) throws IOException")
                .contains("  public Map<String, List<? extends Number>> tree(String... paths)")
                .contains("  protected void hook(long millis, int times)")
                .doesNotContain("hidden")
                .doesNotContain("Secret");
        assertThat(api.describe("org.lib.Mapper.readValue"))
                .contains("readValue(byte[] json, Class<T> type)")
                .doesNotContain("tree(");
        assertThat(api.describe("Mapper.Feature"))
                .contains("public static interface Feature")
                .contains("enabledByDefault()");
        assertThat(api.describe("NoSuchType")).isNull();
        assertThat(Files.list(dir.resolve("cache"))).isNotEmpty();
    }

    @Test
    void typesAreFoundByNameGlobAndKind(@TempDir Path dir) throws IOException {
        LibraryApi api = new LibraryApi(List.of(new LibraryApi.Jar("org.lib:lib:1.0", jar(dir))), null);
        assertThat(api.find(SymbolFinder.namePattern("*"), List.of("interface")))
                .containsExactly("interface  org.lib.Mapper.Feature  (org.lib:lib:1.0)");
        assertThat(api.find(SymbolFinder.namePattern("map*"), List.of()))
                .containsExactly("class  org.lib.Mapper  (org.lib:lib:1.0)");
    }
}
