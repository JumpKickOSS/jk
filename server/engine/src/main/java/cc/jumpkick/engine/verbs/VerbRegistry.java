// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Explicit list — adding {@code jk quux} is one class + one line here. */
public final class VerbRegistry {

    private final Map<String, HostedVerb> byType;

    public VerbRegistry(List<HostedVerb> verbs) {
        Map<String, HostedVerb> map = new LinkedHashMap<>();
        for (HostedVerb v : verbs) {
            HostedVerb prev = map.put(v.wireType(), v);
            if (prev != null) {
                throw new IllegalArgumentException("duplicate verb " + v.wireType());
            }
        }
        this.byType = Map.copyOf(map);
    }

    public static VerbRegistry standard(VerbHost host) {
        return new VerbRegistry(List.of(
                new WorkspaceBuildVerb(host),
                new TestVerb(host),
                new SingleBuildVerb(host),
                new LockVerb(host),
                new UpdateVerb(host),
                new SyncVerb(host)));
    }

    public @Nullable HostedVerb find(String wireType) {
        return byType.get(wireType);
    }

    public List<HostedVerb> all() {
        return List.copyOf(byType.values());
    }
}
