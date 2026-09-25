// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.compile.CompileResult;
import cc.jumpkick.compile.PackageIndex;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code package does not exist} error names the coordinate that provides the package: a lock
 * row whose jar holds it and is not on this module's compile classpath first, the catalog module
 * whose group prefixes it second, nothing when neither knows. The jar's package list is read from
 * the store's index once it has been written.
 */
class PackageProvidersTest {

    private static final String SHA = "ab".repeat(32);

    @Test
    void a_lock_jar_off_this_modules_classpath_is_named_and_indexed_once(@TempDir Path tmp) throws Exception {
        Path util = jar(tmp.resolve("acme-util.jar"), "org/acme/util/Strings.class", "META-INF/versions/9/x/Y.class");
        Path app = jar(tmp.resolve("app-core.jar"), "com/example/App.class");
        Path index = tmp.resolve("index");
        PackageProviders providers = new PackageProviders(
                List.of(entry("org.acme:acme-util", SHA, util), entry("com.example:app-core", "cd".repeat(32), app)),
                List.of(app),
                index,
                LibraryCatalog.bundled());

        List<CompileResult.Diagnostic> in = List.of(
                error(
                        "/ws/app/src/Main.java:3:22: error: package org.acme.util does not exist",
                        "compiler.err.doesnt.exist"),
                error(
                        "/ws/app/src/Main.java:9:5: error: cannot find symbol\n  symbol: x",
                        "compiler.err.cant.resolve"));
        List<CompileResult.Diagnostic> enriched = providers.enrich(in);

        assertThat(enriched.get(0).message())
                .endsWith("\n  provided by: org.acme:acme-util (in the lock, not on this module's compile classpath)");
        assertThat(enriched.get(0).key()).isEqualTo("compiler.err.doesnt.exist");
        assertThat(enriched.get(1)).as("other diagnostics pass untouched").isSameAs(in.get(1));
        assertThat(index.resolve(SHA + ".txt"))
                .exists()
                .content()
                .contains("org.acme.util")
                .doesNotContain("META-INF");

        Files.delete(util);
        assertThat(providers.provider("org.acme.util"))
                .as("the index answers once the jar has been listed")
                .startsWith("org.acme:acme-util");
        assertThat(providers.provider("com.example"))
                .as("a jar already on the classpath is not the repair")
                .isNull();
    }

    @Test
    void a_lock_row_the_store_lacks_is_not_a_candidate_and_is_not_logged(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path src = jar(tmp.resolve("acme-util-src.jar"), "org/acme/util/S.class");
        String sha = Hashing.sha256Hex(src);
        RepoArtifactStore.forStoreId(store, "central")
                .materialize("org/acme/acme-util/1.0/acme-util-1.0.jar", src, sha);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        row("org.acme:acme-util", sha, Scope.MAIN),
                        row("org.acme:acme-test", "ef".repeat(32), Scope.TEST)));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(log, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        PackageProviders providers;
        try {
            providers = PackageProviders.of(
                    new ClasspathResolver(store),
                    lock,
                    List.of(),
                    ClasspathResolver.COMPILE_MAIN,
                    tmp.resolve("index"),
                    LibraryCatalog.bundled());
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }

        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("a row of a scope this build never synced is nobody's warning here")
                .isEmpty();
        assertThat(providers.provider("org.acme.util")).startsWith("org.acme:acme-util");
        assertThat(providers.provider("org.acme.test")).isNull();
    }

    /**
     * A row that stands for a POM alone put nothing on the classpath, so a missing package may be
     * one its jar would have carried: the error names it, with the repository that served the POM.
     */
    @Test
    void a_row_locked_without_a_file_is_named_under_a_missing_package_error(@TempDir Path tmp) {
        Lockfile.Artifact picketbox = new Lockfile.Artifact(
                "org.picketbox:picketbox:jar:",
                "5.0.3.Final",
                "central+https://repo.maven.apache.org/maven2/",
                null,
                "picketbox-5.0.3.Final.pom",
                List.of(Scope.PROVIDED),
                List.of());
        PackageProviders providers = new PackageProviders(
                List.of(), List.of(picketbox), List.of(), tmp.resolve("index"), LibraryCatalog.bundled());

        List<CompileResult.Diagnostic> enriched = providers.enrich(List.of(error(
                "/ws/adapter/src/Main.java:3:22: error: package org.jboss.security does not exist",
                "compiler.err.doesnt.exist")));

        assertThat(enriched.get(0).message())
                .endsWith("\n  locked without a file: org.picketbox:picketbox:5.0.3.Final"
                        + " (central+https://repo.maven.apache.org/maven2/)");
    }

    /**
     * The jar that holds the package is still in the lock, on another scope, because a test starter
     * pulls it. The coordinate to add is the direct dependency that left this compile.
     */
    @Test
    void a_removed_direct_dependency_is_named_ahead_of_the_jar_that_holds_the_package(@TempDir Path tmp)
            throws Exception {
        Path web = jar(tmp.resolve("spring-web.jar"), "org/springframework/web/bind/annotation/GetMapping.class");
        Path starter = jar(tmp.resolve("starter.jar"), "org/springframework/boot/Starter.class");
        String webName = "org.springframework:spring-web:jar:";
        String starterName = "org.springframework.boot:spring-boot-starter-webmvc:jar:";
        List<String> deps = List.of("org.springframework:spring-web:jar:@7.0.9");
        List<Lockfile.Artifact> previous = List.of(
                artifact(starterName, EnumSet.of(Scope.MAIN, Scope.TEST), deps),
                artifact(webName, EnumSet.of(Scope.MAIN, Scope.TEST), List.of()));
        List<Lockfile.Artifact> current = List.of(
                artifact(starterName, EnumSet.of(Scope.TEST), deps),
                artifact(webName, EnumSet.of(Scope.TEST), List.of()));
        PackageProviders providers = new PackageProviders(
                List.of(entry(current.get(1), web), entry(current.get(0), starter)),
                List.of(entry(previous.get(1), web), entry(previous.get(0), starter)),
                previous,
                current,
                ClasspathResolver.COMPILE_MAIN,
                List.of(),
                List.of(),
                tmp.resolve("index"),
                LibraryCatalog.bundled(),
                null,
                4);

        assertThat(providers.provider("org.springframework.web.bind.annotation"))
                .isEqualTo(
                        "org.springframework.boot:spring-boot-starter-webmvc (removed from this module's dependencies)");
    }

    /** No lock row holds the package. A Boot project is told the starter, not left to guess. */
    @Test
    void a_boot_project_is_offered_the_starter_for_a_package_the_lock_does_not_carry(@TempDir Path tmp)
            throws Exception {
        Path boot = jar(tmp.resolve("spring-boot.jar"), "org/springframework/boot/SpringApplication.class");
        Lockfile.Artifact platform =
                artifact("org.springframework.boot:spring-boot:jar:", EnumSet.of(Scope.MAIN), List.of());
        PackageProviders providers = new PackageProviders(
                List.of(entry(platform, boot)),
                List.of(),
                List.of(),
                List.of(platform),
                ClasspathResolver.COMPILE_MAIN,
                List.of(),
                List.of(boot),
                tmp.resolve("index"),
                LibraryCatalog.bundled(),
                null,
                4);

        assertThat(providers.provider("org.springframework.web.bind.annotation"))
                .isEqualTo("org.springframework.boot:spring-boot-starter-webmvc (library catalog)");
        assertThat(providers.provider("jakarta.validation"))
                .isEqualTo("org.springframework.boot:spring-boot-starter-validation (library catalog)");
    }

    @Test
    void the_catalog_answers_by_group_prefix_when_the_lock_has_nothing(@TempDir Path tmp) {
        PackageProviders providers =
                new PackageProviders(List.of(), List.of(), tmp.resolve("index"), LibraryCatalog.bundled());

        assertThat(providers.provider("org.slf4j")).isEqualTo("org.slf4j:slf4j-api (library catalog)");
        assertThat(providers.provider("org.apache.commons.lang3"))
                .isEqualTo("org.apache.commons:commons-lang3 (library catalog)");
        assertThat(providers.provider("com.nowhere.at.all")).isNull();
    }

    /** Libraries whose group prefixes none of their packages are answered by the catalog's package table. */
    @Test
    void the_catalogs_package_table_answers_where_the_group_prefixes_no_package(@TempDir Path tmp) {
        PackageProviders providers =
                new PackageProviders(List.of(), List.of(), tmp.resolve("index"), LibraryCatalog.bundled());

        assertThat(providers.provider("com.google.common.collect"))
                .isEqualTo("com.google.guava:guava (library catalog)");
        assertThat(providers.provider("com.fasterxml.jackson.databind.node"))
                .isEqualTo("com.fasterxml.jackson.core:jackson-databind (library catalog)");
        assertThat(providers.provider("com.fasterxml.jackson.core"))
                .isEqualTo("com.fasterxml.jackson.core:jackson-core (library catalog)");
        assertThat(providers.provider("com.fasterxml.jackson.annotation"))
                .isEqualTo("com.fasterxml.jackson.core:jackson-annotations (library catalog)");
        assertThat(providers.provider("tools.jackson.databind"))
                .isEqualTo("tools.jackson.core:jackson-databind (library catalog)");
        assertThat(providers.provider("com.google.gson")).isEqualTo("com.google.code.gson:gson (library catalog)");
        assertThat(providers.provider("org.junit.jupiter.api"))
                .isEqualTo("org.junit.jupiter:junit-jupiter (library catalog)");
        assertThat(providers.provider("org.assertj.core.api")).isEqualTo("org.assertj:assertj-core (library catalog)");
        assertThat(providers.provider("org.mockito")).isEqualTo("org.mockito:mockito-core (library catalog)");
        assertThat(providers.provider("com.google.commonest"))
                .as("a package table entry is a package prefix, not a string prefix")
                .isNull();
    }

    @Test
    void a_keyless_message_of_the_right_shape_is_a_missing_package_and_a_keyed_one_of_another_kind_is_not() {
        assertThat(PackageProviders.missingPackage(error("package a.b does not exist", "")))
                .isEqualTo("a.b");
        assertThat(PackageProviders.missingPackage(error("package a.b does not exist", "compiler.err.doesnt.exist")))
                .isEqualTo("a.b");
        assertThat(PackageProviders.missingPackage(error("package a.b does not exist", "compiler.err.other")))
                .isNull();
        assertThat(PackageProviders.missingPackage(new CompileResult.Diagnostic(
                        CompileResult.Severity.WARNING, null, 0, 0, "package a.b does not exist", "")))
                .isNull();
    }

    private static CompileResult.Diagnostic error(String message, String key) {
        return new CompileResult.Diagnostic(CompileResult.Severity.ERROR, null, 0, 0, message, key);
    }

    private static Lockfile.Artifact row(String module, String sha, Scope scope) {
        return new Lockfile.Artifact(
                module,
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + sha,
                null,
                List.of(scope),
                List.of());
    }

    private static Lockfile.Artifact artifact(String name, Set<Scope> scopes, List<String> deps) {
        return new Lockfile.Artifact(
                name,
                "4.0.8",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + SHA,
                null,
                List.copyOf(scopes),
                deps);
    }

    private static ClasspathResolver.Entry entry(Lockfile.Artifact artifact, Path jar) {
        return new ClasspathResolver.Entry(artifact, jar);
    }

    private static ClasspathResolver.Entry entry(String ga, String sha, Path jar) {
        return new ClasspathResolver.Entry(
                new Lockfile.Artifact(
                        ga + ":jar:",
                        "1.0.0",
                        "central+https://repo",
                        "sha256:" + sha,
                        null,
                        List.of(Scope.MAIN),
                        List.of(),
                        null),
                jar);
    }

    private static Path jar(Path file, String... entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(os)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
                zip.closeEntry();
            }
        }
        assertThat(PackageIndex.hex("sha256:" + SHA)).isEqualTo(SHA);
        return file;
    }
}
