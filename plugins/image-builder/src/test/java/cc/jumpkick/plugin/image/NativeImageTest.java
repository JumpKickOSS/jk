// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.testing.RegistryStub;
import com.google.cloud.tools.jib.api.buildplan.FileEntriesLayer;
import com.google.cloud.tools.jib.api.buildplan.FileEntry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A native executable's image: one 0755 layer, the executable as entrypoint, an index across platforms. */
class NativeImageTest {

    private RegistryStub registry;
    private String baseRef;

    @BeforeEach
    void start() throws IOException {
        registry = RegistryStub.open();
        baseRef = registry.publishIndex("cc-" + System.nanoTime(), "1", List.of("linux/amd64", "linux/arm64"));
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void the_layer_holds_the_executable_at_mode_0755_whatever_its_mode_on_disk(@TempDir Path tmp) throws Exception {
        Path exe = executable(tmp, "hello", "amd64");
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rw-r--r--"));

        FileEntriesLayer layer = NativeImage.layer(exe, "/app/hello");

        assertThat(layer.getEntries()).singleElement().satisfies(e -> {
            assertThat(e.getExtractionPath().toString()).isEqualTo("/app/hello");
            assertThat(e.getPermissions().toOctalString()).isEqualTo("755");
            assertThat(e.getSourceFile()).isEqualTo(exe);
        });
        assertThat(layer.getEntries().stream().map(FileEntry::getModificationTime))
                .containsOnly(AotCacheTrainer.LAYER_TIME.toInstant());
    }

    @Test
    void one_platform_is_one_image_that_starts_the_executable(@TempDir Path tmp) throws Exception {
        Path tarball = tmp.resolve("out/image.tar");

        NativeImage.writeToTarball(
                plan(Map.of("linux/arm64", executable(tmp, "hello", "arm64"))), tarball, anonymous(null));

        Map<String, byte[]> image = untar(Files.readAllBytes(tarball));
        String config = new String(Objects.requireNonNull(image.get("config.json")), StandardCharsets.UTF_8);
        assertThat(config)
                .contains("\"Entrypoint\":[\"/app/hello\"]")
                .contains("\"WorkingDir\":\"/app\"")
                .contains("\"User\":\"65532\"")
                .contains("\"architecture\":\"arm64\"")
                .contains("\"os\":\"linux\"");
        String manifest = new String(Objects.requireNonNull(image.get("manifest.json")), StandardCharsets.UTF_8);
        String layerName = manifest.replaceAll("(?s).*\"Layers\":\\[\"([^\"]+)\".*", "$1");
        TarArchiveEntry entry = layerEntries(image.get(layerName)).getFirst();
        assertThat(entry.getName().replaceFirst("^\\.?/", "")).isEqualTo("app/hello");
        assertThat(entry.getMode() & 0777).isEqualTo(0755);
    }

    @Test
    void several_platforms_are_an_oci_layout_whose_index_names_each(@TempDir Path tmp) throws Exception {
        Map<String, Path> executables = new LinkedHashMap<>();
        executables.put("linux/amd64", executable(tmp, "amd", "amd64"));
        executables.put("linux/arm64", executable(tmp, "arm", "arm64"));
        Path tarball = tmp.resolve("out/image.tar");

        ImageBuilder.Result built = NativeImage.writeToTarball(plan(executables), tarball, anonymous(null));

        Map<String, byte[]> layout = untar(Files.readAllBytes(tarball));
        assertThat(layout).containsKey("oci-layout");
        Object top = json(layout.get("index.json"));
        Object entry = MiniJson.list(top, "manifests").getFirst();
        assertThat(MiniJson.str(entry, "mediaType")).isEqualTo(Referrers.INDEX_TYPE);
        assertThat(MiniJson.str(entry, "digest")).isEqualTo(built.digest());
        assertThat(MiniJson.str(MiniJson.get(entry, "annotations"), NativeImage.REF_NAME))
                .isEqualTo("1.0.0");
        Object index = json(layout.get(blob(built.digest())));
        List<String> platforms = new ArrayList<>();
        for (Object m : MiniJson.list(index, "manifests")) {
            Object platform = MiniJson.get(m, "platform");
            String arch = MiniJson.str(platform, "architecture");
            platforms.add(MiniJson.str(platform, "os") + "/" + arch);
            assertThat(MiniJson.str(m, "mediaType")).isEqualTo(Referrers.MANIFEST_TYPE);
            Object manifest = json(layout.get(blob(MiniJson.str(m, "digest"))));
            Object config = json(layout.get(blob(MiniJson.str(MiniJson.get(manifest, "config"), "digest"))));
            assertThat(MiniJson.str(config, "architecture")).isEqualTo(arch);
            String layer = MiniJson.str(MiniJson.list(manifest, "layers").getFirst(), "digest");
            TarArchiveEntry file = layerEntries(layout.get(blob(layer))).getFirst();
            assertThat(file.getName().replaceFirst("^\\.?/", "")).isEqualTo("app/hello");
            assertThat(file.getMode() & 0777).isEqualTo(0755);
        }
        assertThat(platforms).containsExactly("linux/amd64", "linux/arm64");
    }

    @Test
    void a_push_of_several_platforms_leaves_the_index_at_the_tag(@TempDir Path tmp) throws Exception {
        Map<String, Path> executables = new LinkedHashMap<>();
        executables.put("linux/amd64", executable(tmp, "amd", "amd64"));
        executables.put("linux/arm64", executable(tmp, "arm", "arm64"));
        NativeImage.Plan plan = plan(executables, registry.hostPort());

        ImageBuilder.Result pushed = NativeImage.pushToRegistry(plan, anonymous(plan.reference()));

        String index = Objects.requireNonNull(registry.manifest("hello", "1.0.0"));
        assertThat(MiniJson.str(json(index.getBytes(StandardCharsets.UTF_8)), "mediaType"))
                .isEqualTo(Referrers.INDEX_TYPE);
        assertThat(registry.manifest("hello", pushed.digest())).isEqualTo(index);
        List<String> archs = new ArrayList<>();
        for (Object m : MiniJson.list(json(index.getBytes(StandardCharsets.UTF_8)), "manifests")) {
            archs.add(MiniJson.str(MiniJson.get(m, "platform"), "architecture"));
            assertThat(registry.manifest("hello", Objects.requireNonNull(MiniJson.str(m, "digest"))))
                    .isNotNull();
        }
        assertThat(archs).containsExactly("amd64", "arm64");
    }

    @Test
    void a_daemon_load_takes_one_platform(@TempDir Path tmp) throws Exception {
        Map<String, Path> executables = new LinkedHashMap<>();
        executables.put("linux/amd64", executable(tmp, "amd", "amd64"));
        executables.put("linux/arm64", executable(tmp, "arm", "arm64"));

        assertThatThrownBy(() -> NativeImage.loadToLocalDaemon(plan(executables), null, anonymous(null)))
                .hasMessageContaining("a daemon holds one platform");
    }

    private NativeImage.Plan plan(Map<String, Path> executables) {
        return plan(executables, null);
    }

    private NativeImage.Plan plan(Map<String, Path> executables, @Nullable String pushTo) {
        ImageConfig config = new ImageConfig(
                baseRef,
                null,
                "65532",
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                pushTo,
                null,
                List.copyOf(executables.keySet()),
                null,
                null,
                null,
                false,
                true,
                false);
        return new NativeImage.Plan(config, "hello", "1.0.0", executables, "/app/hello");
    }

    private RegistryAuth anonymous(@Nullable String pushRef) {
        return RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, pushRef);
    }

    /** A stand-in executable: no GraalVM needed, only distinct bytes per platform. */
    private static Path executable(Path tmp, String name, String arch) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("bin-" + arch));
        Path exe = dir.resolve(name);
        Files.writeString(exe, "#!/bin/sh\necho " + arch + "\n");
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        return exe;
    }

    private static String blob(@Nullable String digest) {
        Objects.requireNonNull(digest, "digest");
        return "blobs/" + digest.replace(':', '/');
    }

    private static Object json(byte @Nullable [] bytes) {
        Objects.requireNonNull(bytes, "document");
        return Objects.requireNonNull(MiniJson.parseRelaxed(new String(bytes, StandardCharsets.UTF_8)));
    }

    /** A gzipped layer's entries. */
    private static List<TarArchiveEntry> layerEntries(byte @Nullable [] layer) throws IOException {
        Objects.requireNonNull(layer, "layer");
        List<TarArchiveEntry> entries = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(layer));
                TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.isFile()) entries.add(e);
            }
        }
        return entries;
    }

    /** A tarball's regular files by name, a leading {@code ./} stripped. */
    private static Map<String, byte[]> untar(byte[] tarball) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(tarball))) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                if (e.isFile()) files.put(e.getName().replaceFirst("^\\./", ""), tar.readAllBytes());
            }
        }
        return files;
    }
}
