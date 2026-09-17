// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Stable {@code <vendor>-<major>} handle under {@link JkDirs#jdks()} (symlink; Windows junction) that
 * tracks the current patch install so IDE {@code homePath}s survive point-release upgrades.
 * {@link #javaHome} does not resolve the link.
 *
 * <p>The pointer and the install it aims at are two different things on disk, and this type never
 * confuses them: it will create, re-aim and retire the <em>pointer</em>, and it will not delete an
 * install to do so. Removing a JDK belongs to an explicit {@code jk jdk} verb. Where an install is
 * sitting on the pointer name it is moved aside, never removed — see {@link #ensure}.
 */
public final class StableJdkPointer {

    private final Path jdksRoot;

    public StableJdkPointer(Path jdksRoot) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
    }

    /** Pointer rooted at jk's default managed JDK directory. */
    public static StableJdkPointer atDefaultRoot() {
        return new StableJdkPointer(JkDirs.jdks());
    }

    /**
     * {@code <jdksRoot>/<pointerName>} — the pointer path (link or, degenerate, the install itself).
     */
    public Path pointerDir(String pointerName) {
        return jdksRoot.resolve(pointerName);
    }

    /**
     * Stable {@code JAVA_HOME} for the pointer, resolving the macOS {@code Contents/Home} bundle
     * layout but <em>not</em> the link itself, so the returned path stays {@code
     * <jdksRoot>/<pointerName>[/Contents/Home]}.
     */
    public Path javaHome(String pointerName) {
        return IntellijJdkDir.javaHome(pointerDir(pointerName));
    }

    /**
     * Ensure {@code <jdksRoot>/<pointerName>} resolves to {@code installDir}. Idempotent: a no-op
     * when the link already resolves there, or when the pointer name already <em>is</em> the install
     * dir (a vendor whose version is its bare major, e.g. {@code graalvm-25}). A no-op when {@code
     * installDir} doesn't exist.
     *
     * <p>When some OTHER install holds the name — the bare-major case again, one release later —
     * that install is moved aside to a name of its own so the pointer can aim at {@code installDir}.
     * jk moves only a tree it installed; anything else still refuses, loudly.
     */
    public void ensure(String pointerName, Path installDir) throws IOException {
        Objects.requireNonNull(pointerName, "pointerName");
        Objects.requireNonNull(installDir, "installDir");
        if (!Files.exists(installDir)) return;

        Path pointer = jdksRoot.resolve(pointerName);
        Path canonicalInstall = installDir.toRealPath();

        if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // toRealPath resolves a symlink OR a Windows junction; if it
                // already lands on the install (or IS the install dir), done.
                if (pointer.toRealPath().equals(canonicalInstall)) return;
            } catch (IOException dangling) {
                // fall through and recreate
            }
            freeTheName(pointer);
        }

        Files.createDirectories(jdksRoot);
        DirLinks.replace(pointer, installDir);
    }

    /**
     * The pointer name for an install identifier — {@code temurin-25.0.4} → {@code temurin-25}, and
     * empty when the identifier carries no vendor or no major.
     *
     * <p>Owned here because the pointer name is this type's rule. It was being re-derived at the
     * call site, which is one rename away from an update repointing a name nothing else uses.
     */
    public static Optional<String> pointerNameFor(String identifier) {
        JdkSelector.FlexibleQuery q = JdkSelector.parseFlexible(identifier);
        if (q.majorOpt().isEmpty() || q.hints().isEmpty()) return Optional.empty();
        return Optional.of(q.hints().get(0) + "-" + q.majorOpt().get());
    }

    /**
     * Re-aim the pointer after one of the installs behind it was removed, or retire it when nothing
     * of that vendor and major is left.
     *
     * <p>The pointer and the install are two different things on disk, and removing an install has
     * to leave the pointer coherent rather than dangling: {@code ~/.jdks/temurin-25} is a symlink,
     * {@code ~/.jdks/temurin-25.0.4.1} is the tree it aims at. Delete the tree and the link survives
     * pointing at nothing — an IDE with that stable path configured then has a broken SDK, which is
     * the whole thing the pointer exists to prevent. So: newest survivor wins the name; if there is
     * no survivor the name goes.
     *
     * <p>{@code survivors} is passed in rather than scanned for here. {@link JdkRegistry#listHits()}
     * already walks this root through the probe chain, and a fourth lister of one layout is how the
     * three that exist came to disagree — quite apart from guard G45, which this module is at the
     * budget of.
     */
    public void healAfterRemoval(String pointerName, List<JdkHit> survivors) throws IOException {
        Objects.requireNonNull(pointerName, "pointerName");
        Path root = jdksRoot.toAbsolutePath().normalize();
        Optional<Path> newest = (survivors == null ? List.<JdkHit>of() : survivors)
                .stream()
                        .filter(h -> h.home() != null && h.version() != null)
                        .filter(h -> pointerNameFor(JdkRegistry.identifierFor(h.home()))
                                .filter(pointerName::equals)
                                .isPresent())
                        .map(h -> Map.entry(JdkSelector.versionKey(h.version()), IntellijJdkDir.installDirOf(h.home())))
                        // Only installs in THIS root may win the name. A registry probe chain reports JDKs
                        // from everywhere — IntelliJ's root, sdkman, Homebrew — and aiming jk's pointer at
                        // one of those is how a link from ~/.jdks came to point into ~/.sdkman, which is the
                        // cross-root link that cost real JDKs in. Caught by an end-to-end smoke:
                        // a pointer in a sandbox root re-aimed at the developer's real ~/.jdks.
                        .filter(e -> e.getValue().toAbsolutePath().normalize().startsWith(root))
                        // A directory emptied by an interrupted delete is not a JDK; linking to one is worse
                        // than having no link, because it reads as configured and fails at exec.
                        .filter(e -> Files.isRegularFile(JdkFingerprint.java(IntellijJdkDir.javaHome(e.getValue()))))
                        .max(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue);

        if (newest.isPresent()) {
            ensure(pointerName, newest.get());
            return;
        }
        retire(pointerDir(pointerName));
    }

    /**
     * Heal every pointer affected by a batch of removals, reporting rather than failing.
     *
     * <p>Both callers that remove installs need exactly this — {@code jk jdk update} after it reaps
     * the superseded patch, and {@code jk jdk uninstall} after it deletes what the user named — and
     * the install is already gone by the time it runs, so a pointer that cannot be fixed is worth
     * a word to the user, not an aborted command.
     */
    public static void healAfterRemovals(
            JdkRegistry registry, Collection<String> removedIdentifiers, Consumer<String> warn) {
        if (registry == null || removedIdentifiers == null || removedIdentifiers.isEmpty()) return;
        registry.refresh(); // the removals invalidated the memoized probe scan
        List<JdkHit> survivors = registry.listHits();
        StableJdkPointer pointer = new StableJdkPointer(registry.jdksRoot());
        removedIdentifiers.stream()
                .map(StableJdkPointer::pointerNameFor)
                .flatMap(Optional::stream)
                .distinct()
                .forEach(name -> {
                    try {
                        pointer.healAfterRemoval(name, survivors);
                    } catch (IOException e) {
                        if (warn != null) warn.accept("could not update the " + name + " pointer: " + e.getMessage());
                    }
                });
    }

    /**
     * Free the pointer name for a re-aim: drop a link or an empty directory as {@link #retire}
     * does, and move an install that is sitting on the name out to a name of its own.
     *
     * <p>An install can hold the stable name because a feed reports some releases with no point
     * version at all — {@code jdk_version} is {@code "25"} for Oracle GraalVM 25, and {@code "27"}
     * for six other JDKs — so {@code <vendor>-<version>} and {@code <vendor>-<major>} come out the
     * same string and the first install of a major lands on its own pointer. Nothing was wrong
     * until the second one arrived: the pointer could then never be re-aimed, and because
     * {@code JdkInstaller} treats a pointer as a convenience it failed silently, leaving the stable
     * name — the one an IDE is configured with — on the older install.
     */
    private void freeTheName(Path pointer) throws IOException {
        try {
            Files.delete(pointer);
        } catch (NoSuchFileException alreadyGone) {
            // nothing to free
        } catch (DirectoryNotEmptyException install) {
            displace(pointer);
        }
    }

    /**
     * Move the install sitting on the pointer name out to a free name of its own.
     *
     * <p>Only a tree jk installed is moved, and it is moved, never deleted — an install costs
     * minutes to re-download, and an alien one in a shared root is refused exactly as before.
     *
     * <p>The new name is the old one with a counter: {@code graalvm-25} becomes {@code
     * graalvm-25-1}. Deliberately NOT a version. This release calls itself {@code 25} in the feed
     * and {@code JAVA_VERSION="25"} in its own {@code release} file, so writing {@code 25.0.0} on
     * disk would state a version nothing else claims — {@code GRAALVM_VERSION} says {@code 25.0.0},
     * the runtime build says {@code 25+37}, and a reader would have to guess which is real. A
     * counter claims nothing except the order the trees arrived in. It also stays legible to the
     * rest of jk: {@link JdkSelector#parseFlexible} reads {@code graalvm-25-1} as graalvm major 25,
     * so the displaced install is still found by spec and still maps back to this pointer through
     * {@link #pointerNameFor}.
     */
    private void displace(Path install) throws IOException {
        if (!JdkOwnership.isJkOwned(install)) throw populatedInstall(install);
        Path aside = freeNameBeside(install);
        String oldId = install.getFileName().toString();
        Files.move(install, aside);
        try {
            JdkInventory.of(jdksRoot).rename(oldId, aside.getFileName().toString(), IntellijJdkDir.javaHome(aside));
        } catch (IOException bookkeeping) {
            // The tree moved and the row did not. `jk jdk repair` reconciles the two, and the
            // install itself is intact either way — this must not undo a move that succeeded.
            Log.warn("moved " + oldId + " to " + aside.getFileName() + " but could not rename its inventory row",
                    bookkeeping);
        }
    }

    /** {@code <name>-1}, {@code <name>-2}, … — the first that nothing is using. */
    private static Path freeNameBeside(Path install) throws IOException {
        Path parent = install.getParent();
        if (parent == null) throw new IOException("cannot move " + install + ": it has no parent directory");
        String base = install.getFileName().toString();
        for (int n = 1; n <= MAX_DISPLACED; n++) {
            Path candidate = parent.resolve(base + "-" + n);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new IOException("cannot free the stable pointer name " + base + ": " + MAX_DISPLACED
                + " displaced installs already sit beside it");
    }

    /**
     * A ceiling on the counter so a loop cannot run away. Reaching it means dozens of installs of
     * one major are stacked up in the root, which is a housekeeping problem to report, not to
     * silently work around.
     */
    private static final int MAX_DISPLACED = 64;

    private static IOException populatedInstall(Path pointer) {
        return new IOException("refusing to remove " + pointer
                + " to free the stable pointer name: it is a populated JDK install that jk did not"
                + " install, and jk never deletes a JDK on its own. Remove it with `jk jdk uninstall`"
                + " if you meant to.");
    }

    /**
     * Drop the pointer itself, never an install.
     *
     * <p>{@link Files#delete} takes a symlink, a junction or an empty directory in one shot without
     * following, which is every shape a pointer legitimately has. A <em>populated</em> directory at
     * the pointer name is an install — somebody's JDK, possibly ours — and removing a JDK is not
     * something any automatic path may do: it costs minutes to re-download and an IDE, a
     * shell, or another project's lockfile may be pinned to it. So it is left, and the caller's
     * attempt to claim the name fails loudly instead of silently costing a JDK.
     *
     * <p>This is the retirement path — {@code healAfterRemoval} with no survivor left to aim at.
     * {@link #ensure} goes through {@link #freeTheName} instead, which has somewhere to put an
     * install that is in the way; here there is nowhere to put it and nothing to gain by moving it.
     */
    private static void retire(Path pointer) throws IOException {
        try {
            Files.delete(pointer);
        } catch (NoSuchFileException alreadyGone) {
            // nothing to retire
        } catch (DirectoryNotEmptyException install) {
            throw populatedInstall(pointer);
        }
    }
}
