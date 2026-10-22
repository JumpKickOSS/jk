// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.LockFinishEvent;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A lock that fails before any plan step runs still names its cause, on the wire and in the journal. */
class LockCascadeFailureTest {

    @Test
    void a_member_manifest_error_reaches_the_client_and_the_journal(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "shop"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["web"]
                """);
        Files.createDirectories(ws.resolve("web"));
        Files.writeString(ws.resolve("web").resolve("jk.toml"), """
                group = "com.example"
                name = "web"
                version = "1.0.0"

                [spring-boot]
                aot = false
                """);
        RecordingHost host = new RecordingHost();

        JobOutcome outcome = LockCascade.run(
                host, ws, ws.resolve("cache"), null, List.of(), true, new LockMode.Keep(false), false, null);

        assertThat(outcome).isEqualTo(JobOutcome.failed(Exit.CONFIG));
        assertThat(host.journaled).singleElement().asString().contains("[spring-boot].version is required");
        String finish = host.sent.getLast();
        assertThat(EngineProtocol.typeOf(finish)).isEqualTo(EngineProtocol.LOCK_FINISH);
        assertThat(LockFinishEvent.decode(finish).errors()).isEqualTo(host.journaled);
    }

    private static final class RecordingHost extends InertVerbHost {
        final List<String> sent = new ArrayList<>();
        final List<String> journaled = new ArrayList<>();

        @Override
        public long eventRequestId() {
            return 7L;
        }

        @Override
        public void sendQuiet(@Nullable BufferedWriter w, String line) {
            sent.add(line);
        }

        @Override
        public void accRunErrors(long rid, List<String> errors) {
            journaled.addAll(errors);
        }
    }
}
