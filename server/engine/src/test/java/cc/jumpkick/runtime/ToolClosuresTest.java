// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.resolver.KmpRedirects;
import cc.jumpkick.resolver.Resolution;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Staging a tool's runtime closure: every resolved jar is linked under a readable alias, two
 * artifacts sharing a file name do not collide, the directory is keyed by the resolving jk and
 * carries a listing its lookup checks, and a failure names the tool, the directory and the cause
 * rather than the bare path a filesystem exception carries as its message.
 */
class ToolClosuresTest {

    @Test
    void the_key_names_the_roots_the_bom_and_the_resolving_jk() {
        List<Coordinate> roots = List.of(Coordinate.of("com.android.tools.build", "bundletool", "1.17.2"));

        assertThat(ToolClosures.cacheKey(roots, null))
                .isEqualTo("com.android.tools.build_bundletool_1.17.2__by_" + ToolClosures.resolverKey());
        assertThat(ToolClosures.cacheKey(roots, "org.acme:bom:1.0"))
                .isEqualTo("com.android.tools.build_bundletool_1.17.2__bom_org.acme_bom_1.0__by_"
                        + ToolClosures.resolverKey());
        assertThat(ToolClosures.resolverKey()).startsWith("jk_" + JkVersion.VERSION);
    }

    /** A closure is served only when its listing is there and every jar it names is a non-empty file. */
    @Test
    void a_closure_is_complete_only_with_its_listing_and_every_listed_jar(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("closure"));
        Files.writeString(dir.resolve("a-1.jar"), "a");
        Files.writeString(dir.resolve("b-1.jar"), "b");
        assertThat(ToolClosures.complete(dir)).as("no listing").isFalse();

        ToolClosures.writeListing(dir);
        assertThat(Files.readString(dir.resolve(ToolClosures.LISTING))).isEqualTo("a-1.jar\nb-1.jar\n");
        assertThat(ToolClosures.complete(dir)).isTrue();

        Files.writeString(dir.resolve("b-1.jar"), "");
        assertThat(ToolClosures.complete(dir)).as("an empty jar").isFalse();
        Files.delete(dir.resolve("b-1.jar"));
        assertThat(ToolClosures.complete(dir)).as("a missing jar").isFalse();
        assertThat(ToolClosures.complete(tmp.resolve("absent"))).isFalse();
    }

    @Test
    void a_jar_is_aliased_by_artifact_and_version(@TempDir Path tmp) throws Exception {
        Path staging = Files.createDirectories(tmp.resolve("staging"));
        Files.createDirectories(tmp.resolve("cas"));
        Path jar = Files.writeString(tmp.resolve("cas/abc.jar"), "jar");

        Path alias = ToolClosures.alias(staging, Coordinate.of("org.springframework", "spring-context", "6.1.15"), jar);

        assertThat(alias)
                .isEqualTo(staging.resolve("spring-context-6.1.15.jar"))
                .hasSameBinaryContentAs(jar);
    }

    /**
     * A classified jar of a GAV lands beside its plain jar under its own name, so a data jar of
     * resources never takes the name of the jar that holds the classes.
     */
    @Test
    void a_classified_jar_keeps_its_classifier_in_its_name_beside_the_plain_jar(@TempDir Path tmp) throws Exception {
        Path staging = Files.createDirectories(tmp.resolve("staging"));
        Files.createDirectories(tmp.resolve("cas"));
        Path classes = Files.writeString(tmp.resolve("cas/classes.jar"), "classes");
        Path data = Files.writeString(tmp.resolve("cas/data.jar"), "data");
        Resolution resolution = new Resolution(Map.of(
                "org.xmlresolver:xmlresolver:jar:",
                new Resolution.ResolvedModule("org.xmlresolver:xmlresolver:jar:", "5.3.3", List.of()),
                "org.xmlresolver:xmlresolver:jar:data",
                new Resolution.ResolvedModule("org.xmlresolver:xmlresolver:jar:data", "5.3.3", List.of())));

        ToolClosures.stage(
                staging,
                List.of(Coordinate.of("org.xmlresolver", "xmlresolver", "5.3.3")),
                resolution,
                coord -> "data".equals(coord.classifier()) ? data : classes,
                KmpRedirects.NONE);

        assertThat(staging.resolve("xmlresolver-5.3.3.jar")).hasSameBinaryContentAs(classes);
        assertThat(staging.resolve("xmlresolver-5.3.3-data.jar")).hasSameBinaryContentAs(data);
        assertThat(ToolClosures.alias(staging, Coordinate.parse("org.acme:tool:1.0:linux-x64"), data))
                .isEqualTo(staging.resolve("tool-1.0-linux-x64.jar"));
    }

    @Test
    void two_artifacts_with_one_file_name_both_land_in_the_closure(@TempDir Path tmp) throws Exception {
        Path staging = Files.createDirectories(tmp.resolve("staging"));
        Files.createDirectories(tmp.resolve("cas"));
        Path first = Files.writeString(tmp.resolve("cas/first.jar"), "first");
        Path second = Files.writeString(tmp.resolve("cas/second.jar"), "second");

        ToolClosures.alias(staging, Coordinate.of("org.springframework", "spring-context", "6.1.15"), first);
        Path alias = ToolClosures.alias(staging, Coordinate.of("com.acme.fork", "spring-context", "6.1.15"), second);

        assertThat(alias)
                .isEqualTo(staging.resolve("com.acme.fork_spring-context-6.1.15.jar"))
                .hasSameBinaryContentAs(second);
        assertThat(staging.resolve("spring-context-6.1.15.jar")).hasSameBinaryContentAs(first);
    }

    @Test
    void a_filesystem_failure_names_the_tool_the_directory_and_the_cause(@TempDir Path tmp) {
        List<Coordinate> roots =
                List.of(Coordinate.of("com.netflix.graphql.dgs.codegen", "graphql-dgs-codegen-core", "8.6.0"));
        Path dir = tmp.resolve("plugin-tools/dgs");

        IOException failure = ToolClosures.failure(
                roots,
                dir,
                new FileAlreadyExistsException(
                        tmp.resolve(".closure-1/spring-context-6.1.15.jar").toString()));

        assertThat(failure)
                .hasMessageContaining("com.netflix.graphql.dgs.codegen:graphql-dgs-codegen-core:8.6.0")
                .hasMessageContaining(dir.toString())
                .hasMessageContaining("FileAlreadyExistsException")
                .hasMessageContaining("spring-context-6.1.15.jar")
                .hasMessageContaining("already exists");
        assertThat(ToolClosures.failure(
                        roots,
                        dir,
                        new NoSuchFileException(tmp.resolve("gone.jar").toString())))
                .hasMessageContaining("NoSuchFileException")
                .hasMessageContaining("gone.jar")
                .hasMessageContaining("does not exist");
    }

    @Test
    void a_failure_with_a_message_of_its_own_keeps_it(@TempDir Path tmp) {
        IOException failure = ToolClosures.failure(
                List.of(Coordinate.of("org.antlr", "antlr4", "4.13.2")),
                tmp,
                new IOException("cannot fetch org.antlr:ST4:4.3.4 — a transitive step-dependency's closure must exist"
                        + " in a declared repo"));

        assertThat(failure)
                .hasMessageContaining("org.antlr:antlr4:4.13.2")
                .hasMessageContaining("cannot fetch org.antlr:ST4:4.3.4")
                .hasMessageNotContaining("IOException");
    }
}
