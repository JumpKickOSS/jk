// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
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
 * latest-etag = "\"ae1c35d5…\""            # the pointer's ETag, sent back as If-None-Match
 * first-seen = "2026-10-01T08:00:00Z"     # the first time this home ran the scheduler
 * held = "0.16.2"                         # a deliberate downgrade: no auto-update until cleared
 * held-from = "0.17.0"
 * snooze-until = "2026-10-31T09:00:00Z"   # "not now" to an offered update, on any surface
 * snooze-version = "0.17.0"               # the version that answer was about
 * interactive-seen = "2026-10-30T09:00:00Z"
 * notice = "0.16.2"                       # the version that last printed the pre-1.0 notice
 * updated-from = "0.16.2"                 # the last install: from this version …
 * updated-to = "0.17.0"                   # … to this one …
 * updated-at = "2026-10-30T09:05:00Z"     # … at this time
 * announced-web = "0.17.0"                # the install the web console's bell has announced
 * announced-cli = "0.17.0"                # the install a terminal command has announced
 * </pre>
 */
public record SelfUpdateState(
        @Nullable Instant lastCheck,
        @Nullable Instant lastAttempt,
        @Nullable String latest,
        @Nullable String latestEtag,
        @Nullable Instant firstSeen,
        @Nullable String held,
        @Nullable String heldFrom,
        @Nullable Instant snoozeUntil,
        @Nullable String snoozeVersion,
        @Nullable Instant interactiveSeen,
        @Nullable String notice,
        @Nullable String updatedFrom,
        @Nullable String updatedTo,
        @Nullable Instant updatedAt,
        @Nullable String announcedWeb,
        @Nullable String announcedCli) {

    public static final String FILE_NAME = "self-update.toml";

    public static final SelfUpdateState EMPTY = new Builder().build();

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
            Builder b = new Builder();
            b.lastCheck = instant(v, "last-check");
            b.lastAttempt = instant(v, "last-attempt");
            b.latest = v.get("latest");
            b.latestEtag = v.get("latest-etag");
            b.firstSeen = instant(v, "first-seen");
            b.held = v.get("held");
            b.heldFrom = v.get("held-from");
            b.snoozeUntil = instant(v, "snooze-until");
            b.snoozeVersion = v.get("snooze-version");
            b.interactiveSeen = instant(v, "interactive-seen");
            b.notice = v.get("notice");
            b.updatedFrom = v.get("updated-from");
            b.updatedTo = v.get("updated-to");
            b.updatedAt = instant(v, "updated-at");
            b.announcedWeb = v.get("announced-web");
            b.announcedCli = v.get("announced-cli");
            return b.build();
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

    /**
     * A check that reached the release site and found {@code found}. The pointer's ETag is kept
     * only while the version it vouched for is still the recorded one.
     */
    public SelfUpdateState withCheck(Instant at, @Nullable String found) {
        return with(b -> {
            b.lastCheck = at;
            b.lastAttempt = at;
            if (found == null || !found.equals(b.latest)) b.latestEtag = null;
            b.latest = found;
        });
    }

    /** {@link #withCheck}, remembering the pointer's ETag for the next conditional request. */
    public SelfUpdateState withCheck(Instant at, @Nullable String found, @Nullable String etag) {
        SelfUpdateState checked = withCheck(at, found);
        return checked.with(b -> b.latestEtag = etag);
    }

    public SelfUpdateState withAttempt(Instant at) {
        return with(b -> b.lastAttempt = at);
    }

    public SelfUpdateState withFirstSeen(Instant at) {
        return with(b -> b.firstSeen = at);
    }

    /** Held at {@code version} after a deliberate downgrade from {@code from}. */
    public SelfUpdateState withHold(String version, String from) {
        return with(b -> {
            b.held = version;
            b.heldFrom = from;
        });
    }

    public SelfUpdateState withoutHold() {
        return with(b -> {
            b.held = null;
            b.heldFrom = null;
        });
    }

    /** "Not now" to {@code version}, until {@code until}; see {@link UpdateOffer}. */
    public SelfUpdateState withSnooze(String version, Instant until) {
        return with(b -> {
            b.snoozeVersion = version;
            b.snoozeUntil = until;
        });
    }

    public SelfUpdateState withInteractiveSeen(Instant at) {
        return with(b -> b.interactiveSeen = at);
    }

    public SelfUpdateState withNotice(String version) {
        return with(b -> b.notice = version);
    }

    /** An install from {@code from} to {@code to} finished at {@code at}; neither surface has announced it. */
    public SelfUpdateState withUpdated(String from, String to, Instant at) {
        return with(b -> {
            b.updatedFrom = from;
            b.updatedTo = to;
            b.updatedAt = at;
        });
    }

    /** The web console's bell has announced the install to {@code version}. */
    public SelfUpdateState withAnnouncedWeb(String version) {
        return with(b -> b.announcedWeb = version);
    }

    /** A terminal command has announced the install to {@code version}. */
    public SelfUpdateState withAnnouncedCli(String version) {
        return with(b -> b.announcedCli = version);
    }

    /**
     * Whether the last install is to {@code running} and {@code announced} (one surface's record)
     * has not told anyone yet: the one moment that surface says "JumpKick was updated".
     */
    public boolean announceDue(String running, @Nullable String announced) {
        return updatedFrom != null && running.equals(updatedTo) && !running.equals(announced);
    }

    private SelfUpdateState with(Consumer<Builder> change) {
        Builder b = new Builder(this);
        change.accept(b);
        return b.build();
    }

    String toToml() {
        Map<String, String> v = new LinkedHashMap<>();
        put(v, "last-check", lastCheck);
        put(v, "last-attempt", lastAttempt);
        put(v, "latest", latest);
        put(v, "latest-etag", latestEtag);
        put(v, "first-seen", firstSeen);
        put(v, "held", held);
        put(v, "held-from", heldFrom);
        put(v, "snooze-until", snoozeUntil);
        put(v, "snooze-version", snoozeVersion);
        put(v, "interactive-seen", interactiveSeen);
        put(v, "notice", notice);
        put(v, "updated-from", updatedFrom);
        put(v, "updated-to", updatedTo);
        put(v, "updated-at", updatedAt);
        put(v, "announced-web", announcedWeb);
        put(v, "announced-cli", announcedCli);
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

    /** Every field, mutable, so a change names only what it changes. */
    private static final class Builder {
        @Nullable
        Instant lastCheck;

        @Nullable
        Instant lastAttempt;

        @Nullable
        String latest;

        @Nullable
        String latestEtag;

        @Nullable
        Instant firstSeen;

        @Nullable
        String held;

        @Nullable
        String heldFrom;

        @Nullable
        Instant snoozeUntil;

        @Nullable
        String snoozeVersion;

        @Nullable
        Instant interactiveSeen;

        @Nullable
        String notice;

        @Nullable
        String updatedFrom;

        @Nullable
        String updatedTo;

        @Nullable
        Instant updatedAt;

        @Nullable
        String announcedWeb;

        @Nullable
        String announcedCli;

        Builder() {}

        Builder(SelfUpdateState s) {
            lastCheck = s.lastCheck;
            lastAttempt = s.lastAttempt;
            latest = s.latest;
            latestEtag = s.latestEtag;
            firstSeen = s.firstSeen;
            held = s.held;
            heldFrom = s.heldFrom;
            snoozeUntil = s.snoozeUntil;
            snoozeVersion = s.snoozeVersion;
            interactiveSeen = s.interactiveSeen;
            notice = s.notice;
            updatedFrom = s.updatedFrom;
            updatedTo = s.updatedTo;
            updatedAt = s.updatedAt;
            announcedWeb = s.announcedWeb;
            announcedCli = s.announcedCli;
        }

        SelfUpdateState build() {
            return new SelfUpdateState(
                    lastCheck,
                    lastAttempt,
                    latest,
                    latestEtag,
                    firstSeen,
                    held,
                    heldFrom,
                    snoozeUntil,
                    snoozeVersion,
                    interactiveSeen,
                    notice,
                    updatedFrom,
                    updatedTo,
                    updatedAt,
                    announcedWeb,
                    announcedCli);
        }
    }
}
