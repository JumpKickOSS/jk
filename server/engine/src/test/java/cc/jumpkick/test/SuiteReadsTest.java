// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.PortablePath;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A suite JVM's reads are recorded, and those no key covers are what it is found to depend on. */
class SuiteReadsTest {

    /**
     * A file under a recording's name that is no recording (a torn write, a test's stand-in) is
     * reported unreadable and left closed: RecordingFile's constructor fails after opening such a
     * file and keeps it open, which on Windows kept it, and its directory, from being deleted.
     */
    @Test
    void a_file_that_is_no_recording_reads_as_incomplete_and_is_not_held_open(@TempDir Path tmp) throws Exception {
        Path reads = Files.createDirectories(tmp.resolve("suite-reads"));
        Path torn = Files.writeString(reads.resolve("torn.jfr"), "not a recording");

        SuiteReads.Observed seen = SuiteReads.collect(reads, tmp);

        assertThat(seen.complete()).as("a torn recording vouches for nothing").isFalse();
        Files.delete(torn);
        assertThat(torn).doesNotExist();
    }

    /**
     * jk's own environment probe, which every probed test JVM reads as it loads a probe class, is
     * never an input of the suite: with {@code -Duser.timezone} on the fork it is read mid-run, and
     * keying it called it changed under every suite that ran beside another (each launch touches it).
     * A file of the same name anywhere else is read like any other.
     */
    @Test
    void the_environment_probe_jar_is_never_an_input(@TempDir Path tmp) throws Exception {
        Path probeJar =
                Files.createDirectories(tmp.resolve("cache/env-reads")).resolve("env-probe-0123456789abcdef.jar");
        Files.writeString(probeJar, "probe");
        Path lookalike = Files.writeString(tmp.resolve("env-probe-0123456789abcdef.jar"), "user's");
        SuiteReads.Observed seen =
                new SuiteReads.Observed(new TreeSet<>(List.of(probeJar, lookalike)), Set.of(), Set.of(), true);
        assertThat(SuiteReads.undeclared(seen, List.of())).containsExactly(lookalike);
    }

    @Test
    void a_recorded_jvm_reports_the_files_it_read(@TempDir Path tmp) throws Exception {
        Path data = Files.writeString(tmp.resolve("data.txt"), "payload");
        Path covered = Files.createDirectories(tmp.resolve("covered"));
        Files.writeString(covered.resolve("in.txt"), "keyed");
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                import java.nio.file.*;
                class Probe {
                    public static void main(String[] a) throws Exception {
                        Files.readAllBytes(Path.of("data.txt"));
                        Files.readAllBytes(Path.of("covered/in.txt"));
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(java.toString(), flag, probe.toString())
                        .directory(tmp.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.files()).contains(data.toAbsolutePath().normalize());
        assertThat(seen.network()).isFalse();
        Set<Path> undeclared =
                SuiteReads.undeclared(seen, List.of(covered, probe, Path.of(System.getProperty("java.home"))));
        assertThat(undeclared).contains(data.toAbsolutePath().normalize());
        assertThat(undeclared)
                .doesNotContain(covered.resolve("in.txt").toAbsolutePath().normalize());
    }

    /**
     * In the workspace, a golden file a test loads and then rewrites in place is an input, by what it
     * held before the run, a sibling member's as much as the module's own; a file the test writes and
     * then reads back is its own output. Outside it, a file read and then rewritten is a tool's state.
     */
    @Test
    void a_workspace_file_read_before_the_jvm_writes_it_is_an_input_and_one_written_first_is_not(@TempDir Path tmp)
            throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("a"));
        Path golden = Files.writeString(module.resolve("golden.txt"), "expected");
        Path sibling =
                Files.writeString(Files.createDirectories(ws.resolve("b")).resolve("fixture.json"), "{}");
        Path registry =
                Files.writeString(Files.createDirectories(tmp.resolve("tool")).resolve("registry.bin"), "1");
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                import java.io.*;
                class Probe {
                    public static void main(String[] a) throws Exception {
                        for (String f : new String[] {"golden.txt", "../b/fixture.json", "../../tool/registry.bin"}) {
                            byte[] old;
                            try (InputStream in = new FileInputStream(f)) { old = in.readAllBytes(); }
                            try (OutputStream out = new FileOutputStream(f)) { out.write(old); }
                        }
                        try (OutputStream out = new FileOutputStream("made.txt")) { out.write(1); }
                        try (InputStream in = new FileInputStream("made.txt")) { in.readAllBytes(); }
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(java.toString(), flag, probe.toString())
                        .directory(module.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        SuiteReads.Observed seen = SuiteReads.collect(recordings, module, ws, f -> true);
        assertThat(seen.files())
                .contains(
                        golden.toAbsolutePath().normalize(),
                        sibling.toAbsolutePath().normalize());
        assertThat(seen.files())
                .doesNotContain(
                        module.resolve("made.txt").toAbsolutePath().normalize(),
                        registry.toAbsolutePath().normalize());
    }

    @Test
    void a_workspace_file_is_an_input_when_its_first_read_comes_before_its_first_write(@TempDir Path tmp) {
        Path readFirst = tmp.resolve("read-first");
        Path writtenFirst = tmp.resolve("written-first");
        Path tied = tmp.resolve("tied");
        Path readOnly = tmp.resolve("read-only");
        Path writtenOnly = tmp.resolve("written-only");
        SuiteReads.FirstTouches touched = new SuiteReads.FirstTouches(tmp);
        touched.read(readFirst, 100);
        touched.wrote(readFirst, 200);
        touched.wrote(writtenFirst, 100);
        touched.read(writtenFirst, 200);
        touched.read(tied, 150);
        touched.wrote(tied, 150);
        touched.read(readOnly, 100);
        touched.wrote(writtenOnly, 100);

        assertThat(touched.inputs())
                .as("a read stamped with its write counts as first: it may have seen the old bytes")
                .containsExactlyInAnyOrder(readFirst, tied, readOnly);
    }

    /**
     * Outside the workspace a file the run read and then wrote is some tool's own state (a daemon
     * registry, a resolver's status file), dropped whatever the order; one it only read is an input.
     */
    @Test
    void a_file_outside_the_workspace_read_then_written_is_no_input(@TempDir Path tmp) {
        Path ws = tmp.resolve("ws");
        Path registry = tmp.resolve("home/.gradle/daemon/registry.bin");
        Path config = tmp.resolve("home/.docker/config.json");
        Path sibling = ws.resolve("b/fixture.json");
        SuiteReads.FirstTouches touched = new SuiteReads.FirstTouches(ws.resolve("a/.."));
        touched.read(registry, 100);
        touched.wrote(registry, 200);
        touched.read(config, 100);
        touched.read(sibling, 100);
        touched.wrote(sibling, 200);

        assertThat(touched.inputs()).containsExactlyInAnyOrder(config, sibling);
    }

    /**
     * The recordings of several JVMs are read one after another, not in the order their events
     * happened: a write in the recording read first does not hide a read another recorded earlier,
     * and a read back that recording gave first does not make another's own output an input.
     */
    @Test
    void the_order_is_the_events_time_not_the_order_the_recordings_are_read(@TempDir Path tmp) {
        Path fixture = tmp.resolve("fixture.json");
        Path scratch = tmp.resolve("scratch.json");
        SuiteReads.FirstTouches touched = new SuiteReads.FirstTouches(tmp);
        // The first recording read: this JVM rewrites the fixture, then reads the scratch file back.
        touched.wrote(fixture, 300);
        touched.read(scratch, 400);
        touched.read(fixture, 500);
        // The second: this JVM read the fixture before the first wrote it, and wrote the scratch file first.
        touched.read(fixture, 100);
        touched.wrote(scratch, 50);

        assertThat(touched.inputs()).containsExactly(fixture);
    }

    @Test
    void a_recorded_jvm_names_a_program_whose_path_holds_a_space_whole(@TempDir Path tmp) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path bin = Files.createDirectories(tmp.resolve("Program Files").resolve("bin"));
        // A program that exits 0, started by its path: a batch file on Windows, a script elsewhere.
        Path tool = Os.isWindows()
                ? Files.writeString(bin.resolve("tool.cmd"), "@exit /b 0\r\n")
                : Files.writeString(bin.resolve("tool"), "#!/bin/sh\nexit 0\n");
        if (!Os.isWindows()) Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                class Probe {
                    public static void main(String[] a) throws Exception {
                        Process p = new ProcessBuilder(a[0], "--flag", a[1])
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        System.exit(p.waitFor());
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(
                                java.toString(),
                                flag,
                                probe.toString(),
                                tool.toString(),
                                tmp.resolve("an arg").toString())
                        .directory(tmp.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        assertThat(SuiteReads.collect(recordings, tmp).commands())
                .as("JFR joins the words with spaces; the program is the words that name its file")
                .contains(tool.toString());
    }

    @Test
    void a_program_whose_path_holds_a_space_is_kept_and_keyed_whole(@TempDir Path tmp) throws Exception {
        Path tool = Files.writeString(
                Files.createDirectories(tmp.resolve("Program Files").resolve("bin"))
                        .resolve("tool"),
                "#!/bin/sh\n");
        assertThat(SuiteReads.executable(tool + " --flag " + tmp.resolve("an arg")))
                .isEqualTo(tool.toString());
        assertThat(SuiteReads.executable("git status --short")).isEqualTo("git");

        Path module = Files.createDirectories(tmp.resolve("module"));
        SuiteReads.Discovered d = SuiteReads.Discovered.NONE.with(Set.of(), Set.of(tool.toString()), Set.of());
        SuiteReads.save(module, TestSelection.DEFAULT, d);
        String slashed = tool.toString().replace('\\', '/');
        assertThat(Files.readString(SuiteReads.discoveredFile(module, TestSelection.DEFAULT)))
                .as("kept with forward slashes on every host")
                .contains("command " + slashed + "\n");
        assertThat(SuiteReads.load(module, TestSelection.DEFAULT).commands())
                .as("read back as a run observes it, with this host's separators")
                .containsExactly(tool.toString());
        assertThat(SuiteReads.executable(slashed + " --flag")).isEqualTo(tool.toString());
        assertThat(SuiteReads.tokens(module, TestSelection.DEFAULT, tmp))
                .as("a program in the workspace, by its workspace path")
                .containsExactly("proc:Program Files/bin/tool=" + FileHashMemo.contentHash(tool));
    }

    /**
     * What a suite was found to read is kept under the module's portable tag with checkout-relative
     * names: another checkout of the project finds it and reads it as its own files (JK-PCXMC).
     */
    @Test
    void another_checkout_of_the_project_finds_the_reads_as_its_own_files(@TempDir Path tmp) throws Exception {
        Path one = checkout(tmp.resolve("one/app"));
        Path two = checkout(tmp.resolve("elsewhere/deeper/two"));
        assertThat(SuiteReads.discoveredFile(one, TestSelection.DEFAULT))
                .isEqualTo(SuiteReads.discoveredFile(two, TestSelection.DEFAULT));
        Path fixture =
                Files.writeString(Files.createDirectories(one.resolve("data")).resolve("in.txt"), "x");
        SuiteReads.save(
                one, TestSelection.DEFAULT, SuiteReads.Discovered.NONE.with(Set.of(fixture), Set.of(), Set.of()));
        assertThat(Files.readString(SuiteReads.discoveredFile(one, TestSelection.DEFAULT)))
                .as("no checkout path is kept")
                .contains("file ws:data/in.txt")
                .doesNotContain(tmp.toString());
        assertThat(SuiteReads.load(two, TestSelection.DEFAULT).files())
                .containsExactly(two.resolve("data/in.txt").toAbsolutePath().normalize());
        Files.writeString(Files.createDirectories(two.resolve("data")).resolve("in.txt"), "x");
        assertThat(SuiteReads.tokens(two, TestSelection.DEFAULT, two))
                .isEqualTo(SuiteReads.tokens(one, TestSelection.DEFAULT, one));
    }

    /**
     * Two jk homes on one machine, each with its own store, state and Maven local repository holding
     * the same content: a suite that read a POM in the repository and a library jk unpacked into its
     * state, and started the store's node, keys alike in both, every path named by its root's word
     * and none by a home's absolute path (JK-FNM8N; the dojo-4 study's jk-engine, jumpkick-intellij
     * and jk-web).
     */
    @Test
    void two_homes_key_reads_and_programs_under_their_roots_alike(@TempDir Path tmp) throws Exception {
        Path module = checkout(tmp.resolve("ws/app"));
        List<String> one = keyedIn(tmp.resolve("home-1"), tmp.resolve("m2-1"), module);
        List<String> two = keyedIn(tmp.resolve("home-2"), tmp.resolve("m2-2"), module);

        assertThat(one)
                .anyMatch(t -> t.startsWith("read:m2:org/acme/a/1.0/a-1.0.pom="))
                .anyMatch(t -> t.startsWith("read:state:tmp/unpacked/4d25/lib/nio-fs.jar="))
                .anyMatch(t -> t.startsWith("proc:store:tools/node/24.22.0/bin/node="))
                .noneMatch(t -> t.contains(tmp.toString()));
        assertThat(two).as("the second home's stamp tokens").isEqualTo(one);
    }

    /**
     * How a started program keys (JK-EV4F4), as the dojo-4 study's records named them: {@code
     * /bin/sh} and {@code sh} are one POSIX utility keyed by OS family, as are {@code sleep} and
     * {@code ps} (whose {@code --version} a Mac answers "illegal option"); a declared tool defers to
     * its declaration; a program named relative to the test's directory keys as such; a tool an
     * administrator installed keys by what it says to {@code --version}, never its bytes, and one
     * in a place this user may write by its bytes.
     */
    @Test
    void a_started_program_keys_by_what_it_is_not_by_its_hosts_bytes(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(Os.isWindows(), "POSIX utilities and file modes");
        Path module = checkout(tmp.resolve("ws/app"));
        Path tools = Files.createDirectories(tmp.resolve("opt/tools/bin"));
        Path admin = Files.writeString(tools.resolve("faketool"), "#!/bin/sh\necho 'faketool version 2.54.0'\n");
        Path mine = Files.writeString(tmp.resolve("faketool2"), "#!/bin/sh\necho 'faketool2 version 1'\n");
        Files.setPosixFilePermissions(admin, PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.setPosixFilePermissions(mine, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(tools, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Assumptions.assumeFalse(Files.isWritable(admin), "a user who may write anything (root)");
            SuiteReads.save(
                    module,
                    TestSelection.DEFAULT,
                    SuiteReads.Discovered.NONE.with(
                            Set.of(),
                            Set.of("/bin/sh", "sh", "sleep", "ps", "protoc", "./jk", admin.toString(), mine.toString()),
                            Set.of()));
            List<String> tokens = SuiteReads.tokens(
                    module, TestSelection.DEFAULT, tmp.resolve("ws"), SuiteEnvReads.Policy.NONE, List.of("protoc"));

            assertThat(tokens)
                    .containsExactlyInAnyOrder(
                            "proc:sh=posix",
                            "proc:sleep=posix",
                            "proc:ps=posix",
                            "proc:protoc=declared",
                            "proc:./jk=relative",
                            "proc:faketool=faketool version 2.54.0",
                            "proc:faketool2=" + FileHashMemo.contentHash(mine));
            assertThat(String.join("\n", tokens)).doesNotContain(tmp.toString());
        } finally {
            Files.setPosixFilePermissions(tools, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /** The stamp tokens of a suite that read and started the same files under {@code home}'s roots and {@code m2}. */
    private static List<String> keyedIn(Path home, Path m2, Path module) throws Exception {
        String[] keys = {
            "jk.env.JK_HOME", "jk.env.JK_STORE_DIR", "jk.env.JK_CACHE_DIR", "jk.env.JK_STATE_DIR", "jk.m2.local"
        };
        String[] was = new String[keys.length];
        String[] now = {
            home.toString(),
            home.resolve("store").toString(),
            home.resolve("cache").toString(),
            home.resolve("state").toString(),
            m2.toString()
        };
        for (int i = 0; i < keys.length; i++) {
            was[i] = System.getProperty(keys[i]);
            System.setProperty(keys[i], now[i]);
        }
        try {
            Path pom = write(m2.resolve("org/acme/a/1.0/a-1.0.pom"), "<project/>");
            Path lib = write(home.resolve("state/tmp/unpacked/4d25/lib/nio-fs.jar"), "jar");
            Path node = write(home.resolve("store/tools/node/24.22.0/bin/node"), "node");
            SuiteReads.save(
                    module,
                    TestSelection.DEFAULT,
                    SuiteReads.Discovered.NONE.with(Set.of(pom, lib), Set.of(node.toString()), Set.of()));
            return SuiteReads.tokens(module, TestSelection.DEFAULT, Objects.requireNonNull(module.getParent()));
        } finally {
            for (int i = 0; i < keys.length; i++) restore(keys[i], was[i]);
        }
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(Objects.requireNonNull(file.getParent()));
        return Files.writeString(file, text);
    }

    private static Path checkout(Path dir) throws Exception {
        Files.createDirectories(dir);
        // Two clones of one commit carry one project id, as the lock's project-id does.
        Files.writeString(
                dir.resolve("jk.toml"),
                "id = \"portable-reads-1\"\ngroup = \"com.example\"\nname = \"portable-reads\"\nversion = \"1.0.0\"\n");
        return dir;
    }

    @Test
    void a_discovered_set_covers_a_run_that_read_nothing_new() {
        Path a = Path.of("/w/a.txt");
        Path b = Path.of("/w/b.txt");
        SuiteReads.Discovered d = SuiteReads.Discovered.NONE.with(Set.of(a), Set.of(), Set.of());
        assertThat(d.covers(Set.of(a), Set.of(), Set.of())).isTrue();
        assertThat(d.covers(Set.of(a, b), Set.of(), Set.of())).isFalse();
        assertThat(d.covers(Set.of(), Set.of("git status"), Set.of())).isFalse();
        assertThat(d.covers(Set.of(), Set.of(), Set.of("repo.example.com"))).isFalse();
    }

    @Test
    void a_recorded_jvm_names_the_host_past_this_one_it_talked_to(@TempDir Path tmp) throws Exception {
        String address = nonLoopbackAddress();
        Assumptions.assumeTrue(address != null, "this host has no address but loopback");
        Path probe = Files.writeString(tmp.resolve("Probe.java"), """
                import java.net.*;
                class Probe {
                    public static void main(String[] a) throws Exception {
                        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName(a[0]));
                                Socket client = new Socket(InetAddress.getByName(a[0]), server.getLocalPort());
                                Socket accepted = server.accept()) {
                            client.getOutputStream().write(1);
                            accepted.getInputStream().read();
                            accepted.getOutputStream().write(2);
                            client.getInputStream().read();
                        }
                    }
                }
                """);
        Path recordings = tmp.resolve("rec");
        String flag = SuiteReads.prepare(recordings);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(java.toString(), flag, probe.toString(), address)
                        .directory(tmp.toFile())
                        .redirectErrorStream(true),
                Duration.ofMinutes(2));
        assertThat(run.exit()).as(run.output()).isZero();

        SuiteReads.Observed seen = SuiteReads.collect(recordings, tmp);
        assertThat(seen.network()).isTrue();
        assertThat(seen.hosts()).contains(address);
    }

    /** An address of an interface that is up and not loopback, or null when there is none. */
    private static @Nullable String nonLoopbackAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                    return a.getHostAddress();
            }
        }
        return null;
    }

    /**
     * A directory made outside the workspace half a second before the run is not the run's own. The
     * JDK reports a macOS birth time cut to the second; the directory's modification time, which
     * keeps the file system's precision, says it was born before the run began.
     */
    @Test
    void a_directory_made_just_before_the_run_is_not_taken_for_the_runs_own() {
        long started = 1_794_165_723_818L;
        long made = started - 492;
        FileTime cut = FileTime.from(made / 1_000, TimeUnit.SECONDS);

        assertThat(SuiteReads.bornDuringRun(attrs(FileTime.fromMillis(made), FileTime.fromMillis(made)), started))
                .as("a birth time the file system stamped finely")
                .isFalse();
        assertThat(SuiteReads.bornDuringRun(attrs(cut, FileTime.fromMillis(made + 3)), started))
                .as("a birth time cut to the second, bounded by the modification")
                .isFalse();
        assertThat(SuiteReads.bornDuringRun(attrs(cut, cut), started))
                .as("a file system that stamps whole seconds leaves the birth anywhere in that second")
                .isTrue();
    }

    /** A directory the run made, whose birth time is cut to the second, is still the run's own. */
    @Test
    void a_directory_the_run_made_is_its_own_whatever_its_birth_time_was_cut_to() {
        long started = 1_794_165_723_818L;
        FileTime cut = FileTime.from(started / 1_000, TimeUnit.SECONDS);

        assertThat(SuiteReads.bornDuringRun(attrs(cut, FileTime.fromMillis(started + 40)), started))
                .isTrue();
        assertThat(SuiteReads.bornDuringRun(attrs(cut, FileTime.fromMillis(started - 60_000)), started))
                .as("a modification older than the birth, a copy keeping its source's time, bounds nothing")
                .isTrue();
    }

    private static BasicFileAttributes attrs(FileTime born, FileTime modified) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return modified;
            }

            @Override
            public FileTime lastAccessTime() {
                return modified;
            }

            @Override
            public FileTime creationTime() {
                return born;
            }

            @Override
            public boolean isRegularFile() {
                return false;
            }

            @Override
            public boolean isDirectory() {
                return true;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public Object fileKey() {
                return born;
            }
        };
    }

    /**
     * A file outside the workspace is spelled under its jk root ({@code store:repos/…}), and the memo
     * reads that spelling back as the file it names: on Windows the bare spelling is no path at all
     * (its colon threw and failed the module's test step), and anywhere it named a file inside the
     * workspace that does not exist. Here the store lies in a module's build output, which the memo
     * leaves out, so only the store's own file, found through the spelling, is left out.
     */
    @Test
    void a_token_spelled_under_the_store_names_the_stores_file(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path module = Files.createDirectories(ws.resolve("mod"));
        Files.writeString(module.resolve("jk.toml"), "");
        Path store = Files.createDirectories(module.resolve("target/store"));
        String home = System.getProperty("jk.env.JK_HOME");
        String storeDir = System.getProperty("jk.env.JK_STORE_DIR");
        System.setProperty("jk.env.JK_HOME", tmp.resolve("home").toString());
        System.setProperty("jk.env.JK_STORE_DIR", store.toString());
        try {
            String token = "read:" + PortablePath.underJkRoot(store.resolve("repos/a.jar")) + "=abc";
            assertThat(token).startsWith("read:store:");

            assertThat(SuiteReads.memoDigest(List.of(token), ws))
                    .as("the store's file lies in mod's build output")
                    .isEqualTo("none");
        } finally {
            restore("jk.env.JK_HOME", home);
            restore("jk.env.JK_STORE_DIR", storeDir);
        }
    }

    private static void restore(String key, @Nullable String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void only_a_comma_quotes_a_recording_path() {
        // A quoted space would split the argument on Windows, where the launcher wraps it in quotes again.
        Path spaced = Path.of("/a b/reads.jfc");
        Path comma = Path.of("/a,b/reads.jfc");
        assertThat(SuiteReads.jfrPath(spaced)).isEqualTo(spaced.toString());
        assertThat(SuiteReads.jfrPath(comma)).isEqualTo("\"" + comma + "\"");
    }
}
