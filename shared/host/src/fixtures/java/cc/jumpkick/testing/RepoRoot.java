// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The jk checkout root, for the tests that read a file out of the source tree — a source
 * tripwire, a doc-parity check, a shipped manifest.
 *
 * <p>Such a test cannot resolve its target against the working directory. A module-rooted run has
 * CWD at the owning module; a workspace {@code jk build} runs the same test with CWD at
 * {@code ~/.jk/state/engine}. Nor can it walk up looking for its own module: jk may place
 * class output at {@code <root>/target/<module>/} rather than {@code <module>/target/}, so the
 * module directory is not always an ancestor of the classes that were loaded. The one thing both
 * layouts share is that the output lives <em>somewhere under the checkout</em>, so that is what
 * this walks to — and every path is then spelled from the root, where the two layouts agree.
 *
 * <p>Before this existed the walk was copied into nine test classes, each with its own module
 * marker baked in. Copy ten was written without it, resolved {@code ../../plugins/android}
 * against the engine state dir, and went red only under {@code jk test}.
 */
public final class RepoRoot {

    private RepoRoot() {}

    /**
     * The checkout root, found by walking up from {@code anchor}'s own class output.
     *
     * @param anchor any class loaded from within the checkout — normally the calling test
     * @throws AssertionError if no ancestor of that output is a jk checkout root
     */
    public static Path find(Class<?> anchor) {
        Path here;
        try {
            here = Path.of(anchor.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (URISyntaxException | NullPointerException e) {
            throw new AssertionError("cannot locate the class output of " + anchor.getName(), e);
        }
        for (Path d = here; d != null; d = d.getParent()) {
            // Both markers, not either: `jk.toml` alone also matches every module directory, and the
            // lock alone would match an enclosing unrelated jk workspace with no manifest here.
            if (Files.isRegularFile(d.resolve("jk-lock.toml")) && Files.isRegularFile(d.resolve("jk.toml"))) {
                return d;
            }
        }
        throw new AssertionError("cannot locate the jk checkout root from " + here);
    }

    /**
     * A checkout file named from the root — {@code "plugins/android/jk-plugin.toml"}, never
     * {@code "../../plugins/android/jk-plugin.toml"}.
     *
     * <p>Asserts the file is present rather than handing back a path that reads as empty later: a
     * tripwire that cannot find its subject has to fail, not pass.
     *
     * @throws AssertionError if the file is absent
     */
    public static Path file(Class<?> anchor, String fromRoot) {
        Path root = find(anchor);
        Path p = root.resolve(fromRoot).normalize();
        if (!Files.isRegularFile(p)) {
            throw new AssertionError("no such file in the checkout: " + fromRoot + " (root " + root + ")");
        }
        return p;
    }

    /**
     * A checkout directory named from the root, asserted present. As {@link #file}, for the
     * tripwires that scan a source tree rather than read one file.
     *
     * @throws AssertionError if the directory is absent
     */
    public static Path dir(Class<?> anchor, String fromRoot) {
        Path root = find(anchor);
        Path p = root.resolve(fromRoot).normalize();
        if (!Files.isDirectory(p)) {
            throw new AssertionError("no such directory in the checkout: " + fromRoot + " (root " + root + ")");
        }
        return p;
    }
}
