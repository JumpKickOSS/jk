// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * An image read back for assertions: its config JSON, and each layer's id and file names, from a
 * {@code jk image --tarball} archive or from what a {@link RegistryStub} was pushed.
 */
public final class OciImages {

    private static final Pattern TARBALL_LAYERS = Pattern.compile("\"Layers\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern TARBALL_CONFIG = Pattern.compile("\"Config\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MANIFEST_CONFIG =
            Pattern.compile("\"config\"\\s*:\\s*\\{[^}]*\"digest\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MANIFEST_LAYERS = Pattern.compile("\"layers\"\\s*:\\s*\\[(.*)\\]", Pattern.DOTALL);
    private static final Pattern DIGEST = Pattern.compile("\"digest\"\\s*:\\s*\"([^\"]+)\"");

    private OciImages() {}

    /**
     * One image: {@code layerIds} in order (a tarball's layer paths or a registry's digests, each
     * naming the layer's bytes), each layer's regular files, and the config JSON.
     */
    public record Image(String config, List<String> layerIds, List<List<String>> layerFiles) {

        /** Every regular file across the layers. */
        public List<String> files() {
            List<String> all = new ArrayList<>();
            layerFiles.forEach(all::addAll);
            return all;
        }
    }

    /** The image in a docker-format tarball ({@code manifest.json}, config, gzipped layers). */
    public static Image fromTarball(Path tarball) throws IOException {
        Map<String, byte[]> entries = untar(Files.readAllBytes(tarball));
        String manifest = text(Objects.requireNonNull(entries.get("manifest.json"), "manifest.json"));
        Matcher config = TARBALL_CONFIG.matcher(manifest);
        if (!config.find()) throw new IOException("no Config in manifest.json: " + manifest);
        List<String> ids = new ArrayList<>();
        Matcher layers = TARBALL_LAYERS.matcher(manifest);
        if (layers.find()) {
            for (String quoted : layers.group(1).split(",")) {
                String id = quoted.trim();
                if (!id.isEmpty()) ids.add(id.substring(1, id.length() - 1));
            }
        }
        List<List<String>> files = new ArrayList<>();
        for (String id : ids) files.add(layerFiles(Objects.requireNonNull(entries.get(id), id)));
        return new Image(text(Objects.requireNonNull(entries.get(config.group(1)), config.group(1))), ids, files);
    }

    /** The image {@code registry} holds at {@code <repo>:<tag>}. */
    public static Image fromRegistry(RegistryStub registry, String repo, String tag) throws IOException {
        String manifest = registry.manifest(repo, tag);
        if (manifest == null) throw new IOException("no manifest for " + repo + ":" + tag);
        Matcher config = MANIFEST_CONFIG.matcher(manifest);
        if (!config.find()) throw new IOException("no config in manifest: " + manifest);
        List<String> ids = new ArrayList<>();
        Matcher layers = MANIFEST_LAYERS.matcher(manifest);
        if (layers.find()) {
            Matcher digest = DIGEST.matcher(layers.group(1));
            while (digest.find()) ids.add(digest.group(1));
        }
        List<List<String>> files = new ArrayList<>();
        for (String id : ids) files.add(layerFiles(Objects.requireNonNull(registry.blob(id), id)));
        return new Image(text(Objects.requireNonNull(registry.blob(config.group(1)), config.group(1))), ids, files);
    }

    private static List<String> layerFiles(byte[] gzipped) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return new ArrayList<>(untar(in.readAllBytes()).keySet());
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
            String octal = field(tar, pos + 124, 12).trim();
            long size = octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
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

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
