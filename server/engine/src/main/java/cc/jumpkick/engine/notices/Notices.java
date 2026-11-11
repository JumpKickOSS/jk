// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The machine's recent notices and its one "seen up to" marker. The bell's number is how many
 * notices are newer than the marker; opening the bell, showing a decision's dialog or clicking a
 * notification moves it, and every listener (each dashboard stream) hears the move, so clearing the
 * count in one tab clears it in all of them. Seen is not resolved: a decision that was seen but not
 * answered stops counting and stays in the bell's "Waiting on you" list, which the decision queue,
 * not this class, keeps.
 *
 * <p>Each notice's {@code at} is a wall-clock millisecond made strictly increasing, so the marker
 * is one number. The newest {@link #KEEP} notices and the marker are kept in one small JSON file,
 * rewritten whole on each change; a file that is missing or unreadable starts empty.
 */
public final class Notices {

    /** The file under the state directory that keeps them. */
    public static final String FILE = "notices.json";

    /** How many notices are kept: the bell's "Just now", not a history (that is the activity log). */
    public static final int KEEP = 50;

    /** One thing JumpKick told the person. {@code decision} names the decision it is about, if any. */
    public record Notice(
            String id,
            long at,
            String kind,
            String text,
            String href,
            @Nullable String decision) {

        /** As {@code GET /api/notices} lists it. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("at", at);
            m.put("kind", kind);
            m.put("text", text);
            m.put("href", href);
            if (decision != null) m.put("decision", decision);
            return m;
        }
    }

    /** What changed: a notice {@code added} (with it), or the marker {@code seen}. */
    public record Change(String change, @Nullable Notice notice, long seenUpTo, int unseen) {

        /** As the SSE {@code notice} frame carries it. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("change", change);
            if (notice != null) m.put("notice", notice.toMap());
            m.put("seenUpTo", seenUpTo);
            m.put("unseen", unseen);
            return m;
        }
    }

    private final @Nullable Path file;
    private final Clock clock;
    private final Deque<Notice> recent = new ArrayDeque<>();
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> noticed = ConcurrentHashMap.newKeySet();
    private long seenUpTo;
    private long last;

    /** Kept in memory only; tests and an engine without a state directory. */
    public Notices() {
        this(null, Clock.SYSTEM);
    }

    /** Kept in {@code file} (null for memory only), stamped by {@code clock}. */
    public Notices(@Nullable Path file, Clock clock) {
        this.file = file;
        this.clock = Objects.requireNonNull(clock);
        load();
    }

    /** Also run {@code listener} with every change, outside the lock. */
    public void onChange(Consumer<Change> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Adds a notice of {@code kind} saying {@code text}, linking to {@code href}. */
    public Notice add(String kind, String text, String href, @Nullable String decision) {
        Change change;
        Notice n;
        synchronized (this) {
            long at = Math.max(clock.millis(), last + 1);
            last = at;
            n = new Notice(Long.toString(at, 36), at, kind, text, href, decision);
            recent.addFirst(n);
            while (recent.size() > KEEP) recent.removeLast();
            change = new Change("added", n, seenUpTo, unseenLocked());
            save();
        }
        listeners.forEach(l -> l.accept(change));
        return n;
    }

    /**
     * Moves the marker to {@code upTo} (never back, never past the newest notice) and answers the
     * state after; a move that changes nothing tells no listener.
     */
    public Change seen(long upTo) {
        Change change;
        synchronized (this) {
            long to = Math.min(upTo, last);
            if (to <= seenUpTo) return new Change("seen", null, seenUpTo, unseenLocked());
            seenUpTo = to;
            change = new Change("seen", null, seenUpTo, unseenLocked());
            save();
        }
        listeners.forEach(l -> l.accept(change));
        return change;
    }

    /** The marker: every notice at or before it has been seen. */
    public synchronized long seenUpTo() {
        return seenUpTo;
    }

    /** How many notices are newer than the marker. */
    public synchronized int unseen() {
        return unseenLocked();
    }

    /** The kept notices, newest first. */
    public synchronized List<Notice> list() {
        return List.copyOf(recent);
    }

    /**
     * Adds a notice for each decision that opens in {@code queue}, once per decision: the bell
     * counts it until the person has seen it.
     */
    public void follow(DecisionQueue queue) {
        queue.onChange(d -> {
            if (d.open() && noticed.add(d.id())) add("decision", decisionText(d), "#decision/" + d.id(), d.id());
        });
    }

    /** "claude-code 3f9a needs you: Clean jk's cache?" */
    static String decisionText(Decision d) {
        String label = d.label();
        String who = label != null && !label.isBlank() ? label : "setup".equals(d.kind()) ? "JumpKick" : "An agent";
        return who + " needs you: " + d.question();
    }

    private int unseenLocked() {
        int n = 0;
        for (Notice notice : recent) {
            if (notice.at() <= seenUpTo) break;
            n++;
        }
        return n;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Object parsed = MiniJson.parse(Files.readString(file));
            if (!(parsed instanceof Map<?, ?> body)) return;
            if (body.get("seenUpTo") instanceof Number n) seenUpTo = n.longValue();
            List<Notice> loaded = new ArrayList<>();
            for (Object row : MiniJson.list(body, "notices")) {
                if (!(row instanceof Map<?, ?> m) || !(m.get("at") instanceof Number at)) continue;
                String id = MiniJson.str(m, "id");
                String kind = MiniJson.str(m, "kind");
                String text = MiniJson.str(m, "text");
                String href = MiniJson.str(m, "href");
                if (id == null || kind == null || text == null || href == null) continue;
                loaded.add(new Notice(id, at.longValue(), kind, text, href, MiniJson.str(m, "decision")));
            }
            loaded.sort((a, b) -> Long.compare(b.at(), a.at()));
            for (Notice n : loaded) {
                if (recent.size() == KEEP) break;
                recent.addLast(n);
                if (n.decision() != null) noticed.add(n.decision());
            }
            last = Math.max(seenUpTo, recent.isEmpty() ? 0 : recent.peekFirst().at());
        } catch (IOException | RuntimeException e) {
            // An unreadable file starts the bell empty; the next change rewrites it.
            recent.clear();
            seenUpTo = 0;
            last = 0;
        }
    }

    private void save() {
        if (file == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seenUpTo", seenUpTo);
        body.put("notices", recent.stream().map(Notice::toMap).toList());
        try {
            Files.createDirectories(file.getParent());
            AtomicWrites.replace(file, MiniJson.write(body));
        } catch (IOException e) {
            // Best effort: the count stays right in memory, and the next change tries again.
        }
    }
}
