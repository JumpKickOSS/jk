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
        /** Repository name of the image; null → the module's artifact id. */
        @Nullable String name,
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

    public ImageConfig {
        ports = ports == null ? List.of() : List.copyOf(ports);
        env = env == null ? Map.of() : Map.copyOf(env);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        platforms = (platforms == null || platforms.isEmpty()) ? List.of("linux/amd64") : List.copyOf(platforms);
        // base, name, user, registry, tag, main, dockerExecutable, dockerFile may be null
    }

    /**
     * Resolve the final {@code <registry>/<image>:<tag>} target: {@code name} when set, else the
     * module's {@code artifact}; {@code tag} when set, else {@code version}.
     */
    public String targetReference(String artifact, String version) {
        String repository = repository(artifact);
        String image = (registry != null && !registry.isBlank()) ? registry + "/" + repository : repository;
        return image + ":" + tagOr(version);
    }

    /** The image repository: {@code name} when set, else the module's artifact id. */
    public String repository(String artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return (name != null && !name.isBlank()) ? name : artifact;
    }

    /** The image tag: {@code tag} when set, else the module version. */
    public String tagOr(String version) {
        Objects.requireNonNull(version, "version");
        return (tag != null && !tag.isBlank()) ? tag : version;
    }
}
