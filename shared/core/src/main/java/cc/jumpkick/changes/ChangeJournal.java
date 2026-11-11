// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.changes;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The change journal: {@code <home>/changes/changes.jsonl}, one {@link Change} per line, appended by
 * whichever process made the change (the {@code jk} client or the engine) under {@code
 * changes.lock}, so no two writers interleave a line or race a roll. When the file passes {@link
 * #ROLL_BYTES} it becomes {@code changes.1.jsonl}, replacing the one before, so the journal keeps
 * between one and two files' worth: thousands of changes, not a history without end.
 *
 * <p>Recording never asks and never throws: a journal that cannot be written costs the change its
 * entry, never the change itself.
 */
public final class ChangeJournal {

    static final String FILE = "changes.jsonl";

    static final String ROLLED = "changes.1.jsonl";

    /** The lock every appender in every process takes before it rolls or writes. */
    static final String LOCK = "changes.lock";

    /** The size past which the live file rolls over. */
    static final long ROLL_BYTES = 2L * 1024 * 1024;

    /** The most entries one page holds. */
    public static final int MAX_PAGE = 500;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The last id stamp this process gave out: milliseconds times a thousand plus a sequence. */
    private static final AtomicLong LAST = new AtomicLong();

    /** Where {@link #current()} writes for the work inside {@link #into}, on this thread. */
    private static final ScopedValue<Path> DIR = ScopedValue.newInstance();

    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private final Path dir;
    private final LongSupplier clock;

    public ChangeJournal(Path dir) {
        this(dir, Clock.SYSTEM::millis);
    }

    ChangeJournal(Path dir, LongSupplier clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /** The journal of the current home, or the one {@link #into} names for the work running now. */
    public static ChangeJournal current() {
        return new ChangeJournal(DIR.isBound() ? DIR.get() : JkDirs.changes());
    }

    /**
     * Run {@code work} with every change it records on this thread going to the journal in {@code
     * dir}: how a test reads what one writer recorded, beside tests that share its home.
     */
    public static <T extends @Nullable Object, X extends Throwable> T into(Path dir, ChangeActor.Work<T, X> work)
            throws X {
        return ScopedValue.where(DIR, dir).call(work::get);
    }

    /** One page of entries, newest first. */
    public record Page(List<Change> entries, @Nullable String next) {}

    /**
     * Record that {@code what} had {@code action} done to it, by the {@link ChangeActor#current()
     * current actor}. Never throws.
     */
    public static Optional<Change> note(String what, String action, String summary) {
        return current().record(what, action, summary, null);
    }

    /** {@link #note} with a before/after summary or a diff the caller wrote; never throws. */
    public static Optional<Change> note(String what, String action, String summary, @Nullable String diff) {
        return current().record(what, action, summary, diff);
    }

    /**
     * Record an edit of the text file {@code file} from {@code before} to {@code after} (empty for an
     * absent file), with its unified diff, credential values masked. An edit that changed nothing is
     * not recorded. Never throws.
     */
    public static Optional<Change> noteEdit(Path file, String summary, String before, String after) {
        return current().recordEdit(file, summary, before, after);
    }

    /** {@link #noteEdit} into this journal. */
    public Optional<Change> recordEdit(Path file, String summary, String before, String after) {
        String name = display(file);
        String diff = TextDiff.unified(name, before, after);
        if (diff.isEmpty()) return Optional.empty();
        return record(name, Change.EDIT, summary, diff);
    }

    /** Append one change by the current actor; empty when it could not be written. */
    public Optional<Change> record(String what, String action, String summary, @Nullable String diff) {
        long at = clock.getAsLong();
        Change change = new Change(newId(at), at, what, action, summary, diff, ChangeActor.current());
        try {
            append(change);
            return Optional.of(change);
        } catch (IOException | RuntimeException e) {
            Log.debug("change not journaled: " + what + " " + action, e);
            return Optional.empty();
        }
    }

    /**
     * Up to {@code limit} entries older than the entry {@code before} (all, when null), newest first,
     * and the cursor of the next page, null on the last.
     */
    public Page page(@Nullable String before, int limit) {
        int max = Math.clamp(limit, 1, MAX_PAGE);
        List<Change> all = newestFirst();
        List<Change> out = new ArrayList<>(max);
        boolean more = false;
        for (Change c : all) {
            if (before != null && c.id().compareTo(before) >= 0) continue;
            if (out.size() == max) {
                more = true;
                break;
            }
            out.add(c);
        }
        return new Page(List.copyOf(out), more ? out.getLast().id() : null);
    }

    /** Every entry the journal keeps, newest first: one read of both files. */
    public List<Change> newestFirst() {
        List<Change> all = new ArrayList<>();
        read(dir.resolve(ROLLED), all);
        read(dir.resolve(FILE), all);
        all.sort((x, y) -> y.id().compareTo(x.id()));
        return all;
    }

    /**
     * A number that changes whenever an entry is appended: the live file's size and modification
     * time, so a watcher learns of new entries from one stat instead of a read. 0 when there is none.
     */
    public long stamp() {
        Path file = dir.resolve(FILE);
        try {
            return Files.size(file) * 31 + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private void append(Change change) throws IOException {
        OwnerOnlyFiles.createDirectories(dir);
        Path file = dir.resolve(FILE);
        String line = MiniJson.write(change.toMap()) + "\n";
        // A sidecar lock, never the live file: the roll moves that file, and a writer queued on the old
        // inode would size-check a stale handle and roll the new file over the history.
        FileLocks.withLock(dir.resolve(LOCK), () -> {
            if (Files.isRegularFile(file) && Files.size(file) > ROLL_BYTES) {
                Files.move(file, dir.resolve(ROLLED), StandardCopyOption.REPLACE_EXISTING);
            }
            OwnerOnlyFiles.writeString(
                    file, line, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        });
    }

    private static void read(Path file, List<Change> into) {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Change c;
                try {
                    c = Change.of(MiniJson.parse(line));
                } catch (RuntimeException broken) {
                    continue;
                }
                if (c != null) into.add(c);
            }
        } catch (IOException e) {
            Log.debug("change journal unreadable: " + file, e);
        }
    }

    /**
     * Sortable by time, unique across processes: the milliseconds and a three-digit sequence that
     * keeps this process's changes in the order it made them within one millisecond, a dash, and
     * four random characters.
     */
    static String newId(long at) {
        long stamp = LAST.updateAndGet(last -> Math.max(Math.max(0, at) * 1000, last + 1));
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%016d", stamp)).append('-');
        for (int i = 0; i < 4; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    /** {@code ~/…} for a path under the user's home, so an entry reads the way a person types it. */
    static String display(Path file) {
        String home = System.getProperty("user.home");
        Path abs = file.toAbsolutePath().normalize();
        if (home != null && !home.isBlank()) {
            Path h = Path.of(home).toAbsolutePath().normalize();
            if (abs.startsWith(h) && !abs.equals(h))
                return "~/" + h.relativize(abs).toString().replace('\\', '/');
        }
        return abs.toString();
    }
}
