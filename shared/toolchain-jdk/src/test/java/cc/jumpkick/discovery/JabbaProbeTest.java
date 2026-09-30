// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jdk.JdkHit;
import cc.jumpkick.testing.FakeJdk;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JabbaProbeTest {

    @Test
    void reads_the_default_jabba_home_when_jabba_home_is_unset(@TempDir Path tmp) throws IOException {
        Path home = FakeJdk.create(tmp.resolve(".jabba/jdk/zulu@1.21.0"), "21.0.5");

        List<JdkHit> hits = new JabbaProbe(name -> null, tmp.toString()).discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(home.toRealPath());
        assertThat(hits).allSatisfy(h -> assertThat(h.source()).isEqualTo("jabba"));
    }

    @Test
    void jabba_home_replaces_the_default(@TempDir Path tmp) throws IOException {
        Path home = FakeJdk.create(tmp.resolve("custom/jdk/temurin@1.25.0"), "25.0.1");
        FakeJdk.create(tmp.resolve("user/.jabba/jdk/zulu@1.21.0"), "21.0.5");
        Map<String, String> env = Map.of("JABBA_HOME", tmp.resolve("custom").toString());

        List<JdkHit> hits = new JabbaProbe(env::get, tmp.resolve("user").toString()).discoverAllJdks();

        assertThat(hits).extracting(JdkHit::home).containsExactly(home.toRealPath());
    }

    @Test
    void a_missing_jabba_home_is_empty(@TempDir Path tmp) throws IOException {
        assertThat(new JabbaProbe(name -> null, tmp.toString()).discoverAllJdks())
                .isEmpty();
    }
}
