// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.GitFetchRequest;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.InstallPlans;
import java.io.BufferedWriter;
import java.nio.file.Path;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                GitFetchRequest body = GitFetchRequest.decode(requestLine);
                Path cache = Path.of(body.cache());
                JkConfig config = JkConfig.empty().withForce(body.refresh());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                BuildPlan plan = InstallPlans.gitFetchBuildPlan(
                        body.url(), body.canonicalUrl(), body.ref(), cache, body.refresh(), body.requireJkToml());
                return host.streamSinglePlan(plan, session, writer, result -> {
                    Path checkout = plan.get(InstallPlans.CHECKOUT).orElse(null);
                    String sha = plan.get(InstallPlans.FETCHED_SHA).orElse(null);
                    return ProtoEvents.planFinishGitFetch(
                            dir, result.success(), checkout != null ? checkout.toString() : null, sha);
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
