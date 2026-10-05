// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.base.CompatPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProvisionProgressEvent;
import cc.jumpkick.wire.protocol.ProvisionRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.Nullable;

public final class ProvisionVerb implements HostedVerb {

    private final VerbHost host;

    public ProvisionVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.PROVISION_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.toolchain("provision");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-provision-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                // `tool` present means an explicit `jk tool install <tool>[:<version>]`; absent
                // means the historical "read this project's wrapper" form.
                ProvisionRequest body = ProvisionRequest.decode(requestLine);
                Path toolsRoot = Path.of(body.toolsRoot());
                ToolProgress progress = new StreamedProgress(writer, cancelToken);
                var outcome = body.tool() != null && !body.tool().isBlank()
                        ? CompatPlans.provisionTool(
                                body.tool(),
                                body.version(),
                                toolsRoot,
                                body.noDiscover(),
                                body.acceptUnverified(),
                                progress)
                        : CompatPlans.provision(
                                Path.of(body.dir()),
                                toolsRoot,
                                body.noDiscover(),
                                body.acceptUnverified(),
                                body.gradle(),
                                progress);
                host.sendQuiet(
                        writer,
                        ProtoEvents.provisionResult(
                                outcome.bin(),
                                outcome.version(),
                                outcome.source(),
                                outcome.verification(),
                                outcome.error(),
                                outcome.exit()));
                return outcome.exit() == Exit.SUCCESS ? JobOutcome.ok() : JobOutcome.failed(outcome.exit());
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }

    /**
     * The install's progress as {@code provision-progress} lines to the client, one per percent
     * (one per MiB when the length is unknown). A cancelled job stops the download at its next chunk.
     */
    private final class StreamedProgress implements ToolProgress {
        private final @Nullable BufferedWriter writer;
        private final Session.CancelToken cancelToken;
        private long lastStep = -1;

        StreamedProgress(@Nullable BufferedWriter writer, Session.CancelToken cancelToken) {
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
}
