// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.AffectedChanged;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AffectedChangedPublishTest {

    @Test
    void classifies_only_this_modules_dirty_main_sources() {
        AffectedChanged carrier = new AffectedChanged();
        var prev = new ClassAbi.Fingerprint("api1", "b1");
        var now = new ClassAbi.Fingerprint("api2", "b2");
        AffectedChangedPublish.classifyInto(
                carrier,
                Path.of("/ws"),
                Path.of("/ws/a"),
                List.of(
                        "a/src/main/java/com/acme/a/Foo.java", // mine: ABI (api hash moved)
                        "a/src/test/java/com/acme/a/FooTest.java", // test source: not production
                        "b/src/main/java/com/acme/b/Other.java", // another module's file
                        "a/README.md"), // not a class source
                Map.of("com.acme.a.Foo", prev),
                Map.of("com.acme.a.Foo", now));
        assertThat(carrier.snapshot()).containsExactlyEntriesOf(Map.of("com.acme.a.Foo", "ABI"));
    }

    @Test
    void abi_wins_when_two_modules_vote_on_one_type() {
        AffectedChanged carrier = new AffectedChanged();
        carrier.put("com.acme.Foo", "BODY");
        carrier.put("com.acme.Foo", "ABI");
        carrier.put("com.acme.Foo", "BODY");
        assertThat(carrier.snapshot()).containsEntry("com.acme.Foo", "ABI");
    }

    @Test
    void foreign_for_excludes_local_production_types() {
        AffectedChanged carrier = new AffectedChanged();
        carrier.put("com.acme.a.Foo", "ABI");
        carrier.put("com.acme.b.Bar", "BODY");
        Map<String, ClassAbi.Kind> foreign = AffectedChangedPublish.foreignFor(carrier, Set.of("com.acme.b.Bar"));
        assertThat(foreign).containsExactlyEntriesOf(Map.of("com.acme.a.Foo", ClassAbi.Kind.ABI));
    }
}
