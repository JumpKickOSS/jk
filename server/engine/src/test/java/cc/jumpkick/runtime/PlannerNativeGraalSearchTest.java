// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.GraalLauncher;
import cc.jumpkick.tool.NativeImageDriver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the native step's home search reports about itself. The search falling through a home is
 * fine — it has tiers for a reason — but falling through one SILENTLY is what made the reported
 * failure unreadable: the client resolved {@code ~/.jdks/graalvm-25}, that directory held no
 * launcher, and the error named the pinned Temurin instead.
 */
class PlannerNativeGraalSearchTest {

    @Test
    void a_resolved_home_with_no_launcher_is_in_the_trail_ahead_of_what_replaced_it(@TempDir Path tmp)
            throws IOException {
        // The shape on the reporting host: a GraalVM home that exists and carries nothing.
        Path graalHome = Files.createDirectories(tmp.resolve("graalvm-25"));
        Path jdks = Files.createDirectories(tmp.resolve("jdks"));
        Path project = Files.createDirectories(tmp.resolve("app"));

        PlannerNative.GraalSearch search = PlannerNative.searchNativeImageHome(graalHome, project, jdks, null);

        assertThat(search.checked())
                .extracting(NativeImageDriver.Candidate::home)
                .as("the home the client resolved is recorded before it is tested")
                .startsWith(graalHome);
        assertThat(search.home())
                .as("and the search still answers a home to fail against")
                .isNotNull();

        String message =
                requireNonNull(NativeImageDriver.notFoundError(search.checked()).getMessage());
        assertThat(message)
                .as("so the failure names the home that is actually wrong")
                .contains(graalHome.toString());
    }

    @Test
    void a_home_that_holds_the_launcher_is_the_answer_and_the_only_one_tried(@TempDir Path tmp) throws IOException {
        Path graalHome = tmp.resolve("graalvm-25");
        Files.createDirectories(graalHome.resolve("bin"));
        Files.writeString(graalHome.resolve("bin").resolve(GraalLauncher.NAME), "#!/fake");
        Path project = Files.createDirectories(tmp.resolve("app"));

        PlannerNative.GraalSearch search =
                PlannerNative.searchNativeImageHome(graalHome, project, tmp.resolve("jdks"), null);

        assertThat(search.home()).isEqualTo(graalHome);
        assertThat(search.checked()).hasSize(1);
    }
}
