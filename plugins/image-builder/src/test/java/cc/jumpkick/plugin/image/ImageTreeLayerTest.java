// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Settled;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.testing.RegistryStub;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
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
 * A node image's {@code node_modules} is kept under the cache root by each file's identity, and a
 * packager's app tree's {@code lib/} by its bytes; a second build of the unchanged layer ships the
 * kept one instead of building it again. A file rewritten in place, or written too recently for its
 * identity to vouch for its bytes, ships what it holds now.
 */
class ImageTreeLayerTest {

    private static final FileTime PINNED = FileTime.fromMillis(315_532_800_000L);

    private RegistryStub registry;
    private String baseRef;

    @BeforeEach
    void start() throws IOException {
        registry = RegistryStub.open();
        baseRef = registry.publishImage("base-" + System.nanoTime(), "1");
        // The fixtures are written just before the build; judge them as long settled.
        Settled.aheadForTests(Duration.ofMinutes(1));
    }

    @AfterEach
    void stop() {
        Settled.aheadForTests(null);
        registry.close();
    }

    @Test
    void a_second_node_image_of_unchanged_node_modules_ships_the_kept_layer(@TempDir Path tmp) throws Exception {
        Node n = node(tmp);
        LayeredImage.writeToTarball(n.plan(), tmp.resolve("a.tar"), n.cache(), anonymous());
        Map<String, byte[]> first = untar(Files.readAllBytes(tmp.resolve("a.tar")));

        Path blob = keptBlob(n.cache());
        assertThat(Files.readAllBytes(blob))
                .isEqualTo(first.get(layerNames(first).get(0)));
        byte[] marker = "kept-layer".getBytes(StandardCharsets.UTF_8);
        Files.delete(blob);
        Files.write(blob, marker);

        LayeredImage.writeToTarball(n.plan(), tmp.resolve("b.tar"), n.cache(), anonymous());

        Map<String, byte[]> second = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        assertThat(layerNames(second)).hasSize(2);
        assertThat(second.get(layerNames(second).get(0)))
                .as("the node_modules layer, as kept")
                .isEqualTo(marker);
    }

    @Test
    void a_node_module_rewritten_in_place_under_its_old_size_and_time_ships_its_new_bytes(@TempDir Path tmp)
            throws Exception {
        Node n = node(tmp);
        LayeredImage.writeToTarball(n.plan(), tmp.resolve("a.tar"), n.cache(), anonymous());

        Path index = n.deps().resolve("greet/index.js");
        Files.writeString(index, "module.exports = 'yo';");
        Files.setLastModifiedTime(index, PINNED);
        LayeredImage.writeToTarball(n.plan(), tmp.resolve("b.tar"), n.cache(), anonymous());

        Map<String, byte[]> image = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        Map<String, byte[]> deps = layer(image, layerNames(image).get(0));
        assertThat(new String(deps.get("app/node_modules/greet/index.js"), StandardCharsets.UTF_8))
                .isEqualTo("module.exports = 'yo';");
    }

    @Test
    void a_tree_written_inside_the_settle_window_is_not_kept(@TempDir Path tmp) throws Exception {
        Node n = node(tmp);
        // Its mtime is pinned in the past, but the write that pinned it was just now.
        Settled.aheadForTests(null);

        LayeredImage.writeToTarball(n.plan(), tmp.resolve("a.tar"), n.cache(), anonymous());

        assertThat(blobs(n.cache())).isEmpty();
    }

    @Test
    void the_app_layer_of_a_node_image_is_not_kept(@TempDir Path tmp) throws Exception {
        Node n = node(tmp);
        LayeredImage.writeToTarball(n.plan(), tmp.resolve("a.tar"), n.cache(), anonymous());

        assertThat(blobs(n.cache())).as("node_modules alone").hasSize(1);
    }

    @Test
    void an_app_tree_relaid_after_a_code_edit_ships_its_kept_lib_layer(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("quarkus-app");
        layAppTree(app, "CODE-1");
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        ImageBuilder.Plan plan = appTreePlan(tmp, app);
        ImageBuilder.writeToTarball(plan, tmp.resolve("a.tar"), cache, anonymous());
        Map<String, byte[]> first = untar(Files.readAllBytes(tmp.resolve("a.tar")));
        assertThat(layerNames(first)).hasSize(2);

        Path blob = keptBlob(cache);
        assertThat(Files.readAllBytes(blob))
                .isEqualTo(first.get(layerNames(first).get(0)));
        byte[] marker = "kept-lib".getBytes(StandardCharsets.UTF_8);
        Files.delete(blob);
        Files.write(blob, marker);

        // The packager lays the whole tree down again: lib/ under its old bytes, app/ changed.
        PathUtil.deleteRecursively(app);
        layAppTree(app, "CODE-2");
        ImageBuilder.writeToTarball(plan, tmp.resolve("b.tar"), cache, anonymous());

        Map<String, byte[]> second = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        assertThat(layerNames(second)).hasSize(2);
        assertThat(second.get(layerNames(second).get(0)))
                .as("the lib layer, as kept")
                .isEqualTo(marker);
        Map<String, byte[]> application = layer(second, layerNames(second).get(1));
        assertThat(new String(application.get("app/app/quark.jar"), StandardCharsets.UTF_8))
                .isEqualTo("CODE-2");
        assertThat(application).doesNotContainKey("app/lib/main/dep.jar");
        assertThat(blobs(cache)).as("lib/ alone").hasSize(1);
    }

    @Test
    void an_app_tree_lib_jar_rewritten_under_its_old_name_ships_its_new_bytes(@TempDir Path tmp) throws Exception {
        Path app = tmp.resolve("quarkus-app");
        layAppTree(app, "CODE");
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        ImageBuilder.Plan plan = appTreePlan(tmp, app);
        ImageBuilder.writeToTarball(plan, tmp.resolve("a.tar"), cache, anonymous());

        Files.writeString(app.resolve("lib/main/dep.jar"), "DEP-2");
        ImageBuilder.writeToTarball(plan, tmp.resolve("b.tar"), cache, anonymous());

        Map<String, byte[]> image = untar(Files.readAllBytes(tmp.resolve("b.tar")));
        Map<String, byte[]> lib = layer(image, layerNames(image).get(0));
        assertThat(new String(lib.get("app/lib/main/dep.jar"), StandardCharsets.UTF_8))
                .isEqualTo("DEP-2");
    }

    /** A fast-jar tree: run jar, {@code lib/} and an application jar holding {@code code}. */
    private static void layAppTree(Path app, String code) throws IOException {
        Files.createDirectories(app.resolve("lib/main"));
        Files.createDirectories(app.resolve("lib/boot"));
        Files.createDirectories(app.resolve("app"));
        Files.writeString(app.resolve("quarkus-run.jar"), "RUN");
        Files.writeString(app.resolve("lib/main/dep.jar"), "DEP");
        Files.writeString(app.resolve("lib/boot/boot.jar"), "BOOT");
        Files.writeString(app.resolve("app/quark.jar"), code);
    }

    private ImageBuilder.Plan appTreePlan(Path tmp, Path app) {
        return new ImageBuilder.Plan(
                config(),
                "quark",
                "0.1.0",
                "com.example.Application",
                tmp.resolve("quark-0.1.0.jar"),
                List.of(),
                List.of(),
                null,
                Map.of(),
                app,
                "quarkus-run.jar",
                null);
    }

    private record Node(LayeredImage.Plan plan, Path cache, Path deps) {}

    private Node node(Path tmp) throws IOException {
        Path deps = tmp.resolve("prod/node_modules");
        Files.createDirectories(deps.resolve("greet"));
        Files.writeString(deps.resolve("greet/index.js"), "module.exports = 'hi';");
        Files.setLastModifiedTime(deps.resolve("greet/index.js"), PINNED);
        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("server.js"), "require('greet');");
        LayeredImage.Plan plan = new LayeredImage.Plan(
                config(),
                "web",
                "0.1.0",
                List.of(
                        new LayeredImage.Layer("dependencies", deps, "/app/node_modules"),
                        new LayeredImage.Layer("app", app, "/app")),
                List.of("node", "server.js"),
                "/app");
        return new Node(plan, Files.createDirectories(tmp.resolve("cache")), deps);
    }

    private ImageConfig config() {
        return new ImageConfig(
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
    }

    private RegistryAuth anonymous() {
        return RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, null);
    }

    /** The one compressed layer kept under {@code cache}. */
    private static Path keptBlob(Path cache) throws IOException {
        List<Path> blobs = blobs(cache);
        assertThat(blobs).hasSize(1);
        return blobs.get(0);
    }

    /** Every compressed layer kept under {@code cache}. */
    private static List<Path> blobs(Path cache) throws IOException {
        Path root = CacheTree.IMAGE_LAYERS.under(cache);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p ->
                            root.relativize(p).toString().replace('\\', '/').contains("/jib/layers/"))
                    .toList();
        }
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
