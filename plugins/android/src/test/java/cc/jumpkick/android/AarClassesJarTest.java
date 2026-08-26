// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.BuildStamps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the AAR's {@code classes.jar} — and the sibling jar built from the same call — is allowed to
 * contain. It ships to consumers, so the generated {@code R} classes and the compile freshness
 * stamps both stay out; a stamp body is a wall clock, which would churn the AAR every build.
 */
class AarClassesJarTest {

    @Test
    void classes_jar_excludes_r_classes_and_compile_stamps(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/com/ex"));
        Files.writeString(classes.resolve("App.class"), "app");
        Files.writeString(classes.resolve("R.class"), "generated");
        Files.writeString(classes.resolve("R$string.class"), "generated");
        for (String stamp : BuildStamps.ALL) {
            Files.writeString(tmp.resolve("classes").resolve(stamp), "STAMP_MILLIS 1758000000000");
        }

        Path jar = tmp.resolve("lib.jar");
        AarPackager.writeClassesJar(tmp.resolve("classes"), jar);

        assertThat(entryNames(jar)).containsExactly("com/ex/App.class");
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
}
