// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.ConfigSources;
import cc.jumpkick.config.WorkspaceLocator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** The project a directory belongs to: its workspace root when it is a member, else the nearest {@code jk.toml}. */
public final class ProjectRoots {

    private ProjectRoots() {}

    /** Empty when no {@code jk.toml} is at or above {@code start}. */
    public static Optional<Path> find(Path start) {
        Path abs = start.toAbsolutePath().normalize();
        Path toml = ConfigSources.findProjectConfig(abs);
        if (toml == null) return Optional.empty();
        Path dir = Objects.requireNonNull(toml.getParent(), "jk.toml dir");
        try {
            return Optional.of(WorkspaceLocator.findRoot(dir).orElse(dir));
        } catch (IOException e) {
            return Optional.of(dir);
        }
    }
}
