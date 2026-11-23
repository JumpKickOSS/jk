// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A discovery fork's list holds the classes it named, and a suite runs off it only once {@code
 * discovery_total} closed it and the parent's decoder read it to the end.
 */
class DiscoveryListingTest {

    @Test
    void the_list_holds_the_named_classes_and_closes_on_its_total() {
        List<String> said = new ArrayList<>();
        var listener = new TestProgressListener() {
            @Override
            public void onDiscoveryTotal(int classes, int tests) {
                said.add("total " + classes + "/" + tests);
            }

            @Override
            public void onWarning(String code, String message) {
                said.add(code + ": " + message);
            }
        };
        var listing = new DiscoveryListing();
        Consumer<String> handler = listing.handler(listener);
        handler.accept("{\"event\":\"discovered\",\"class\":\"com.acme.ATest\"}");
        handler.accept("{\"event\":\"warning\",\"code\":\"tag-dropped\",\"message\":\"BTest\"}");
        handler.accept("{\"event\":\"started\",\"uniqueId\":\"x\"}");
        assertThat(listing.complete()).isFalse();
        handler.accept("{\"event\":\"discovered\",\"class\":\"com.acme.CTest\"}");
        handler.accept("{\"event\":\"discovery_total\",\"classes\":2,\"tests\":7}");

        assertThat(listing.classes()).containsExactly("com.acme.ATest", "com.acme.CTest");
        assertThat(listing.complete()).isTrue();
        assertThat(said).containsExactly("tag-dropped: BTest", "total 2/7");
    }

    @Test
    void a_suite_runs_off_a_list_only_when_it_closed_and_was_read_to_the_end() {
        assertThat(DiscoveryListing.runnable(true, false)).isTrue();
        assertThat(DiscoveryListing.runnable(false, false))
                .as("cut short before its total")
                .isFalse();
        assertThat(DiscoveryListing.runnable(true, true))
                .as("the parent's decoder ended it")
                .isFalse();
    }
}
