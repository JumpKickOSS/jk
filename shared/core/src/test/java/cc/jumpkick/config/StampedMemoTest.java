// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link StampedMemo} itself, and the fold: all five config memos get their staleness rule from this
 * one type, so a change here moves all of them.
 *
 * <p>The table is written so that each memo's <em>declared</em> stamp is observable, and so that
 * breaking the owner cannot leave it green. Every row is read three times against one file:
 *
 * <ol>
 *   <li>fresh — the value must come back;
 *   <li>rewritten to the <em>same byte length</em> with the mtime restored — a {@code FileStamp} memo
 *       must still serve the first value (it did not re-read), a body-stamped memo must see the new
 *       one (it did);
 *   <li>rewritten with the mtime moved — every memo must revalidate.
 * </ol>
 *
 * <p>Arm 2 fails for every row if {@link StampedMemo#get} stops memoizing; arm 3 fails for every row
 * if it stops comparing stamps. Neither can be satisfied by a memo that is not there.
 */
class StampedMemoTest {

    // ---- the owner ---------------------------------------------------------

    @Test
    void serves_a_matching_stamp_and_recomputes_a_moved_one() {
        StampedMemo<String, Integer, String> memo = StampedMemo.create();
        AtomicInteger computes = new AtomicInteger();

        assertThat(memo.get("k", 1, () -> "v" + computes.incrementAndGet())).isEqualTo("v1");
        assertThat(memo.get("k", 1, () -> "v" + computes.incrementAndGet())).isEqualTo("v1");
        assertThat(computes).hasValue(1);

        assertThat(memo.get("k", 2, () -> "v" + computes.incrementAndGet())).isEqualTo("v2");
        assertThat(computes).hasValue(2);
        // A rewrite replaces the entry rather than adding one: the map is bounded by distinct keys.
        assertThat(memo.size()).isEqualTo(1);

        memo.forget("k");
        assertThat(memo.get("k", 2, () -> "v" + computes.incrementAndGet())).isEqualTo("v3");
        memo.clear();
        assertThat(memo.size()).isZero();
    }

    @Test
    void a_file_stamp_moves_with_size_or_mtime(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("x.toml");
        Files.writeString(f, "aaa");
        StampedMemo.FileStamp first = Objects.requireNonNull(StampedMemo.FileStamp.of(f), "stamp of an existing file");

        Files.writeString(f, "bbb");
        Files.setLastModifiedTime(f, first.modified());
        assertThat(StampedMemo.FileStamp.of(f)).isEqualTo(first); // same size, same mtime

        Files.writeString(f, "bbbb");
        Files.setLastModifiedTime(f, first.modified());
        assertThat(StampedMemo.FileStamp.of(f)).isNotNull().isNotEqualTo(first); // size moved

        Files.writeString(f, "aaa");
        Files.setLastModifiedTime(f, FileTime.fromMillis(first.modified().toMillis() + 5_000));
        assertThat(StampedMemo.FileStamp.of(f)).isNotNull().isNotEqualTo(first); // mtime moved

        assertThat(StampedMemo.FileStamp.of(dir.resolve("nope"))).isNull();
        assertThat(StampedMemo.FileStamp.of(null)).isNull();
    }

    // ---- the fold ----------------------------------------------------------

    /** How a memo decides its entry is stale. Two rules, and each one is a stated policy. */
    enum Stamp {
        /** Size + mtime: one stat in front of a parse, for files jk only ever reads. */
        FILE,
        /** The file's own bytes: {@code jk.toml}, where a same-length edit in one tick is real. */
        BODY
    }

    /**
     * One memoized reader. {@code body} must produce the same byte length for every marker, so arm 2
     * can rewrite the file without moving a {@link Stamp#FILE} stamp.
     */
    record Memo(String name, String fileName, Stamp stamp, Body body, Read read) {
        @Override
        public String toString() {
            return name;
        }
    }

    interface Body {
        String of(String marker);
    }

    interface Read {
        String of(Path file) throws Exception;
    }

    /** Each row's marker is the last path segment of what it reads, so the arms compare markers. */
    private static String lastSegment(String s) {
        return s.substring(s.lastIndexOf('/') + 1);
    }

    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static List<Memo> memos() {
        return List.of(
                new Memo(
                        "GlobalConfig SCAN_CACHE",
                        "config.toml",
                        Stamp.FILE,
                        m -> "[toolchain]\njdk = \"" + m + "\"\n",
                        f -> GlobalConfig.engineJdkPin(f, null).orElse("")),
                new Memo(
                        "GlobalConfig CONFIG_CACHE",
                        "config.toml",
                        Stamp.FILE,
                        m -> "[repositories]\nacme = \"https://example.invalid/" + m + "\"\n",
                        f -> lastSegment(
                                GlobalConfig.repositories(f).getFirst().url().toString())),
                new Memo(
                        "UserPlugins CACHE",
                        "config.toml",
                        Stamp.FILE,
                        m -> "[plugins]\nspring-boot = { path = \"/tmp/" + m + ".jar\", sha256 = \"" + SHA + "\" }\n",
                        f -> lastSegment(Objects.requireNonNull(
                                        UserPlugins.fromConfig(f).getFirst().path()))
                                .replace(".jar", "")),
                new Memo(
                        "JkBuildParser PARSE_CACHE",
                        "jk.toml",
                        Stamp.BODY,
                        m -> "group = \"com.example\"\nname = \"" + m + "\"\nversion = \"1.0.0\"\n",
                        f -> JkBuildParser.parseLocal(f).project().name()),
                new Memo(
                        "JkBuildParser DOC_CACHE",
                        "jk.toml",
                        Stamp.BODY,
                        m -> "group = \"com.example\"\nname = \"widget\"\nversion = \"1.0.0\"\n"
                                + "[test]\ninclude-tags = [\"" + m + "\"]\n",
                        f -> JkBuildParser.parseTestTags(f).includeTags().getFirst()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memos")
    void each_memo_serves_its_own_stamp_and_revalidates_when_it_moves(Memo memo, @TempDir Path dir) throws Exception {
        Path file = dir.resolve(memo.fileName());

        // 1. fresh read
        Files.writeString(file, memo.body().of("AAA"));
        FileTime pinned = Files.getLastModifiedTime(file);
        assertThat(memo.read().of(file)).isEqualTo("AAA");

        // 2. same size, same mtime, different bytes — the memo's declared stamp decides.
        Files.writeString(file, memo.body().of("BBB"));
        Files.setLastModifiedTime(file, pinned);
        assertThat(Files.size(file)).isEqualTo(memo.body().of("AAA").length());
        assertThat(memo.read().of(file)).isEqualTo(memo.stamp() == Stamp.FILE ? "AAA" : "BBB");

        // 3. mtime moved — every memo revalidates, whatever its stamp.
        Files.writeString(file, memo.body().of("CCC"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(pinned.toMillis() + 5_000));
        assertThat(memo.read().of(file)).isEqualTo("CCC");
    }

    /**
     * The table's own size. Five memos, both stamp rules represented — if a row stops running, or a
     * sixth memo is hand-rolled beside these, this is what notices.
     */
    @Test
    void the_table_covers_all_five_memos() {
        assertThat(memos()).hasSize(5);
        assertThat(memos().stream().filter(m -> m.stamp() == Stamp.FILE)).hasSize(3);
        assertThat(memos().stream().filter(m -> m.stamp() == Stamp.BODY)).hasSize(2);
    }
}
