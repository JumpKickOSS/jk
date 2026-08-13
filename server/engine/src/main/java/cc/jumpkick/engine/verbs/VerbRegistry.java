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
                new SyncVerb(host),
                new AuditVerb(host),
                new FormatVerb(host),
                new PublishVerb(host),
                new ImageVerb(host),
                new ImportVerb(host),
                new ProvisionVerb(host),
                new TrainVerb(host),
                new CompileVerb(host),
                new InstallVerb(host),
                new GitFetchVerb(host),
                new ScriptPrepareVerb(host),
                new ToolResolveVerb(host),
                new NativeVerb(host),
                new CacheMaintenanceVerb(host),
                new ProjectInfoVerb(host),
                new OutdatedVerb(host),
                new TreeVerb(host),
                new WhyVerb(host),
                new PluginCommandVerb(host),
                new GenerateVerb(host),
                new IdeModelVerb(host),
                new DenyCheckVerb(host),
                new EditVerb(host),
                new FreshenCatalogVerb(host),
                new ExecPlanVerb(host),
                new ForecastVerb(host),
                new ExplainVerb(host),
                new OptimizeVerb(host),
                new CalibrateVerb(host),
                new MetricsVerb(host),
                new HistoryListVerb(host),
                new HistoryShowVerb(host),
                new HistoryDeleteVerb(host)));
    }

    public @Nullable HostedVerb find(String wireType) {
        return byType.get(wireType);
    }

    public List<HostedVerb> all() {
        return List.copyOf(byType.values());
    }
}
