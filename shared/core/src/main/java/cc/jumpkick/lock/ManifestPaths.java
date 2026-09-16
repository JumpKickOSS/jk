// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.ManifestNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>{@link #manifestIn} is the one way to name a module's manifest. A directory with a {@code
 * pom.xml} and no {@code jk.toml} is <em>shadowed</em>: its manifest is the rendering of Maven's
 * effective POM under {@link #shadowDir}, a build artefact beside the module's other outputs. A
 * reactor root's shadow carries the reactor's leaves as {@code [workspace] modules}, each leaf's
 * shadow sits under the leaf's own {@link #SHADOW_DIR}, and the lock lives beside the shadow of the
 * directory that owns it ({@link LockPaths#lockFile}) so the repository is never dirtied. The
 * engine installs the {@link ShadowSource} that renders and refreshes shadows; a process without
 * one (the native client) only names the path.
 */
public final class ManifestPaths {

    /** Maven's manifest. A directory with one and no {@link #MANIFEST} is built through a shadow. */
    public static final String POM = "pom.xml";

    /**
     * Where a shadowed module's manifest and lock live, relative to the module: inside the build
     * output tree so {@code jk clean} reaches it and no checkout has to ignore a new path.
     */
    public static final String SHADOW_DIR = "target/jk/shadow";

    /** Renders the shadow manifest of a shadowed module, refreshing it when the POM changed. */
    @FunctionalInterface
    public interface ShadowSource {
        /** The shadow manifest of {@code dir}, current with {@code dir/pom.xml}. */
        Path shadowManifest(Path dir);
    }

    private static volatile ShadowSource shadows = ManifestPaths::shadowManifestPath;

    /** The engine installs the source that renders the effective POM; nothing else calls this. */
    public static void installShadowSource(ShadowSource source) {
        shadows = Objects.requireNonNull(source, "source");
    }

    /**
     * The manifest that defines the module at {@code dir}: {@code dir/jk.toml} when it exists,
     * the shadow manifest when {@code dir} is {@linkplain #isShadowed shadowed}, else the absent
     * {@code dir/jk.toml} so existence tests keep their meaning.
     */
    public static Path manifestIn(Path dir) {
        Path toml = dir.resolve(MANIFEST);
        if (Files.isRegularFile(toml)) return toml;
        if (Files.isRegularFile(dir.resolve(POM))) return shadows.shadowManifest(dir);
        return toml;
    }

    /** True when {@code dir} has a {@code pom.xml} and no {@code jk.toml}. */
    public static boolean isShadowed(Path dir) {
        return !Files.isRegularFile(dir.resolve(MANIFEST)) && Files.isRegularFile(dir.resolve(POM));
    }

    /** True when {@code dir} holds a manifest jk can build from: a {@code jk.toml} or a {@code pom.xml}. */
    public static boolean describesProject(Path dir) {
        return Files.isRegularFile(dir.resolve(MANIFEST)) || Files.isRegularFile(dir.resolve(POM));
    }

    /** {@code dir/target/jk/shadow}: the shadow manifest's and lock's home for a shadowed module. */
    public static Path shadowDir(Path dir) {
        return dir.resolve(SHADOW_DIR);
    }

    /** The shadow manifest's path, whether or not it has been rendered. */
    public static Path shadowManifestPath(Path dir) {
        return shadowDir(dir).resolve(MANIFEST);
    }

    /**
     * The module {@code manifest} defines: the directory holding it, or for a shadow manifest the
     * module whose {@link #SHADOW_DIR} holds it. {@code null} for a manifest at a filesystem root.
     */
    public static @Nullable Path moduleOf(Path manifest) {
        Path dir = manifest.toAbsolutePath().normalize().getParent();
        if (dir == null || !dir.endsWith(SHADOW_DIR)) return dir;
        Path module = dir;
        for (int i = 0; i < SHADOW_DIR.split("/").length; i++) module = Objects.requireNonNull(module.getParent());
        return module;
    }

    /**
     * Why an edit of the manifest ({@code jk add}, {@code jk remove}, {@code jk update}, the MCP
     * editors) refuses a shadowed directory: the shadow is rendered, never written to, so there are
     * two ways to change what the build reads, and this names both.
     */
    public static String noManifestToEdit(String dir) {
        return "no " + MANIFEST + " in " + dir + ": the project is built in place from " + POM
                + " (effective POM), and this command edits " + MANIFEST + " only — run `jk import " + POM
                + "` to own a " + MANIFEST + ", or edit the POM";
    }

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
