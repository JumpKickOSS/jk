// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.ToolUse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A resolved closure is a use of its tool version, which is what the tools collector ages it by. */
class ToolClosureStampTest {

    @Test
    void stamping_a_closure_records_a_use_the_collector_can_read(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("store"));
        Path version = Files.createDirectories(cas.root().resolve("tools/kotlin-bta/2.4.21"));

        ToolClosure.stamp(cas, "kotlin-bta", "2.4.21");

        ToolUse.ledger(cas.root().resolve("tools")).flush();
        assertThat(ToolUse.ledger(cas.root().resolve("tools")).load()).containsKey(ToolUse.key("kotlin-bta", "2.4.21"));
        assertThat(version).isDirectory();
    }
}
