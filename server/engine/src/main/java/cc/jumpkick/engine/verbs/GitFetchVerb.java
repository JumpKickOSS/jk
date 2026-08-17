// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.jsonl.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Optional;

public final class GitFetchVerb implements HostedVerb {

    private final VerbHost host;

    public GitFetchVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GIT_FETCH_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("git-fetch");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-gitfetch-";
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                boolean refresh = Jsonl.bool(requestLine, "refresh", false);
                JkConfig config = new JkConfig(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(refresh),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.InstallPlans.gitFetchBuildPlan(
                        Jsonl.str(requestLine, "url"),
                        Jsonl.str(requestLine, "canonicalUrl"),
                        Jsonl.str(requestLine, "ref"),
                        cache,
                        refresh,
                        Jsonl.bool(requestLine, "requireJkToml", true));
                host.streamSinglePlan(plan, session, writer, result -> {
                    Path checkout =
                            plan.get(cc.jumpkick.runtime.InstallPlans.CHECKOUT).orElse(null);
                    String sha = plan.get(cc.jumpkick.runtime.InstallPlans.FETCHED_SHA)
                            .orElse(null);
                    return ProtoEvents.planFinishGitFetch(
                            dir, result.success(), checkout != null ? checkout.toString() : null, sha);
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
