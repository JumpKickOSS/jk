// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.androidsdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two extractions of one component can race; the second keeps what the first published. */
class AndroidSdkInstallerPublishTest {

    @Test
    void the_first_extraction_lands_and_the_second_accepts_it(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("platforms/android-36");
        Files.createDirectories(target.getParent());
        Path first = Files.createDirectories(tmp.resolve(".extract-1/android-36"));
        Files.writeString(first.resolve("android.jar"), "first");
        AndroidSdkInstaller.publishOrAccept(first, target);
        assertThat(target.resolve("android.jar")).hasContent("first");

        Path second = Files.createDirectories(tmp.resolve(".extract-2/android-36"));
        Files.writeString(second.resolve("android.jar"), "second");
        AndroidSdkInstaller.publishOrAccept(second, target);
        assertThat(target.resolve("android.jar"))
                .as("the published component stands")
                .hasContent("first");
        assertThat(second)
                .as("the loser's staging is left for its caller to delete")
                .exists();
    }
}
