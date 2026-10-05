// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.config.Session;
import cc.jumpkick.wire.protocol.ProvisionProgressEvent;
import java.io.BufferedWriter;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;

/**
 * A tool install's progress as {@code provision-progress} lines to the client, one per percent (one
 * per MiB when the length is unknown), which the client draws as the JDK download bar. A cancelled
 * job stops the download at its next chunk.
 */
final class StreamedToolProgress implements ToolProgress {

    private final VerbHost host;
    private final @Nullable BufferedWriter writer;
    private final Session.CancelToken cancelToken;
    private long lastStep = -1;

    StreamedToolProgress(VerbHost host, @Nullable BufferedWriter writer, Session.CancelToken cancelToken) {
        this.host = host;
        this.writer = writer;
        this.cancelToken = cancelToken;
    }

    @Override
    public void downloading(String name, long readBytes, long totalBytes) {
        if (cancelToken.cancelled()) throw new CancellationException(name + " download cancelled");
        long step = totalBytes > 0 ? readBytes * 100 / totalBytes : readBytes >> 20;
        if (step == lastStep && readBytes != 0) return;
        lastStep = step;
        host.sendQuiet(
                writer,
                new ProvisionProgressEvent(ProvisionProgressEvent.DOWNLOAD, name, readBytes, totalBytes).encode());
    }

    @Override
    public void installing(String name) {
        host.sendQuiet(writer, new ProvisionProgressEvent(ProvisionProgressEvent.INSTALL, name, 0, 0).encode());
    }
}
