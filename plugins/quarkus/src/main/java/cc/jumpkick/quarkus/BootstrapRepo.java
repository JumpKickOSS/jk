// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The augment's private Maven repository, holding the jars jk built: the application itself and
 * its workspace siblings, which no remote repository publishes.
 *
 * <p>Quarkus's model resolver reads the application artifact's descriptor through Aether before it
 * collects the closure, and Aether serves a descriptor from the POM beside the jar. A coordinate
 * installed without one is asked of every remote repository, so Central's rate-limit 403 or an
 * offline session fails the augment on a coordinate that was never Central's to answer. Every
 * install here lands the jar and a minimal POM together, and the descriptor is a local read.
 */
final class BootstrapRepo {

    /** The install half of the bootstrap resolver: one file, under one coordinate, of one type. */
    @FunctionalInterface
    interface Installer {
        void install(String group, String artifact, String version, String type, Path file) throws Exception;
    }

    /**
     * Install {@code jar} as {@code group:artifact:version} with a POM saying only what the
     * coordinate is; the closure is declared directly, so the POM carries no dependencies.
     *
     * @param staging where the POM is written before the repository copies it in
     */
    static void install(Installer repo, String group, String artifact, String version, Path jar, Path staging)
            throws Exception {
        repo.install(group, artifact, version, "jar", jar);
        Path pom = Files.createDirectories(staging).resolve(artifact + "-" + version + ".pom");
        Files.writeString(pom, pom(group, artifact, version), StandardCharsets.UTF_8);
        repo.install(group, artifact, version, "pom", pom);
    }

    /** The minimal POM for a jar coordinate: model version and GAV, nothing else. */
    static String pom(String group, String artifact, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }

    private BootstrapRepo() {}
}
