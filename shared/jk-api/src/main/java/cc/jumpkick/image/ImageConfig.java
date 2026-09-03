// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.image;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resolved {@code [image]} config for {@code jk image}. {@code base} is non-null by the time this
 * reaches the image worker (CLI supplies a default when undeclared).
 */
public record ImageConfig(
        @Nullable String base,
        @Nullable String user,
        List<Integer> ports,
        Map<String, String> env,
        Map<String, String> labels,
        @Nullable String registry,
        @Nullable String tag,
        List<String> platforms,
        @Nullable String main,
        /** Docker/Podman executable; null → auto-detect. */
        @Nullable String dockerExecutable,
        /** Relative Dockerfile path; non-null → {@code docker build}, else Jib. */
        @Nullable String dockerFile,
        /**
         * Train a JVM AOT cache for the image. Off by default: it costs a container run at build
         * time and tens of MiB of image, and it only pays off for start-up-sensitive workloads.
         */
        boolean aotCache) {

    /** Without an AOT cache — the shape every existing caller builds. */
    public ImageConfig(
            @Nullable String base,
            @Nullable String user,
            List<Integer> ports,
            Map<String, String> env,
            Map<String, String> labels,
            @Nullable String registry,
            @Nullable String tag,
            List<String> platforms,
            @Nullable String main,
            @Nullable String dockerExecutable,
            @Nullable String dockerFile) {
        this(base, user, ports, env, labels, registry, tag, platforms, main, dockerExecutable, dockerFile, false);
    }

    public ImageConfig {
        ports = ports == null ? List.of() : List.copyOf(ports);
        env = env == null ? Map.of() : Map.copyOf(env);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        platforms = (platforms == null || platforms.isEmpty()) ? List.of("linux/amd64") : List.copyOf(platforms);
        // base, user, registry, tag, main, dockerExecutable, dockerFile may be null
    }

    /** Resolve the final {@code <registry>/<image>:<tag>} target. */
    public String targetReference(String artifact, String version) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(version, "version");
        String image = (registry != null && !registry.isBlank()) ? registry + "/" + artifact : artifact;
        String t = (tag != null && !tag.isBlank()) ? tag : version;
        return image + ":" + t;
    }
}
