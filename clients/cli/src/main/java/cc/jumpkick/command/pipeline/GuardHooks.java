// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.layout.BuildLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk guard hooks [install]}: the git hooks that put the house rules at the commit boundary.
 *
 * <ul>
 *   <li>{@code commit-msg} hands the message to {@code jk guard commit-msg}, which refuses on any
 *       {@code commit} rule violation.
 *   <li>{@code pre-commit} is the protected-file check: a staged change to the guard baseline is
 *       refused unless {@code jk guard freeze} left its marker, and a staged line shaped like a guard
 *       suppression comment is refused outright — there is no suppression syntax, so one is always a
 *       workaround.
 * </ul>
 *
 * <p>POSIX {@code sh}: Git for Windows runs hooks through its own shell, so one script serves every
 * host. Advisory by nature — a hook is local state, and {@code --no-verify} skips it; the engine's
 * lanes and the CI history scan are the enforcement, the hooks are the early word.
 */
final class GuardHooks {

    /** Files the hooks protect; also written as {@code target/jk-guards.protected} for agent harnesses. */
    static final List<String> PROTECTED = List.of("jk-guards-baseline.toml", "jk-guards.toml");

    /** {@code jk guard freeze} touches this so the next commit may carry the baseline it grew. */
    static final String FREEZE_MARKER = "jk-guard-freeze";

    static final String PROTECTED_LIST = "jk-guards.protected";

    private GuardHooks() {}

    record Hook(String name, String script) {}

    static List<Hook> hooks() {
        return List.of(new Hook("commit-msg", commitMsg()), new Hook("pre-commit", preCommit()));
    }

    static String commitMsg() {
        return """
                #!/bin/sh
                # jk guard: judge the commit message against the [guards.*] commit rules.
                # Installed by `jk guard hooks install`; `jk guard hooks` prints this script.
                if ! command -v jk >/dev/null 2>&1; then
                  echo "jk guard: jk is not on PATH; commit-msg rules not checked" >&2
                  exit 0
                fi
                exec jk guard commit-msg "$1"
                """;
    }

    static String preCommit() {
        return """
                #!/bin/sh
                # jk guard: protected files. The baseline grows only through `jk guard freeze`, and there is
                # no suppression syntax, so a staged line shaped like one is a workaround.
                # Installed by `jk guard hooks install`; `jk guard hooks` prints this script.
                git_dir=$(git rev-parse --git-common-dir 2>/dev/null || echo .git)
                staged=$(git diff --cached --name-only --diff-filter=ACMR)
                if printf '%s\n' "$staged" | grep -qx 'jk-guards-baseline.toml'; then
                  if [ -f "$git_dir/jk-guard-freeze" ]; then
                    rm -f "$git_dir/jk-guard-freeze"
                  else
                    echo "jk guard: jk-guards-baseline.toml is staged but no 'jk guard freeze' wrote it;" >&2
                    echo "  the baseline grows only through 'jk guard freeze <id> --reason ...', never by hand" >&2
                    exit 1
                  fi
                fi
                if git diff --cached -U0 | grep -E '^[+]' | grep -Eiq '(jk-?guards?|guard)[:[:space:]-]*(ignore|off|disable|suppress)|@SuppressWarnings[(]"jk'; then
                  echo "jk guard: a staged line looks like a guard suppression; there is no suppression syntax —" >&2
                  echo "  fix the site per Instead, or ask the user to add an allow entry with a reason" >&2
                  exit 1
                fi
                exit 0
                """;
    }

    /** The printed form: every hook with a header naming it. */
    static String render() {
        StringBuilder sb = new StringBuilder();
        for (Hook h : hooks()) {
            sb.append("# ---- .git/hooks/")
                    .append(h.name())
                    .append(" ----\n")
                    .append(h.script())
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** What an install did or refused, one line per hook. */
    record Installed(List<String> written, List<String> refused, Path hooksDir, Path protectedList) {}

    /**
     * Write both hooks under the repository's hooks directory (the common git dir, so a worktree's
     * commits see them too) and the protected-file list under {@code target/}. An existing hook with
     * different content is refused unless {@code force} ({@code --replace}): overwriting someone's hook silently is the
     * kind of quiet change these hooks exist to catch.
     */
    static Installed install(Path repoDir, boolean force) throws IOException {
        Path gitDir = gitDir(repoDir);
        if (gitDir == null) throw new IOException(repoDir + " is not inside a git repository (no .git)");
        Path hooksDir = gitDir.resolve("hooks");
        Files.createDirectories(hooksDir);
        List<String> written = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (Hook h : hooks()) {
            Path target = hooksDir.resolve(h.name());
            if (Files.exists(target) && !force) {
                String have = Files.readString(target, StandardCharsets.UTF_8);
                if (!have.equals(h.script())) {
                    refused.add(h.name() + ": " + target
                            + " exists with other content; re-run with --replace to overwrite it");
                    continue;
                }
            }
            Files.writeString(target, h.script(), StandardCharsets.UTF_8);
            executable(target);
            written.add(h.name());
        }
        Path list = repoDir.resolve(BuildLayout.TARGET).resolve(PROTECTED_LIST);
        Files.createDirectories(list.getParent());
        Files.writeString(list, String.join("\n", PROTECTED) + "\n", StandardCharsets.UTF_8);
        return new Installed(written, refused, hooksDir, list);
    }

    /** Touch the freeze marker in the common git dir so the pre-commit hook lets the grown baseline through. */
    static void markFreeze(Path repoDir) {
        try {
            Path gitDir = gitDir(repoDir);
            if (gitDir == null) return;
            Files.writeString(gitDir.resolve(FREEZE_MARKER), "jk guard freeze\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the marker is a courtesy to the hook; a freeze that cannot leave one still froze
        }
    }

    /**
     * The common git directory of the repository containing {@code dir}: {@code .git} as a directory,
     * or the {@code gitdir:} pointer of a worktree or submodule, followed through {@code commondir}
     * so hooks land where every worktree's git reads them.
     */
    static @Nullable Path gitDir(Path dir) throws IOException {
        Path d = dir.toAbsolutePath().normalize();
        while (d != null) {
            Path dotGit = d.resolve(".git");
            if (Files.isDirectory(dotGit)) return dotGit;
            if (Files.isRegularFile(dotGit)) {
                String pointer =
                        Files.readString(dotGit, StandardCharsets.UTF_8).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                Path gitDir =
                        d.resolve(pointer.substring("gitdir:".length()).strip()).normalize();
                Path common = gitDir.resolve("commondir");
                if (Files.isRegularFile(common)) {
                    return gitDir.resolve(Files.readString(common, StandardCharsets.UTF_8)
                                    .strip())
                            .normalize();
                }
                return gitDir;
            }
            d = d.getParent();
        }
        return null;
    }

    private static void executable(Path file) {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // a filesystem without POSIX permissions (Windows) runs hooks regardless
        }
    }
}
