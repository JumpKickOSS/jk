// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.ProjectFacts;
import cc.jumpkick.plugin.build.PluginCommandExec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code jk android licenses -y} accepts the licences, as {@code --yes} does: the usage line and
 * every message name {@code -y}, and only {@code --yes} used to accept.
 */
class AndroidLicensesAcceptTest {

    private static final String FEED = "<sdk:sdk-repository>"
            + "<license id=\"android-sdk-license\" type=\"text\">Terms and Conditions</license>"
            + "</sdk:sdk-repository>";

    @ParameterizedTest
    @ValueSource(strings = {"-y", "--yes"})
    void either_spelling_accepts_the_licences(String flag, @TempDir Path dir) throws Exception {
        Path sdk = Files.createDirectories(dir.resolve("sdk"));
        List<String> out = new ArrayList<>();

        int exit = AndroidCommand.run(exec(List.of("licenses", flag), sdk, out), feed(dir));

        assertThat(exit).as(String.join("\n", out)).isZero();
        assertThat(out).contains("accepted android-sdk-license");
        assertThat(sdk.resolve("licenses/android-sdk-license")).isRegularFile();
    }

    @Test
    void without_it_the_licences_are_listed_and_the_message_names_y(@TempDir Path dir) throws Exception {
        Path sdk = Files.createDirectories(dir.resolve("sdk"));
        List<String> out = new ArrayList<>();

        int exit = AndroidCommand.run(exec(List.of("licenses"), sdk, out), feed(dir));

        assertThat(exit).isZero();
        assertThat(String.join("\n", out)).contains("accept with jk android licenses -y");
        assertThat(sdk.resolve("licenses")).doesNotExist();
    }

    private static String feed(Path dir) throws Exception {
        return Files.writeString(dir.resolve("repository2-3.xml"), FEED).toUri().toString();
    }

    private static PluginCommandExec exec(List<String> args, Path sdkRoot, List<String> lines) {
        return new PluginCommandExec() {
            @Override
            public List<String> args() {
                return args;
            }

            @Override
            public PluginConfig config() {
                return new PluginConfig("android", Map.of());
            }

            @Override
            public ProjectFacts project() {
                return new ProjectFacts("com.example", "app", "1.0.0", 25, null, false, false, Map.of());
            }

            @Override
            public Path moduleDir() {
                return sdkRoot;
            }

            @Override
            public Optional<Path> extra(String name) {
                return "sdk-root".equals(name) ? Optional.of(sdkRoot) : Optional.empty();
            }

            @Override
            public Optional<Path> mainArtifact() {
                return Optional.empty();
            }

            @Override
            public void out(String line) {
                lines.add(line);
            }

            @Override
            public Path javaHome() {
                return Path.of(Objects.requireNonNull(System.getProperty("java.home")));
            }

            @Override
            public boolean offline() {
                return false;
            }

            @Override
            public void label(String text) {}
        };
    }
}
