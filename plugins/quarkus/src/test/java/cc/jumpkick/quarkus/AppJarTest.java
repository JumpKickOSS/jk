// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application jar the augment hands Quarkus. Its bytes reach the fast-jar and key a downstream
 * action cache, so it carries neither a compile freshness stamp nor a wall-clock timestamp.
 */
class AppJarTest {

    private static final LocalDateTime PINNED = LocalDateTime.of(1980, 2, 1, 0, 0);

    @Test
    void the_app_jar_drops_stamps_and_pins_every_entry(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/com/ex"));
        Files.writeString(classes.resolve("App.class"), "class");
        for (String stamp : BuildStamps.ALL) {
            Files.writeString(tmp.resolve("classes").resolve(stamp), "STAMP_MILLIS 1758000000000");
        }

        Path jar = tmp.resolve("app.jar");
        AppJar.write(tmp.resolve("classes"), jar);

        assertThat(entryNames(jar)).containsExactly(JarFile.MANIFEST_NAME, "com/ex/App.class");
        // The manifest included: `new JarOutputStream(out, manifest)` would stamp it with now.
        assertThat(entryTimes(jar)).isNotEmpty().allSatisfy(time -> assertThat(time)
                .isEqualTo(PINNED));
    }

    private static List<String> entryNames(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static List<LocalDateTime> entryTimes(Path jar) throws Exception {
        List<LocalDateTime> times = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                times.add(e.getTimeLocal());
            }
        }
        return times;
    }
}
