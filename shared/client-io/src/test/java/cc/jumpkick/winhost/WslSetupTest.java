// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WslSetupTest {

    private static final WslDistros.Distro UBUNTU = new WslDistros.Distro("Ubuntu-26.10", 2, true, true, null);
    private static final WslDistros.Distro LEGACY = new WslDistros.Distro("Legacy", 1, false, false, null);
    private static final ReleasePin.Pin PIN = new ReleasePin.Pin("0.51.1", "the version of JumpKick for Windows");

    @TempDir
    Path dir;

    /** One {@code wsl.exe} run as the fake saw it: its arguments, the environment it added, the script it ran. */
    record Ran(List<String> argv, Map<String, String> env, String script) {}

    /** {@code wsl.exe} answering by the script it is asked to run. */
    final class Wsl implements WslShell {
        final List<Ran> ran = new ArrayList<>();
        final Map<String, Function<Ran, Integer>> answers = new HashMap<>();
        final Map<String, List<String>> output = new HashMap<>();

        @Override
        public int run(List<String> argv, Map<String, String> env, Duration limit, Consumer<String> out) {
            String script = argv.getLast();
            Ran r = new Ran(argv, env, script);
            ran.add(r);
            output.getOrDefault(script, List.of()).forEach(out);
            return answers.getOrDefault(script, x -> 0).apply(r);
        }

        Wsl probe(String... lines) {
            output.put("./probe.sh", List.of(lines));
            return this;
        }

        List<String> scripts() {
            return ran.stream().map(Ran::script).toList();
        }
    }

    final class Downloads implements WslSetup.Downloads {
        final List<String> fetched = new ArrayList<>();

        @Override
        public byte[] installer() {
            fetched.add("install.sh");
            return "#!/usr/bin/env bash\necho installer\n".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] client(String version, String arch) {
            fetched.add("jk-linux-" + arch + "-" + version);
            return new byte[] {0x7f, 'E', 'L', 'F'};
        }

        @Override
        public byte[] engine(String version) {
            fetched.add("jk-engine-" + version + ".jar");
            return new byte[] {'P', 'K'};
        }
    }

    private static final String[] READY = {
        "uid=1000", "has=bash", "has=curl", "has=openssl", "has=gunzip", "arch=x86_64"
    };

    @Test
    void a_distro_is_picked_from_wsls_list_and_never_from_a_requests_text() {
        List<WslDistros.Distro> known =
                List.of(UBUNTU, LEGACY, new WslDistros.Distro("bad name", 2, false, true, null));
        assertThat(WslSetup.find("ubuntu-26.10", known)).contains(UBUNTU);
        assertThat(WslSetup.find("Ubuntu-26.10", known)).contains(UBUNTU);
        for (String hostile : List.of(
                "Ubuntu-26.10; rm -rf ~",
                "Ubuntu-26.10 --exec sh",
                "-d",
                "--exec",
                "../Ubuntu-26.10",
                "Ubuntu-26.10\nDebian",
                "Ubuntu-26.10\"",
                "$(id)",
                "bad name",
                "Debian",
                "")) {
            assertThat(WslSetup.find(hostile, known)).as(hostile).isEmpty();
        }
    }

    @Test
    void installs_through_the_distros_own_download_with_the_pin_in_the_environment() throws IOException {
        Wsl wsl = new Wsl().probe(READY);
        Downloads downloads = new Downloads();
        List<String> progress = new ArrayList<>();
        WslSetup.Outcome out = new WslSetup(
                        wsl, downloads, Map.of("HTTPS_PROXY", "http://proxy:3128", "WSLENV", "WT_SESSION"), null)
                .install(UBUNTU, PIN, dir, progress::add);

        assertThat(out.ok()).as(out.line()).isTrue();
        assertThat(out.line()).isEqualTo("installed JumpKick 0.51.1 in Ubuntu-26.10");
        assertThat(wsl.scripts()).containsExactly("./probe.sh", "./install.sh", "./start.sh");
        for (Ran r : wsl.ran) {
            // Arrays only: the distro, the folder and a script name; never a shell line.
            assertThat(r.argv().subList(0, 6))
                    .containsExactly("wsl.exe", "-d", "Ubuntu-26.10", "--cd", dir.toString(), "--exec");
            assertThat(r.argv()).hasSize(8);
        }
        Ran installer = wsl.ran.get(1);
        assertThat(installer.argv().get(6)).isEqualTo("/bin/bash");
        assertThat(installer.env()).containsEntry("JK_VERSION", "0.51.1").containsEntry("JK_NONINTERACTIVE", "1");
        assertThat(installer.env().get("WSLENV"))
                .isEqualTo("WT_SESSION:JK_VERSION/u:JK_NONINTERACTIVE/u:HTTPS_PROXY/u");
        assertThat(downloads.fetched).containsExactly("install.sh");
        assertThat(Files.readString(dir.resolve("probe.sh"))).doesNotContain("\r");
        assertThat(progress).anyMatch(l -> l.contains("installing JumpKick 0.51.1 in Ubuntu-26.10"));
    }

    @Test
    void a_distro_without_curl_or_wget_gets_the_release_from_windows() throws IOException {
        Wsl wsl = new Wsl().probe("uid=1000", "has=bash", "has=gunzip", "arch=x86_64");
        Downloads downloads = new Downloads();
        List<String> progress = new ArrayList<>();
        WslSetup.Outcome out = new WslSetup(wsl, downloads, Map.of(), null).install(UBUNTU, PIN, dir, progress::add);

        assertThat(out.ok()).as(out.line()).isTrue();
        assertThat(wsl.scripts()).containsExactly("./probe.sh", "./local.sh", "./start.sh");
        assertThat(downloads.fetched).containsExactly("install.sh", "jk-linux-x86_64-0.51.1", "jk-engine-0.51.1.jar");
        assertThat(dir.resolve("dist/jk")).exists();
        assertThat(dir.resolve("dist/lib/jk-engine-0.51.1.jar")).exists();
        assertThat(progress)
                .anyMatch(l -> l.contains("has no curl or wget, no openssl")
                        && l.contains("verifies it against the release's signed checksums"));
    }

    @Test
    void wsl_1_root_and_no_bash_are_refused_each_with_its_reason() throws IOException {
        WslSetup.Outcome legacy =
                new WslSetup(new Wsl(), new Downloads(), Map.of(), null).install(LEGACY, PIN, dir, l -> {});
        assertThat(legacy.ok()).isFalse();
        assertThat(legacy.line()).contains("WSL 1").contains("wsl --set-version Legacy 2");

        Wsl root = new Wsl().probe("uid=0", "has=bash", "has=curl", "has=openssl", "has=gunzip", "arch=x86_64");
        WslSetup.Outcome asRoot =
                new WslSetup(root, new Downloads(), Map.of(), null).install(UBUNTU, PIN, dir, l -> {});
        assertThat(asRoot.ok()).isFalse();
        assertThat(asRoot.line()).contains("default user is root");
        assertThat(root.scripts()).containsExactly("./probe.sh");

        Wsl alpine = new Wsl().probe("uid=1000", "has=gunzip", "arch=x86_64");
        WslSetup.Outcome noBash =
                new WslSetup(alpine, new Downloads(), Map.of(), null).install(UBUNTU, PIN, dir, l -> {});
        assertThat(noBash.ok()).isFalse();
        assertThat(noBash.line()).contains("has no bash");
    }

    @Test
    void a_failing_installer_reports_its_own_reason() throws IOException {
        Wsl wsl = new Wsl().probe(READY);
        wsl.output.put(
                "./install.sh",
                List.of("* Installing JumpKick", "x failed to download https://jumpkick.build/x", "trailing"));
        wsl.answers.put("./install.sh", r -> 1);
        WslSetup.Outcome out = new WslSetup(wsl, new Downloads(), Map.of(), null).install(UBUNTU, PIN, dir, l -> {});
        assertThat(out.ok()).isFalse();
        assertThat(out.line())
                .isEqualTo("JumpKick's installer failed in Ubuntu-26.10: failed to download https://jumpkick.build/x");
        assertThat(wsl.scripts()).doesNotContain("./start.sh");
    }

    @Test
    void a_distro_that_does_not_answer_says_so() throws IOException {
        Wsl wsl = new Wsl();
        wsl.output.put("./probe.sh", List.of("There is no distribution with the supplied name."));
        wsl.answers.put("./probe.sh", r -> 1);
        WslSetup.Outcome out = new WslSetup(wsl, new Downloads(), Map.of(), null).install(UBUNTU, PIN, dir, l -> {});
        assertThat(out.ok()).isFalse();
        assertThat(out.line()).contains("did not start or answer").contains("no distribution");
    }

    @Test
    void the_pinned_version_already_there_only_starts_the_engine() throws IOException {
        List<String> ready = new ArrayList<>(List.of(READY));
        ready.add("jk=jk 0.51.1");
        Wsl wsl = new Wsl().probe(ready.toArray(String[]::new));
        WslSetup.Outcome out = new WslSetup(wsl, new Downloads(), Map.of(), null).install(UBUNTU, PIN, dir, l -> {});
        assertThat(out.ok()).isTrue();
        assertThat(wsl.scripts()).containsExactly("./probe.sh", "./start.sh");
    }

    @Test
    void remove_stops_the_engines_and_removes_the_home_and_shell_lines() throws IOException {
        Wsl wsl = new Wsl();
        WslSetup.Outcome out = new WslSetup(wsl, new Downloads(), Map.of(), null).remove(UBUNTU, dir, l -> {});
        assertThat(out.ok()).isTrue();
        assertThat(out.line()).isEqualTo("removed JumpKick from Ubuntu-26.10");
        String script = Files.readString(dir.resolve("remove.sh"));
        assertThat(script)
                .contains("engine stop -A --now")
                .contains("rm -rf \"$HOME/.jk\"")
                .contains("# >>> jk installer >>>")
                .doesNotContain("/mnt/");
    }

    @Test
    void a_local_dist_installs_in_place_of_a_release_with_its_folder_handed_over_as_a_wsl_path() throws IOException {
        Path dist = dir.resolve("linux-dist");
        Files.createDirectories(dist.resolve("lib"));
        Files.writeString(dist.resolve("jk"), "elf");
        Files.writeString(dist.resolve("lib").resolve("jk-engine-0.51.2-SNAPSHOT.jar"), "jar");
        ReleasePin.Pin pin = Objects.requireNonNull(WslSetup.distPin(dist));
        assertThat(pin.version()).isEqualTo("0.51.2-SNAPSHOT");
        assertThat(WslSetup.distPin(dir)).isNull();

        Path work = Files.createDirectories(dir.resolve("work"));
        Wsl wsl = new Wsl().probe(READY);
        WslSetup.Outcome out = new WslSetup(wsl, new Downloads(), Map.of(), dist).install(UBUNTU, pin, work, l -> {});
        assertThat(out.ok()).as(out.line()).isTrue();
        assertThat(wsl.scripts()).containsExactly("./probe.sh", "./local.sh", "./start.sh");
        Ran local = wsl.ran.get(1);
        assertThat(local.env()).containsEntry("JK_SETUP_DIST", dist.toString());
        assertThat(local.env().get("WSLENV")).endsWith(":JK_SETUP_DIST/p");
        assertThat(Files.readString(work.resolve("local.sh"))).contains("\"$JK_SETUP_DIST/jk\"");
    }

    @Test
    void a_dev_dist_is_honoured_only_by_a_snapshot_for_an_absolute_folder_holding_a_dist() throws IOException {
        Path dist = dir.resolve("linux-dist");
        Files.createDirectories(dist.resolve("lib"));
        Files.writeString(dist.resolve("jk"), "elf");
        Files.writeString(dist.resolve("lib").resolve("jk-engine-0.51.2-SNAPSHOT.jar"), "jar");
        assertThat(WslSetup.devDist(dist.toString(), "0.51.2-SNAPSHOT")).isEqualTo(dist);
        assertThat(WslSetup.devDist(dist.toString(), "0.51.2"))
                .as("a release installs a release")
                .isNull();
        assertThat(WslSetup.devDist("linux-dist", "0.51.2-SNAPSHOT"))
                .as("relative")
                .isNull();
        assertThat(WslSetup.devDist(dir.toString(), "0.51.2-SNAPSHOT"))
                .as("not a dist")
                .isNull();
        assertThat(WslSetup.devDist(null, "0.51.2-SNAPSHOT")).isNull();
    }

    @Test
    void a_distros_output_never_carries_a_token_on() {
        assertThat(WslShell.clean(" Web UI.....: http://127.0.0.1:8944/#t=s3cr3t-T0ken\r"))
                .isEqualTo("Web UI.....: http://127.0.0.1:8944/#t=<withheld>");
        assertThat(WslShell.clean("GET /api/events?access_token=abc123&x=1"))
                .isEqualTo("GET /api/events?access_token=<withheld>&x=1");
        assertThat(WslShell.clean("Authorization: Bearer abc.def")).isEqualTo("Authorization: Bearer <withheld>");
        assertThat(WslShell.clean("\u001B[32m* Installing\u001B[0m")).isEqualTo("* Installing");
    }

    @Test
    void release_arch_names() {
        assertThat(WslSetup.releaseArch("x86_64")).isEqualTo("x86_64");
        assertThat(WslSetup.releaseArch("aarch64")).isEqualTo("aarch64");
        assertThat(WslSetup.releaseArch("riscv64")).isNull();
    }
}
