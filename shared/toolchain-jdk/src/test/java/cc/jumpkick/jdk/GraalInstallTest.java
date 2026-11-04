// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.GraalLauncher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraalInstallTest {

    /** A home with no launcher is refused naming it, rather than handed to a native step that would fall through. */
    @Test
    void a_home_that_holds_no_launcher_is_refused_naming_it(@TempDir Path dir) throws IOException {
        Path home = Files.createDirectories(dir.resolve("graalvm-25"));

        assertThatThrownBy(() -> GraalInstall.requireNativeImage(home))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(home.toString())
                .hasMessageContaining(GraalLauncher.NAME);

        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve(GraalLauncher.NAME), "#!/fake");

        assertThatCode(() -> GraalInstall.requireNativeImage(home)).doesNotThrowAnyException();
    }
}
