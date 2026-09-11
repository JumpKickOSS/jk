// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where GraalVM's {@code native-image} launcher lives inside a GraalVM home, and — given a launcher
 * — which home owns it.
 *
 * <p>Round 3 found four independent answers to that question, one per module: {@code
 * NativeImageDriver} ({@code :toolchain-jdk}), {@code NativePreflight} ({@code :core}), {@code
 * TrainRunner} ({@code :engine}) and {@code GraalResolver} ({@code :cli}). They disagreed on three
 * axes — the Windows suffix ({@code .cmd}, {@code .exe}, or neither), whether {@code lib/svm/bin}
 * exists at all (one of the four knew about it), and the direction of the mapping. The disagreement
 * was not academic. {@code GraalResolver} inverted the path by taking the parent of the parent, so a
 * launcher legitimately found at {@code <home>/lib/svm/bin/native-image.exe} yielded {@code
 * <home>/lib/svm} as the "GraalVM home" and every step downstream was handed a directory that is not
 * one. {@code TrainRunner} probed only {@code native-image} and {@code native-image.cmd}, so a
 * Windows GraalVM whose launcher is {@code .exe}-only was not recognised as GraalVM at all.
 *
 * <p>This class lives in the {@code :host} leaf because that is the only module all four reach.
 * {@code :core} cannot depend on {@code :toolchain-jdk} — the edge already runs the other way — so
 * the richest of the four copies could not have become the owner without deleting the {@code :core}
 * one, and {@code :host} is where the campaign's other path vocabularies ({@code CacheTree}, {@code
 * AotCacheFiles}, {@code Classpaths}) already sit.
 *
 * <p>{@code native-image} is spelled here as a FILE. The identical characters are also a step name,
 * owned by {@code cc.jumpkick.run.TaskNames.NATIVE_IMAGE}. Two vocabularies, free to diverge, and
 * neither may borrow the other's constant: rename the step and the launcher must not follow. Guard
 * G12 knows about both owners by path and bans the bare literal everywhere else.
 */
public final class GraalLauncher {

    private GraalLauncher() {}

    /** The launcher's bare filename — the POSIX spelling, and the stem every suffixed one is built from. */
    public static final String NAME = "native-image";

    /**
     * The {@code bin/} launcher on Windows: a {@code .cmd} shim, and therefore bound by {@code
     * cmd.exe}'s own command-line length limit, which a native-image classpath routinely exceeds.
     */
    public static final String CMD = NAME + ".cmd";

    /** The real Windows launcher, normally under {@code lib/svm/bin}, with no {@code cmd.exe} limit. */
    public static final String EXE = NAME + ".exe";

    /** {@code <home>/bin} — present in every GraalVM. */
    private static final List<String> BIN = List.of("bin");

    /** {@code <home>/lib/svm/bin} — where the unshimmed launcher sits when there is one. */
    private static final List<String> SVM_BIN = List.of("lib", "svm", "bin");

    /**
     * Both launcher directories, DEEPEST FIRST. {@link #homeOf} walks this order so that a {@code
     * lib/svm/bin} path is never matched by the shorter {@code bin} rule first — which is the exact
     * shape that made the old parent-of-parent inverse answer {@code <home>/lib/svm}.
     */
    private static final List<List<String>> DIRS_DEEPEST_FIRST = List.of(SVM_BIN, BIN);

    /**
     * The launcher filenames to look for, most likely first for this host.
     *
     * <p>Every spelling is probed on every host, not just the one this OS is expected to use: a name
     * that cannot exist here simply does not exist, and it costs one {@code isRegularFile} to be
     * sure. A one-name-per-OS search is how {@code TrainRunner} came to miss an {@code .exe}-only
     * GraalVM, and how {@code NativeImageDriver} came to miss it under a POSIX-looking {@code $PATH}.
     */
    public static List<String> filenames() {
        return filenames(Os.isWindows());
    }

    /** {@link #filenames()} for an explicitly chosen host — the test seam, and the ordering rule. */
    static List<String> filenames(boolean windows) {
        return windows ? List.of(EXE, CMD, NAME) : List.of(NAME, EXE, CMD);
    }

    /** Every path {@link #in} would probe under {@code home}, most preferred first. */
    public static List<Path> candidatesIn(Path home) {
        return candidatesIn(home, Os.isWindows());
    }

    /**
     * {@link #candidatesIn(Path)} for an explicitly chosen host. Only the ORDER depends on the OS:
     * Windows prefers {@code lib/svm/bin} because the {@code bin} entry there is the length-limited
     * shim, and everywhere else {@code bin} comes first because that is where the launcher is.
     */
    static List<Path> candidatesIn(Path home, boolean windows) {
        List<Path> out = new ArrayList<>();
        for (List<String> dir : windows ? DIRS_DEEPEST_FIRST : List.of(BIN, SVM_BIN)) {
            Path resolved = resolveAll(home, dir);
            for (String name : filenames(windows)) out.add(resolved.resolve(name));
        }
        return List.copyOf(out);
    }

    /**
     * The {@code native-image} launcher under {@code home}, or empty when {@code home} is not a
     * GraalVM home. A regular file is enough: a launcher that exists but is not executable fails
     * loudly at exec time with the OS's own message, which beats silently reporting "no GraalVM".
     */
    public static Optional<Path> in(@Nullable Path home) {
        if (home == null) return Optional.empty();
        for (Path candidate : candidatesIn(home)) {
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /**
     * The launcher reachable from one {@code $PATH} entry: the entry itself first, then — when the
     * entry is a launcher directory of some home — the other spellings that home might use. A
     * {@code $PATH} carrying {@code <home>/bin} therefore still finds {@code
     * <home>/lib/svm/bin/native-image.exe}.
     *
     * <p>Splitting {@code $PATH} is the caller's job; this class owns the layout, not the search
     * policy.
     */
    public static Optional<Path> onPathEntry(@Nullable Path dir) {
        if (dir == null) return Optional.empty();
        for (String name : filenames()) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        return homeOf(dir.resolve(NAME)).flatMap(GraalLauncher::in);
    }

    /**
     * The GraalVM home that owns {@code launcher} — the inverse of {@link #in}, and the reason this
     * is a class and not a filename constant. A forward mapping with no named inverse is how the
     * inverse ended up hand-written at a call site, one level short.
     *
     * <p>Empty when the launcher does not sit in a directory {@link #in} searches. That is the honest
     * answer: guessing a fixed depth produces a plausible path that is not a home, and a plausible
     * wrong path is worse than none.
     */
    public static Optional<Path> homeOf(@Nullable Path launcher) {
        if (launcher == null) return Optional.empty();
        Path dir = launcher.getParent();
        if (dir == null) return Optional.empty();
        for (List<String> rel : DIRS_DEEPEST_FIRST) {
            Path home = stripSuffix(dir, rel);
            if (home != null) return Optional.of(home);
        }
        return Optional.empty();
    }

    /**
     * The directories under a home this class searches, rendered for a user-facing message: {@code
     * bin, lib/svm/bin}. Read it instead of restating the layout in prose — a message naming only
     * {@code bin} is how {@code NativePreflight} came to describe a search it did not perform.
     */
    public static String searchedDirs() {
        StringBuilder out = new StringBuilder();
        for (List<String> rel : List.of(BIN, SVM_BIN)) {
            if (out.length() > 0) out.append(", ");
            out.append(String.join("/", rel));
        }
        return out.toString();
    }

    /** {@code dir} with {@code rel} removed from its tail, or {@code null} when it does not end there. */
    private static @Nullable Path stripSuffix(Path dir, List<String> rel) {
        Path walk = dir;
        for (int i = rel.size() - 1; i >= 0; i--) {
            if (walk == null) return null;
            Path name = walk.getFileName();
            if (name == null || !name.toString().equals(rel.get(i))) return null;
            walk = walk.getParent();
        }
        return walk;
    }

    private static Path resolveAll(Path home, List<String> rel) {
        Path walk = home;
        for (String segment : rel) walk = walk.resolve(segment);
        return walk;
    }
}
