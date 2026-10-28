// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * jk's section of a project's {@code AGENTS.md}: the text between {@link #BEGIN} and {@link #END}
 * belongs to jk and is rewritten to this release's guide; everything outside the markers belongs to
 * the project and is never touched. Line endings follow the file's own (CRLF when it has any).
 */
public final class AgentsGuide {

    public static final String FILE = "AGENTS.md";
    public static final String BEGIN = "<!-- BEGIN:jumpkick -->";
    public static final String END = "<!-- END:jumpkick -->";

    /** The guide between the markers, LF-terminated. */
    public static final String SECTION = """
            ## JumpKick

            This project builds with **JumpKick** (`jk`), not Maven or Gradle. `jk skill <topic>` (MCP
            `skill`) has details when you need them. jk rewrites this section; put project rules outside
            the markers.

            - Loop: `jk -a test` (MCP `run`) → read the verdict → edit → rerun. The verdict names each
              error's file and line with its source line; act on it rather than the terminal or
              `target/jk-results.md`. `jk -a build` (`run(kind=build)`) also packages.
            - Before handing work back: `jk format`, then `jk guard`.
            - `jk add g:a` / `jk remove g:a` (MCP `deps`) edit `jk.toml` and relock `jk-lock.toml`, which
              is committed.
            - Never add `pom.xml` or Gradle files, or run `mvn` or `./gradlew`.
            - Never edit `jk-guards-baseline.toml`; `jk guard explain <id>` says what to do instead.
            - `node = N` in `jk.toml` pins Node.js: use `jk node exec -- <cmd>`, never a host `node` or `npm`.
            """;

    /** What a sync did to the file. */
    public enum Change {
        /** No {@code AGENTS.md}: written with a heading and jk's section. */
        CREATED,
        /** An {@code AGENTS.md} without jk's section: the section went in below its title. */
        INSERTED,
        /** jk's section differed from this release's and was rewritten. */
        UPDATED,
        /** jk's section already matched. */
        UNCHANGED,
        /** {@link #refresh} only: no file, or a file without jk's section; left alone. */
        ABSENT
    }

    private AgentsGuide() {}

    /** jk's section with its markers, LF-terminated. */
    public static String block() {
        return BEGIN + "\n" + SECTION + END + "\n";
    }

    /** A new {@code AGENTS.md}: a title and jk's section. */
    public static String newFile() {
        return "# AGENTS.md\n\n" + block();
    }

    /**
     * Create {@code dir/AGENTS.md}, insert jk's section into it, or bring that section up to date.
     *
     * @throws IOException on a read or write failure, or when the file holds a {@link #BEGIN} with no
     *     {@link #END} after it (or either marker twice), which jk will not guess at
     */
    public static Change sync(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            Files.writeString(file, newFile(), StandardCharsets.UTF_8);
            return Change.CREATED;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String updated = apply(text, file);
        if (updated.equals(text)) return Change.UNCHANGED;
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return text.contains(BEGIN) ? Change.UPDATED : Change.INSERTED;
    }

    /**
     * Rewrite jk's section of {@code dir/AGENTS.md} when the file already has one and it is stale. A
     * file without the section, or with malformed markers, is left alone: a build never adds the
     * section uninvited and never fails over it.
     */
    public static Change refresh(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return Change.ABSENT;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.contains(BEGIN)) return Change.ABSENT;
        String updated;
        try {
            updated = apply(text, file);
        } catch (IOException malformed) {
            return Change.UNCHANGED;
        }
        if (updated.equals(text)) return Change.UNCHANGED;
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return Change.UPDATED;
    }

    /** {@code text} with jk's section current: replaced between the markers, or inserted. */
    static String apply(String text, Path file) throws IOException {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String block = block().replace("\n", eol);
        int begin = text.indexOf(BEGIN);
        if (begin < 0) {
            if (text.contains(END)) throw malformed(file, "has " + END + " with no " + BEGIN);
            return insert(text, block, eol);
        }
        int end = text.indexOf(END, begin);
        if (end < 0) throw malformed(file, "has " + BEGIN + " with no " + END + " after it");
        if (text.indexOf(BEGIN, begin + 1) >= 0 || text.indexOf(END, end + 1) >= 0) {
            throw malformed(file, "has jk's markers more than once");
        }
        int after = end + END.length();
        if (text.startsWith(eol, after)) after += eol.length();
        return text.substring(0, begin) + block + text.substring(after);
    }

    /** Below a leading {@code # } title (and the blank line after it), else at the top. */
    private static String insert(String text, String block, String eol) {
        if (text.startsWith("# ")) {
            int lineEnd = text.indexOf(eol);
            if (lineEnd < 0) return text + eol + eol + block;
            String head = text.substring(0, lineEnd + eol.length());
            String rest = text.substring(lineEnd + eol.length());
            while (rest.startsWith(eol)) rest = rest.substring(eol.length());
            return head + eol + block + (rest.isEmpty() ? "" : eol + rest);
        }
        return block + (text.isEmpty() ? "" : eol + text);
    }

    private static IOException malformed(Path file, String what) {
        return new IOException(file + " " + what + "; fix the markers by hand (jk owns only the text" + " between "
                + BEGIN + " and " + END + ")");
    }
}
