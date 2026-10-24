// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Coordinate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A sandboxed JVM's workspace worker closure reads Central from the host's local repository. */
class PomRuntimeClasspathHostM2Test {

    @Test
    void a_host_stores_central_artifacts_are_read_from_the_host_m2_not_the_sandbox_m2(@TempDir Path tmp)
            throws Exception {
        Path host = tmp.resolve("host-store");
        Path hostM2 = tmp.resolve("host-m2");
        Path sandbox = Files.createDirectories(tmp.resolve("sandbox-home"));
        Path sandboxM2 = Files.createDirectories(tmp.resolve("sandbox-m2"));
        Coordinate worker = Coordinate.of("cc.jumpkick", "jk-host-worker", "1.0.0");
        Coordinate dep = Coordinate.of("org.example", "lib", "1.0");
        Path workspaceJar = tmp.resolve("target/plugins/host-worker/jk-host-worker-1.0.0.jar");
        Path moduleOut = requireNonNull(workspaceJar.getParent());
        Files.createDirectories(moduleOut.resolve("classes").resolve("main"));
        Files.writeString(workspaceJar, "workspace-worker");
        Path shelf = host.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        write(shelf.resolve(MavenLayout.artifactPath(worker)), "store-worker");
        write(shelf.resolve(MavenLayout.pomPath(worker)), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-host-worker</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path depJar = write(hostM2.resolve(MavenLayout.artifactPath(dep)), "dep-bytes");
        write(hostM2.resolve(MavenLayout.pomPath(dep)), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>1.0</version>
                </project>
                """);

        List<String> keys = List.of(
                "jk.env.JK_HOME",
                PomRuntimeClasspath.HOST_STORE_PROPERTY,
                PomRuntimeClasspath.HOST_M2_PROPERTY,
                "jk.m2.local");
        List<String> previous = keys.stream().map(System::getProperty).toList();
        try {
            System.setProperty("jk.env.JK_HOME", sandbox.toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    host.toAbsolutePath().toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_M2_PROPERTY,
                    hostM2.toAbsolutePath().toString());
            System.setProperty("jk.m2.local", sandboxM2.toString());
            Path found = PomRuntimeClasspath.resolve(workspaceJar).stream()
                    .filter(p -> "lib-1.0.jar".equals(String.valueOf(p.getFileName())))
                    .findFirst()
                    .orElseThrow();
            assertThat(found).hasSameBinaryContentAs(depJar);
        } finally {
            for (int i = 0; i < keys.size(); i++) {
                if (previous.get(i) == null) System.clearProperty(keys.get(i));
                else System.setProperty(keys.get(i), previous.get(i));
            }
        }
    }

    private static Path write(Path file, String content) throws Exception {
        Files.createDirectories(requireNonNull(file.getParent()));
        return Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
