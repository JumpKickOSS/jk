// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

/**
 * Every file name jk itself owns on disk, spelled once.
 *
 * <p>This class answers <em>what a file is called</em>; {@link LockPaths} answers <em>which
 * directory owns it</em>. They are deliberately separate: the lock's home is a workspace policy
 * with three branches, while its name is a single word that a hundred call sites need and none of
 * them should re-type.
 *
 * <p>The names below are a user-visible contract — a project on disk, a plugin jar's root entry, a
 * dotenv file a CI job writes — so none of them can be changed on a whim. What can be changed is
 * where they are read, and that only stays true while there is exactly one place to look. Guard
 * G13 reads its ban list straight out of this file, so a constant added here is banned as a bare
 * literal in the same minute.
 *
 * <p>Test sources keep the literal on purpose: a fixture that writes {@code jk.toml} and asserts on
 * {@code no jk.toml in <dir>} is pinning the on-disk vocabulary, and a rename that the suite
 * silently followed would be a rename nothing verified.
 */
public final class ManifestPaths {

    /** The build manifest that defines a project or a workspace member. */
    public static final String MANIFEST = "jk.toml";

    /** The resolved dependency lock; {@link LockPaths#lockFile(java.nio.file.Path)} places it. */
    public static final String LOCK = "jk-lock.toml";

    /** Workspace-root catalog of library short names. Never valid in a member module. */
    public static final String LIBRARIES = "jk-libs.toml";

    /** A build plugin's descriptor: its module root, and the root entry of its jar. */
    public static final String PLUGIN_MANIFEST = "jk-plugin.toml";

    /** User config under the config root, and the per-app {@code <bin>/} override beside it. */
    public static final String CONFIG = "config.toml";

    /** Dotenv defaults, layered under the real environment. No {@code .env.<profile>} yet. */
    public static final String ENV = ".env";

    private ManifestPaths() {}
}
