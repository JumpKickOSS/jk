// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineHeapDump;
import cc.jumpkick.command.system.DoctorCommand.Check;
import cc.jumpkick.command.system.DoctorCommand.Status;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A present engine heap dump is a doctor finding on the engine row. */
class DoctorHeapDumpFindingTest {

    @Test
    void a_heap_dump_turns_a_healthy_engine_row_into_a_warning_that_names_it() {
        Path dump = Path.of("/home/u/.jk/state/engine/0123456789abcdef.hprof");
        Check ok = new Check(Status.OK, "engine", "running pid 42");

        Check found = DoctorCommand.withHeapDump(ok, Optional.of(dump));

        assertThat(found.status()).isEqualTo(Status.WARN);
        assertThat(found.detail())
                .startsWith("running pid 42 · ")
                .contains("exited on OutOfMemoryError; heap dump at " + dump)
                .endsWith(EngineHeapDump.REMEDY);
    }

    @Test
    void a_failing_engine_row_stays_a_failure_and_no_dump_leaves_the_row_alone() {
        Check fail = new Check(Status.FAIL, "engine", "wedged");
        assertThat(DoctorCommand.withHeapDump(fail, Optional.of(Path.of("/x.hprof")))
                        .status())
                .isEqualTo(Status.FAIL);

        Check ok = new Check(Status.OK, "engine", "running");
        assertThat(DoctorCommand.withHeapDump(ok, Optional.empty())).isSameAs(ok);
    }
}
