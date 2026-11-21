// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Errors;
import cc.jumpkick.http.ConnectFaults;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.HttpStatusException;
import cc.jumpkick.http.SafeUri;
import cc.jumpkick.repo.MavenRepo.CredentialNotRefreshedException;
import cc.jumpkick.repo.MavenRepo.Mirror;
import cc.jumpkick.repo.MavenRepo.RepositoryUnreachableException;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Whether one {@link MavenRepo} is still asked in this job, and the failure a request to it throws
 * once it is not. The job is the repository object's life: a resolve builds its repositories
 * afresh, so the next one asks again. An address that refuses connections is also remembered
 * process-wide by {@link ConnectFaults}, so a fresh repository over the same URL is refused before
 * it dials as well.
 */
final class RepoReachability {

    /**
     * What put a repository out of the job, the failure it was read from (null for a refusal no
     * request met), and whether the resolve goes on without it.
     */
    private record OutOfJob(String fault, @Nullable Throwable met, boolean passedOver) {}

    private final String name;
    private final URI baseUrl;
    private final @Nullable Mirror mirror;

    /** {@link MavenRepo#passable()}: fixed for the repository's life. */
    private final boolean passable;

    /**
     * The fault that put the repository out of the job, once a request met one — nothing answers
     * at its address, a request got no answer within the timeout, or a passable one refuses access
     * — so it is asked nothing more; else null.
     */
    private final AtomicReference<@Nullable OutOfJob> unreachable = new AtomicReference<>();

    /** Why every request is refused before it is sent; null when requests go out. */
    private volatile @Nullable String refusal;

    /** A mirror jk will not use puts the repository out before any request: it has no other route. */
    RepoReachability(String name, URI baseUrl, @Nullable Mirror mirror, boolean passable) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.mirror = mirror;
        this.passable = passable;
        if (mirror != null && mirror.refusal() != null) {
            unreachable.set(new OutOfJob("JumpKick refuses it: " + mirror.refusal(), null, false));
        }
    }

    /**
     * The gate every request passes under its host's permit: refused once the repository is out of
     * the job, and its silence or a refused address puts the repository out before the permit goes
     * to a request queued behind it.
     */
    RequestGate gate() {
        return new RequestGate(this::refuseIfUnreachable, failure -> {
            if (outOfJobSilence(failure) != null || addressRefusal(failure) != null) unreachableOr(failure);
        });
    }

    /** Refuse every request with {@code why} instead of sending it. */
    void refuseRequests(String why) {
        this.refusal = Objects.requireNonNull(why, "why");
    }

    /** True once a request put the repository out of the job, or its mirror is refused. */
    boolean out() {
        return unreachable.get() != null;
    }

    /** The fault that put the repository out of the rest of the job, or null. */
    @Nullable
    String fault() {
        return unreachable.get() instanceof OutOfJob out ? out.fault() : null;
    }

    /** {@link MavenRepo#passedOver()}. */
    boolean passedOver() {
        return unreachable.get() instanceof OutOfJob out && out.passedOver();
    }

    /**
     * The fault an earlier request met, thrown again without a request, once the repository is
     * unreachable; or the reason it refuses every request. The failure carries the one that request
     * met: a download's body refused because a checksum asked beside it met the silence first reads
     * as the request that met it does, and not as another event.
     */
    void refuseIfUnreachable() throws IOException {
        String why = refusal;
        if (why != null) {
            throw new IOException(
                    "repository " + name + " at " + SafeUri.forMessage(baseUrl) + " is not asked: " + why);
        }
        if (unreachable.get() instanceof OutOfJob out) throw unreachableFailure(out.fault(), out.met());
    }

    /** The failure for {@code fault}: naming the mirror when one stands in for the repository. */
    private RepositoryUnreachableException unreachableFailure(String fault, @Nullable Throwable cause) {
        Mirror through = mirror;
        if (through == null) return new RepositoryUnreachableException(name, baseUrl, fault, cause);
        return RepositoryUnreachableException.throughMirror(name, baseUrl, through, fault, cause);
    }

    /**
     * {@code transport} as the repository's unreachable failure when its cause is connect-level,
     * when it waited out the request timeout, or — for a passable one, or one a mirror stands in
     * for — when it got no HTTP answer at all (reset, dropped) or an answer refusing access (401,
     * 403), which Maven passes over as well: remembered, so the repository is asked nothing more.
     * Else {@code transport} itself. A request that meets such a fault after another put the
     * repository out gets that one's failure: a body and the checksums asked beside it can meet one
     * dropped connection as a reset and as an empty response, and the repository is out once, for
     * the first.
     */
    IOException unreachableOr(IOException transport) {
        boolean answersMatter = passable || mirror != null;
        String fault = ConnectFaults.describe(transport);
        String silent = fault == null ? outOfJobSilence(transport) : null;
        if (silent != null) fault = silent;
        if (fault == null) fault = addressRefusal(transport);
        if (fault == null && answersMatter) fault = refusal(transport);
        if (fault == null) return transport;
        String unrefreshed = notRefreshed(transport);
        if (unrefreshed != null) fault = fault + "; " + unrefreshed;
        OutOfJob first = unreachable.compareAndExchange(
                null, new OutOfJob(fault, transport, mirror == null && (silent != null || passable)));
        if (first == null || first.met() == null) return unreachableFailure(fault, transport);
        return unreachableFailure(first.fault(), first.met());
    }

    /**
     * The silence in {@code failure} that puts the repository out of the job, as one line, or null.
     * A timeout does for any repository: asked again, it would only wait as long, and a GitLab
     * registry asked for Central's artifacts would cost the timeout per coordinate. A reset or a
     * dropped request does only for a passable or mirrored one; elsewhere it is that request's alone.
     */
    private @Nullable String outOfJobSilence(Throwable failure) {
        if (ConnectFaults.describe(failure) != null) return null;
        if (!RequestGate.timedOut(failure) && !passable && mirror == null) return null;
        return RequestGate.silence(failure);
    }

    /**
     * Why a refused credential was not replaced, from {@code failure}'s cause chain, or null: the
     * credential helper's own explanation, which the fault alone ({@code HTTP 401}) does not give.
     */
    private static @Nullable String notRefreshed(Throwable failure) {
        for (Throwable t : Errors.chain(failure)) {
            if (t instanceof CredentialNotRefreshedException helper) return helper.why();
        }
        return null;
    }

    /** The address refusal in {@code failure}'s cause chain as one line naming it, or null. */
    private static @Nullable String addressRefusal(Throwable failure) {
        Http.AddressRefusedException refused = Errors.inChain(failure, Http.AddressRefusedException.class);
        return refused == null ? null : "JumpKick refuses it: " + refused.reason();
    }

    /** The 401 or 403 in {@code failure}'s cause chain as one line naming it, or null. */
    static @Nullable String refusal(Throwable failure) {
        for (Throwable t : Errors.chain(failure)) {
            if (t instanceof HttpStatusException answer && answer.refusesAccess()) {
                return "HTTP " + answer.status()
                        + (answer.status() == 401 ? ": it asks for credentials" : ": access refused");
            }
        }
        return null;
    }
}
