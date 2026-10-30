// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * What this jk home remembers about updating itself: {@code ~/.jk/state/self-update.toml}. The
 * engine's scheduler and the client both read and write it, each change a read-modify-write under
 * a lock file beside it.
 *
 * <pre>
 * last-check = "2026-10-30T09:00:00Z"     # last check that reached the release site
 * last-attempt = "2026-10-30T09:00:00Z"   # last check tried, reached or not
 * latest = "0.17.0"                       # what that check found
 * first-seen = "2026-10-01T08:00:00Z"     # the first time this home ran the scheduler
 * held = "0.16.2"                         # a deliberate downgrade: no auto-update until cleared
 * held-from = "0.17.0"
 * snooze-until = "2026-10-31T09:00:00Z"   # "not now" at the prompt
 * interactive-seen = "2026-10-30T09:00:00Z"
 * notice = "0.16.2"                       # the version that last printed the pre-1.0 notice
 * </pre>
 */
public record SelfUpdateState(
        @Nullable Instant lastCheck,
        @Nullable Instant lastAttempt,
        @Nullable String latest,
        @Nullable Instant firstSeen,
        @Nullable String held,
        @Nullable String heldFrom,
        @Nullable Instant snoozeUntil,
        @Nullable Instant interactiveSeen,
        @Nullable String notice) {

    public static final String FILE_NAME = "self-update.toml";

    public static final SelfUpdateState EMPTY =
            new SelfUpdateState(null, null, null, null, null, null, null, null, null);

    /** The live file under the state root. */
    public static Path file() {
        return JkDirs.state().resolve(FILE_NAME);
    }

    /** Whether a deliberate downgrade holds this home at its version. */
    public boolean isHeld() {
        return held != null;
    }

    /** The state in {@code file}; {@link #EMPTY} when it is absent or unreadable. */
    public static SelfUpdateState read(Path file) {
        if (!Files.isRegularFile(file)) return EMPTY;
        try {
            Map<String, String> v = MinimalToml.readFlat(AtomicWrites.readString(file));
            return new SelfUpdateState(
                    instant(v, "last-check"),
                    instant(v, "last-attempt"),
                    v.get("latest"),
                    instant(v, "first-seen"),
                    v.get("held"),
                    v.get("held-from"),
                    instant(v, "snooze-until"),
                    instant(v, "interactive-seen"),
                    v.get("notice"));
        } catch (IOException e) {
            return EMPTY;
        }
    }

    /** {@link #read(Path)} of the live file. */
    public static SelfUpdateState read() {
        return read(file());
    }

    /** Apply {@code change} to the state in {@code file} under its lock and write the result. */
    public static SelfUpdateState update(Path file, UnaryOperator<SelfUpdateState> change) throws IOException {
        Path dir = file.getParent();
        if (dir != null) Files.createDirectories(dir);
        return FileLocks.withLock(file.resolveSibling(FILE_NAME + ".lock"), () -> {
            SelfUpdateState next = change.apply(read(file));
            AtomicWrites.replace(file, next.toToml());
            return next;
        });
    }

    /** {@link #update(Path, UnaryOperator)} of the live file. */
    public static SelfUpdateState update(UnaryOperator<SelfUpdateState> change) throws IOException {
        return update(file(), change);
    }

    public SelfUpdateState withCheck(Instant at, @Nullable String found) {
        return new SelfUpdateState(at, at, found, firstSeen, held, heldFrom, snoozeUntil, interactiveSeen, notice);
    }

    public SelfUpdateState withAttempt(Instant at) {
        return new SelfUpdateState(
                lastCheck, at, latest, firstSeen, held, heldFrom, snoozeUntil, interactiveSeen, notice);
    }

    public SelfUpdateState withFirstSeen(Instant at) {
        return new SelfUpdateState(
                lastCheck, lastAttempt, latest, at, held, heldFrom, snoozeUntil, interactiveSeen, notice);
    }

    /** Held at {@code version} after a deliberate downgrade from {@code from}. */
    public SelfUpdateState withHold(String version, String from) {
        return new SelfUpdateState(
                lastCheck, lastAttempt, latest, firstSeen, version, from, snoozeUntil, interactiveSeen, notice);
    }

    public SelfUpdateState withoutHold() {
        return new SelfUpdateState(
                lastCheck, lastAttempt, latest, firstSeen, null, null, snoozeUntil, interactiveSeen, notice);
    }

    public SelfUpdateState withSnoozeUntil(@Nullable Instant until) {
        return new SelfUpdateState(
                lastCheck, lastAttempt, latest, firstSeen, held, heldFrom, until, interactiveSeen, notice);
    }

    public SelfUpdateState withInteractiveSeen(Instant at) {
        return new SelfUpdateState(lastCheck, lastAttempt, latest, firstSeen, held, heldFrom, snoozeUntil, at, notice);
    }

    public SelfUpdateState withNotice(String version) {
        return new SelfUpdateState(
                lastCheck, lastAttempt, latest, firstSeen, held, heldFrom, snoozeUntil, interactiveSeen, version);
    }

    String toToml() {
        Map<String, String> v = new LinkedHashMap<>();
        put(v, "last-check", lastCheck);
        put(v, "last-attempt", lastAttempt);
        put(v, "latest", latest);
        put(v, "first-seen", firstSeen);
        put(v, "held", held);
        put(v, "held-from", heldFrom);
        put(v, "snooze-until", snoozeUntil);
        put(v, "interactive-seen", interactiveSeen);
        put(v, "notice", notice);
        return MinimalToml.writeFlat(v);
    }

    private static void put(Map<String, String> v, String key, @Nullable Object value) {
        if (value != null) v.put(key, value.toString());
    }

    private static @Nullable Instant instant(Map<String, String> v, String key) {
        String s = v.get(key);
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
