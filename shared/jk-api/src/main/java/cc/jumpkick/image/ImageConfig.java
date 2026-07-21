// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.image;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved {@code [image]} config for {@code jk image}. {@code base} is non-null by the time this
 * reaches the image worker (CLI supplies a default when undeclared).
 */
public record ImageConfig(
        String base,
        String user,
        List<Integer> ports,
        Map<String, String> env,
        Map<String, String> labels,
        String registry,
        String tag,
        List<String> platforms,
        String main,
        /** Docker/Podman executable; null → auto-detect. */
        String dockerExecutable,
        /** Relative Dockerfile path; non-null → {@code docker build}, else Jib. */
        String dockerFile) {

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
