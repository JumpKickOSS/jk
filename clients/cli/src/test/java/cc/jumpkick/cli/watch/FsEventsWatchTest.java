// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.watch.DirWatch.Change;
import cc.jumpkick.cli.watch.FsEventsWatch.Raw;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The macOS watch: FSEvents' changes as the loop reads them, and what the native image must register for it. */
class FsEventsWatchTest {

    /** Far below the 2 s the JDK's macOS watch service polls at, with room for a loaded host. */
    private static final long PROMPT_MILLIS = 500;

    @Test
    @EnabledOnOs(OS.MAC)
    void the_host_watch_on_macos_is_fsevents(@TempDir Path tmp) throws IOException {
        try (DirWatch watch = DirWatch.open(List.of(tmp, tmp.resolve("not-made-yet")))) {
            assertThat(watch).isInstanceOf(FsEventsWatch.class);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void a_volume_is_local_by_its_mount_flags(@TempDir Path tmp) throws IOException {
        assertThat(FsEventsWatch.local(tmp)).isTrue();
        assertThat(FsEventsWatch.local(tmp.resolve("not/made/yet")))
                .as("the nearest directory that exists")
                .isTrue();
        // macOS mounts the automounter's home map here, a volume that is not local.
        Path autofs = Path.of("/System/Volumes/Data/home");
        assumeTrue(
                Files.isDirectory(autofs) && Files.getFileStore(autofs).type().equals("autofs"), "no autofs mount");
        assertThat(FsEventsWatch.local(autofs)).isFalse();
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void each_save_reaches_the_watch_promptly(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        try (DirWatch watch = DirWatch.open(List.of(dir))) {
            watch.register(dir);
            Path file = dir.resolve("Main.java");
            for (int i = 0; i < 3; i++) {
                Files.writeString(file, "class Main {} // " + i + "\n");
                long start = System.nanoTime();
                assertThat(changesTo(watch, file, TimeUnit.MILLISECONDS.toNanos(PROMPT_MILLIS)))
                        .as("save %d within %d ms", i, PROMPT_MILLIS)
                        .isNotEmpty();
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                        .isLessThan(PROMPT_MILLIS);
                Thread.sleep(50);
                while (watch.poll(0) != null) {
                    // Whatever else the save raised.
                }
            }
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void a_change_names_the_directory_as_registered_and_its_kind(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        Path file = Files.writeString(dir.resolve("A.java"), "class A {}\n");
        try (FsEventsWatch watch = Objects.requireNonNull(FsEventsWatch.open())) {
            watch.register(dir);
            Path made = Files.createDirectories(dir.resolve("pkg"));
            assertThat(kindsOf(watch, made)).contains(StandardWatchEventKinds.ENTRY_CREATE);
            Files.delete(file);
            assertThat(kindsOf(watch, file)).contains(StandardWatchEventKinds.ENTRY_DELETE);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void a_change_in_a_directory_not_registered_is_not_reported(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Path target = Files.createDirectories(module.resolve("target/classes"));
        try (FsEventsWatch watch = Objects.requireNonNull(FsEventsWatch.open())) {
            watch.register(module);
            Files.writeString(target.resolve("Main.class"), "x");
            Path marker = Files.writeString(module.resolve("jk.toml"), "name = \"app\"\n");
            List<Change> seen = changesTo(watch, marker, TimeUnit.SECONDS.toNanos(10));
            assertThat(seen).isNotEmpty();
            assertThat(seen).allSatisfy(c -> assertThat(c.dir()).isEqualTo(module));
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void a_registered_directory_deleted_is_no_longer_watched(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src/pkg"));
        try (FsEventsWatch watch = Objects.requireNonNull(FsEventsWatch.open())) {
            watch.register(tmp.resolve("src"));
            watch.register(dir);
            assertThat(watch.watches(dir)).isTrue();
            Files.delete(dir);
            assertThat(kindsOf(watch, dir)).contains(StandardWatchEventKinds.ENTRY_DELETE);
            assertThat(watch.watches(dir)).isFalse();
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void dropped_events_or_a_rescan_over_a_watched_directory_overflow(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app/src"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        try (FsEventsWatch watch = Objects.requireNonNull(FsEventsWatch.open())) {
            watch.register(dir);
            Path real = dir.toRealPath();
            assertThat(watch.changes(List.of(new Raw("/", FsEventsWatch.KERNEL_DROPPED))))
                    .containsExactly(Change.OVERFLOW);
            assertThat(watch.changes(List.of(new Raw("/", FsEventsWatch.USER_DROPPED))))
                    .containsExactly(Change.OVERFLOW);
            assertThat(watch.changes(List.of(new Raw(
                            Objects.requireNonNull(real.getParent()).toString(), FsEventsWatch.MUST_SCAN_SUB_DIRS))))
                    .as("a rescan of the tree that holds the watched directory")
                    .containsExactly(Change.OVERFLOW);
            assertThat(watch.changes(List.of(new Raw(other.toRealPath().toString(), FsEventsWatch.MUST_SCAN_SUB_DIRS))))
                    .as("a rescan of a tree nothing watched is in")
                    .isEmpty();
        }
    }

    /** A file's flags carry over between events: an existing file flagged created is still reported, a watched directory is not news. */
    @Test
    @EnabledOnOs(OS.MAC)
    void the_kind_is_read_from_the_disk(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("src"));
        Path sub = Files.createDirectories(dir.resolve("pkg"));
        Path file = Files.writeString(dir.resolve("A.java"), "class A {}\n");
        int coalesced = FsEventsWatch.ITEM_CREATED | FsEventsWatch.ITEM_MODIFIED;
        try (FsEventsWatch watch = Objects.requireNonNull(FsEventsWatch.open())) {
            watch.register(dir);
            watch.register(sub);
            Files.writeString(file, "class A { int a; }\n");
            Files.writeString(sub.resolve("B.java"), "class B {}\n");
            assertThat(watch.changes(List.of(raw(file, coalesced))))
                    .containsExactly(new Change(StandardWatchEventKinds.ENTRY_CREATE, dir, dir.resolve("A.java")));
            assertThat(watch.changes(List.of(raw(sub, coalesced))))
                    .containsExactly(new Change(StandardWatchEventKinds.ENTRY_MODIFY, dir, sub));
            Path gone = dir.toRealPath().resolve("C.java");
            assertThat(watch.changes(List.of(new Raw(gone.toString(), coalesced))))
                    .containsExactly(new Change(StandardWatchEventKinds.ENTRY_DELETE, dir, dir.resolve("C.java")));
        }
    }

    /**
     * A stream delivers what happened just before it started, and two overlap while one replaces the
     * other: an entry unchanged since its directory was registered, or since its last change, is not
     * reported again.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void an_entry_unchanged_since_it_was_last_seen_is_not_reported(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Path manifest = Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n");
        try (FsEventsWatch watch = Objects.requireNonNull(FsEventsWatch.open())) {
            watch.register(dir);
            assertThat(watch.changes(List.of(raw(manifest, FsEventsWatch.ITEM_MODIFIED))))
                    .as("written before the directory was registered")
                    .isEmpty();
            Files.writeString(manifest, "name = \"app2\"\n");
            assertThat(watch.changes(List.of(raw(manifest, FsEventsWatch.ITEM_MODIFIED))))
                    .as("written after")
                    .containsExactly(new Change(StandardWatchEventKinds.ENTRY_MODIFY, dir, manifest));
            assertThat(watch.changes(List.of(raw(manifest, FsEventsWatch.ITEM_MODIFIED))))
                    .as("the same change delivered again")
                    .isEmpty();
        }
    }

    private static Raw raw(Path path, int flags) throws IOException {
        return new Raw(path.toRealPath().toString(), flags);
    }

    @Test
    void the_native_image_registers_every_call_the_watch_makes() throws IOException {
        String json = readMetadata();
        int upcalls = json.indexOf("\"upcalls\"");
        assertThat(upcalls).as("an upcalls section").isGreaterThan(json.indexOf("\"downcalls\""));
        List<String> downcalls = keys(json.substring(json.indexOf("\"downcalls\""), upcalls));
        assertThat(downcalls)
                .contains(
                        key(FsEventsWatch.STRING_CREATE),
                        key(FsEventsWatch.ARRAY_CREATE),
                        key(FsEventsWatch.STREAM_CREATE),
                        key(FsEventsWatch.SET_QUEUE),
                        key(FsEventsWatch.START),
                        key(FsEventsWatch.RELEASE),
                        key(FsEventsWatch.QUEUE_CREATE),
                        key(FsEventsWatch.STATFS));
        assertThat(keys(json.substring(upcalls))).contains(key(FsEventsWatch.CALLBACK));
    }

    private static List<Change> changesTo(DirWatch watch, Path entry, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        List<Change> found = new ArrayList<>();
        while (found.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            List<Change> batch = watch.poll(remaining);
            if (batch == null) break;
            for (Change c : batch) {
                if (entry.equals(c.entry())) found.add(c);
            }
        }
        return found;
    }

    private static List<Object> kindsOf(DirWatch watch, Path entry) throws InterruptedException {
        return changesTo(watch, entry, TimeUnit.SECONDS.toNanos(10)).stream()
                .map(Change::kind)
                .collect(Collectors.toList());
    }

    private static final Pattern CALL = Pattern.compile(
            "\"returnType\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"parameterTypes\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);

    private static List<String> keys(String json) {
        Matcher m = CALL.matcher(json);
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1) + "|"
                    + m.group(2).replace("\"", "").replaceAll("\\s+", " ").trim());
        }
        return keys;
    }

    private static String key(FunctionDescriptor fd) {
        String ret = fd.returnLayout().map(FsEventsWatchTest::type).orElse("void");
        return ret + "|"
                + fd.argumentLayouts().stream().map(FsEventsWatchTest::type).collect(Collectors.joining(", "));
    }

    private static String type(MemoryLayout layout) {
        if (layout instanceof ValueLayout.OfInt) return "jint";
        if (layout instanceof ValueLayout.OfLong) return "jlong";
        if (layout instanceof ValueLayout.OfDouble) return "jdouble";
        if (layout instanceof ValueLayout.OfBoolean) return "jboolean";
        if (layout instanceof AddressLayout) return "void*";
        throw new AssertionError(layout);
    }

    private static String readMetadata() throws IOException {
        String path = "/META-INF/native-image/cc.jumpkick/cli/reachability-metadata.json";
        try (InputStream in = FsEventsWatchTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
