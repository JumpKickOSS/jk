// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.ManifestNames;

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
 * where they are read, and that only stays true while there is exactly one place to look: the
 * words are spelled in {@link ManifestNames}, on the host layer the tree walkers live on, and this
 * class carries them for core and above by reference. Guard G13 reads its ban list out of that
 * class, so a constant added there is banned as a bare literal in the same minute.
 *
 * <p>Test sources keep the literal on purpose: a fixture that writes {@code jk.toml} and asserts on
 * {@code no jk.toml in <dir>} is pinning the on-disk vocabulary, and a rename that the suite
 * silently followed would be a rename nothing verified.
 */
public final class ManifestPaths {

    /** The build manifest that defines a project or a workspace member. */
    public static final String MANIFEST = ManifestNames.MANIFEST;

    /** The resolved dependency lock; {@link LockPaths#lockFile(java.nio.file.Path)} places it. */
    public static final String LOCK = ManifestNames.LOCK;

    /** Workspace-root catalog of library short names. Never valid in a member module. */
    public static final String LIBRARIES = ManifestNames.LIBRARIES;

    /** A build plugin's descriptor: its module root, and the root entry of its jar. */
    public static final String PLUGIN_MANIFEST = ManifestNames.PLUGIN_MANIFEST;

    /**
     * Beside each repository store under {@code <store>/repos/}: the canonical origin that fills the
     * tree and the name it was first filled under. A tree without one, that is not a reserved
     * public origin or the first-party shelf, was keyed by a repository name and is read by nothing.
     */
    public static final String REPO_ORIGIN = ManifestNames.REPO_ORIGIN;

    /** User config under the config root, and the per-app {@code <bin>/} override beside it. */
    public static final String CONFIG = ManifestNames.CONFIG;

    /** Dotenv defaults, layered under the real environment. No {@code .env.<profile>} yet. */
    public static final String ENV = ManifestNames.ENV;

    private ManifestPaths() {}
}
