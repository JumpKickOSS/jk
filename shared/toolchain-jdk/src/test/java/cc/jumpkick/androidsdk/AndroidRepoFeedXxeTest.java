// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Google's SDK feed is fetched over the network before any checksum exists to compare it against —
 * whatever answers {@code dl.google.com} is parsed. A DOCTYPE is rejected rather than resolved, so a
 * substituted feed cannot smuggle a local file into an archive URL or a license id.
 */
class AndroidRepoFeedXxeTest {

    @Test
    void feed_with_a_system_entity_does_not_read_the_local_file(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE");

        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE sdk-repository [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <sdk-repository>
                  <license id="android-sdk-license">&leak;</license>
                </sdk-repository>
                """.formatted(secret.toAbsolutePath());

        assertThatThrownBy(() -> AndroidRepoFeed.parse(xml.getBytes(StandardCharsets.UTF_8)))
                .as("the DOCTYPE is refused outright, not merely the reference to what it declares")
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .isInstanceOf(IOException.class);
    }

    @Test
    void a_plain_feed_still_parses() throws Exception {
        AndroidRepoFeed feed = AndroidRepoFeed.parse("""
                <?xml version="1.0" encoding="utf-8"?>
                <sdk-repository>
                  <license id="android-sdk-license">Terms</license>
                  <remotePackage path="platform-tools">
                    <uses-license ref="android-sdk-license"/>
                    <channelRef ref="channel-0"/>
                    <revision><major>35</major><minor>0</minor><micro>2</micro></revision>
                    <archives>
                      <archive>
                        <complete>
                          <size>12</size>
                          <checksum>abc123</checksum>
                          <url>platform-tools_r35.0.2-linux.zip</url>
                        </complete>
                        <host-os>linux</host-os>
                      </archive>
                    </archives>
                  </remotePackage>
                </sdk-repository>
                """.getBytes(StandardCharsets.UTF_8));

        AndroidRepoFeed.Component tools = requireNonNull(feed.find("platform-tools"));
        assertThat(tools.revision()).isEqualTo("35.0.2");
        assertThat(tools.licenseId()).isEqualTo("android-sdk-license");
        assertThat(requireNonNull(tools.archiveFor("linux")).url()).isEqualTo("platform-tools_r35.0.2-linux.zip");
        assertThat(feed.licenseText("android-sdk-license")).isEqualTo("Terms");
    }
}
