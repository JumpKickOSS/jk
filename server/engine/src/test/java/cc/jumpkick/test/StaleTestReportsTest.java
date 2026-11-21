// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaleTestReportsTest {

    @TempDir
    Path tmp;

    @Test
    void a_report_of_a_class_gone_from_the_test_classes_is_removed() throws IOException {
        Path classes = classes("com/acme/KeptTest", "com/acme/Outer$Inner");
        Path surefire =
                reports("surefire-reports", "com.acme.KeptTest", "com.acme.DeletedTest", "com.acme.Outer$Inner");
        Path failsafe = reports("failsafe-reports", "com.acme.MovedAwayIT");

        int deleted = StaleTestReports.prune(classes, List.of(), c -> surefire, surefire, failsafe);

        assertThat(deleted).isEqualTo(2);
        assertThat(names(surefire))
                .containsExactlyInAnyOrder("TEST-com.acme.KeptTest.xml", "TEST-com.acme.Outer$Inner.xml");
        assertThat(names(failsafe)).isEmpty();
    }

    @Test
    void a_report_this_run_wrote_stays_and_its_copy_in_the_other_suite_goes() throws IOException {
        Path classes = classes("com/acme/NowFastTest");
        Path surefire = reports("surefire-reports", "com.acme.NowFastTest", "com.acme.FromAJarTest");
        Path failsafe = reports("failsafe-reports", "com.acme.NowFastTest");

        StaleTestReports.prune(
                classes, List.of("com.acme.NowFastTest", "com.acme.FromAJarTest"), c -> surefire, surefire, failsafe);

        assertThat(names(surefire))
                .containsExactlyInAnyOrder("TEST-com.acme.NowFastTest.xml", "TEST-com.acme.FromAJarTest.xml");
        assertThat(names(failsafe)).isEmpty();
    }

    @Test
    void a_report_that_does_not_name_a_qualified_class_is_left_alone() throws IOException {
        Path classes = classes();
        Path surefire = reports("surefire-reports", "node", "DefaultPackageTest", "Login_feature-1");
        Files.writeString(surefire.resolve("summary.txt"), "");

        int deleted = StaleTestReports.prune(classes, List.of(), c -> surefire, surefire, tmp.resolve("absent"), null);

        assertThat(deleted).isZero();
        assertThat(names(surefire)).hasSize(4);
    }

    private Path classes(String... binaryPaths) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("test-classes"));
        for (String p : binaryPaths) {
            Path file = dir.resolve(p + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
        return dir;
    }

    private Path reports(String name, String... classNames) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name));
        for (String c : classNames) Files.writeString(dir.resolve(XmlTestReport.fileName(c)), "<testsuite/>");
        return dir;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
