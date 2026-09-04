// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [image]} table → plain data (concrete image types live outside {@code :core}). A table
 * parser, not a reader: the project document comes from {@link
 * JkBuildParser#imageConfig(java.nio.file.Path)} and the user-global one from {@link
 * GlobalConfig#image()}, each of which owns its file's read and error policy.
 */
public final class ManifestImage {

    public record ImageConfigData(
            @Nullable String base,
            @Nullable String user,
            List<Integer> ports,
            Map<String, String> env,
            Map<String, String> labels,
            @Nullable String registry,
            @Nullable String tag,
            List<String> platforms,
            @Nullable String main,
            /** {@code image.docker-executable} — override for the docker/podman CLI. */
            @Nullable String dockerExecutable,
            /** {@code image.docker-file} — relative path to a Dockerfile; enables Dockerfile mode. */
            @Nullable String dockerFile,
            /** {@code image.aot-cache} — train a JVM AOT cache into the image. */
            @Nullable Boolean aotCache) {

        /** No {@code [image]} table anywhere — every field unset. */
        public static final ImageConfigData EMPTY = new ImageConfigData(
                null, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null, null, null, null);
    }

    private ManifestImage() {}

    /**
     * Merge two {@link ImageConfigData} layers: {@code project} wins over {@code global} for every
     * field. String fields use the project value when non-blank; list fields use the project value
     * when non-empty; map fields are union-merged with project keys overriding global keys.
     */
    public static ImageConfigData merge(ImageConfigData project, ImageConfigData global) {
        String base = nonBlank(project.base()) != null ? project.base() : global.base();
        String user = nonBlank(project.user()) != null ? project.user() : global.user();
        String registry = nonBlank(project.registry()) != null ? project.registry() : global.registry();
        String tag = nonBlank(project.tag()) != null ? project.tag() : global.tag();
        String main = nonBlank(project.main()) != null ? project.main() : global.main();
        List<Integer> ports = !project.ports().isEmpty() ? project.ports() : global.ports();
        List<String> platforms = !project.platforms().isEmpty() ? project.platforms() : global.platforms();
        Map<String, String> env = new LinkedHashMap<>(global.env());
        env.putAll(project.env());
        Map<String, String> labels = new LinkedHashMap<>(global.labels());
        labels.putAll(project.labels());
        String dockerExecutable =
                nonBlank(project.dockerExecutable()) != null ? project.dockerExecutable() : global.dockerExecutable();
        String dockerFile = nonBlank(project.dockerFile()) != null ? project.dockerFile() : global.dockerFile();
        Boolean aotCache = project.aotCache() != null ? project.aotCache() : global.aotCache();
        return new ImageConfigData(
                base,
                user,
                ports,
                Map.copyOf(env),
                Map.copyOf(labels),
                registry,
                tag,
                platforms,
                main,
                dockerExecutable,
                dockerFile,
                aotCache);
    }

    private static @Nullable String nonBlank(@Nullable String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }

    /** {@link ImageConfigData#EMPTY} when the document has no {@code [image]} table. */
    static ImageConfigData parse(TomlTable root) {
        TomlTable image = root.getTable("image");
        if (image == null) return ImageConfigData.EMPTY;
        return new ImageConfigData(
                image.getString("base"),
                image.getString("user"),
                optionalIntList(image, "ports"),
                optionalStringMap(image, "env"),
                optionalStringMap(image, "labels"),
                image.getString("registry"),
                image.getString("tag"),
                optionalStringList(image, "platforms"),
                image.getString("main"),
                image.getString("docker-executable"),
                image.getString("docker-file"),
                image.getBoolean("aot-cache"));
    }

    private static List<String> optionalStringList(TomlTable table, String key) {
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `image." + key + "` to be a list of strings");
            }
            result.add(s);
        }
        return List.copyOf(result);
    }

    private static List<Integer> optionalIntList(TomlTable table, String key) {
        TomlArray arr = table.getArray(key);
        if (arr == null) return List.of();
        List<Integer> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof Long l)) {
                throw new JkBuildParseException("expected `image." + key + "` to be a list of integers");
            }
            result.add(l.intValue());
        }
        return List.copyOf(result);
    }

    private static Map<String, String> optionalStringMap(TomlTable parent, String key) {
        TomlTable t = parent.getTable(key);
        if (t == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : t.keySet()) {
            Object v = t.get(k);
            out.put(k, v == null ? "" : v.toString());
        }
        return out;
    }
}
