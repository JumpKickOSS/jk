// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The cancel the engine raises on its own, its wall deadline, against a live engine on a real socket
 * ({@link CancellationHarness}). The test waits the deadline out on the wall, so it runs with the
 * slow suite.
 */
@Tag("slow")
class CancellationDeadlineTest extends CancellationHarness {

    @Test
    void a_wall_deadline_is_a_third_reason_and_names_itself_on_the_wire() throws Exception {
        // The deadline is measured from job start, and the point of the test is a job cut off while
        // parked on the held download. Fifteen seconds is past the time a cold lock takes to reach
        // that download on a loaded machine, and short of the latch's own 30 s release, so the kill
        // lands where the test says it does rather than in an earlier metadata fetch. The fast tier
        // drives the deadline's cancel with a 50 ms deadline (JobEnvelopeTest) and its error line's
        // code (JobWatchdogConfigTest).
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS.withJobLimits(new JobLimits(
                0L,
                15_000L,
                JobLimits.DEFAULT_DETACHED_DEADLINE_MS,
                200L,
                500L,
                0L,
                JobLimits.DEFAULT_DRAIN_DEADLINE_MS)));
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            // The deadline's error line and the cancelled terminal race each other onto the wire:
            // the watchdog writes the error after interrupting the runner, whose unwinding may
            // already have settled the plan. Both must be there, and job-finish must be last.
            List<String> rest = new ArrayList<>();
            String line;
            while ((line = readOne(building, rest)) != null) {
                rest.add(line);
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.ERROR.equals(type) || EngineProtocol.BUILDPLAN_FINISH.equals(type)) break;
            }
            unpark();
            rest.addAll(readToEof(building));
            List<String> types = rest.stream().map(EngineProtocol::typeOf).toList();
            assertThat(types)
                    .as(String.join("\n", rest))
                    .contains(EngineProtocol.ERROR, EngineProtocol.BUILDPLAN_FINISH);
            assertThat(types.getLast()).isEqualTo(EngineProtocol.JOB_FINISH);
            String error = rest.stream()
                    .filter(l -> EngineProtocol.ERROR.equals(EngineProtocol.typeOf(l)))
                    .findFirst()
                    .orElseThrow();
            assertThat(Jsonl.str(error, "code")).isEqualTo(EngineProtocol.ERR_DEADLINE);
            String terminal = rest.stream()
                    .filter(l -> EngineProtocol.BUILDPLAN_FINISH.equals(EngineProtocol.typeOf(l)))
                    .reduce((a, b) -> b)
                    .orElseThrow();
            assertThat(Jsonl.bool(terminal, "cancelled", false))
                    .as("wire:\n" + String.join("\n", rest) + "\nengine log:\n" + String.join("\n", engineLog))
                    .isTrue();
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(finish, "cancelReason"))
                .isEqualTo("exceeded the 15000ms wall deadline (JK_ENGINE_JOB_DEADLINE_MS); cancelled")
                .isNotEqualTo(BY_USER)
                .isNotEqualTo(BY_DISCONNECT);
    }
}
