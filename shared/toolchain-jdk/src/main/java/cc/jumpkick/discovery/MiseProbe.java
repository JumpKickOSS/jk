// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.jdk.JdkHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Discovers mise installs under {@code <data-dir>/installs/…}
 * ({@code MISE_DATA_DIR} → {@code XDG_DATA_HOME/mise} → {@code ~/.local/share/mise}).
 */
public final class MiseProbe implements LocalToolProbe {

    private final Path dataDir;

    public MiseProbe() {
        this(resolveDataDir(System::getenv, System.getProperty("user.home", "")));
    }

    MiseProbe(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public String name() {
        return "mise";
    }

    @Override
    public Optional<DiscoveredTool> find(ToolSpec spec) throws IOException {
        Path kindDir = dataDir.resolve("installs").resolve(spec.kind());
        if (!Files.isDirectory(kindDir)) return Optional.empty();
        try (Stream<Path> entries = Files.list(kindDir)) {
            return entries.filter(Files::isDirectory)
                    // Skip mise's `latest` symlink so we don't double-report.
                    .filter(p -> !"latest".equals(p.getFileName().toString()))
                    .map(path -> {
                        try {
                            return path.toRealPath();
                        } catch (IOException e) {
                            return path;
                        }
                    })
                    .filter(path -> ToolHealth.isHealthy(spec, path))
                    .findFirst()
                    .map(path -> new DiscoveredTool(path, spec.version(), name()));
        }
    }

    @Override
    public List<JdkHit> discoverAllJdks() throws IOException {
        Path javaDir = dataDir.resolve("installs").resolve("java");
        if (!Files.isDirectory(javaDir)) return List.of(); // fail fast
        List<JdkHit> hits = new ArrayList<>();
        try (Stream<Path> entries = Files.list(javaDir)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !"latest".equals(p.getFileName().toString()))
                    .forEach(p -> ProbeSupport.discoverJdk(p, name()).ifPresent(hits::add));
        }
        return hits;
    }

    /** The variable naming the XDG data directory version managers install under. */
    public static final String DATA_HOME_ENV = "XDG_DATA_HOME";

    /**
     * Resolve mise's data dir per <a href="https://mise.jdx.dev/configuration.html">mise's config
     * docs</a>.
     */
    public static Path resolveDataDir(Function<String, @Nullable String> env, String userHome) {
        String miseData = env.apply("MISE_DATA_DIR");
        if (miseData != null && !miseData.isBlank()) return Path.of(miseData);
        return xdgDataHome(env, userHome).resolve("mise");
    }

    /**
     * The XDG data directory mise and fnm keep their installs under: {@value #DATA_HOME_ENV}, else
     * {@code ~/.local/share}.
     */
    public static Path xdgDataHome(Function<String, @Nullable String> env, String userHome) {
        String xdg = env.apply(DATA_HOME_ENV);
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg);
        return Path.of(userHome, ".local", "share");
    }
}
