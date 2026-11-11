// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A response body read into memory, at most {@code limit} bytes of it: past that the transfer is
 * cancelled and the body fails with {@link BodyTooLargeException}. A gzip-encoded body is inflated
 * on arrival and the limit bounds the inflated bytes too.
 */
final class CappedBody implements BodySubscriber<byte[]> {

    private final URI uri;
    private final long limit;
    private final boolean gzipped;
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final List<ByteBuffer> received = new ArrayList<>();
    private long size;
    private Flow.@Nullable Subscription subscription;

    CappedBody(URI uri, long limit, boolean gzipped) {
        this.uri = uri;
        this.limit = limit;
        this.gzipped = gzipped;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        if (result.isDone()) return;
        for (ByteBuffer item : items) size += item.remaining();
        if (size > limit) {
            Flow.Subscription s = subscription;
            if (s != null) s.cancel();
            received.clear();
            result.completeExceptionally(new BodyTooLargeException(uri, limit));
            return;
        }
        received.addAll(items);
    }

    @Override
    public void onError(Throwable failure) {
        received.clear();
        result.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
        if (result.isDone()) return;
        byte[] body = new byte[(int) size];
        int at = 0;
        for (ByteBuffer item : received) {
            int n = item.remaining();
            item.get(body, at, n);
            at += n;
        }
        received.clear();
        if (!gzipped) {
            result.complete(body);
            return;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] inflated = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, limit) + 1);
            if (inflated.length > limit) result.completeExceptionally(new BodyTooLargeException(uri, limit));
            else result.complete(inflated);
        } catch (IOException e) {
            result.completeExceptionally(e);
        }
    }
}
