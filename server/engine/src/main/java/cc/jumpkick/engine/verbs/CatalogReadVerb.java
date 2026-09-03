// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.runtime.CatalogReadOps;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.CatalogReadAck;
import cc.jumpkick.wire.protocol.CatalogReadRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class CatalogReadVerb implements HostedVerb {

    private final VerbHost host;

    public CatalogReadVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.CATALOG_READ_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("catalog-read");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-catalog-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            CatalogReadAck ack;
            try {
                CatalogReadRequest req = CatalogReadRequest.decode(requestLine);
                String dir = req.dir();
                String cache = req.cache();
                if (dir == null || dir.isBlank()) {
                    // A resident server has no meaningful cwd to fall back to.
                    throw new IllegalArgumentException("catalog-read request names no dir");
                }
                ack = CatalogReadOps.read(new CatalogReadOps.Request(
                        Path.of(dir),
                        cache == null || cache.isBlank() ? null : Path.of(cache),
                        null,
                        req.query(),
                        req.terms(),
                        req.offline(),
                        req.includeCached(),
                        req.bundledOnly()));
            } catch (Exception e) {
                ack = CatalogReadAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
