// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.task.IoLedger;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The request the work running now serves: bound once where the request starts and carried by
 * {@link SessionContext} onto every pool task and every thread it starts, beside the session.
 * {@link #current()} is {@code null} off a request.
 *
 * <p>A copy made with a {@code with} method is the same request: {@link RequestScope} keys on
 * {@link #io}, and forked workers register to {@link #id}.
 *
 * @param id the engine's id for the job: positive for a job, {@code 0} for a request that is no job
 *     (a synchronous read, a command line), negative for a compile scope the engine owns
 * @param io the request's byte ledger; every {@link Session#defaults()} built under it meters here
 * @param shell the environment of the shell that sent the request, or {@code null} for none
 * @param actor who the request's changes are recorded as, or {@code null} for the process default
 * @param slots values of types above this module, each under its {@link Slot}
 */
public record Request(
        long id,
        IoLedger io,
        @Nullable RequestShell shell,
        @Nullable ChangeActor actor,
        Map<Slot<?>, Object> slots) {

    static {
        ChangeActor.resolveWith(() -> {
            Request r = current();
            return r == null ? null : r.actor();
        });
    }

    public Request {
        Objects.requireNonNull(io);
        slots = Map.copyOf(slots);
    }

    /** A request value of type {@code T}, for a type this module cannot name. Compared by identity. */
    public static final class Slot<T> {
        private final String name;

        public Slot(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Work under a request that answers a value and may throw {@code X}. */
    @FunctionalInterface
    public interface Body<T extends @Nullable Object, X extends Throwable> {
        T call() throws X;
    }

    /** Work under a request with no result that may throw {@code X}. */
    @FunctionalInterface
    public interface Action<X extends Throwable> {
        void run() throws X;
    }

    /** A request with {@code id} and {@code io} and nothing else. */
    public static Request of(long id, IoLedger io) {
        return new Request(id, io, null, null, Map.of());
    }

    /** A request that is no job, with a ledger of its own. */
    public static Request detached() {
        return of(0, new IoLedger());
    }

    /** The request bound now, or {@code null} off a request. */
    public static @Nullable Request current() {
        return SessionContext.request();
    }

    /** The request bound now, or {@link #detached()} off one: the base a narrower binding copies. */
    public static Request currentOrDetached() {
        Request r = current();
        return r != null ? r : detached();
    }

    /**
     * Run {@code work} with {@code actor} as the actor of every change it records, on whatever
     * thread it reaches: the current request's copy, or a request that is no job off one.
     */
    public static <T extends @Nullable Object, X extends Throwable> T actingAs(ChangeActor actor, Body<T, X> work)
            throws X {
        return currentOrDetached().withActor(actor).call(work);
    }

    /** True for an engine job: its derived facts are {@link RequestScope request-scoped}. */
    public boolean isJob() {
        return id > 0;
    }

    /** {@code slot}'s value, or {@code null} when this request carries none. */
    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(Slot<T> slot) {
        return (T) slots.get(slot);
    }

    /** This request carrying {@code value} under {@code slot}; a {@code null} removes it. */
    public <T> Request with(Slot<T> slot, @Nullable T value) {
        Map<Slot<?>, Object> next = new IdentityHashMap<>(slots);
        if (value == null) next.remove(slot);
        else next.put(slot, value);
        return new Request(id, io, shell, actor, next);
    }

    /** This request under another id: the scope forks register to. */
    public Request withId(long next) {
        return new Request(next, io, shell, actor, slots);
    }

    public Request withShell(@Nullable RequestShell next) {
        return new Request(id, io, next, actor, slots);
    }

    public Request withActor(@Nullable ChangeActor next) {
        return new Request(id, io, shell, next, slots);
    }

    /** Run {@code body} with this request bound, keeping the session bound now. */
    public <T extends @Nullable Object, X extends Throwable> T call(Body<T, X> body) throws X {
        return SessionContext.bind(this, body);
    }

    /** {@link #call} for a body with no result. */
    public <X extends Throwable> void run(Action<X> body) throws X {
        SessionContext.bind(this, () -> {
            body.run();
            return null;
        });
    }

    /** Names only the id and the slot names: a shell and a keychain hold the caller's secrets. */
    @Override
    public String toString() {
        return "Request[" + id + ", slots=" + slots.keySet() + "]";
    }
}
