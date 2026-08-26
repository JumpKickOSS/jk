// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.jdk.IntellijJdkDir;
import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.jdk.JdkOwnership;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Surfaces JDKs that {@code jk jdk install} placed under {@link JkDirs#jdks()} (IntelliJ shared
 * root by default; overridable via {@code JK_JDKS_DIR}). Only directories marked with
 * {@link cc.jumpkick.jdk.JdkOwnership#MARKER} are attributed to jk — other trees in the same
 * root remain IntelliJ/shared installs ({@link IntellijProbe}).
 *
 * <p>The macOS {@code Contents/Home} bundle unwrap is applied via {@link IntellijJdkDir#javaHome}
 * before the path is handed to {@link ProbeSupport#discoverJdk}, so jk-installed macOS tarballs
 * that ship as {@code .jdk} bundles work the same as flat Linux/Windows installs.
 */
public final class JkProbe implements LocalToolProbe {

    private final Path jdksRoot;
    private final boolean requireOwnership;

    /** Default shared IntelliJ root: only {@code .jk-owned} trees are attributed to jk. */
    public JkProbe() {
        this(JkDirs.jdks(), true);
    }

    /**
     * Explicit root ({@code --jdks-dir} / {@link cc.jumpkick.jdk.JdkRegistry#JdkRegistry(Path)}):
     * the caller declared this whole directory as the JDK root, so every valid install in it is
     * in scope — the ownership marker only disambiguates the <em>shared</em> root.
     */
    public JkProbe(Path jdksRoot) {
        this(jdksRoot, false);
    }

    private JkProbe(Path jdksRoot, boolean requireOwnership) {
        this.jdksRoot = jdksRoot;
        this.requireOwnership = requireOwnership;
    }

    @Override
    public String name() {
        return "jk";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        if (!"java".equals(spec.kind())) return Optional.empty();
        if (!Files.isDirectory(jdksRoot)) return Optional.empty();
        try (Stream<Path> entries = Files.list(jdksRoot)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !requireOwnership || JdkOwnership.isJkOwned(p))
                    .map(IntellijJdkDir::javaHome)
                    .filter(home -> ToolHealth.isHealthy(spec, home))
                    .findFirst()
                    .map(home -> new DiscoveredTool(home, spec.version(), name()));
        }
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        if (!Files.isDirectory(jdksRoot)) return List.of();
        List<JdkHit> hits = new ArrayList<>();
        try (Stream<Path> entries = Files.list(jdksRoot)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !requireOwnership || JdkOwnership.isJkOwned(p))
                    .map(IntellijJdkDir::javaHome)
                    .forEach(home -> ProbeSupport.discoverJdk(home, name()).ifPresent(hits::add));
        }
        return hits;
    }
}
