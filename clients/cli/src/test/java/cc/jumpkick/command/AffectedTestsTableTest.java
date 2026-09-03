// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.wire.protocol.AffectedTestsReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class AffectedTestsTableTest {

    @Test
    void table_lists_score_class_reason_not_modules() {
        List<String> lines = TestCommand.renderAffectedTable(List.of(
                new AffectedTestsReport.Row(100, "com.acme.FooTest", "name-body:com.acme.Foo"),
                new AffectedTestsReport.Row(90, "com.acme.BarTest", "abi-import:com.acme.Foo")));
        String plain = TestAnsi.strip(String.join("\n", lines));
        assertThat(plain).contains("Affected tests");
        assertThat(plain).contains("Score");
        assertThat(plain).contains("Class");
        assertThat(plain).contains("Reason");
        assertThat(plain).contains("com.acme.FooTest");
        assertThat(plain).contains("100");
        assertThat(plain).doesNotContain("testing modules");
    }
}
