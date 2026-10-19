// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The commit a checkout's {@code HEAD} names, read from {@code .git} rather than by forking {@code
 * git rev-parse --short HEAD}: the fork was 20–25 ms of a ~27 ms journal write, on the critical path
 * of every build the client waits for.
 *
 * <p>Reads {@code HEAD}, the loose ref it points at, then {@code packed-refs}; a linked worktree's
 * {@code .git} file is followed to its git dir, and refs are looked for there and in the common dir
 * it names. The abbreviation is git's own auto length — {@code max(7, ⌈(msb(objects) + 1) / 2⌉)},
 * objects counted from the pack index fan-outs plus a sample of loose ones — so the stamp matches
 * what {@code git log --oneline} shows. Anything this does not read (a reftable repository, a
 * symbolic ref chain, a ref it cannot find) is {@link Head#unknown()}, and the caller asks git.
 */
final class GitHead {

    /** git's floor for an automatic abbreviation. */
    static final int MIN_ABBREV = 7;

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    private GitHead() {}

    /** What {@code .git} said: a commit, no repository at all, or something only git can answer. */
    record Head(boolean known, @Nullable String commit) {
        static Head none() {
            return new Head(true, null);
        }

        static Head unknown() {
            return new Head(false, null);
        }
    }

    static Head of(Path dir) {
        try {
            Path dotGit = null;
            for (Path d = dir.toAbsolutePath(); d != null; d = d.getParent()) {
                Path candidate = d.resolve(".git");
                if (Files.exists(candidate)) {
                    dotGit = candidate;
                    break;
                }
            }
            if (dotGit == null) return Head.none();
            Path gitDir = dotGit;
            if (Files.isRegularFile(dotGit)) {
                String link = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                if (!link.startsWith("gitdir:")) return Head.unknown();
                gitDir =
                        dotGit.resolveSibling(link.substring("gitdir:".length()).trim());
            }
            Path common = gitDir;
            Path commondir = gitDir.resolve("commondir");
            if (Files.isRegularFile(commondir)) {
                common = gitDir.resolve(
                        Files.readString(commondir, StandardCharsets.UTF_8).trim());
            }
            String head = Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8)
                    .trim();
            String full = head.startsWith("ref:") ? ref(head.substring(4).trim(), gitDir, common) : head;
            if (full == null || !HEX.matcher(full).matches()) return Head.unknown();
            return new Head(true, full.substring(0, abbrev(common.resolve("objects"))));
        } catch (IOException | RuntimeException e) {
            return Head.unknown();
        }
    }

    /** {@code name}'s commit: the per-worktree loose ref, the shared one, then packed-refs. */
    private static @Nullable String ref(String name, Path gitDir, Path common) throws IOException {
        for (Path base : new Path[] {gitDir, common}) {
            Path loose = base.resolve(name);
            if (Files.isRegularFile(loose))
                return Files.readString(loose, StandardCharsets.UTF_8).trim();
        }
        Path packed = common.resolve("packed-refs");
        if (!Files.isRegularFile(packed)) return null;
        for (String line : Files.readAllLines(packed, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '^') continue;
            int space = line.indexOf(' ');
            if (space > 0 && line.substring(space + 1).equals(name)) return line.substring(0, space);
        }
        return null;
    }

    /** git's automatic abbreviation for a repository with about this many objects. */
    static int abbrev(Path objects) throws IOException {
        long[] count = {0};
        PathUtil.forEachChild(objects.resolve("pack"), (p, attrs) -> {
            if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".idx")) count[0] += packedObjects(p);
            return true;
        });
        // git samples one loose fan-out directory and scales it, as here.
        long[] loose = {0};
        PathUtil.forEachChild(objects.resolve("17"), (p, attrs) -> {
            loose[0]++;
            return true;
        });
        return abbrevFor(count[0] + loose[0] * 256);
    }

    /** {@code max(7, ⌈(msb(count) + 1) / 2⌉)}, git's {@code find_unique_abbrev} starting length. */
    static int abbrevFor(long count) {
        if (count <= 0) return MIN_ABBREV;
        int bits = 64 - Long.numberOfLeadingZeros(count);
        return Math.max(MIN_ABBREV, (bits + 1) / 2);
    }

    /** Objects in a version-2 pack index: the last entry of its 256-entry fan-out table. */
    private static long packedObjects(Path idx) throws IOException {
        try (InputStream in = Files.newInputStream(idx)) {
            byte[] header = in.readNBytes(8 + 256 * 4);
            if (header.length < 8 + 256 * 4 || (header[0] & 0xff) != 0xff || header[1] != 't') return 0;
            int at = 8 + 255 * 4;
            return ((header[at] & 0xffL) << 24)
                    | ((header[at + 1] & 0xffL) << 16)
                    | ((header[at + 2] & 0xffL) << 8)
                    | (header[at + 3] & 0xffL);
        }
    }
}
