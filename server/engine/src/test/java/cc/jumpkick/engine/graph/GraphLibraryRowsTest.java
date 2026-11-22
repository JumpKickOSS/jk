// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class GraphLibraryRowsTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path dir;

    @BeforeEach
    void scratchStore() {
        System.setProperty("jk.env.JK_STORE_DIR", dir.resolve("store").toString());
        System.setProperty("jk.env.HOME", dir.resolve("home").toString());
        System.setProperty("jk.env.JK_M2_INTEGRATION", "off");
    }

    @AfterEach
    void realStore() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        System.clearProperty("jk.env.HOME");
        System.clearProperty("jk.env.JK_M2_INTEGRATION");
    }

    @Test
    void aLockedJarsRowNamesItsSourcesJarOnlyWhenOneIsOnDisk() throws Exception {
        Path repo = Objects.requireNonNull(RepoArtifactStore.forSource(dir.resolve("store"), CENTRAL, false)
                .root());
        Path jar = repo.resolve("org/example/graph/lib/1.0/lib-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "classes");
        Path root = lock(List.of(row("org.example.graph:lib", CENTRAL, jar)));

        assertThat(GraphRunner.libraryRows(root, null)).containsExactly("org.example.graph:lib:1.0\t" + jar + "\t");

        Path sources = repo.resolve("org/example/graph/lib/1.0/lib-1.0-sources.jar");
        Files.writeString(sources, "sources");
        assertThat(GraphRunner.libraryRows(root, null))
                .containsExactly("org.example.graph:lib:1.0\t" + jar + "\t" + sources);
    }

    @Test
    void aReadFetchesTheSourcesJarOfTheOneJarThatDeclaresItsType() throws Exception {
        String source = "central+" + http.base() + "/";
        Path widgets = served(source, "org.example", "widgets", "org/example/widgets/Widget.class");
        Path other = served(source, "org.example", "other", "org/example/other/Gadget.class");
        http.served()
                .put(
                        "/org/example/widgets/1.0/widgets-1.0-sources.jar",
                        "widget sources".getBytes(StandardCharsets.UTF_8));
        http.served()
                .put("/org/example/other/1.0/other-1.0-sources.jar", "gadget sources".getBytes(StandardCharsets.UTF_8));
        Path root = lock(List.of(row("org.example:widgets", source, widgets), row("org.example:other", source, other)));

        assertThat(GraphRunner.libraryRows(root, null)).allMatch(r -> r.endsWith("\t"));
        assertThat(http.requestsFor("/org/example/widgets/1.0/widgets-1.0-sources.jar"))
                .isZero();

        List<String> rows = GraphRunner.libraryRows(root, "Widget.spin");
        Path fetched = widgets.resolveSibling("widgets-1.0-sources.jar");
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "org.example:widgets:1.0\t" + widgets + "\t" + fetched,
                        "org.example:other:1.0\t" + other + "\t");
        assertThat(Files.readString(fetched)).isEqualTo("widget sources");
        assertThat(http.requestsFor("/org/example/other/1.0/other-1.0-sources.jar"))
                .isZero();
    }

    @Test
    void aLibraryThatPublishesNoSourcesIsAskedOnce() throws Exception {
        String source = "central+" + http.base() + "/";
        Path widgets = served(source, "org.example", "bare", "org/example/bare/Widget.class");
        Path root = lock(List.of(row("org.example:bare", source, widgets)));

        assertThat(GraphRunner.libraryRows(root, "org.example.bare.Widget"))
                .containsExactly("org.example:bare:1.0\t" + widgets + "\t");
        long asked = http.requestsFor("/org/example/bare/1.0/bare-1.0-sources.jar");
        assertThat(asked).isPositive();
        GraphRunner.libraryRows(root, "Widget");
        assertThat(http.requestsFor("/org/example/bare/1.0/bare-1.0-sources.jar"))
                .isEqualTo(asked);
    }

    @Test
    void onlyASingleTypeOrMemberAddressNamesALibraryType() {
        assertThat(LibrarySources.target(List.of("ObjectMapper.readValue"))).isEqualTo("ObjectMapper.readValue");
        assertThat(LibrarySources.target(List.of(" com.acme.Shop "))).isEqualTo("com.acme.Shop");
        assertThat(LibrarySources.target(List.of("src/main/java/Shop.java"))).isNull();
        assertThat(LibrarySources.target(List.of("Shop.java"))).isNull();
        assertThat(LibrarySources.target(List.of("Shop;Order"))).isNull();
        assertThat(LibrarySources.target(List.of("Shop.*"))).isNull();
        assertThat(LibrarySources.target(List.of("Shop#line=3"))).isNull();
        assertThat(LibrarySources.target(List.of("Shop", "Order"))).isNull();
    }

    @Test
    void aTypeIsFoundAsTheWorkerResolvesItAndAnAmbiguousNameFetchesNothing() throws Exception {
        Map<String, Path> jars = new LinkedHashMap<>();
        jars.put("a", zip("a.jar", "com/a/Config.class", "com/a/Config$Builder.class", "com/a/Widget.class"));
        jars.put("b", zip("b.jar", "com/b/Config.class", "com/b/Widget$Part.class"));

        assertThat(LibrarySources.declaring(jars, "Widget")).isEqualTo("a");
        assertThat(LibrarySources.declaring(jars, "Widget.spin")).isEqualTo("a");
        assertThat(LibrarySources.declaring(jars, "Widget.Part")).isEqualTo("b");
        assertThat(LibrarySources.declaring(jars, "com.b.Config")).isEqualTo("b");
        assertThat(LibrarySources.declaring(jars, "Config.Builder")).isEqualTo("a");
        assertThat(LibrarySources.declaring(jars, "Config")).isNull();
        assertThat(LibrarySources.declaring(jars, "Config.load")).isNull();
        assertThat(LibrarySources.declaring(jars, "Missing")).isNull();
    }

    /** A jar of {@code classes} under {@code source}'s store tree, served by the stub as {@code group:artifact:1.0}. */
    private Path served(String source, String group, String artifact, String... classes) throws IOException {
        Path repo = Objects.requireNonNull(
                RepoArtifactStore.forSource(dir.resolve("store"), source, false).root());
        Path jar = repo.resolve(group.replace('.', '/') + "/" + artifact + "/1.0/" + artifact + "-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.copy(zip(artifact + ".jar", classes), jar);
        return jar;
    }

    private Path zip(String name, String... entries) throws IOException {
        Path out = dir.resolve("zips").resolve(name);
        Files.createDirectories(out.getParent());
        try (OutputStream file = Files.newOutputStream(out);
                ZipOutputStream zip = new ZipOutputStream(file)) {
            for (String e : entries) {
                zip.putNextEntry(new ZipEntry(e));
                zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
                zip.closeEntry();
            }
        }
        return out;
    }

    private static Lockfile.Artifact row(String module, String source, Path jar) throws IOException {
        return new Lockfile.Artifact(
                module, "1.0", source, "sha256:" + Hashing.sha256Hex(jar), null, List.of(Scope.MAIN), List.of());
    }

    private Path lock(List<Lockfile.Artifact> rows) throws IOException {
        Path root = Files.createDirectories(dir.resolve("app"));
        LockfileWriter.write(
                Lockfile.empty("test").withArtifacts(new ArrayList<>(rows)), root.resolve(ManifestNames.LOCK));
        return root;
    }

    @Test
    void aQuestionOrFindReadsTheLockedJarsAndCompilesNothingOnlyWhenItNamesALibrary() {
        assertThat(GraphRunner.readsLibraries(request("query", List.of("library=jackson-databind"))))
                .isTrue();
        assertThat(GraphRunner.readsLibraries(request("query", List.of()))).isFalse();
        assertThat(GraphRunner.readsLibraries(request("read", List.of()))).isTrue();
        assertThat(GraphRunner.readsLibraries(request("usages", List.of()))).isFalse();
        assertThat(GraphRunner.asksALibrary(request("query", List.of("library=guava"))))
                .isTrue();
        assertThat(GraphRunner.asksALibrary(request("read", List.of()))).isFalse();
        assertThat(GraphRunner.asksALibrary(request("find", List.of("library=*"))))
                .isTrue();
        assertThat(GraphRunner.readsLibraries(request("find", List.of("kind=class"))))
                .isFalse();
    }

    private static GraphRequest request(String op, List<String> options) {
        return new GraphRequest(
                "/w",
                op,
                List.of("x"),
                "bfs",
                3,
                2000,
                List.of(),
                false,
                8,
                null,
                false,
                false,
                null,
                null,
                null,
                options);
    }
}
