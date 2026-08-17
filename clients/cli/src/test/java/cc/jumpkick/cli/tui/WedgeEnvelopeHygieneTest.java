// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the blank-line envelope rule: never print a rendered {@link CommandWedge} fail/ok/working
 * chip via raw {@code CliOutput} — use {@code printFail}/{@code printOk}/{@code printWorking} (or
 * {@code envelopeStart} + body) so the leading blank cannot be skipped.
 *
 * <p>Source discovery must not rely on process CWD — under {@code jk build} the engine worker
 * cwd is often {@code ~/.local/state/jk/engine/}, not the module or monorepo root.
 */
class WedgeEnvelopeHygieneTest {

    private static final Pattern ANTI = Pattern.compile(
            "CliOutput\\.(err|out)\\(\\s*(?:cc\\.jumpkick\\.cli\\.tui\\.)?CommandWedge\\.(fail|ok|working|chip)\\s*\\(");

    /** Marker so we know we found the cli module tree, not some other src/main/java. */
    private static final String MARKER = "cc/jumpkick/cli/tui/CommandWedge.java";

    @Test
    void no_raw_command_wedge_prints_without_print_helpers() throws IOException {
        Optional<Path> mainOpt = locateMainSources();
        assumeTrue(mainOpt.isPresent(), "cli main sources not adjacent to test classpath — skip hygiene scan");
        Path main = mainOpt.get();
        assertThat(main).exists();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(main)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p);
                    var m = ANTI.matcher(text);
                    while (m.find()) {
                        int line = 1
                                + (int) text.substring(0, m.start())
                                        .chars()
                                        .filter(c -> c == '\n')
                                        .count();
                        offenders.add(main.relativize(p) + ":" + line);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(offenders)
                .as("use CommandWedge.printFail/printOk/printWorking instead of CliOutput+(fail|ok|working|chip)")
                .isEmpty();
    }

    /**
     * Resolve {@code clients/cli/src/main/java} (or module-local {@code src/main/java}).
     *
     * <p>Does not use process CWD alone: under {@code jk build} the test JVM often has
     * {@code user.dir} under the engine state tree. Prefer walking up from the {@link CommandWedge}
     * class file (or this test class), which live under the checkout's {@code target/}.
     */
    static Optional<Path> locateMainSources() {
        for (Path start : List.of(
                resourcePath(CommandWedge.class, "CommandWedge.class"),
                codeSourcePath(CommandWedge.class),
                codeSourcePath(WedgeEnvelopeHygieneTest.class),
                Path.of("").toAbsolutePath().normalize())) {
            Path found = walkUpForMainSources(start);
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

    private static Path walkUpForMainSources(Path start) {
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
