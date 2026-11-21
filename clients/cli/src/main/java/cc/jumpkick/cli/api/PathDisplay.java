// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.util.DirKeys;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Renders filesystem paths for human-facing output. Virtually every path jk prints should be
 * relative to a familiar anchor — the directory the command was run from, the enclosing workspace
 * root (root {@code jk.toml}), or the git repo root — so output stays short and recognizable.
 *
 * <p>{@link #of} relativizes against whichever of those three anchors sits <em>closest above</em>
 * the target (the deepest ancestor), which keeps paths short and guarantees no {@code ../} escapes.
 * A path is shown absolute only when it falls outside all three scopes, where a relative form would
 * be meaningless.
 */
public final class PathDisplay {

    /**
     * An absolute path standing as a word of its own: at the start, or after a space, bracket, quote
     * or {@code =}, so the slashes of a URL and a path already painted are left alone. It stops at a
     * colon, so a {@code :line:column} after it stays as written.
     */
    private static final Pattern ABSOLUTE_IN_TEXT = Pattern.compile(
            "(?<![^\\s(\\[\"'`=])(?:[A-Za-z]:)?[/\\\\][^\\s\\u001b\"'`\\]),;:]*[^\\s\\u001b\"'`\\]),;:.]");

    private PathDisplay() {}

    /**
     * {@code text} with every absolute path in it shown as {@link #of(Path)} shows it: what the engine
     * wrote of a file, such as a manifest's {@code file:line:column}, read from where jk ran. The
     * working directory itself stays as written, since {@code .} would name nothing.
     */
    public static @Nullable String inText(@Nullable String text) {
        if (text == null || (text.indexOf('/') < 0 && text.indexOf('\\') < 0)) return text;
        Matcher m = ABSOLUTE_IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(shownInText(m.group())));
        return m.appendTail(out).toString();
    }

    private static String shownInText(String token) {
        Path path;
        try {
            path = Path.of(token);
        } catch (InvalidPathException notAPath) {
            return token;
        }
        if (!path.isAbsolute()) return token;
        String shown = of(path);
        return shown.equals(".") ? token : shown;
    }

    /** {@link #of(Path, Path)} relativized and painted in the theme's {@code path} color. */
    public static String styled(Path target, Path workingDir) {
        return Theme.paint(of(target, workingDir), Theme.active().path());
    }

    /** {@link #of(Path)} relativized and painted in the theme's {@code path} color. */
    public static String styled(Path target) {
        return Theme.paint(of(target), Theme.active().path());
    }

    /**
     * Paint an already-rendered path string in the theme's {@code path} color, <em>without</em>
     * relativizing — for error/context messages whose path is the working directory itself
     * (relativizing it to "." would be useless).
     */
    public static String styledRaw(@Nullable Object pathLike) {
        return Theme.paint(String.valueOf(pathLike), Theme.active().path());
    }

    /**
     * Display {@code target} relative to the closest anchor, using the JVM working directory as the
     * "working dir" anchor. Convenient for static helpers with no {@link GlobalOptions} in scope —
     * the workspace-root and git-root anchors don't depend on the working dir, so a path inside the
     * project still renders relative regardless.
     */
    public static String of(Path target) {
        return of(target, null);
    }

    /**
     * Display {@code target} relative to the closest of {the working dir, workspace root, git repo
     * root} that contains it, else absolute. Always forward-slashed whatever the host separator is,
     * so one project reads the same in output, diagnostics and docs on every OS.
     *
     * @param target the path to render (relative paths resolve against {@link WorkingDirectory})
     * @param workingDir the command's working directory (see {@link GlobalOptions#workingDir()}); may
     *     be {@code null} to use {@link WorkingDirectory#current()}
     */
    public static String of(Path target, @Nullable Path workingDir) {
        Path abs = WorkingDirectory.resolve(target);
        Path anchor = closestAnchor(abs, workingDir);
        // DirKeys rewrites only real Windows paths — a POSIX file named a\b displays verbatim.
        if (anchor == null) return DirKeys.slashes(abs.toString());
        String rel = DirKeys.slashes(anchor.relativize(abs).toString());
        return rel.isEmpty() ? "." : rel;
    }

    /** The deepest ancestor of {@code abs} among the working dir, workspace root, and git root. */
    private static @Nullable Path closestAnchor(Path abs, @Nullable Path workingDir) {
        Path best = null;
        for (Path anchor : new Path[] {cwd(workingDir), workspaceRoot(abs), gitRoot(abs)}) {
            if (anchor != null
                    && abs.startsWith(anchor)
                    && (best == null || anchor.getNameCount() > best.getNameCount())) {
                best = anchor;
            }
        }
        return best;
    }

    private static Path cwd(@Nullable Path workingDir) {
        return workingDir != null ? WorkingDirectory.resolve(workingDir) : WorkingDirectory.current();
    }

    private static @Nullable Path workspaceRoot(Path abs) {
        return WorkspaceScan.findEnclosingWorkspace(abs).orElse(null);
    }

    private static @Nullable Path gitRoot(Path abs) {
        for (Path dir = abs; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) return dir;
        }
        return null;
    }
}
