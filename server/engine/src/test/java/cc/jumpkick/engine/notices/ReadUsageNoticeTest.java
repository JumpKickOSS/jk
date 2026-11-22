// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ReadUsage;
import cc.jumpkick.config.TomlValues;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bell says once that jk reads agents' token counts, and not at all once it is turned off. */
class ReadUsageNoticeTest {

    @TempDir
    Path dir;

    @Test
    void the_bell_says_it_once() {
        Notices notices = new Notices();
        Path config = dir.resolve("config.toml");
        assertThat(ReadUsageNotice.announce(notices, config, dir.resolve("state")))
                .isTrue();
        assertThat(ReadUsageNotice.announce(notices, config, dir.resolve("state")))
                .isFalse();
        assertThat(notices.list()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(ReadUsageNotice.KIND);
            assertThat(n.text()).isEqualTo(ReadUsage.NOTICE + "; " + ReadUsage.OFF);
            assertThat(n.href()).isEqualTo("#console");
        });
    }

    @Test
    void nothing_is_said_when_reading_is_off() throws IOException {
        Path config = dir.resolve("config.toml");
        Files.writeString(config, "[agents]\nread-usage = false\n");
        TomlValues.clearCache();
        Notices notices = new Notices();
        assertThat(ReadUsageNotice.announce(notices, config, dir.resolve("state")))
                .isFalse();
        assertThat(notices.list()).isEmpty();
    }
}
