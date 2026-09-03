// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.runtime.CacheInventoryOps;
import cc.jumpkick.host.Errors;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.CacheInventoryAck;
import cc.jumpkick.wire.protocol.CacheInventoryRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class CacheInventoryVerb implements HostedVerb {

    private final VerbHost host;

    public CacheInventoryVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.CACHE_INVENTORY_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("cache-inventory");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-cacheinv-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            CacheInventoryAck ack;
            try {
                CacheInventoryRequest wire = CacheInventoryRequest.decode(requestLine);
                String query = wire.query();
                String cache = wire.cache();
                String store = wire.store();
                CacheInventoryOps.Request req = new CacheInventoryOps.Request(
                        query,
                        cache == null || cache.isBlank() ? null : Path.of(cache),
                        store == null || store.isBlank() ? null : Path.of(store),
                        wire.terms(),
                        wire.coords(),
                        wire.dryRun());
                boolean write = "wipe-store".equals(query) || "repo-refresh".equals(query);
                if (write) {
                    Path lockRoot = req.cache() != null ? req.cache() : JkDirs.cache();
                    CacheInventoryAck[] box = new CacheInventoryAck[1];
                    CacheMaintenanceLocks.exclusively(
                            host.cacheGate(),
                            lockRoot,
                            () -> host.sendQuiet(writer, ProtoSession.pruneWait(host.activePlanCount(), false)),
                            () -> host.sendQuiet(writer, ProtoSession.pruneWait(0, true)),
                            () -> box[0] = CacheInventoryOps.run(req));
                    ack = box[0];
                } else {
                    ack = CacheInventoryOps.run(req);
                }
            } catch (Exception e) {
                ack = CacheInventoryAck.error(Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
