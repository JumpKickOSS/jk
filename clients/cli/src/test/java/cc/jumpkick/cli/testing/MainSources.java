// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import cc.jumpkick.cli.tui.CommandWedge;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates the CLI module's {@code src/main/java} for the guards that scan source text rather than
 * behaviour (a rule like "only dispatch begins an envelope" has no runtime seam to assert on).
 *
 * <p>Discovery must not rely on the process CWD: under {@code jk build} the test JVM's
 * {@code user.dir} is often the engine state tree, not the checkout. It walks up from a compiled
 * class instead, and every entry point returns empty rather than throwing so a packaged-jar run
 * skips the scan instead of failing it.
 */
public final class MainSources {
    private MainSources() {}

    /** Proof that the tree found is the cli module's, not some other {@code src/main/java}. */
    private static final String MARKER = "cc/jumpkick/cli/tui/CommandWedge.java";

    /** {@code clients/cli/src/main/java}, or the module-local {@code src/main/java}. */
    public static Optional<Path> locate() {
        for (Path start : List.of(
                resourcePath(CommandWedge.class, "CommandWedge.class"),
                codeSourcePath(CommandWedge.class),
                codeSourcePath(MainSources.class),
                Path.of("").toAbsolutePath().normalize())) {
            Path found = walkUp(start);
            if (found != null) return Optional.of(found);
        }
        return Optional.empty();
    }

    private static Path resourcePath(Class<?> type, String resourceName) {
        try {
            var url = type.getResource(resourceName);
            if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) return null;
            Path file = Path.of(url.toURI()).toAbsolutePath().normalize();
            return Files.isRegularFile(file) ? file.getParent() : file;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Path codeSourcePath(Class<?> type) {
        try {
            var cs = type.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            if (!"file".equalsIgnoreCase(cs.getLocation().getProtocol())) return null;
            Path p = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
            // Directory of classes, or a jar file — walk from parent when jar.
            return Files.isRegularFile(p) ? p.getParent() : p;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Path walkUp(Path start) {
        if (start == null) return null;
        Path dir = start.toAbsolutePath().normalize();
        for (int i = 0; i < 20 && dir != null; i++) {
            Path moduleMain = dir.resolve("src/main/java");
            if (Files.isRegularFile(moduleMain.resolve(MARKER))) {
                return moduleMain;
            }
            Path monorepoMain = dir.resolve("clients/cli/src/main/java");
            if (Files.isRegularFile(monorepoMain.resolve(MARKER))) {
                return monorepoMain;
            }
            // JumpKick module output: workspace/target/<module-rel>/classes/... → sources live under
            // workspace/<module-rel>/src/main/java, not under target/.
            if ("target".equals(fileName(dir))) {
                Path workspace = dir.getParent();
                if (workspace != null) {
                    Path underWorkspace = workspace.resolve("clients/cli/src/main/java");
                    if (Files.isRegularFile(underWorkspace.resolve(MARKER))) {
                        return underWorkspace;
                    }
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String fileName(Path p) {
        Path f = p.getFileName();
        return f == null ? "" : f.toString();
    }
}
