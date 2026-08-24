// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.CacheInventoryAck;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.engine.runtime.CacheInventoryOps;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.util.JkDirs;
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
                String query = Jsonl.str(requestLine, "query");
                String cache = Jsonl.str(requestLine, "cache");
                String store = Jsonl.str(requestLine, "store");
                CacheInventoryOps.Request req = new CacheInventoryOps.Request(
                        query,
                        cache == null || cache.isBlank() ? null : Path.of(cache),
                        store == null || store.isBlank() ? null : Path.of(store),
                        Jsonl.strArray(requestLine, "terms"),
                        Jsonl.strArray(requestLine, "coords"),
                        Jsonl.bool(requestLine, "dryRun", false));
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
                ack = CacheInventoryAck.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
