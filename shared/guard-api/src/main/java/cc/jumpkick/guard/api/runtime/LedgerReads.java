// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * What a suite's guards read in their last run, as one digest a key can be taken over: the key
 * holds while every read holds, so a suite whose reads are unchanged need not be forked to find
 * out that each guard replays.
 *
 * <p>The digest folds each guard's line in {@link ReadLedger}'s file: its id, its stamp, and every
 * glob and file it read with a digest of the match list or text. Taken over the digests the ledger
 * {@linkplain Mode#RECORDED recorded}, it names what the run that wrote the ledger judged; taken
 * over the tree {@linkplain Mode#NOW as it reads now}, it names what a run would judge, and the two
 * are equal exactly when every read holds. The stamp is folded as recorded: what it covers (the
 * suite's class path, the JDK, the operating system, the run's scope and the model) the key must
 * name by its own tokens.
 */
public final class LedgerReads {

    private LedgerReads() {}

    /** Which digests of the reads the key is taken over. */
    public enum Mode {
        /** As the ledger recorded them: the key the run that wrote it is stored under. */
        RECORDED,
        /** As the tree reads now: the key a run would look up. */
        NOW
    }

    /** The SHA-256 of a file's bytes, in hex, as a caller that remembers them answers it. */
    @FunctionalInterface
    public interface ContentHash {
        String sha256Hex(Path file) throws IOException;
    }

    /** {@link #digest(GuardConfig, Collection, Mode, ContentHash)}, hashing every file it reads. */
    public static @Nullable String digest(GuardConfig config, Collection<String> ids, Mode mode) throws IOException {
        return digest(config, ids, mode, Hashing::sha256Hex);
    }

    /**
     * The digest of the reads guards {@code ids} made in the last run configured by {@code config},
     * or {@code null} when the ledger cannot stand for the suite: a fixture or tree-case run (which
     * keeps no ledger), no ledger, a guard of {@code ids} with no line, or a line whose guard reached
     * no verdict or takes a view the ledger does not see. {@code hash} answers whether a file still
     * reads as recorded: a UTF-8 text's digest is its bytes', so a match spares reading it, and a
     * file that does not match is read and digested as the ledger digests it.
     */
    public static @Nullable String digest(GuardConfig config, Collection<String> ids, Mode mode, ContentHash hash)
            throws IOException {
        if (config.fixture() || config.textRoot() != null || ids.isEmpty()) return null;
        Path file = config.report().toAbsolutePath().normalize().resolveSibling(ReadLedger.FILE);
        if (!Files.isRegularFile(file)) return null;
        Map<String, ReadLedger.Line> lines = ReadLedger.read(file);
        Path root = config.root().toAbsolutePath().normalize();
        TextView view =
                mode == Mode.NOW ? new TextView(root, config.sources(), false, GuardRuntime.outputDirOf(config)) : null;
        Map<String, String> nowByPath = new HashMap<>();
        MessageDigest md = Hashing.newSha256();
        for (String id : new TreeSet<>(ids)) {
            ReadLedger.Line line = lines.get(id);
            if (line == null || !"ok".equals(line.outcome()) || !line.replayable()) return null;
            feed(md, "guard", id);
            feed(md, "stamp", String.valueOf(line.stamp()));
            for (var e : line.globs().entrySet()) {
                String now = view == null ? e.getValue() : ReadLedger.listingDigest(view.unrecordedFiles(e.getKey()));
                feed(md, "glob:" + e.getKey(), now);
            }
            for (var e : line.files().entrySet()) {
                String now = view == null ? e.getValue() : now(view, root, e.getKey(), e.getValue(), hash, nowByPath);
                feed(md, "file:" + e.getKey(), now);
            }
        }
        return Hashing.hex(md.digest());
    }

    /** How {@code rel} reads now: {@code recorded} while its bytes hash to it, else its text's digest. */
    private static String now(
            TextView view, Path root, String rel, String recorded, ContentHash hash, Map<String, String> known)
            throws IOException {
        String done = known.get(rel);
        if (done != null) return done;
        Path file = root.resolve(rel);
        String now;
        if (recorded.length() == 16
                && Files.isRegularFile(file)
                && hash.sha256Hex(file).startsWith(recorded)) now = recorded;
        else now = ReadLedger.textDigest(view, rel);
        known.put(rel, now);
        return now;
    }

    private static void feed(MessageDigest md, String tag, String value) {
        md.update((tag + "\0" + value + "\0").getBytes(StandardCharsets.UTF_8));
    }
}
