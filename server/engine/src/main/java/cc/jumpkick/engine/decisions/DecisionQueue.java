// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Every decision the engine holds, open and recently settled. The first answer wins; a later one is
 * told how it was settled. An open decision expires as declined at its deadline ({@link #DEADLINE}
 * after it opened unless the asker names another).
 *
 * <p>Each decision has a one-time {@linkplain #hostKey host key}, which rides only the link the
 * engine hands the agent's host by URL elicitation. A dashboard answer that carries it was made on
 * the page the host opened, so it counts as the host's; one without it is the dashboard's, which
 * may decline a trust-changing decision but never accept it, since an agent can read the bearer
 * token and drive a browser.
 *
 * <p>Ids are 24 hex digits from {@link SecureRandom}: unguessable, never reused.
 */
public final class DecisionQueue {

    /** How long an open decision waits for a person. */
    public static final Duration DEADLINE = Duration.ofMinutes(30);

    /** Decisions kept in all, open ones included; past it the oldest settled ones go first. */
    static final int MAX_KEPT = 200;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile Consumer<Decision> changed = d -> {};

    private static final class Entry {
        Decision decision;
        final String hostKey;
        final CompletableFuture<Decision> settled = new CompletableFuture<>();
        boolean spent;

        Entry(Decision decision, String hostKey) {
            this.decision = decision;
            this.hostKey = hostKey;
        }
    }

    /** What raises a decision: everything but its id, its times and its resolution. */
    public record Ask(
            String kind,
            String question,
            List<String> detail,
            Form form,
            boolean trust,
            @Nullable String session,
            @Nullable String label,
            @Nullable String episode,
            @Nullable String project) {

        public Ask {
            detail = List.copyOf(detail);
        }
    }

    /** How an answer landed. */
    public sealed interface Result {
        /** The answer settled the decision. */
        record Settled(Decision decision) implements Result {}

        /** The decision was settled before this answer; {@code decision} says how. */
        record Already(Decision decision) implements Result {}

        /** No decision has the id, or it has been dropped. */
        record Missing() implements Result {}

        /** This surface may not give this answer; {@code reason} says why. */
        record Refused(String reason) implements Result {}

        /** The answers do not fit the form. */
        record Invalid(String problems) implements Result {}
    }

    public DecisionQueue() {
        this(Clock.SYSTEM);
    }

    public DecisionQueue(Clock clock) {
        this.clock = clock;
    }

    /** Run {@code changed} with every decision that opens or settles, outside the queue's lock. */
    public void onChange(Consumer<Decision> changed) {
        this.changed = Objects.requireNonNull(changed);
    }

    /** Opens a decision that expires {@link #DEADLINE} from now. */
    public Decision open(Ask ask) {
        return open(ask, DEADLINE);
    }

    /** Opens a decision that expires {@code within} from now. */
    public Decision open(Ask ask, Duration within) {
        Decision d;
        List<Decision> expired;
        synchronized (this) {
            expired = expire();
            String id = random(12);
            long now = clock.millis();
            d = new Decision(
                    id,
                    ask.kind(),
                    ask.question(),
                    ask.detail(),
                    "jk decide " + id,
                    ask.form(),
                    ask.trust(),
                    ask.session(),
                    ask.label(),
                    ask.episode(),
                    ask.project(),
                    now,
                    now + within.toMillis(),
                    Decision.State.OPEN,
                    Map.of(),
                    null,
                    0);
            entries.put(id, new Entry(d, random(16)));
            trim();
        }
        expired.forEach(changed);
        changed.accept(d);
        return d;
    }

    /** The decision {@code id} as it stands now. */
    public Optional<Decision> find(String id) {
        List<Decision> expired;
        Optional<Decision> found;
        synchronized (this) {
            expired = expire();
            Entry e = entries.get(id);
            found = e == null ? Optional.empty() : Optional.of(e.decision);
        }
        expired.forEach(changed);
        return found;
    }

    /** An open decision of {@code kind} that {@code label}'s session raised about {@code project}. */
    public synchronized Optional<Decision> openFor(String kind, @Nullable String label, @Nullable String project) {
        for (Entry e : entries.values()) {
            Decision d = e.decision;
            if (d.open()
                    && !e.spent
                    && d.kind().equals(kind)
                    && Objects.equals(d.label(), label)
                    && Objects.equals(d.project(), project)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** Open decisions first, newest first within each group. */
    public List<Decision> list() {
        List<Decision> expired;
        List<Decision> all;
        synchronized (this) {
            expired = expire();
            all = entries.values().stream().map(e -> e.decision).collect(Collectors.toCollection(ArrayList::new));
        }
        expired.forEach(changed);
        all.sort(Comparator.comparing((Decision d) -> !d.open())
                .thenComparing(Comparator.comparingLong(Decision::createdAt).reversed()));
        return all;
    }

    /**
     * Accept {@code id} with {@code answers}, checked against its form, from {@code surface}. A
     * confirmation answered {@link DecisionForms#NO} settles declined. {@code hostKey}, when it is
     * the decision's own, makes a dashboard answer the host's.
     */
    public Result accept(String id, Map<String, ?> answers, DecisionSurface surface, @Nullable String hostKey) {
        return answer(id, answers, true, surface, hostKey);
    }

    /** Decline {@code id} from {@code surface}. Any surface may decline. */
    public Result decline(String id, DecisionSurface surface, @Nullable String hostKey) {
        return answer(id, Map.of(), false, surface, hostKey);
    }

    private Result answer(
            String id, Map<String, ?> answers, boolean accept, DecisionSurface surface, @Nullable String hostKey) {
        List<Decision> expired;
        Result result;
        synchronized (this) {
            expired = expire();
            result = settle(id, answers, accept, surface, hostKey);
        }
        expired.forEach(changed);
        if (result instanceof Result.Settled s) changed.accept(s.decision());
        return result;
    }

    private Result settle(
            String id, Map<String, ?> answers, boolean accept, DecisionSurface surface, @Nullable String hostKey) {
        Entry e = entries.get(id);
        if (e == null) return new Result.Missing();
        if (!e.decision.open()) return new Result.Already(e.decision);
        DecisionSurface from =
                surface == DecisionSurface.DASHBOARD && keyMatches(e, hostKey) ? DecisionSurface.HOST_URL : surface;
        Decision.State state = Decision.State.DECLINED;
        Map<String, Object> values = Map.of();
        if (accept) {
            FormCheck check = e.decision.form().check(answers, DerivedDefaults.NONE);
            if (!check.ok()) {
                return new Result.Invalid(check.problems().stream()
                        .map(FormCheck.Problem::message)
                        .collect(Collectors.joining("; ")));
            }
            values = check.values();
            if (DecisionForms.shows(values)) return new Result.Invalid("'show' is not an answer; open the decision");
            if (!DecisionForms.declines(values)) state = Decision.State.ACCEPTED;
        }
        if (state == Decision.State.ACCEPTED && e.decision.trust() && !from.provesAPerson()) {
            return new Result.Refused("this decision changes what the project trusts, so the dashboard can"
                    + " decline it but not accept it; accept it in a terminal with " + e.decision.command());
        }
        e.decision = e.decision.settled(state, values, from, clock.millis());
        e.settled.complete(e.decision);
        return new Result.Settled(e.decision);
    }

    /**
     * Waits up to {@code timeout} for {@code id} to settle and answers it as it then stands; empty
     * when there is no such decision. A deadline that passes meanwhile settles it expired.
     */
    public Optional<Decision> await(String id, Duration timeout) {
        Entry e;
        long until;
        synchronized (this) {
            e = entries.get(id);
            if (e == null) return Optional.empty();
            until = Math.min(timeout.toMillis(), Math.max(0, e.decision.deadline() - clock.millis()));
        }
        try {
            e.settled.get(Math.max(1, until), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException waited) {
            // Still open, or its deadline passed: find() settles the expiry.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return find(id);
    }

    /**
     * Marks an accepted decision used by the operation that asked it; true only the first time, so a
     * decision proceeds one operation once.
     */
    public synchronized boolean spend(String id) {
        Entry e = entries.get(id);
        if (e == null || !e.decision.accepted() || e.spent) return false;
        e.spent = true;
        return true;
    }

    /** True once {@link #spend} took {@code id}. */
    public synchronized boolean spent(String id) {
        Entry e = entries.get(id);
        return e != null && e.spent;
    }

    /**
     * The one-time key the URL elicitation link for {@code id} carries; null when there is no such
     * decision. Never put it in a tool result or a notification: the agent reads those.
     */
    public synchronized @Nullable String hostKey(String id) {
        Entry e = entries.get(id);
        return e == null ? null : e.hostKey;
    }

    private static boolean keyMatches(Entry e, @Nullable String key) {
        if (key == null || key.isBlank()) return false;
        return MessageDigest.isEqual(
                e.hostKey.getBytes(StandardCharsets.UTF_8), key.strip().getBytes(StandardCharsets.UTF_8));
    }

    /** Settles every open decision past its deadline; answers them for {@link #changed}. */
    private List<Decision> expire() {
        long now = clock.millis();
        List<Decision> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.decision.open() && now >= e.decision.deadline()) {
                e.decision = e.decision.settled(Decision.State.EXPIRED, Map.of(), null, now);
                e.settled.complete(e.decision);
                out.add(e.decision);
            }
        }
        return out;
    }

    /** Drops the oldest settled decisions past {@link #MAX_KEPT}, then the oldest open ones. */
    private void trim() {
        while (entries.size() > MAX_KEPT) {
            String victim = entries.entrySet().stream()
                    .filter(x -> !x.getValue().decision.open())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseGet(() -> entries.keySet().iterator().next());
            entries.remove(victim);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Hashing.hex(b);
    }
}
