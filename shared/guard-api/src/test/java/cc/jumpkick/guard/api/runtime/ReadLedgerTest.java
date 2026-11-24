// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Model;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.guard.api.TextSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.jsonl.MiniJson;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** A guard whose reads all hold replays its last line; an edit to anything it read runs it again. */
class ReadLedgerTest {

    static final Map<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();
    /** Each file a per-file scan read, by guard: "guard path". */
    static final Map<String, AtomicInteger> SCANS = new ConcurrentHashMap<>();

    private static void scanned(String guard, String path) {
        SCANS.computeIfAbsent(guard + " " + path, k -> new AtomicInteger()).incrementAndGet();
    }

    private static void ran(String id) {
        RUNS.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
    }

    @Disabled("run by the tests below through the launcher, with the condition deactivated")
    @GuardSuite(scope = Scope.WORKSPACE)
    static class Suite {
        @Guard(id = "reads-a", why = "w")
        void readsA(Text text, Violations v) {
            ran("reads-a");
            List<String> lines = text.lines("src/a.txt");
            for (int i = 0; i < lines.size(); i++)
                if (lines.get(i).contains("bad")) v.add(new TextSite("src/a.txt", i + 1, "bad"), "a bad line");
            v.population(lines.size());
        }

        @Guard(id = "lists-docs", why = "w")
        void listsDocs(Text text, Violations v) {
            ran("lists-docs");
            v.population(text.files("src/**/*.md").size());
        }

        @Guard(id = "reads-model", why = "w")
        void readsModel(Model model, Violations v) {
            ran("reads-model");
            v.population(model.modules().size());
        }

        @Guard(id = "reads-facts", why = "w")
        void readsFacts(Facts facts, Violations v) {
            ran("reads-facts");
            v.population(facts.classes().size());
        }

        /** Judges each file on its own: a line that says "bad" is a violation. */
        @Guard(id = "per-file", why = "w")
        void perFile(Text text, Violations v) {
            ran("per-file");
            List<String> files = text.files("src/k/*.txt");
            for (String rel : files) {
                List<String> bad = text.perFile(rel, "bad lines", r -> {
                    scanned("per-file", r);
                    List<String> lines = text.lines(r);
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < lines.size(); i++)
                        if (lines.get(i).contains("bad")) out.add(String.valueOf(i + 1));
                    return out;
                });
                for (String line : bad) v.add(new TextSite(rel, Integer.parseInt(line), "bad"), "a bad line");
            }
            v.population(files.size());
        }

        /** A key used in one file and defined in none is a violation: a verdict that hangs on other files. */
        @Guard(id = "closure", why = "w")
        void closure(Text text, Violations v) {
            ran("closure");
            List<String> files = text.files("src/k/*.txt");
            Set<String> defined = new HashSet<>();
            Map<String, String> used = new TreeMap<>();
            for (String rel : files) {
                for (String word : text.perFile(rel, "words", r -> {
                    scanned("closure", r);
                    return text.lines(r);
                })) {
                    if (word.startsWith("define ")) defined.add(word.substring(7));
                    if (word.startsWith("use ")) used.put(word.substring(4), rel);
                }
            }
            used.forEach((key, rel) -> {
                if (!defined.contains(key)) v.add(new TextSite(rel, 1, key), "uses " + key + ", which nothing defines");
            });
            v.population(files.size());
        }

        @Guard(id = "throws", why = "w")
        void throwsUp(Text text, Violations v) {
            ran("throws");
            text.lines("src/a.txt");
            throw new IllegalStateException("kaboom");
        }
    }

    @TempDir
    Path root;

    private Path report;

    @BeforeEach
    void tree() throws Exception {
        RUNS.clear();
        SCANS.clear();
        Files.createDirectories(root.resolve("src/docs"));
        Files.createDirectories(root.resolve("src/k"));
        Files.writeString(root.resolve("src/k/one.txt"), "use key\nbad\n");
        Files.writeString(root.resolve("src/k/two.txt"), "define key\nfine\n");
        Files.writeString(root.resolve("src/a.txt"), "fine\nbad\n");
        Files.writeString(root.resolve("src/docs/one.md"), "# one\n");
        Files.writeString(root.resolve("model.json"), "{\"modules\":[\"\",\"lib\"]}");
        report = root.resolve("target/guard/report.jsonl");
    }

    /** One run as the engine forks it: a fresh runtime, the report emptied first. */
    private Map<String, Object> run(boolean fixture) throws Exception {
        Files.deleteIfExists(report);
        GuardRuntime.install(config(fixture));
        Launcher launcher = LauncherFactory.create();
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Suite.class))
                .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .build();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        launcher.execute(req, summary);
        assertThat(summary.getSummary().getTestsFailedCount()).isZero();
        Map<String, Object> byId = new TreeMap<>();
        for (String l : Files.readAllLines(report)) byId.put(MiniJson.str(MiniJson.parse(l), "id"), MiniJson.parse(l));
        assertThat(byId).as("every guard reports, run or replayed").hasSize(7);
        return byId;
    }

    private Map<String, Object> run() throws Exception {
        return run(false);
    }

    private GuardConfig config(boolean fixture) {
        return new GuardConfig(
                report,
                root,
                "",
                List.of(),
                List.of(),
                root.resolve("model.json"),
                List.of(root.resolve("src")),
                List.of(),
                List.of(),
                List.of(),
                null,
                fixture,
                null);
    }

    /** The guards whose last run was a verdict over views the ledger sees. */
    private static final List<String> REPLAYABLE =
            List.of("reads-a", "lists-docs", "reads-model", "per-file", "closure");

    private @Nullable String reads(List<String> ids, LedgerReads.Mode mode) throws Exception {
        return LedgerReads.digest(config(false), ids, mode);
    }

    @Test
    void the_ledger_s_reads_hold_until_a_file_a_guard_read_changes() throws Exception {
        run();
        String recorded = reads(REPLAYABLE, LedgerReads.Mode.RECORDED);
        assertThat(recorded).isNotNull();
        assertThat(reads(REPLAYABLE, LedgerReads.Mode.NOW))
                .as("an untouched tree")
                .isEqualTo(recorded);

        Files.createDirectories(root.resolve("src/other"));
        Files.writeString(root.resolve("src/other/unread.txt"), "no guard lists or reads this\n");
        Files.writeString(root.resolve("src/k/one.txt"), "use key\nbad\n");
        assertThat(reads(REPLAYABLE, LedgerReads.Mode.NOW))
                .as("a file no guard read, and a read file written with the same text")
                .isEqualTo(recorded);

        Files.writeString(root.resolve("src/a.txt"), "fine\nbad\nbad again\n");
        assertThat(reads(REPLAYABLE, LedgerReads.Mode.NOW))
                .as("a file a guard read")
                .isNotEqualTo(recorded);
        assertThat(reads(List.of("lists-docs"), LedgerReads.Mode.NOW))
                .as("only the guard that read it moves")
                .isEqualTo(reads(List.of("lists-docs"), LedgerReads.Mode.RECORDED));

        run();
        String again = reads(REPLAYABLE, LedgerReads.Mode.RECORDED);
        assertThat(again).isNotEqualTo(recorded);
        Files.writeString(root.resolve("src/docs/two.md"), "# two\n");
        assertThat(reads(REPLAYABLE, LedgerReads.Mode.NOW))
                .as("a file added under a glob a guard listed")
                .isNotEqualTo(again);
        Files.writeString(root.resolve("src/k/three.txt"), "fine\n");
        Files.delete(root.resolve("src/docs/two.md"));
        assertThat(reads(List.of("per-file"), LedgerReads.Mode.NOW))
                .as("a file added to a per-file scan")
                .isNotEqualTo(reads(List.of("per-file"), LedgerReads.Mode.RECORDED));
    }

    @Test
    void a_ledger_with_a_guard_it_cannot_replay_stands_for_no_suite() throws Exception {
        assertThat(reads(REPLAYABLE, LedgerReads.Mode.NOW)).as("no run yet").isNull();
        run();
        List<String> withFacts = new ArrayList<>(REPLAYABLE);
        withFacts.add("reads-facts");
        assertThat(reads(withFacts, LedgerReads.Mode.NOW))
                .as("a guard taking Facts")
                .isNull();
        List<String> withThrow = new ArrayList<>(REPLAYABLE);
        withThrow.add("throws");
        assertThat(reads(withThrow, LedgerReads.Mode.NOW))
                .as("a guard that threw")
                .isNull();
        List<String> withNew = new ArrayList<>(REPLAYABLE);
        withNew.add("added-since");
        assertThat(reads(withNew, LedgerReads.Mode.NOW))
                .as("a guard the last run did not run")
                .isNull();
        assertThat(LedgerReads.digest(config(true), REPLAYABLE, LedgerReads.Mode.NOW))
                .as("a fixture run")
                .isNull();
    }

    private static int runs(String id) {
        AtomicInteger n = RUNS.get(id);
        return n == null ? 0 : n.get();
    }

    private static int scans(String guard, String path) {
        AtomicInteger n = SCANS.get(guard + " " + path);
        return n == null ? 0 : n.get();
    }

    private static boolean replayed(Map<String, Object> byId, String id) {
        return Boolean.TRUE.equals(MiniJson.get(byId.get(id), "replayed"));
    }

    @Test
    void a_guard_whose_reads_hold_replays_its_line_and_the_rest_run() throws Exception {
        Map<String, Object> first = run();
        assertThat(replayed(first, "reads-a")).isFalse();

        Map<String, Object> second = run();

        assertThat(runs("reads-a")).isEqualTo(1);
        assertThat(runs("lists-docs")).isEqualTo(1);
        assertThat(runs("reads-model")).isEqualTo(1);
        assertThat(replayed(second, "reads-a")).isTrue();
        assertThat(MiniJson.get(second.get("reads-a"), "violations"))
                .as("the replayed line carries the verdict of the run it repeats")
                .isEqualTo(MiniJson.get(first.get("reads-a"), "violations"));
        assertThat(runs("reads-facts")).as("a guard taking Facts always runs").isEqualTo(2);
        assertThat(runs("throws"))
                .as("a guard that threw is no verdict to repeat")
                .isEqualTo(2);
        assertThat(MiniJson.str(second.get("throws"), "outcome")).isEqualTo("threw");

        Map<String, Object> third = run();
        assertThat(runs("reads-a")).as("a replayed guard replays again").isEqualTo(1);
        assertThat(replayed(third, "reads-a")).isTrue();
    }

    @Test
    void an_edit_to_a_file_a_guard_read_runs_that_guard_only() throws Exception {
        run();
        Files.writeString(root.resolve("src/a.txt"), "fine\nbad\nbad again\n");

        Map<String, Object> after = run();

        assertThat(runs("reads-a")).isEqualTo(2);
        assertThat(replayed(after, "reads-a")).isFalse();
        assertThat((List<?>) MiniJson.get(after.get("reads-a"), "violations")).hasSize(2);
        assertThat(runs("lists-docs")).isEqualTo(1);
        assertThat(runs("reads-model")).isEqualTo(1);
    }

    @Test
    void a_file_added_under_a_listed_glob_runs_the_guard_that_listed_it() throws Exception {
        run();
        Files.writeString(root.resolve("src/docs/two.md"), "# two\n");

        Map<String, Object> after = run();

        assertThat(runs("lists-docs")).isEqualTo(2);
        assertThat(MiniJson.get(after.get("lists-docs"), "population")).isEqualTo(2L);
        assertThat(runs("reads-a")).isEqualTo(1);
    }

    @Test
    void a_changed_model_runs_the_guards_that_take_it() throws Exception {
        run();
        Files.writeString(root.resolve("model.json"), "{\"modules\":[\"\",\"lib\",\"app\"]}");

        run();

        assertThat(runs("reads-model")).isEqualTo(2);
        assertThat(runs("reads-a")).isEqualTo(1);
    }

    @Test
    void a_guard_that_runs_again_scans_only_the_files_that_changed() throws Exception {
        run();
        Files.writeString(root.resolve("src/k/two.txt"), "define key\nbad\n");

        Map<String, Object> after = run();

        assertThat(runs("per-file")).isEqualTo(2);
        assertThat(replayed(after, "per-file")).isFalse();
        assertThat(scans("per-file", "src/k/one.txt"))
                .as("an unedited file's findings come from the last run")
                .isEqualTo(1);
        assertThat(scans("per-file", "src/k/two.txt"))
                .as("the edited file is scanned again")
                .isEqualTo(2);
        assertThat((List<?>) MiniJson.get(after.get("per-file"), "violations"))
                .as("the verdict joins the kept findings with the new ones")
                .hasSize(2);

        run();
        assertThat(scans("per-file", "src/k/one.txt")).isEqualTo(1);
        assertThat(scans("per-file", "src/k/two.txt")).isEqualTo(2);
    }

    @Test
    void a_verdict_on_an_unedited_file_moves_with_the_file_it_depends_on() throws Exception {
        Map<String, Object> first = run();
        assertThat((List<?>) MiniJson.get(first.get("closure"), "violations")).isEmpty();
        Files.writeString(root.resolve("src/k/two.txt"), "fine\n");

        Map<String, Object> after = run();

        assertThat(runs("closure"))
                .as("the guard read the edited file, so it runs")
                .isEqualTo(2);
        assertThat(scans("closure", "src/k/one.txt")).isEqualTo(1);
        assertThat(String.valueOf(MiniJson.get(after.get("closure"), "violations")))
                .as("the unedited file that uses the key is flagged once its definition is gone")
                .contains("src/k/one.txt")
                .contains("which nothing defines");
    }

    @Test
    void a_file_added_to_a_per_file_scan_is_scanned_and_the_rest_are_not() throws Exception {
        run();
        Files.writeString(root.resolve("src/k/three.txt"), "use other\n");

        Map<String, Object> after = run();

        assertThat(scans("closure", "src/k/three.txt")).isEqualTo(1);
        assertThat(scans("closure", "src/k/one.txt")).isEqualTo(1);
        assertThat(String.valueOf(MiniJson.get(after.get("closure"), "violations")))
                .contains("uses other");
    }

    @Test
    void the_ledger_names_each_file_once_however_many_guards_read_it() throws Exception {
        run();
        run();
        String ledger = Files.readString(report.resolveSibling(ReadLedger.FILE));

        assertThat(ledger.split("\"src/k/one.txt\"", -1))
                .as("per-file and closure both scan it, in a run and a replay; a report line quotes it escaped")
                .hasSize(2);
        ReadLedger.Line closure = Objects.requireNonNull(
                ReadLedger.read(report.resolveSibling(ReadLedger.FILE)).get("closure"));
        assertThat(closure.files()).containsOnlyKeys("src/k/one.txt", "src/k/two.txt");
    }

    @Test
    void a_fixture_run_scans_every_file_every_time() throws Exception {
        run(true);
        run(true);

        assertThat(scans("per-file", "src/k/one.txt")).isEqualTo(2);
    }

    @Test
    void a_fixture_run_replays_nothing() throws Exception {
        run(true);
        run(true);

        assertThat(runs("reads-a")).isEqualTo(2);
        assertThat(runs("lists-docs")).isEqualTo(2);
    }

    @Test
    void the_stamp_moves_with_the_suite_s_classes_and_not_with_the_compile_s_stamp() throws Exception {
        Path classes = Files.createDirectories(root.resolve("classes/a"));
        Files.writeString(classes.resolve("Rules.class"), "v1");
        Files.writeString(root.resolve("classes").resolve(BuildStamps.JAVA), "STAMP_MILLIS 1\n");
        Path jar = root.resolve("lib.jar");
        Files.writeString(jar, "jar v1");
        List<Path> cp = List.of(root.resolve("classes"), jar);
        String before = ReadLedger.stamp(cp, "scope");

        Files.writeString(root.resolve("classes").resolve(BuildStamps.JAVA), "STAMP_MILLIS 2\n");
        assertThat(ReadLedger.stamp(cp, "scope"))
                .as("the compile's clock is no code")
                .isEqualTo(before);

        Files.writeString(classes.resolve("Rules.class"), "v2");
        String ruleEdited = ReadLedger.stamp(cp, "scope");
        assertThat(ruleEdited).as("a rule edit").isNotEqualTo(before);

        Files.writeString(jar, "jar v2");
        assertThat(ReadLedger.stamp(cp, "scope")).as("a library edit").isNotEqualTo(ruleEdited);
        assertThat(ReadLedger.stamp(cp, "other scope")).isNotEqualTo(ReadLedger.stamp(cp, "scope"));
    }

    @Test
    void a_class_an_earlier_entry_defines_is_not_code_the_stamp_covers() throws Exception {
        Path host = Files.createDirectories(root.resolve("host/cc/host"));
        Files.writeString(host.resolve("Os.class"), "host v1");
        Path store = Files.createDirectories(root.resolve("cas"));
        Path runner =
                jar(store.resolve("1.jar"), Map.of("cc/host/Os.class", "vendored v1", "cc/run/Main.class", "main v1"));
        String before = ReadLedger.stamp(List.of(root.resolve("host"), runner), "scope");

        Path moved =
                jar(store.resolve("2.jar"), Map.of("cc/host/Os.class", "vendored v2", "cc/run/Main.class", "main v1"));
        assertThat(ReadLedger.stamp(List.of(root.resolve("host"), moved), "scope"))
                .as("a vendored class the earlier entry shadows, in a jar at another path")
                .isEqualTo(before);

        Path main =
                jar(store.resolve("3.jar"), Map.of("cc/host/Os.class", "vendored v1", "cc/run/Main.class", "main v2"));
        assertThat(ReadLedger.stamp(List.of(root.resolve("host"), main), "scope"))
                .as("a runner class nothing shadows")
                .isNotEqualTo(before);

        Files.writeString(host.resolve("Os.class"), "host v2");
        assertThat(ReadLedger.stamp(List.of(root.resolve("host"), runner), "scope"))
                .as("the class the JVM loads")
                .isNotEqualTo(before);

        assertThat(ReadLedger.stamp(List.of(runner), "scope"))
                .as("the vendored class, with nothing ahead of it")
                .isNotEqualTo(ReadLedger.stamp(List.of(moved), "scope"));
    }

    @Test
    void a_vendored_class_the_jar_s_entry_points_never_reach_is_not_code_the_stamp_covers() throws Exception {
        Path host = Files.createDirectories(root.resolve("host/cc/host"));
        Files.writeString(host.resolve("Os.class"), "host");
        List<Path> cp = List.of(root.resolve("host"), runner("unused v1", "used v1", "provider v1"));
        String before = ReadLedger.stamp(cp, "scope");

        assertThat(ReadLedger.stamp(
                        List.of(root.resolve("host"), runner("unused v2", "used v1", "provider v1")), "scope"))
                .as("a class neither the Main-Class nor a service provider reaches")
                .isEqualTo(before);
        assertThat(ReadLedger.stamp(
                        List.of(root.resolve("host"), runner("unused v1", "used v2", "provider v1")), "scope"))
                .as("a class the Main-Class names in a descriptor")
                .isNotEqualTo(before);
        assertThat(ReadLedger.stamp(
                        List.of(root.resolve("host"), runner("unused v1", "used v1", "provider v2")), "scope"))
                .as("a service provider")
                .isNotEqualTo(before);
    }

    private int runners;

    /**
     * A runner jar vendoring {@code cc/host/Os}, whose {@code Main-Class} names {@code cc/sdk/Used} and
     * whose service provider is {@code cc.run.Provider}, beside {@code cc/sdk/Unused}; each body's text
     * is the given version.
     */
    private Path runner(String unused, String used, String provider) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>();
        entries.put(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nMain-Class: cc.run.Main\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/cc.run.Plugin", "cc.run.Provider\n".getBytes(StandardCharsets.UTF_8));
        entries.put("cc/host/Os.class", classNaming("cc/host/Os"));
        entries.put("cc/run/Main.class", classNaming("cc/run/Main", "(Lcc/sdk/Used;)V"));
        entries.put("cc/run/Provider.class", classNaming("cc/run/Provider", provider));
        entries.put("cc/sdk/Used.class", classNaming("cc/sdk/Used", used));
        entries.put("cc/sdk/Unused.class", classNaming("cc/sdk/Unused", unused));
        return jar(Files.createDirectories(root.resolve("cas")).resolve(++runners + ".jar"), entries);
    }

    /** A class file's head whose constant pool holds {@code strings} as UTF-8 entries, which is all the stamp reads. */
    private static byte[] classNaming(String... strings) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0xCAFEBABE);
            out.writeInt(69);
            out.writeShort(strings.length + 1);
            for (String s : strings) {
                out.writeByte(1);
                out.writeUTF(s);
            }
        }
        return bytes.toByteArray();
    }

    private static Path jar(Path file, Map<String, ?> entries) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : new TreeMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(
                        e.getValue() instanceof byte[] b
                                ? b
                                : String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
