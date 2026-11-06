// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test class that modified a tracked file of the jk checkout it runs from.
 *
 * <p>A command a test runs in-process works in the test JVM's working directory unless the test
 * hands it {@code -C} or an absolute path, and that directory is the module inside the checkout.
 * A wrong or relative target therefore edits the source tree: {@code jk new} once registered a
 * module into the module's own {@code jk.toml} and rewrote its lock, and nothing failed. This
 * extension compares {@code git status} of the checkout's tracked files before and after each class
 * and fails the class that changed one, naming the files to restore.
 *
 * <p>A file counts as changed when it became dirty, stopped being dirty, or was already dirty and
 * its size or modification time moved. One {@code git status} per class edge, without the
 * optional index lock, so a developer's own git commands never wait on it. Outside a git work tree
 * — a source archive, a host without git — it checks nothing. A file edited by hand while the class
 * runs is reported too: the message says so.
 */
public final class CheckoutUntouched implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(CheckoutUntouched.class);

    private static final String BEFORE = "before";

    private static final long GIT_TIMEOUT_SECONDS = 30;

    /** The tracked files that differ from the index or HEAD, each with its size and modification time. */
    record Snapshot(Path root, Map<String, String> dirty) {}

    @Override
    public void beforeAll(ExtensionContext context) {
        Path root = root(context.getRequiredTestClass());
        Snapshot before = root == null ? null : take(root);
        if (before != null) context.getStore(NS).put(BEFORE, before);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Snapshot before = context.getStore(NS).remove(BEFORE, Snapshot.class);
        if (before == null) return;
        Snapshot after = take(before.root());
        if (after == null) return;
        List<String> changed = changed(before, after);
        if (changed.isEmpty()) return;
        throw new AssertionError(context.getRequiredTestClass().getName()
                + " modified tracked files of the checkout (" + before.root() + "): "
                + String.join(", ", changed)
                + ". A command run without -C or with a relative path works in the module directory;"
                + " give it a temp directory. Restore the files with: git checkout -- "
                + String.join(" ", changed)
                + " (unless you edited them yourself while the class ran).");
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

    /** {@code root}'s dirty tracked files now; {@code null} when git cannot say. */
    static @Nullable Snapshot take(Path root) {
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
        return new Snapshot(root, dirty);
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
        try {
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
