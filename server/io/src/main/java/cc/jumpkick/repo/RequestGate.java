// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.HostRateLimiter;
import cc.jumpkick.http.Http;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What a repository's request passes once it holds its host's permit: {@code admit} may refuse it,
 * and {@code failed} hears how it failed before the permit is released, so a request queued for the
 * permit already knows what the one ahead of it learned.
 */
record RequestGate(Admission admit, Consumer<IOException> failed) {

    /** Admits every request and notes nothing. */
    static final RequestGate OPEN = new RequestGate(() -> {}, failure -> {});

    /** Throws to refuse the request. */
    @FunctionalInterface
    interface Admission {
        void check() throws IOException;
    }

    /** {@code request}, sent through this gate. */
    <T> T send(HostRateLimiter.ThrowingSupplier<T, IOException> request) throws IOException, InterruptedException {
        admit.check();
        try {
            return request.get();
        } catch (IOException failure) {
            failed.accept(failure);
            throw failure;
        }
    }

    /** {@code transport} with every read sent through this gate; uploads pass as they are. */
    RepoTransport over(RepoTransport transport) {
        return new RepoTransport() {
            @Override
            public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
                return send(() -> transport.fetch(uri, credential));
            }

            @Override
            public Optional<InputStream> fetchStream(URI uri, RepoCredential credential)
                    throws IOException, InterruptedException {
                return send(() -> transport.fetchStream(uri, credential));
            }

            @Override
            public Optional<Body> fetchBody(URI uri, RepoCredential credential)
                    throws IOException, InterruptedException {
                return send(() -> transport.fetchBody(uri, credential));
            }

            @Override
            public int put(URI uri, byte[] body, String contentType, RepoCredential credential)
                    throws IOException, InterruptedException {
                return transport.put(uri, body, contentType, credential);
            }
        };
    }

    /** True when {@code failure}'s cause chain holds a request that waited out its timeout. */
    static boolean timedOut(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    /** The no-answer failure in {@code failure}'s cause chain as one line naming what it met, or null. */
    static @Nullable String silence(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            Throwable met = t;
            if (t instanceof Http.NoAnswerException && t.getCause() != null) {
                met = t.getCause();
            } else if (!(t instanceof Http.NoAnswerException
                    || t instanceof HttpTimeoutException
                    || t instanceof SocketTimeoutException)) {
                continue;
            }
            String detail = met.getMessage();
            return met.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
        }
        return null;
    }
}
