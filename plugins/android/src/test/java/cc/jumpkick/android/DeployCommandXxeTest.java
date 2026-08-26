// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code deploy} reads the merged {@code AndroidManifest.xml}, and a merged manifest carries every
 * dependency AAR's manifest fragment — third-party content, the same as the resources beside it.
 * Before JK-2421 this was the weakest parser in the tree: secure-processing only, no DOCTYPE ban.
 */
class DeployCommandXxeTest {

    @Test
    void manifest_with_a_system_entity_does_not_read_the_local_file(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP_SECRET_VALUE");

        Path manifest = tmp.resolve("AndroidManifest.xml");
        Files.writeString(manifest, """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE manifest [ <!ENTITY leak SYSTEM "file://%s"> ]>
                <manifest package="com.example">
                  <application>
                    <activity android:name="&leak;">
                      <intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
                    </activity>
                  </application>
                </manifest>
                """.formatted(secret.toAbsolutePath()));

        assertThatThrownBy(() -> DeployCommand.launcherActivity(manifest, "com.example"))
                .as("the DOCTYPE is refused outright, not merely the reference to what it declares")
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("TOP_SECRET_VALUE")
                .isInstanceOf(IOException.class);
    }

    @Test
    void a_plain_manifest_still_resolves_a_relative_activity(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("AndroidManifest.xml");
        Files.writeString(manifest, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest package="com.example">
                  <application>
                    <activity android:name=".MainActivity">
                      <intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
                    </activity>
                  </application>
                </manifest>
                """);

        assertThat(DeployCommand.launcherActivity(manifest, "com.example")).isEqualTo("com.example.MainActivity");
    }
}
