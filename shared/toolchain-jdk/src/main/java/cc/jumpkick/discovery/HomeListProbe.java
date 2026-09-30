// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A JDK probe that knows its candidate homes up front, from a directory listing or a file that
 * names them. A candidate that is not a full JDK is dropped by {@link ProbeSupport#discoverJdk}.
 */
abstract class HomeListProbe implements LocalToolProbe {

    /** Candidate JDK homes; an absent source is an empty list. */
    abstract List<Path> candidateHomes() throws IOException;

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        for (Path home : candidateHomes()) {
            if (Files.isDirectory(home) && ToolHealth.isHealthy(spec, home)) {
                return Optional.of(new DiscoveredTool(home, spec.version(), name()));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        List<JdkHit> hits = new ArrayList<>();
        for (Path home : candidateHomes()) {
            ProbeSupport.discoverJdk(home, name()).ifPresent(hits::add);
        }
        return hits;
    }

    /**
     * Every non-hidden directory directly under {@code root}, with a macOS {@code Contents/Home}
     * unwrapped. A missing root is an empty list.
     */
    static List<Path> childHomes(Path root) throws IOException {
        List<Path> children = new ArrayList<>();
        PathUtil.forEachChild(root, (child, attrs) -> {
            // A link to a directory arrives as a non-directory entry, so ask the path itself.
            if (!child.getFileName().toString().startsWith(".") && Files.isDirectory(child)) children.add(child);
            return true;
        });
        return children.stream().sorted().map(IntellijJdkDir::javaHome).toList();
    }
}
