// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

/**
 * Every file name jk itself owns on disk, spelled once, at the layer the tree walkers live on: a
 * walker that prunes a module's {@code build/} output has to know what a module manifest is called,
 * and it sits below the lock model. {@code cc.jumpkick.lock.ManifestPaths} carries the same names
 * for core and everything above it, by reference; the words are written here and nowhere else.
 *
 * <p>The names are a user-visible contract — a project on disk, a plugin jar's root entry, a dotenv
 * file a CI job writes — so none of them can be changed on a whim. Guard G13 reads its ban list
 * straight out of this class, so a constant added here is banned as a bare literal in the same
 * minute. Test sources keep the literal on purpose: a fixture that writes {@code jk.toml} is pinning
 * the on-disk vocabulary.
 */
public final class ManifestNames {

    /** The build manifest that defines a project or a workspace member. */
    public static final String MANIFEST = "jk.toml";

    /** The resolved dependency lock. */
    public static final String LOCK = "jk-lock.toml";

    /** Workspace-root catalog of library short names. Never valid in a member module. */
    public static final String LIBRARIES = "jk-libs.toml";

    /** A build plugin's descriptor: its module root, and the root entry of its jar. */
    public static final String PLUGIN_MANIFEST = "jk-plugin.toml";

    /**
     * Beside each repository store under {@code <store>/repos/}: the canonical origin that fills the
     * tree and the name it was first filled under. A tree without one, that is not a reserved
     * public origin or the first-party shelf, was keyed by a repository name and is read by nothing.
     */
    public static final String REPO_ORIGIN = ".origin";

    /** User config under the config root, and the per-app {@code <bin>/} override beside it. */
    public static final String CONFIG = "config.toml";

    /** Dotenv defaults, layered under the real environment. No {@code .env.<profile>} yet. */
    public static final String ENV = ".env";

    private ManifestNames() {}
}
