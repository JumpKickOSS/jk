// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A node build's image: its layers in order, at their image paths, and how the container starts. */
class LayeredImageTest {

    private FakeRegistry registry;
    private String baseRef;

    @BeforeEach
    void start() throws IOException {
        registry = FakeRegistry.open();
        baseRef = registry.publishImage("nodejs-" + System.nanoTime(), "1");
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    @Test
    void a_node_server_image_has_its_dependencies_under_its_app_and_starts_node(@TempDir Path tmp) throws Exception {
        Path deps = tmp.resolve("prod/node_modules");
        Files.createDirectories(deps.resolve("greet"));
        Files.writeString(deps.resolve("greet/index.js"), "module.exports = 'hi';");
        Files.createDirectories(deps.resolve(".bin"));
        Files.writeString(deps.resolve(".bin/greet"), "#!/bin/sh");
        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("server.js"), "require('greet');");
        Files.writeString(app.resolve("package.json"), "{}");
        Path tarball = tmp.resolve("out/image.tar");

        LayeredImage.writeToTarball(plan(app, deps, "server.js"), tarball, anonymous());

        Map<String, byte[]> image = untar(Files.readAllBytes(tarball));
        String config = new String(image.get("config.json"), StandardCharsets.UTF_8);
        assertThat(config)
                .contains("\"Entrypoint\":[\"node\",\"server.js\"]")
                .contains("\"WorkingDir\":\"/app\"")
                .contains("\"User\":\"65532\"")
                .contains("PORT=3000")
                .contains("NODE_ENV=production")
                .contains("\"3000/tcp\"");
        List<List<String>> layers = layers(image);
        assertThat(layers).hasSize(2);
        assertThat(layers.get(0))
                .as("dependencies first: they change least")
                .contains("app/node_modules/greet/index.js")
                .noneMatch(e -> e.contains(".bin"));
        assertThat(layers.get(1)).contains("app/server.js", "app/package.json");
    }

    @Test
    void an_edit_to_the_app_rewrites_the_app_layer_alone(@TempDir Path tmp) throws Exception {
        Path deps = tmp.resolve("prod/node_modules");
        Files.createDirectories(deps.resolve("greet"));
        Files.writeString(deps.resolve("greet/index.js"), "module.exports = 'hi';");
        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("server.js"), "one");

        LayeredImage.writeToTarball(plan(app, deps, "server.js"), tmp.resolve("a.tar"), anonymous());
        Files.writeString(app.resolve("server.js"), "two");
        LayeredImage.writeToTarball(plan(app, deps, "server.js"), tmp.resolve("b.tar"), anonymous());

        List<String> first = layerDigests(untar(Files.readAllBytes(tmp.resolve("a.tar"))));
        List<String> second = layerDigests(untar(Files.readAllBytes(tmp.resolve("b.tar"))));
        assertThat(second.get(0)).isEqualTo(first.get(0));
        assertThat(second.get(1)).isNotEqualTo(first.get(1));
    }

    private LayeredImage.Plan plan(Path app, Path deps, String main) {
        ImageConfig config = new ImageConfig(
                baseRef,
                null,
                "65532",
                List.of(3000),
                Map.of("PORT", "3000", "NODE_ENV", "production"),
                Map.of(),
                null,
                "1",
                List.of("linux/amd64"),
                null,
                null,
                null,
                false);
        return new LayeredImage.Plan(
                config,
                "web",
                "0.1.0",
                List.of(
                        new LayeredImage.Layer("dependencies", deps, "/app/node_modules"),
                        new LayeredImage.Layer("app", app, "/app")),
                List.of("node", main),
                "/app");
    }

    private RegistryAuth anonymous() {
        return RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, null);
    }

    private static final Pattern LAYER = Pattern.compile("\"Layers\":\\[([^]]*)]");

    /** The entry names of each layer, in the manifest's order. */
    private static List<List<String>> layers(Map<String, byte[]> image) throws IOException {
        List<List<String>> out = new ArrayList<>();
        for (String name : layerNames(image)) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(image.get(name)))) {
                out.add(new ArrayList<>(untar(in.readAllBytes()).keySet()));
            }
        }
        return out;
    }

    private static List<String> layerDigests(Map<String, byte[]> image) {
        return layerNames(image);
    }

    private static List<String> layerNames(Map<String, byte[]> image) {
        String manifest = new String(image.get("manifest.json"), StandardCharsets.UTF_8);
        Matcher m = LAYER.matcher(manifest);
        List<String> names = new ArrayList<>();
        if (!m.find()) return names;
        for (String quoted : m.group(1).split(",")) {
            String n = quoted.trim();
            if (!n.isEmpty()) names.add(n.substring(1, n.length() - 1));
        }
        return names;
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
            long size = Long.parseLong(
                    field(tar, pos + 124, 12).trim().isEmpty()
                            ? "0"
                            : field(tar, pos + 124, 12).trim(),
                    8);
            char type = (char) tar[pos + 156];
            pos += 512;
            if (type == '0' || type == 0) {
                byte[] body = new byte[(int) size];
                System.arraycopy(tar, pos, body, 0, (int) size);
                files.put(name.replaceFirst("^\\./", "").replaceFirst("^/", ""), body);
            }
            pos += (int) ((size + 511) / 512 * 512);
        }
        return files;
    }

    private static String field(byte[] tar, int at, int len) {
        int end = at;
        while (end < at + len && tar[end] != 0) end++;
        return new String(tar, at, end - at, StandardCharsets.US_ASCII);
    }
}
