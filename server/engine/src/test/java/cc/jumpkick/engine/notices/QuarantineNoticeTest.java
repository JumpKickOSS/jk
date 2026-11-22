// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.TestFlakes;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bell rings once for each run that quarantines test classes, linking to the Flaky tests tab. */
class QuarantineNoticeTest {

    @Test
    void a_quarantine_adds_one_notice_naming_the_class_and_the_project(@TempDir Path dir) {
        Path shop = dir.resolve("shop");
        Notices notices = new Notices();

        QuarantineNotice.on(notices).accept(new TestFlakes.Quarantined(shop, "orders", List.of("com.acme.OrdersTest")));

        assertThat(notices.list()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(QuarantineNotice.KIND);
            assertThat(n.text()).isEqualTo("JumpKick quarantined flaky test OrdersTest in shop");
            assertThat(n.decision()).isNull();
        });
    }

    @Test
    void several_classes_are_counted_in_one_notice() {
        var q = new TestFlakes.Quarantined(Path.of("/ws/shop"), ".", List.of("a.OneTest", "b.TwoTest"));
        assertThat(QuarantineNotice.text(q)).isEqualTo("JumpKick quarantined 2 flaky tests in shop");
    }

    @Test
    void it_links_to_the_checkouts_flaky_tests_tab_or_the_projects_without_an_id() {
        assertThat(QuarantineNotice.href(Path.of("ws", "my shop"), "ab12"))
                .startsWith("#project/ab12/flaky?dir=ws%")
                .endsWith("my%20shop");
        assertThat(QuarantineNotice.href(Path.of("/ws/shop"), null)).isEqualTo("#projects");
    }
}
