// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [image]} table: what {@code jk image} builds and where it pushes. Every field is
 * unset in {@link #EMPTY}; the user-global table under {@code ~/.jk/config.toml} fills the gaps.
 */
public record ImageTable(
        @Nullable String base,
        /** {@code image.name} — the image repository; default the module's artifact id. */
        @Nullable String name,
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

    /** No {@code [image]} table — every field unset. */
    public static final ImageTable EMPTY = new ImageTable(
            null, null, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null, null, null, null);

    public ImageTable {
        ports = ports == null ? List.of() : List.copyOf(ports);
        env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
    }

    /** Whether no key of the table is set. */
    public boolean isEmpty() {
        return equals(EMPTY);
    }
}
