// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A gzip-encoded response body inflated on the caller's first read, not when the response arrives:
 * reading the gzip header blocks on the server, so it happens on the reading thread and through
 * whatever bounds that thread's reads. A body with no bytes at all (a 304 or a 404 a server still
 * labels gzip) reads as empty rather than failing for want of a header.
 */
final class InflatingBody extends InputStream {

    private final InputStream raw;
    private @Nullable InputStream inflated;

    InflatingBody(InputStream raw) {
        this.raw = raw;
    }

    private InputStream inflated() throws IOException {
        InputStream open = inflated;
        if (open != null) return open;
        PushbackInputStream ahead = new PushbackInputStream(raw, 1);
        int first = ahead.read();
        if (first < 0) {
            open = InputStream.nullInputStream();
        } else {
            ahead.unread(first);
            open = new GZIPInputStream(ahead);
        }
        inflated = open;
        return open;
    }

    @Override
    public int read() throws IOException {
        return inflated().read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return inflated().read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        InputStream open = inflated;
        try {
            if (open != null) open.close();
        } finally {
            raw.close();
        }
    }
}
