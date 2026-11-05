// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.JobFinishFrame;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A job settles beside a {@code ~/.jk/config.toml} that does not parse: a success says so once, on
 * its {@code job-finish} line and as a dashboard {@code warn}; a failure, which already names what
 * broke, adds nothing.
 */
class JobSettlementConfigWarningTest {

    @Test
    void a_succeeded_job_beside_a_config_toml_that_does_not_parse_says_so_once_on_its_finish_line(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", dir.toString(), null, "cli");
        host.userConfigWarning = "/home/u/.jk/config.toml:3:1 does not parse; this command read nothing from it";
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env,
                "{\"type\":\"build-request\",\"dir\":" + Jsonl.quote(dir.toString()) + "}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.ok()),
                out);

        List<String> finishes =
                out.toString().lines().filter(l -> l.contains("\"job-finish\"")).toList();
        assertThat(finishes)
                .singleElement()
                .satisfies(l -> assertThat(JobFinishFrame.decode(l).warning()).isEqualTo(host.userConfigWarning));
        assertThat(host.events)
                .filteredOn(e -> e.startsWith("warn:"))
                .singleElement()
                .asString()
                .contains(JobSettlement.USER_CONFIG_UNPARSED, "config.toml:3:1 does not parse");
    }

    @Test
    void a_failed_job_beside_a_config_toml_that_does_not_parse_adds_no_warning(@TempDir Path dir) {
        FakeEnvelopeHost host = new FakeEnvelopeHost();
        host.accumulator = new BuildAccumulator("build", dir.toString(), null, "cli");
        host.userConfigWarning = "/home/u/.jk/config.toml:3:1 does not parse; this command read nothing from it";
        JobEnvelope env = new JobEnvelope(host, JobLimits.DEFAULTS);
        StringWriter out = new StringWriter();
        ConnectedClient.submit(
                env,
                "{\"type\":\"build-request\",\"dir\":" + Jsonl.quote(dir.toString()) + "}",
                JobRequest.plan("build", "jk-test-", (line, tok, w) -> JobOutcome.failed(1)),
                out);

        assertThat(out.toString().lines().filter(l -> l.contains("\"job-finish\"")))
                .singleElement()
                .satisfies(l -> assertThat(JobFinishFrame.decode(l).warning()).isNull());
        assertThat(host.events).noneMatch(e -> e.startsWith("warn:"));
    }
}
