// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A store whose bytes no longer decode is set aside, and the next run starts learning again. */
class ProjectStoreSelfHealTest {

    @Test
    void a_store_with_an_undecodable_byte_learns_again(@TempDir Path home) throws Exception {
        ProjectStore.foldRun(home, "task.compile-java.wall-ms = 100\n");
        Path file = ProjectStore.file(home);
        byte[] text = Files.readString(file).getBytes(StandardCharsets.UTF_8);
        byte[] torn = new byte[text.length + 1];
        System.arraycopy(text, 0, torn, 0, text.length);
        torn[text.length] = (byte) 0xC3; // a lone UTF-8 lead byte, as a torn or foreign write leaves
        Files.write(file, torn);

        for (int i = 0; i < 3; i++) ProjectStore.foldRun(home, "task.compile-java.wall-ms = 200\n");

        Map<String, RunningMean> means = new LinkedHashMap<>();
        ProjectStore.scan(home, (key, m) -> means.put(key, m), null);
        System.out.println("means after three folds into the torn store: " + means + "; file still torn: "
                + (Files.readAllBytes(file)[(int) Files.size(file) - 1] == (byte) 0xC3));
        assertThat(means.get("task.compile-java.wall-ms"))
                .as("the three folds after the bad byte were learned")
                .isNotNull()
                .extracting(RunningMean::last)
                .isEqualTo(200.0);
    }
}
