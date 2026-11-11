// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class that modified a tracked file of the jk checkout it runs from, or left a new
 * untracked one in the test JVM's working directory.
 *
 * <p>The test JVM's working directory is the module inside the checkout, so anything that resolves
 * a relative path against it, rather than against {@link cc.jumpkick.host.WorkingDirectory} (which
 * {@link TempWorkingDirectory} pins to a temp directory), edits the source tree: {@code jk new}
 * once registered a module into the module's own {@code jk.toml} and rewrote its lock, and nothing
 * failed. This extension compares {@code git status} of the checkout's tracked files before and after each class
 * and fails the class that changed one, naming the files to restore.
 *
 * <p>Untracked files that {@code .gitignore} does not cover count too, but only under the JVM's
 * working directory when it lies inside the checkout: that is where a relative path lands, and
 * scanning only there keeps a class edge near the cost of the tracked scan (about 6 ms against
 * 4 ms on the jk checkout, where an untracked scan of the whole tree takes about 33 ms). A suite
 * run with its working directory outside the checkout scans no untracked files.
 *
 * <p>A file counts as changed when it became dirty, stopped being dirty, or was already dirty and
 * its size or modification time moved. Two {@code git status} calls per class edge, without the
 * optional index lock, so a developer's own git commands never wait on them. Outside a git work tree
 * — a source archive, a host without git — it checks nothing. A file edited by hand while the class
 * runs is reported too: the message says so.
 */
public final class CheckoutUntouched implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(CheckoutUntouched.class);

    private static final String BEFORE = "before";

    private static final long GIT_TIMEOUT_SECONDS = 30;

    /**
     * The tracked files that differ from the index or HEAD, and the untracked files under {@code
     * untrackedScope}, each with its size and modification time.
     */
    record Snapshot(Path root, @Nullable Path untrackedScope, Map<String, String> dirty) {}

    @Override
    public void beforeAll(ExtensionContext context) {
        Path root = root(context.getRequiredTestClass());
        Snapshot before = root == null ? null : take(root, untrackedScope(root));
        if (before != null) context.getStore(NS).put(BEFORE, before);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Snapshot before = context.getStore(NS).remove(BEFORE, Snapshot.class);
        if (before == null) return;
        Snapshot after = take(before.root(), before.untrackedScope());
        if (after == null) return;
        List<String> changed = changed(before, after);
        if (changed.isEmpty()) return;
        throw new AssertionError(context.getRequiredTestClass().getName()
                + " modified or created files of the checkout (" + before.root() + "): "
                + String.join(", ", changed)
                + ". A path the test resolves against the JVM's working directory lands in the module"
                + " directory; give it a temp directory. Restore tracked files with git checkout -- <file>"
                + " and delete untracked ones (unless you changed them yourself while the class ran).");
    }

    /** The files whose state differs between the two snapshots, sorted. */
    static List<String> changed(Snapshot before, Snapshot after) {
        TreeSet<String> out = new TreeSet<>();
        after.dirty().forEach((path, stamp) -> {
            if (!stamp.equals(before.dirty().get(path))) out.add(path);
        });
        before.dirty().keySet().forEach(path -> {
            if (!after.dirty().containsKey(path)) out.add(path);
        });
        return List.copyOf(out);
    }

    /** The checkout {@code anchor} was loaded from, when it is a git work tree; else {@code null}. */
    private static @Nullable Path root(Class<?> anchor) {
        Path root;
        try {
            root = RepoRoot.find(anchor);
        } catch (AssertionError notInACheckout) {
            return null;
        }
        return Files.exists(root.resolve(".git")) ? root : null;
    }

    /**
     * The JVM's working directory when it lies inside {@code root}, where an untracked file a test
     * leaves by a relative path appears; else {@code null}.
     */
    static @Nullable Path untrackedScope(Path root) {
        try {
            Path cwd = Path.of("").toAbsolutePath().toRealPath();
            Path real = root.toRealPath();
            return cwd.startsWith(real) ? cwd : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@code root}'s dirty tracked files now, with the untracked files outside ignored paths under
     * {@code untrackedScope} when it is given; {@code null} when git cannot say.
     */
    static @Nullable Snapshot take(Path root, @Nullable Path untrackedScope) {
        String status = git(
                root,
                "--no-optional-locks",
                "status",
                "--porcelain=v1",
                "-z",
                "--untracked-files=no",
                "--ignore-submodules=all");
        if (status == null) return null;
        Map<String, String> dirty = new TreeMap<>();
        collect(root, status, dirty);
        if (untrackedScope != null) {
            String untracked = git(
                    root,
                    "--no-optional-locks",
                    "status",
                    "--porcelain=v1",
                    "-z",
                    "--untracked-files=normal",
                    "--ignore-submodules=all",
                    "--",
                    pathspec(root, untrackedScope));
            if (untracked == null) return null;
            collect(root, untracked, dirty);
        }
        return new Snapshot(root, untrackedScope, dirty);
    }

    /** {@code dir} spelled from {@code root}, which git reads whatever symlinks lead to either. */
    private static String pathspec(Path root, Path dir) {
        try {
            String rel = root.toRealPath().relativize(dir.toRealPath()).toString();
            return rel.isEmpty() ? "." : rel;
        } catch (IOException | IllegalArgumentException e) {
            return dir.toString();
        }
    }

    /** Each path {@code status} names, stamped, into {@code dirty}. */
    private static void collect(Path root, String status, Map<String, String> dirty) {
        String[] fields = status.split("\0");
        for (int i = 0; i < fields.length; i++) {
            String entry = fields[i];
            if (entry.length() < 4) continue;
            char index = entry.charAt(0);
            String path = entry.substring(3);
            dirty.put(path, stamp(root.resolve(path)));
            // A rename or copy carries its source as the next field.
            if (index == 'R' || index == 'C') i++;
        }
    }

    /** Size and modification time, or {@code deleted}. */
    private static String stamp(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return attrs.size() + "@" + attrs.lastModifiedTime().toMillis();
        } catch (IOException gone) {
            return "deleted";
        }
    }

    private static @Nullable String git(Path root, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        return Processes.outputIfOk(
                new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD),
                Duration.ofSeconds(GIT_TIMEOUT_SECONDS));
    }
}
