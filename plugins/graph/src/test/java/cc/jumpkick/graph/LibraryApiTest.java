// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.SymbolFinder;
import cc.jumpkick.codegraph.SymbolReader.View;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

                /** Maps JSON text to objects. */
                public class Mapper implements java.io.Serializable {
                    public static final int VERSION = 3;

                    public Mapper() {}

                    /**
                     * Reads {@code json} as a {@code type}.
                     *
                     * @throws IOException when the text is not JSON
                     */
                    @Deprecated
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

    /** The sources jar of {@link #jar}: the one source file at its package path. */
    private static Path sourcesJar(Path dir) throws IOException {
        Path jar = dir.resolve("lib-1.0-sources.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("org/lib/Mapper.java"));
            j.write(Files.readAllBytes(dir.resolve("src/org/lib/Mapper.java")));
            j.closeEntry();
        }
        return jar;
    }

    private static LibraryApi withSources(Path dir) throws IOException {
        Path classes = jar(dir);
        return new LibraryApi(List.of(new LibraryApi.Jar("org.lib:lib:1.0", classes, sourcesJar(dir))), null);
    }

    @Test
    void aLockedTypeReadsAsItsPublicApiWithGenericsAndParameterNames(@TempDir Path dir) throws IOException {
        LibraryApi api = new LibraryApi(List.of(new LibraryApi.Jar("org.lib:lib:1.0", jar(dir))), dir.resolve("cache"));
        String out = api.read("Mapper", null, 2000);
        assertThat(out)
                .startsWith("[i] org.lib:lib:1.0 is the locked version; no sources jar on this machine")
                .contains("signatures from the class file, without Javadoc.")
                .contains("== org.lib.Mapper  (org.lib:lib:1.0, library)\npublic class Mapper implements Serializable")
                .contains("  public static final int VERSION")
                .contains("  public Mapper()")
                .contains("  public <T> T readValue(String json, Class<T> type) throws IOException")
                .contains("  public Map<String, List<? extends Number>> tree(String... paths)")
                .contains("  protected void hook(long millis, int times)")
                .doesNotContain("hidden")
                .doesNotContain("Secret");
        assertThat(api.read("org.lib.Mapper.readValue", null, 2000))
                .contains("readValue(byte[] json, Class<T> type)")
                .doesNotContain("tree(");
        assertThat(api.read("Mapper.Feature", null, 2000))
                .contains("public static interface Feature")
                .contains("enabledByDefault()");
        assertThat(api.read("NoSuchType", null, 2000)).isNull();
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

    @Test
    void aMemberReadsFromTheSourcesJarWithItsJavadocAndTheLockedVersion(@TempDir Path dir) throws IOException {
        String out = withSources(dir).read("Mapper.readValue", null, 2000);
        assertThat(out)
                .startsWith("[i] org.lib:lib:1.0 is the locked version; source and Javadoc from its sources jar.")
                .contains("== Mapper.readValue  org/lib/Mapper.java:")
                .contains("|     /**")
                .contains("|      * Reads {@code json} as a {@code type}.")
                .contains("|     @Deprecated")
                .contains("|     public <T> T readValue(String json, Class<T> type) throws IOException {")
                .contains("|     public <T> T readValue(byte[] json, Class<T> type) throws IOException {")
                .doesNotContain("tree(");
    }

    @Test
    void aLibraryTypeReadsInTheRichestViewTheBudgetFits(@TempDir Path dir) throws IOException {
        LibraryApi api = withSources(dir);
        assertThat(api.read("org.lib.Mapper", null, 4000))
                .contains("| /** Maps JSON text to objects. */")
                .contains("| public class Mapper implements java.io.Serializable {")
                .contains("return null;");
        String structure = api.read("org.lib.Mapper", null, 250);
        assertThat(structure)
                .startsWith("[i] org.lib:lib:1.0 is the locked version")
                .contains("structure view: content needs ~")
                .contains("public Map<String, List<? extends Number>> tree(String... paths)")
                .doesNotContain("return null;");
        assertThat(api.read("Mapper.readValue", View.HEADLINE, 2000))
                .contains("Mapper.readValue [method public]  org/lib/Mapper.java:")
                .contains("Reads json as a type.");
    }

    @Test
    void aSourcesJarWithoutTheTypesFileFallsBackToSignatures(@TempDir Path dir) throws IOException {
        Path classes = jar(dir);
        Path empty = dir.resolve("other-sources.jar");
        try (JarOutputStream j = new JarOutputStream(Files.newOutputStream(empty))) {
            j.putNextEntry(new JarEntry("org/other/Other.java"));
            j.write("package org.other; class Other {}".getBytes(StandardCharsets.UTF_8));
            j.closeEntry();
        }
        String out = new LibraryApi(List.of(new LibraryApi.Jar("org.lib:lib:1.0", classes, empty)), null)
                .read("Mapper.readValue", null, 2000);
        assertThat(out)
                .startsWith("[i] org.lib:lib:1.0 is the locked version; its sources jar has no org/lib/Mapper.java,")
                .contains("public <T> T readValue(String json, Class<T> type) throws IOException");
    }

    @Test
    void aSpecRowNamesItsSourcesJarWhenOneIsOnDisk() {
        assertThat(LibraryApi.jars(
                        List.of("g:a:1.0\t/s/a.jar\t/s/a-sources.jar", "g:b:2.0\t/s/b.jar\t", "g:c:3\t/s/c.jar")))
                .containsExactly(
                        new LibraryApi.Jar("g:a:1.0", Path.of("/s/a.jar"), Path.of("/s/a-sources.jar")),
                        new LibraryApi.Jar("g:b:2.0", Path.of("/s/b.jar"), null),
                        new LibraryApi.Jar("g:c:3", Path.of("/s/c.jar"), null));
    }
}
