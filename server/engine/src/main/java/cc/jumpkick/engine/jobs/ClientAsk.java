// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.CallerAsker;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.EnvAskFrame;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A job's client, asked about its shell on the job's own connection ({@link EnvAskFrame}): the
 * engine side of {@link CallerAsker}. The connection's read loop hands every answer to {@link
 * #deliver} and says when the client is gone ({@link #gone}).
 *
 * <p>Each question is asked once per job. Every reader that wants the same thing waits on that one
 * question, and no longer than its answer bound: a question the client left unanswered reads as
 * unknown at once from then on, so a slow client costs a run one bound, not one per reader. An
 * answer that comes late is still kept for the readers after it.
 *
 * <p>What the client answers is kept here, in memory, for the job's life. Nothing is logged and
 * nothing is written; the job drops this object when it ends, and with it every value.
 */
final class ClientAsk implements CallerAsker {

    /** How long a question waits for its answer before the client counts as gone. */
    static final long ANSWER_MS = 30_000;

    private final BufferedWriter writer;
    private final long answerNanos;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Question> pending = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> known = new ConcurrentHashMap<>();

    /** The question that asks for each name, by {@link #key}; guarded by this. */
    private final Map<String, Question> byName = new LinkedHashMap<>();

    private @Nullable Question namesAsked;
    private @Nullable Question allAsked;
    private volatile @Nullable Set<String> names;
    private volatile @Nullable Map<String, String> all;
    private volatile boolean gone;
    private volatile boolean lost;

    ClientAsk(BufferedWriter writer) {
        this(writer, ANSWER_MS);
    }

    ClientAsk(BufferedWriter writer, long answerMs) {
        this.writer = writer;
        this.answerNanos = TimeUnit.MILLISECONDS.toNanos(answerMs);
    }

    /** One question on the wire: answered true, or false once the client is gone. */
    private record Question(CompletableFuture<Boolean> answered, Consumer<String> keep, long deadlineNanos) {}

    /** Hand {@code line}, read from the job's connection, to the question it answers; any other line is ignored. */
    void deliver(String line) {
        long id = EnvAskFrame.answerId(line);
        if (id < 0) return;
        Question q = pending.remove(id);
        if (q == null) return;
        q.keep().accept(line);
        q.answered().complete(true);
    }

    /** The client is gone: every question waiting, and every later one, goes unanswered. */
    void gone() {
        gone = true;
        for (Question q : pending.values()) q.answered().complete(false);
        pending.clear();
    }

    @Override
    public boolean lost() {
        return lost;
    }

    @Override
    public @Nullable Map<String, String> values(Collection<String> wanted) {
        List<Question> waits = new ArrayList<>();
        synchronized (this) {
            if (all == null) {
                List<String> ask = new ArrayList<>();
                for (String name : new LinkedHashSet<>(wanted)) {
                    if (known.containsKey(key(name))) continue;
                    Question q = byName.get(key(name));
                    if (q != null) waits.add(q);
                    else ask.add(name);
                }
                if (!ask.isEmpty()) {
                    List<String> asked = List.copyOf(ask);
                    Question q = ask(new EnvAskFrame(ids.incrementAndGet(), asked, null), line -> keep(asked, line));
                    for (String name : asked) byName.put(key(name), q);
                    waits.add(q);
                }
            }
        }
        for (Question q : waits) {
            if (!answered(q)) return null;
        }
        Map<String, String> whole = all;
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : wanted) {
            String v = whole != null
                    ? lookup(whole, name)
                    : known.getOrDefault(key(name), Optional.empty()).orElse(null);
            if (v != null) out.put(name, v);
        }
        return out;
    }

    @Override
    public @Nullable Set<String> names() {
        Map<String, String> whole = all;
        if (whole != null) return whole.keySet();
        Set<String> sent = names;
        if (sent != null) return sent;
        Question q;
        synchronized (this) {
            q = namesAsked;
            if (q == null) {
                q = namesAsked = ask(new EnvAskFrame(ids.incrementAndGet(), List.of(), EnvAskFrame.NAMES), line -> {
                    List<String> answered = EnvAskFrame.answerNames(line);
                    if (answered != null) names = Set.copyOf(new LinkedHashSet<>(answered));
                });
            }
        }
        return answered(q) ? names : null;
    }

    @Override
    public @Nullable Map<String, String> all() {
        Map<String, String> whole = all;
        if (whole != null) return whole;
        Question q;
        synchronized (this) {
            q = allAsked;
            if (q == null) {
                q = allAsked = ask(
                        new EnvAskFrame(ids.incrementAndGet(), List.of(), EnvAskFrame.ALL),
                        line -> all = Map.copyOf(EnvAskFrame.answerVars(line)));
            }
        }
        return answered(q) ? all : null;
    }

    /** What an answer to a question for {@code asked} says of each of them. */
    private void keep(List<String> asked, String line) {
        Map<String, String> answered = new LinkedHashMap<>();
        EnvAskFrame.answerVars(line).forEach((name, value) -> answered.put(key(name), value));
        for (String name : asked) known.put(key(name), Optional.ofNullable(answered.get(key(name))));
    }

    /** Send {@code frame}, its answer handed to {@code keep}; a client already gone gets no question. */
    private Question ask(EnvAskFrame frame, Consumer<String> keep) {
        Question q = new Question(new CompletableFuture<>(), keep, Clock.SYSTEM.nanos() + answerNanos);
        if (gone) {
            q.answered().complete(false);
            return q;
        }
        pending.put(frame.id(), q);
        if (gone) {
            pending.remove(frame.id());
            q.answered().complete(false);
            return q;
        }
        WireWriter.sendNoWait(writer, frame.encode());
        return q;
    }

    /** Whether the client answered {@code q} by its deadline; a question it did not marks the job lost. */
    private boolean answered(Question q) {
        CompletableFuture<Boolean> answered = q.answered();
        try {
            if (answered.isDone()) return mark(answered.get());
            long left = q.deadlineNanos() - Clock.SYSTEM.nanos();
            if (left <= 0) return mark(false);
            return mark(answered.get(left, TimeUnit.NANOSECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return mark(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return mark(false);
        }
    }

    private boolean mark(boolean answered) {
        if (!answered) lost = true;
        return answered;
    }

    private static @Nullable String lookup(Map<String, String> vars, String name) {
        String v = vars.get(name);
        if (v != null || !Os.isWindows()) return v;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String key(String name) {
        return Os.isWindows() ? name.toUpperCase(Locale.ROOT) : name;
    }

    @Override
    public String toString() {
        return "ClientAsk[" + known.size() + " names known" + (gone ? ", gone" : "") + "]"; // never a value
    }
}
