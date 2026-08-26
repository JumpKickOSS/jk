// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in plugin worker for {@link PluginWorkerOfflineTest}: a real child JVM that decodes the
 * spec it was forked with using the production {@link PluginSpec} reader and prints the one thing
 * under test.
 *
 * <p>It exists as a separate main rather than an in-process call because the claim is about what a
 * <em>forked</em> worker sees. In-process, the ambient {@code SessionContext} the engine binds is
 * still there and would make the assertion pass for the wrong reason; across a fork it is gone, so
 * only what rode the spec file can be read back.
 */
public final class OfflineEchoWorker {

    private OfflineEchoWorker() {}

    public static void main(String[] args) throws Exception {
        Path spec = Path.of(args[0]);
        // Two facts, because they can disagree and only one of them is the route. `offline` is the
        // decoded answer, which "absent means offline" also produces; `stated` says the spec
        // actually carried a policy line. A test that asserted only the first would stay green with
        // the engine's stamp removed — for an offline job the default happens to give the same
        // value. See code-as-art.md, fake-green mechanism #9.
        System.out.println("offline=" + PluginSpec.read(spec).offline() + " stated="
                + Files.readString(spec).contains("\"" + PluginProtocol.OFFLINE + "\""));
    }
}
