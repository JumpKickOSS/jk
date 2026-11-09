// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Every decision the engine holds, open and recently settled. Any surface may give any answer; the
 * first answer wins, and a later one is told how it was settled. An open decision expires as
 * declined at its deadline ({@link #DEADLINE} after it opened unless the asker names another).
 *
 * <p>Decisions outlive the engine. Every change is written to the {@link Store} before it takes
 * effect, and a new engine {@link #adopt adopts} what a previous one left, so a decision opened on
 * one engine is answered on its successor and its operation still runs once. A store that refuses a
 * write (this engine was displaced) refuses the answer or the use it would record.
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
    private final List<Consumer<Decision>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Decision>> alerters = new CopyOnWriteArrayList<>();
    private volatile Function<String, @Nullable String> links = id -> null;
    private final Consumer<Decision> changed = d -> listeners.forEach(l -> l.accept(d));
    private volatile Store store = Store.MEMORY;

    /** Whether {@link #adopt} has run. */
    private boolean adopted;

    /**
     * Where decisions outlive the engine: one record per decision, each the decision's {@link
     * Decision#toMap} plus {@code spent} and {@code call}.
     */
    public interface Store {
        /** Keeps nothing and refuses nothing: an engine with no journal. */
        Store MEMORY = new Store() {
            @Override
            public boolean save(String id, Map<String, Object> record) {
                return true;
            }

            @Override
            public boolean drop(String id) {
                return true;
            }

            @Override
            public List<Map<String, Object>> load() {
                return List.of();
            }
        };

        /** Replace {@code id}'s record; false when this engine no longer writes decisions. */
        boolean save(String id, Map<String, Object> record);

        /** Forget {@code id}'s record; false when this engine no longer writes decisions. */
        boolean drop(String id);

        /** Every stored record, once the engine this one displaced has stopped writing them. */
        List<Map<String, Object>> load();
    }

    private static final class Entry {
        Decision decision;

        final CompletableFuture<Decision> settled = new CompletableFuture<>();
        boolean spent;

        /** The call this decision continues, as its asker stored it; null for none. */
        @Nullable
        Map<String, Object> call;

        /** The channel that told the person ({@code in-app}, {@code browser}, {@code native}, …); null for none yet. */
        @Nullable
        String notified;

        Entry(Decision decision) {
            this.decision = decision;
        }

        /** This entry as the store keeps it, with {@code d} for its decision and {@code spent}. */
        Map<String, Object> record(Decision d, boolean spent) {
            Map<String, Object> m = new LinkedHashMap<>(d.toMap());
            m.remove("route");
            m.put("spent", spent);
            if (call != null) m.put("call", call);
            if (notified != null) m.put("notified", notified);
            return m;
        }

        @SuppressWarnings("unchecked")
        static @Nullable Entry of(Map<String, Object> record) {
            Decision d = Decision.of(record).orElse(null);
            if (d == null) return null;
            Entry e = new Entry(d);
            e.spent = Boolean.TRUE.equals(record.get("spent"));
            if (record.get("call") instanceof Map<?, ?> c) e.call = (Map<String, Object>) c;
            if (record.get("notified") instanceof String n) e.notified = n;
            if (!d.open()) e.settled.complete(d);
            return e;
        }
    }

    /** What raises a decision: everything but its id, its times and its resolution. */
    public record Ask(
            String kind,
            String question,
            List<String> detail,
            Form form,
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

        /** The answers do not fit the form. */
        record Invalid(String problems) implements Result {}
    }

    public DecisionQueue() {
        this(Clock.SYSTEM);
    }

    public DecisionQueue(Clock clock) {
        this.clock = clock;
    }

    /** Also run {@code listener} with every decision that opens or settles, outside the queue's lock. */
    public void onChange(Consumer<Decision> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Also run {@code alerter} with every decision whose asker wants a person told: {@link #alert}. */
    public void onAlert(Consumer<Decision> alerter) {
        alerters.add(Objects.requireNonNull(alerter));
    }

    /**
     * Tell a person about {@code d} the way {@code [ui] ask} says, for an asker with no agent's host
     * to ask through: the dashboard's alerters notify or open the browser.
     */
    public void alert(Decision d) {
        alerters.forEach(a -> a.accept(d));
    }

    /** Resolve dashboard links to decisions with {@code links}; set once the dashboard serves. */
    public void links(Function<String, @Nullable String> links) {
        this.links = Objects.requireNonNull(links);
    }

    /** The dashboard link to {@code id}; null while the dashboard is not serving. */
    public @Nullable String link(String id) {
        return links.apply(id);
    }

    /** Keep decisions in {@code store} and adopt what it holds. Before the queue is first used. */
    public void store(Store store) {
        this.store = Objects.requireNonNull(store);
    }

    /**
     * Take over every decision the store holds, as a previous engine left it: open ones stay open
     * until their deadline, and one whose deadline passed meanwhile settles expired here. Runs once;
     * every other entry point runs it first. Answers how many it adopted.
     */
    public int adopt() {
        List<Decision> expired;
        int n = 0;
        synchronized (this) {
            if (adopted) return 0;
            adopted = true;
            for (Map<String, Object> record : store.load()) {
                Entry e = Entry.of(record);
                if (e == null || entries.containsKey(e.decision.id())) continue;
                entries.put(e.decision.id(), e);
                n++;
            }
            List<Entry> byAge = new ArrayList<>(entries.values());
            byAge.sort(Comparator.comparingLong(x -> x.decision.createdAt()));
            entries.clear();
            byAge.forEach(x -> entries.put(x.decision.id(), x));
            trim();
            expired = expire();
        }
        expired.forEach(changed);
        return n;
    }

    /** Opens a decision that expires {@link #DEADLINE} from now. */
    public Decision open(Ask ask) {
        return open(ask, DEADLINE);
    }

    /** Opens a decision that expires {@code within} from now. */
    public Decision open(Ask ask, Duration within) {
        adopt();
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
            Entry e = new Entry(d);
            entries.put(id, e);
            save(e, d, false);
            trim();
        }
        expired.forEach(changed);
        changed.accept(d);
        return d;
    }

    /** The decision {@code id} as it stands now. */
    public Optional<Decision> find(String id) {
        adopt();
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
    public Optional<Decision> openFor(String kind, @Nullable String label, @Nullable String project) {
        adopt();
        return openAmong(kind, label, project);
    }

    /**
     * The decision of {@code kind} about {@code project}, with no session, that still waits to be used:
     * open, or accepted and not yet spent, so asking again after a late answer proceeds with it.
     */
    public Optional<Decision> unspentFor(String kind, @Nullable String project) {
        adopt();
        synchronized (this) {
            for (Entry e : entries.values()) {
                Decision d = e.decision;
                if ((d.open() || d.accepted())
                        && !e.spent
                        && d.kind().equals(kind)
                        && d.label() == null
                        && Objects.equals(d.project(), project)) {
                    return Optional.of(d);
                }
            }
        }
        return Optional.empty();
    }

    private synchronized Optional<Decision> openAmong(String kind, @Nullable String label, @Nullable String project) {
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
        adopt();
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
     * confirmation answered {@link DecisionForms#NO} settles declined.
     */
    public Result accept(String id, Map<String, ?> answers, DecisionSurface surface) {
        return answer(id, answers, true, surface);
    }

    /** Decline {@code id} from {@code surface}. */
    public Result decline(String id, DecisionSurface surface) {
        return answer(id, Map.of(), false, surface);
    }

    private Result answer(String id, Map<String, ?> answers, boolean accept, DecisionSurface surface) {
        adopt();
        List<Decision> expired;
        Result result;
        synchronized (this) {
            expired = expire();
            result = settle(id, answers, accept, surface);
        }
        expired.forEach(changed);
        if (result instanceof Result.Settled s) changed.accept(s.decision());
        return result;
    }

    private Result settle(String id, Map<String, ?> answers, boolean accept, DecisionSurface surface) {
        Entry e = entries.get(id);
        if (e == null) return new Result.Missing();
        if (!e.decision.open()) return new Result.Already(e.decision);
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
        Decision settled = e.decision.settled(state, values, surface, clock.millis());
        if (!save(e, settled, e.spent)) return new Result.Missing();
        e.decision = settled;
        e.settled.complete(settled);
        return new Result.Settled(settled);
    }

    /**
     * Waits up to {@code timeout} for {@code id} to settle and answers it as it then stands; empty
     * when there is no such decision. A deadline that passes meanwhile settles it expired.
     */
    public Optional<Decision> await(String id, Duration timeout) {
        adopt();
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
    public boolean spend(String id) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            if (e == null || !e.decision.accepted() || e.spent) return false;
            if (!save(e, e.decision, true)) return false;
            e.spent = true;
            return true;
        }
    }

    /** True once {@link #spend} took {@code id}. */
    public boolean spent(String id) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            return e != null && e.spent;
        }
    }

    /**
     * Completes with {@code id} once it settles, by any surface or its deadline: how a surface that
     * asked stops asking when another answered first. Never completes when there is no such decision.
     */
    public CompletableFuture<Decision> whenSettled(String id) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            return e == null ? new CompletableFuture<>() : e.settled.copy();
        }
    }

    /** Remember {@code call} as what {@code id} continues; null forgets it. Nothing when there is no such decision. */
    public void call(String id, @Nullable Map<String, Object> call) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            if (e == null) return;
            e.call = call == null ? null : Map.copyOf(call);
            save(e, e.decision, e.spent);
        }
    }

    /** The call {@code id} continues, as {@link #call(String, Map)} stored it; empty for none. */
    public Optional<Map<String, Object>> call(String id) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            return e == null ? Optional.empty() : Optional.ofNullable(e.call);
        }
    }

    /**
     * Remember that {@code channel} told the person about {@code id}: {@code in-app}, {@code browser},
     * {@code native}, {@code open} (the browser opened on it) or {@code none}. Kept with the decision,
     * so its history says how it reached the person. Nothing when there is no such decision.
     */
    public void notified(String id, String channel) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            if (e == null) return;
            e.notified = channel;
            save(e, e.decision, e.spent);
        }
    }

    /** The channel {@link #notified(String, String)} recorded for {@code id}; empty for none. */
    public Optional<String> notified(String id) {
        adopt();
        synchronized (this) {
            Entry e = entries.get(id);
            return e == null ? Optional.empty() : Optional.ofNullable(e.notified);
        }
    }

    /**
     * Forget {@code id} and its record, for the history prune: it leaves {@link #list} and no later
     * write brings the record back. One past its deadline settles expired first; false for one
     * still open before it, which stays, and when the store refuses.
     */
    public boolean prune(String id) {
        List<Decision> expired;
        boolean dropped;
        synchronized (this) {
            expired = expire();
            Entry e = entries.get(id);
            dropped = (e == null || !e.decision.open()) && store.drop(id);
            if (dropped) entries.remove(id);
        }
        expired.forEach(changed);
        return dropped;
    }

    /** Write {@code e} as it stands with {@code d} and {@code spent}; false when the store refused it. */
    private boolean save(Entry e, Decision d, boolean spent) {
        return store.save(d.id(), e.record(d, spent));
    }

    /** Settles every open decision past its deadline; answers them for {@link #changed}. */
    private List<Decision> expire() {
        long now = clock.millis();
        List<Decision> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.decision.open() && now >= e.decision.deadline()) {
                e.decision = e.decision.settled(Decision.State.EXPIRED, Map.of(), null, now);
                save(e, e.decision, e.spent);
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
            store.drop(victim);
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Hashing.hex(b);
    }
}
