// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.CallerAsker;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.host.Os;
import cc.jumpkick.wire.protocol.EnvAskFrame;
import java.io.BufferedWriter;
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
import org.jspecify.annotations.Nullable;

/**
 * A job's client, asked about its shell on the job's own connection ({@link EnvAskFrame}): the
 * engine side of {@link CallerAsker}. The connection's read loop hands every answer to {@link
 * #deliver} and says when the client is gone ({@link #gone}).
 *
 * <p>What the client answers is kept here, in memory, for the job's life: each name once, so a
 * second test JVM that reads the same variable asks no one. Nothing is logged and nothing is
 * written; the job drops this object when it ends, and with it every value.
 */
final class ClientAsk implements CallerAsker {

    /** How long a question waits for its answer before the client counts as gone. */
    static final long ANSWER_MS = 30_000;

    private final BufferedWriter writer;
    private final long answerMs;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<@Nullable String>> pending = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> known = new ConcurrentHashMap<>();
    private volatile @Nullable Set<String> names;
    private volatile @Nullable Map<String, String> all;
    private volatile boolean gone;
    private volatile boolean lost;

    ClientAsk(BufferedWriter writer) {
        this(writer, ANSWER_MS);
    }

    ClientAsk(BufferedWriter writer, long answerMs) {
        this.writer = writer;
        this.answerMs = answerMs;
    }

    /** Hand {@code line}, read from the job's connection, to the question it answers; any other line is ignored. */
    void deliver(String line) {
        long id = EnvAskFrame.answerId(line);
        if (id < 0) return;
        CompletableFuture<@Nullable String> waiting = pending.remove(id);
        if (waiting != null) waiting.complete(line);
    }

    /** The client is gone: every question waiting, and every later one, goes unanswered. */
    void gone() {
        gone = true;
        for (CompletableFuture<@Nullable String> waiting : pending.values()) waiting.complete(null);
        pending.clear();
    }

    @Override
    public boolean lost() {
        return lost;
    }

    @Override
    public @Nullable Map<String, String> values(Collection<String> wanted) {
        Map<String, String> whole = all;
        Set<String> ask = new LinkedHashSet<>();
        for (String name : wanted) {
            if (whole == null && !known.containsKey(key(name))) ask.add(name);
        }
        if (!ask.isEmpty()) {
            String line = ask(new EnvAskFrame(ids.incrementAndGet(), List.copyOf(ask), null));
            if (line == null) return null;
            Map<String, String> answered = new LinkedHashMap<>();
            EnvAskFrame.answerVars(line).forEach((name, value) -> answered.put(key(name), value));
            for (String name : ask) known.put(key(name), Optional.ofNullable(answered.get(key(name))));
        }
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
        String line = ask(new EnvAskFrame(ids.incrementAndGet(), List.of(), EnvAskFrame.NAMES));
        List<String> answered = line == null ? null : EnvAskFrame.answerNames(line);
        if (answered == null) return null;
        Set<String> got = Set.copyOf(new LinkedHashSet<>(answered));
        names = got;
        return got;
    }

    @Override
    public @Nullable Map<String, String> all() {
        Map<String, String> whole = all;
        if (whole != null) return whole;
        String line = ask(new EnvAskFrame(ids.incrementAndGet(), List.of(), EnvAskFrame.ALL));
        if (line == null) return null;
        Map<String, String> got = Map.copyOf(EnvAskFrame.answerVars(line));
        all = got;
        return got;
    }

    /** One question and its answer line, or null when the client is gone or does not answer in time. */
    private @Nullable String ask(EnvAskFrame frame) {
        if (gone) {
            lost = true;
            return null;
        }
        CompletableFuture<@Nullable String> answer = new CompletableFuture<>();
        pending.put(frame.id(), answer);
        if (gone) {
            pending.remove(frame.id());
            lost = true;
            return null;
        }
        WireWriter.sendNoWait(writer, frame.encode());
        try {
            String line = answer.get(answerMs, TimeUnit.MILLISECONDS);
            if (line == null) lost = true;
            return line;
        } catch (TimeoutException | ExecutionException e) {
            lost = true;
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lost = true;
            return null;
        } finally {
            pending.remove(frame.id());
        }
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
