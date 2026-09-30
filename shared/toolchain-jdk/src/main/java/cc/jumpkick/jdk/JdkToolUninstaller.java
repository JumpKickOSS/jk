// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Delegation to the owning tool for {@code jk jdk uninstall}.
 *
 * <p>A JDK from a tool that keeps its own index (SDKMAN, mise, JBang, jenv, asdf, Homebrew, Jabba)
 * is removed through that tool's uninstall command, non-interactive and with a timeout, so the
 * tool's manifest stays in sync. When that leaves the directory in place, {@link JdkRegistry#purge}
 * deletes it only under the managed root ({@link JdkUninstallPolicy#deletable}).
 */
public final class JdkToolUninstaller {

    /** How long any one tool command is allowed to run before we abandon it. */
    private static final long TIMEOUT_SECONDS = 30;

    /** How the install went away, for the caller's success line. */
    public enum Outcome {
        HANDLED_BY_TOOL,
        /** jk deleted the directory under the managed root. */
        PURGED
    }

    private JdkToolUninstaller() {}

    /**
     * Uninstall {@code hit}: its owning tool first, then {@link JdkRegistry#purge} for whatever
     * remains. Throws when the directory remains outside the managed root; nothing is deleted then.
     */
    public static Outcome uninstall(JdkHit hit, JdkRegistry registry) throws IOException {
        List<String> command = commandFor(hit, JdkRegistry.identifierFor(hit.home()));
        // Some tools exit 0 without deleting, so the directory is the verdict.
        if (command != null && runQuietly(command) && !Files.exists(hit.home())) return Outcome.HANDLED_BY_TOOL;
        registry.purge(hit);
        return Outcome.PURGED;
    }

    /** Whether {@code hit}'s source has an owning-tool uninstall for this home. */
    static boolean hasRecipe(JdkHit hit) {
        return commandFor(hit, JdkRegistry.identifierFor(hit.home())) != null;
    }

    /**
     * The non-interactive command line for {@code hit.source()}'s owning tool; {@code null} when
     * there is no recipe for this source or home.
     */
    private static @Nullable List<String> commandFor(JdkHit hit, String identifier) {
        return switch (hit.source()) {
            // `sdk` is a shell function from sdkman-init.sh, not a binary —
            // source the init explicitly so this works under cron / non-login
            // shells too. SDKMAN's uninstall is non-interactive when given
            // both candidate and version.
            case "sdkman" ->
                List.of(
                        "bash",
                        "-c",
                        "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && "
                                + "sdk uninstall java "
                                + shellQuote(identifier));
            case "mise" -> List.of("mise", "uninstall", "--yes", "java@" + identifier);
            case "jbang" -> List.of("jbang", "jdk", "uninstall", identifier);
            // jenv only registers JDKs: `remove` drops the alias and leaves the files.
            case "jenv" -> List.of("jenv", "remove", identifier);
            case "asdf" -> List.of("asdf", "uninstall", "java", identifier);
            case "jabba" -> jabbaCommand(hit.home());
            // Homebrew installs land in {Cellar}/<formula>/<version>; the
            // formula name (e.g. "openjdk@21") is what `brew uninstall`
            // takes, not the install-folder name.
            case "homebrew" -> {
                String formula = homebrewFormulaFor(hit.home());
                yield formula == null ? null : List.of("brew", "uninstall", formula);
            }
            default -> null;
        };
    }

    /**
     * {@code <JABBA_HOME>/bin/jabba uninstall <name>} for a home at {@code <JABBA_HOME>/jdk/<name>}
     * (or its {@code Contents/Home}); {@code jabba} from the PATH when that binary is absent.
     */
    private static @Nullable List<String> jabbaCommand(Path home) {
        Path install = IntellijJdkDir.installDirOf(home);
        Path jdkDir = install.getParent();
        Path name = install.getFileName();
        if (jdkDir == null || name == null || jdkDir.getFileName() == null) return null;
        if (!"jdk".equals(jdkDir.getFileName().toString())) return null;
        Path jabbaHome = jdkDir.getParent();
        Path binary =
                jabbaHome == null ? null : jabbaHome.resolve("bin").resolve(Os.isWindows() ? "jabba.exe" : "jabba");
        String exe = binary != null && Files.isRegularFile(binary) ? binary.toString() : "jabba";
        return List.of(exe, "uninstall", name.toString());
    }

    /**
     * Walk up from a Homebrew JDK home to find the {@code Cellar/<formula>} segment and return the
     * formula name. Returns {@code null} when the path doesn't look like a Cellar install.
     */
    private static @Nullable String homebrewFormulaFor(Path home) {
        Path p = home;
        while (p != null && p.getParent() != null) {
            Path parent = p.getParent();
            if (parent.getFileName() != null
                    && "Cellar".equals(parent.getFileName().toString())) {
                return p.getFileName() != null ? p.getFileName().toString() : null;
            }
            p = parent;
        }
        return null;
    }

    /**
     * Fire-and-wait subprocess with stdio dropped — we don't want the tool's progress chatter mixed
     * into jk's own spinner output. Bounded by {@link #TIMEOUT_SECONDS}; anything that hangs longer
     * than that is killed and treated as a failure.
     */
    private static boolean runQuietly(List<String> command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Close stdin immediately so any unexpected prompt sees EOF
            // (most tools default to "no" on EOF, which is fine here since
            // we still verify via the dir-exists check afterwards).
            p.getOutputStream().close();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            // Most-common case: the tool's binary isn't on PATH.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Single-quote a token for {@code bash -c} interpolation. */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
