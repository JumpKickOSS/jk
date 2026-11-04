// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import cc.jumpkick.plugin.build.Transfers;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferListener;
import org.eclipse.aether.transfer.TransferResource;
import org.jspecify.annotations.Nullable;

/**
 * Quarkus's resolver downloads (the build-time closure the augment and the test model resolve)
 * reported to the run's download board through {@link Transfers}: a row per body while it
 * streams, a jar counted as an artifact. Every event is passed on to the listener the session had.
 */
final class AetherTransfers implements TransferListener {

    private final @Nullable TransferListener previous;
    private final Map<TransferResource, Transfers.Transfer> open = new ConcurrentHashMap<>();

    AetherTransfers(@Nullable TransferListener previous) {
        this.previous = previous;
    }

    /**
     * Report {@code session}'s downloads, keeping the listener it had. Called only in the JVM that
     * resolves: the worker loads {@link RepositoryRoutes} without Aether on its classpath, so the
     * resolver's types stay out of every class the worker loads.
     */
    static void attach(RepositorySystemSession session) {
        if (!(session instanceof DefaultRepositorySystemSession mutable)) return;
        try {
            mutable.setTransferListener(new AetherTransfers(mutable.getTransferListener()));
        } catch (IllegalStateException readOnly) {
            // a read-only session keeps its own listener: its downloads go undrawn, nothing more
        }
    }

    /** The row label: {@code g/a/v/a-v.jar} → its file name, which says what it is. */
    static String label(TransferResource resource) {
        String name = resource.getResourceName();
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    @Override
    public void transferInitiated(TransferEvent event) throws TransferCancelledException {
        if (previous != null) previous.transferInitiated(event);
    }

    @Override
    public void transferStarted(TransferEvent event) throws TransferCancelledException {
        if (event.getRequestType() != TransferEvent.RequestType.GET) {
            if (previous != null) previous.transferStarted(event);
            return;
        }
        TransferResource resource = event.getResource();
        String label = label(resource);
        Transfers.Transfer t = Transfers.open(label, label.endsWith(".jar"));
        Transfers.Transfer was = open.put(resource, t);
        if (was != null) was.close();
        t.started(resource.getContentLength());
        if (previous != null) previous.transferStarted(event);
    }

    @Override
    public void transferProgressed(TransferEvent event) throws TransferCancelledException {
        Transfers.Transfer t = open.get(event.getResource());
        if (t != null) t.advanced(event.getTransferredBytes());
        if (previous != null) previous.transferProgressed(event);
    }

    @Override
    public void transferCorrupted(TransferEvent event) throws TransferCancelledException {
        Transfers.Transfer t = open.remove(event.getResource());
        if (t != null) t.close();
        if (previous != null) previous.transferCorrupted(event);
    }

    @Override
    public void transferSucceeded(TransferEvent event) {
        Transfers.Transfer t = open.remove(event.getResource());
        if (t != null) {
            t.finished();
            t.close();
        }
        if (previous != null) previous.transferSucceeded(event);
    }

    @Override
    public void transferFailed(TransferEvent event) {
        Transfers.Transfer t = open.remove(event.getResource());
        if (t != null) t.close();
        if (previous != null) previous.transferFailed(event);
    }
}
