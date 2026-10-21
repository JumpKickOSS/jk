// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A Node install ready to run, with the package manager a project uses: where {@code node},
 * {@code npm} and {@code npx} are, the manager's command, and the {@code PATH} entries a process
 * needs in front so a script that calls {@code node}, {@code npm} or the manager by name gets these.
 *
 * @param source {@code jk} for a managed install, else the manager that put it there ({@code nvm},
 *     {@code system}, …)
 * @param manager the provisioned pnpm / yarn / bun; {@code null} for npm
 */
public record NodeHome(
        Path home, String version, String source, @Nullable ManagerHome manager) {

    public NodeHome {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(source, "source");
    }

    /** A provisioned pnpm, Yarn or bun install. */
    public record ManagerHome(PackageManager manager, String version, Path home) {

        public ManagerHome {
            Objects.requireNonNull(manager, "manager");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(home, "home");
        }

        /** The shims that run this manager by name ({@code yarn}, {@code pnpx}, {@code bunx}). */
        public Path shimDir() {
            return home.resolve(PackageManagerShims.DIR);
        }
    }

    public NodeHome withManager(@Nullable ManagerHome m) {
        return new NodeHome(home, version, source, m);
    }

    public Path node() {
        return BuildTool.NODE.launcher(home);
    }

    /** The directory holding {@code node}, {@code npm} and {@code npx}. */
    public Path binDir() {
        return Os.isWindows() ? home : home.resolve("bin");
    }

    public Path npm() {
        return binDir().resolve(Os.isWindows() ? "npm.cmd" : "npm");
    }

    public Path npx() {
        return binDir().resolve(Os.isWindows() ? "npx.cmd" : "npx");
    }

    public PackageManager packageManager() {
        return manager == null ? PackageManager.NPM : manager.manager();
    }

    /** The argv that runs the project's package manager, before its own arguments. */
    public List<String> managerCommand() throws IOException {
        ManagerHome m = manager;
        Optional<BuildTool> tool = m == null ? Optional.empty() : m.manager().tool();
        if (m == null || tool.isEmpty()) {
            return List.of(
                    node().toString(), npmModule().resolve("bin/npm-cli.js").toString());
        }
        Path entry = PackageManagerShims.entry(tool.get(), m.home());
        return PackageManagerShims.isScript(entry)
                ? List.of(node().toString(), entry.toString())
                : List.of(entry.toString());
    }

    /** {@code PATH} entries to put first: the manager's shims, then Node's binaries. */
    public List<Path> pathPrefix() {
        List<Path> out = new ArrayList<>(2);
        ManagerHome m = manager;
        if (m != null) out.add(m.shimDir());
        out.add(binDir());
        return out;
    }

    /** {@link #pathPrefix()} in front of {@code inherited}. */
    public String path(@Nullable String inherited) {
        String out = inherited;
        List<Path> prefix = pathPrefix();
        for (int i = prefix.size() - 1; i >= 0; i--)
            out = SearchPath.prepend(prefix.get(i).toString(), out);
        return out == null ? "" : out;
    }

    private Path npmModule() {
        return (Os.isWindows() ? home : home.resolve("lib")).resolve("node_modules/npm");
    }
}
