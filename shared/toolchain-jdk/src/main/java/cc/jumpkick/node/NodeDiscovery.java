// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.discovery.MiseProbe;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Node installs other managers already put on this machine, so jk links one instead of
 * downloading it: nvm, fnm, volta, mise, asdf, Homebrew, then the {@code node} on {@code PATH}.
 * Every manager's install is read from its directory name; only the {@code PATH} one runs {@code
 * node --version}.
 */
public final class NodeDiscovery {

    private static final Pattern VERSION = Pattern.compile("v?(\\d+\\.\\d+\\.\\d+)");

    private final Function<String, @Nullable String> env;
    private final Path userHome;
    private final List<Path> cellars;

    /** This machine's environment and home. */
    public NodeDiscovery() {
        this(System::getenv, Path.of(System.getProperty("user.home", "")), defaultCellars());
    }

    /** An explicit environment, home and Homebrew cellars: a test's fabricated machine. */
    public NodeDiscovery(Function<String, @Nullable String> env, Path userHome, List<Path> cellars) {
        this.env = Objects.requireNonNull(env, "env");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.cellars = List.copyOf(cellars);
    }

    /** Every install found, managers before {@code PATH}, newest first within each. */
    public List<DiscoveredNode> discover() {
        List<DiscoveredNode> found = new ArrayList<>();
        found.addAll(
                versionDirs("nvm", envDir("NVM_DIR", userHome.resolve(".nvm")).resolve("versions/node"), ""));
        for (Path fnm : fnmDirs()) found.addAll(versionDirs("fnm", fnm.resolve("node-versions"), "installation"));
        found.addAll(versionDirs(
                "volta", envDir("VOLTA_HOME", userHome.resolve(".volta")).resolve("tools/image/node"), ""));
        found.addAll(versionDirs("mise", miseDir().resolve("installs/node"), ""));
        found.addAll(versionDirs(
                "asdf", envDir("ASDF_DATA_DIR", userHome.resolve(".asdf")).resolve("installs/nodejs"), ""));
        for (Path cellar : cellars) found.addAll(homebrew(cellar));
        Set<Path> seen = new LinkedHashSet<>();
        for (DiscoveredNode d : found) seen.add(real(d.home()));
        for (DiscoveredNode d : onPath()) {
            if (seen.add(real(d.home()))) found.add(d);
        }
        return found;
    }

    /** The newest install that satisfies {@code spec}, judged against {@code releases} (may be empty). */
    public Optional<DiscoveredNode> find(NodeSpec spec, List<NodeRelease> releases) {
        return discover().stream()
                .filter(d -> NodeSelector.satisfies(spec, d.version(), releases))
                .max(Comparator.comparing(d -> VersionKey.of(d.version())));
    }

    private List<DiscoveredNode> versionDirs(String source, Path dir, String sub) {
        if (!Files.isDirectory(dir)) return List.of();
        List<DiscoveredNode> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (entry, attrs) -> {
                var m = VERSION.matcher(entry.getFileName().toString());
                if (!m.matches()) return true;
                Path home = sub.isEmpty() ? entry : entry.resolve(sub);
                if (Files.exists(BuildTool.NODE.launcher(home))) out.add(new DiscoveredNode(home, m.group(1), source));
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparing((DiscoveredNode d) -> VersionKey.of(d.version()))
                .reversed());
        return out;
    }

    /** {@code <cellar>/node/<v>} and {@code <cellar>/node@<major>/<v>}. */
    private List<DiscoveredNode> homebrew(Path cellar) {
        if (!Files.isDirectory(cellar)) return List.of();
        List<DiscoveredNode> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(cellar, (keg, attrs) -> {
                String n = keg.getFileName().toString();
                if (n.equals("node") || n.startsWith("node@")) out.addAll(versionDirs("brew", keg, ""));
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private List<DiscoveredNode> onPath() {
        String exe = BuildTool.NODE.binaryName();
        List<DiscoveredNode> out = new ArrayList<>();
        for (String entry : SearchPath.entries(env.apply("PATH"))) {
            Path dir = entry.isBlank() ? null : SearchPath.path(entry);
            if (dir == null) continue;
            Path node = dir.resolve(exe);
            if (!PathUtil.isRunnable(node)) continue;
            Path bin = node.getParent();
            Path home = bin == null || Os.isWindows() ? bin : bin.getParent();
            if (home == null) continue;
            String version = nodeVersion(node);
            if (version != null) out.add(new DiscoveredNode(home, version, "system"));
        }
        return out;
    }

    /** {@code node --version}, or null when it does not answer with a version within two seconds. */
    static @Nullable String nodeVersion(Path node) {
        try {
            Process p = new ProcessBuilder(node.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            var m = VERSION.matcher(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
            return m.matches() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private List<Path> fnmDirs() {
        String fnm = env.apply("FNM_DIR");
        if (fnm != null && !fnm.isBlank()) return List.of(Path.of(fnm));
        Path data = MiseProbe.xdgDataHome(env, userHome.toString());
        return List.of(
                data.resolve("fnm"), userHome.resolve("Library/Application Support/fnm"), userHome.resolve(".fnm"));
    }

    private Path miseDir() {
        return MiseProbe.resolveDataDir(env, userHome.toString());
    }

    private Path envDir(String name, Path fallback) {
        String v = env.apply(name);
        return v != null && !v.isBlank() ? Path.of(v) : fallback;
    }

    private static List<Path> defaultCellars() {
        String cellar = System.getenv("HOMEBREW_CELLAR");
        if (cellar != null && !cellar.isBlank()) return List.of(Path.of(cellar));
        return List.of(
                Path.of("/opt/homebrew/Cellar"),
                Path.of("/usr/local/Cellar"),
                Path.of("/home/linuxbrew/.linuxbrew/Cellar"));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** Numeric ordering of {@code x.y.z}; a missing or non-numeric part counts as 0. */
    public record VersionKey(int major, int minor, int patch) implements Comparable<VersionKey> {
        public static VersionKey of(String v) {
            String[] parts = v.split("\\.");
            return new VersionKey(num(parts, 0), num(parts, 1), num(parts, 2));
        }

        private static int num(String[] parts, int i) {
            try {
                return i < parts.length ? Integer.parseInt(parts[i]) : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        @Override
        public int compareTo(VersionKey o) {
            return Comparator.comparingInt(VersionKey::major)
                    .thenComparingInt(VersionKey::minor)
                    .thenComparingInt(VersionKey::patch)
                    .compare(this, o);
        }
    }
}
