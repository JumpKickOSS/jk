// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.TomlTableReader.optionalArray;
import static cc.jumpkick.config.TomlTableReader.optionalBool;
import static cc.jumpkick.config.TomlTableReader.optionalString;
import static cc.jumpkick.config.TomlTableReader.optionalTable;
import static cc.jumpkick.config.TomlTableReader.rejectUnknownKeys;

import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * The {@code [image]} table → {@link ImageTable}. A table parser, not a reader: the project
 * document comes from {@link JkBuildParser}, which carries the result as {@link JkBuild#image()},
 * and the user-global one from {@link GlobalConfig#image()}; each owns its file's read and error
 * policy.
 */
public final class ManifestImage {

    private ManifestImage() {}

    /**
     * Merge two {@link ImageTable} layers: {@code project} wins over {@code global} for every
     * field. String fields use the project value when non-blank; list fields use the project value
     * when non-empty; map fields are union-merged with project keys overriding global keys.
     */
    public static ImageTable merge(ImageTable project, ImageTable global) {
        String base = nonBlank(project.base()) != null ? project.base() : global.base();
        String name = nonBlank(project.name()) != null ? project.name() : global.name();
        String user = nonBlank(project.user()) != null ? project.user() : global.user();
        String registry = nonBlank(project.registry()) != null ? project.registry() : global.registry();
        String tag = nonBlank(project.tag()) != null ? project.tag() : global.tag();
        String main = nonBlank(project.main()) != null ? project.main() : global.main();
        List<Integer> ports = !project.ports().isEmpty() ? project.ports() : global.ports();
        List<String> platforms = !project.platforms().isEmpty() ? project.platforms() : global.platforms();
        Map<String, String> env = new LinkedHashMap<>(global.env());
        env.putAll(project.env());
        Map<String, String> trainingEnv = new LinkedHashMap<>(global.trainingEnv());
        trainingEnv.putAll(project.trainingEnv());
        Map<String, String> labels = new LinkedHashMap<>(global.labels());
        labels.putAll(project.labels());
        String dockerExecutable =
                nonBlank(project.dockerExecutable()) != null ? project.dockerExecutable() : global.dockerExecutable();
        String dockerFile = nonBlank(project.dockerFile()) != null ? project.dockerFile() : global.dockerFile();
        Boolean aotCache = project.aotCache() != null ? project.aotCache() : global.aotCache();
        String kind = nonBlank(project.kind()) != null ? project.kind() : global.kind();
        Boolean referrers = project.referrers() != null ? project.referrers() : global.referrers();
        Boolean sigstore = project.sigstore() != null ? project.sigstore() : global.sigstore();
        return new ImageTable(
                base,
                name,
                user,
                ports,
                Map.copyOf(env),
                Map.copyOf(trainingEnv),
                Map.copyOf(labels),
                registry,
                tag,
                platforms,
                main,
                dockerExecutable,
                dockerFile,
                aotCache,
                kind,
                referrers,
                sigstore);
    }

    /**
     * A workspace member's table over the root's shared keys. The root answers for what is a
     * workspace fact — {@code base}, {@code user}, {@code registry}, {@code tag}, {@code env}, {@code training-env},
     * {@code labels}, {@code platforms}, {@code docker-executable}, {@code aot-cache}, {@code
     * referrers}, {@code sigstore} — and the member's own value wins per key, as it does over the user-global table.
     * What names one image never flows down: {@code name}, {@code main}, {@code ports} and {@code
     * docker-file} stay the member's, so two members cannot inherit one image name and push over
     * each other. A {@code nodeModule} takes neither the root's {@code base} nor its {@code
     * aot-cache}.
     */
    public static ImageTable inheritFromRoot(ImageTable member, ImageTable root, boolean nodeModule) {
        if (root.isEmpty()) return member;
        // A node module's image runs Node.js: the root's JRE base and JVM AOT cache are not its.
        ImageTable shared = new ImageTable(
                nodeModule ? null : root.base(),
                null,
                root.user(),
                List.of(),
                root.env(),
                root.trainingEnv(),
                root.labels(),
                root.registry(),
                root.tag(),
                root.platforms(),
                null,
                root.dockerExecutable(),
                null,
                nodeModule ? null : root.aotCache(),
                null,
                root.referrers(),
                root.sigstore());
        return merge(member, shared);
    }

    private static @Nullable String nonBlank(@Nullable String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }

    static final List<String> KEYS = List.of(
            "base",
            "name",
            "user",
            "ports",
            "env",
            "training-env",
            "labels",
            "registry",
            "tag",
            "platforms",
            "main",
            "docker-executable",
            "docker-file",
            "aot-cache",
            "kind",
            "referrers",
            "sigstore");

    /** {@link ImageTable#EMPTY} when the document has no {@code [image]} table. */
    static ImageTable parse(TomlTable root) {
        TomlTable image = optionalTable(root, "image", "`image`");
        if (image == null) return ImageTable.EMPTY;
        rejectUnknownKeys(image, KEYS, "[image]");
        return new ImageTable(
                string(image, "base"),
                string(image, "name"),
                string(image, "user"),
                optionalIntList(image, "ports"),
                optionalStringMap(image, "env"),
                optionalStringMap(image, "training-env"),
                optionalStringMap(image, "labels"),
                string(image, "registry"),
                string(image, "tag"),
                optionalStringList(image, "platforms"),
                string(image, "main"),
                string(image, "docker-executable"),
                string(image, "docker-file"),
                optionalBool(image, "aot-cache", "image.aot-cache"),
                kind(image),
                optionalBool(image, "referrers", "image.referrers"),
                optionalBool(image, "sigstore", "image.sigstore"));
    }

    private static @Nullable String string(TomlTable image, String key) {
        return optionalString(image, key, "image." + key);
    }

    /**
     * {@code image.kind}: unset for the module's own image, {@code "static"} for an nginx image of a
     * node build, {@code "native"} for an image of the {@code [native]} executable.
     */
    private static @Nullable String kind(TomlTable image) {
        String kind = string(image, "kind");
        if (kind != null && !kind.equals(ImageTable.KIND_STATIC) && !kind.equals(ImageTable.KIND_NATIVE)) {
            throw new JkBuildParseException("[image] kind must be \"" + ImageTable.KIND_STATIC + "\", \""
                    + ImageTable.KIND_NATIVE + "\" or unset, got `" + kind + "`");
        }
        return kind;
    }

    private static List<String> optionalStringList(TomlTable table, String key) {
        TomlArray arr = optionalArray(table, key, "image." + key);
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
        TomlArray arr = optionalArray(table, key, "image." + key);
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
        TomlTable t = optionalTable(parent, key, "image." + key);
        if (t == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : t.keySet()) {
            Object v = t.get(List.of(k));
            out.put(k, v == null ? "" : v.toString());
        }
        return out;
    }
}
