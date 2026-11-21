// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.testing.RegistryStub;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An image's release-jar layer is kept under the cache root by its content, and a second build of
 * the same jars ships the kept layer instead of building it again. Snapshot jars are never kept, so
 * a snapshot rebuilt under its old name and time ships its new bytes.
 */
class ImageReleaseLayerTest {

    private static final FileTime PINNED = FileTime.fromMillis(315_532_800_000L);

    private RegistryStub registry;
    private String baseRef;

    @BeforeEach
    void start() throws IOException {
        registry = RegistryStub.open();
        baseRef = registry.publishImage("jre-" + System.nanoTime(), "1");
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void a_second_image_of_unchanged_release_jars_ships_the_kept_layer(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        ImageBuilder.writeToTarball(f.plan(), tmp.resolve("a.tar"), f.cache(), anonymous());
        Map<String, byte[]> first = untar(Files.readAllBytes(tmp.resolve("a.tar")));
        List<String> firstLayers = layerNames(first);

        // The build after this one must read the release layer from what this one kept. Swap that
        // blob's bytes for a marker: a build that tarred the jars again would not ship the marker.
        Path blob = keptBlob(f.cache());
        assertThat(Files.readAllBytes(blob)).isEqualTo(first.get(firstLayers.get(0)));
        byte[] marker = "kept-layer".getBytes(StandardCharsets.UTF_8);
        Files.delete(blob);
        Files.write(blob, marker);

        ImageBuilder.writeToTarball(f.plan(), tmp.resolve("b.tar"), f.cache(), anonymous());

        Map<String, byte[]> second = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        List<String> secondLayers = layerNames(second);
        assertThat(secondLayers).hasSize(3);
        assertThat(second.get(secondLayers.get(0)))
                .as("the release layer, as kept")
                .isEqualTo(marker);
    }

    @Test
    void a_snapshot_jar_rebuilt_under_its_old_name_and_time_ships_its_new_bytes(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        ImageBuilder.writeToTarball(f.plan(), tmp.resolve("a.tar"), f.cache(), anonymous());

        Files.writeString(f.snapshot(), "snapshot two");
        Files.setLastModifiedTime(f.snapshot(), PINNED);
        ImageBuilder.writeToTarball(f.plan(), tmp.resolve("b.tar"), f.cache(), anonymous());

        Map<String, byte[]> image = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        Map<String, byte[]> snapshotLayer = layer(image, layerNames(image).get(1));
        assertThat(new String(snapshotLayer.get("app/libs/sibling-0.1.0-SNAPSHOT.jar"), StandardCharsets.UTF_8))
                .isEqualTo("snapshot two");
    }

    @Test
    void a_release_jar_changed_in_place_ships_its_new_bytes(@TempDir Path tmp) throws Exception {
        Fixture f = fixture(tmp);
        ImageBuilder.writeToTarball(f.plan(), tmp.resolve("a.tar"), f.cache(), anonymous());

        Files.writeString(f.release(), "release two");
        Files.setLastModifiedTime(f.release(), PINNED);
        ImageBuilder.writeToTarball(f.plan(), tmp.resolve("b.tar"), f.cache(), anonymous());

        Map<String, byte[]> image = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        Map<String, byte[]> releaseLayer = layer(image, layerNames(image).get(0));
        assertThat(new String(releaseLayer.get("app/libs/lib-1.0.jar"), StandardCharsets.UTF_8))
                .isEqualTo("release two");
    }

    private record Fixture(ImageBuilder.Plan plan, Path cache, Path release, Path snapshot) {}

    private Fixture fixture(Path tmp) throws IOException {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Path release = Files.writeString(store.resolve("aa"), "release one");
        Path snapshot = Files.writeString(store.resolve("bb"), "snapshot one");
        Path app = Files.writeString(tmp.resolve("app.jar"), "app");
        for (Path p : List.of(release, snapshot, app)) Files.setLastModifiedTime(p, PINNED);
        ImageConfig config = new ImageConfig(
                baseRef,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                "1",
                List.of("linux/amd64"),
                null,
                null,
                null,
                false,
                true);
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config,
                "app",
                "1.0.0",
                "x.Main",
                app,
                List.of(release),
                List.of(snapshot),
                null,
                Map.of(release, "lib-1.0.jar", snapshot, "sibling-0.1.0-SNAPSHOT.jar"),
                null,
                null,
                null);
        return new Fixture(plan, Files.createDirectories(tmp.resolve("cache")), release, snapshot);
    }

    /** The one compressed layer kept under {@code cache}. */
    private static Path keptBlob(Path cache) throws IOException {
        Path root = CacheTree.IMAGE_LAYERS.under(cache);
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> blobs = walk.filter(Files::isRegularFile)
                    .filter(p ->
                            root.relativize(p).toString().replace('\\', '/').contains("/jib/layers/"))
                    .toList();
            assertThat(blobs).hasSize(1);
            return blobs.get(0);
        }
    }

    private RegistryAuth anonymous() {
        return RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, null);
    }

    private static final Pattern LAYERS = Pattern.compile("\"Layers\":\\[([^]]*)]");

    private static List<String> layerNames(Map<String, byte[]> image) {
        Matcher m = LAYERS.matcher(new String(image.get("manifest.json"), StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        if (!m.find()) return names;
        for (String quoted : m.group(1).split(",")) {
            String n = quoted.trim();
            if (!n.isEmpty()) names.add(n.substring(1, n.length() - 1));
        }
        return names;
    }

    private static Map<String, byte[]> layer(Map<String, byte[]> image, String name) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(image.get(name)))) {
            return untar(in.readAllBytes());
        }
    }

    /** A ustar archive's regular files by name, leading {@code ./} and {@code /} stripped. */
    private static Map<String, byte[]> untar(byte[] tar) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        int pos = 0;
        while (pos + 512 <= tar.length) {
            String name = field(tar, pos, 100);
            if (name.isEmpty()) break;
            String prefix = field(tar, pos + 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            String size = field(tar, pos + 124, 12).trim();
            long length = size.isEmpty() ? 0 : Long.parseLong(size, 8);
            char type = (char) tar[pos + 156];
            pos += 512;
            if (type == '0' || type == 0) {
                byte[] body = new byte[(int) length];
                System.arraycopy(tar, pos, body, 0, (int) length);
                files.put(name.replaceFirst("^\\./", "").replaceFirst("^/", ""), body);
            }
            pos += (int) ((length + 511) / 512 * 512);
        }
        return files;
    }

    private static String field(byte[] tar, int at, int len) {
        int end = at;
        while (end < at + len && tar[end] != 0) end++;
        return new String(tar, at, end - at, StandardCharsets.US_ASCII);
    }
}
