// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceGroup;
import cc.jumpkick.lock.ManifestPaths;
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

    /**
     * The guide between the markers, LF-terminated, for a project that does not enforce formatting:
     * a "to do X, call Y" table, the MCP tool first and the CLI as fallback. Its MCP column names
     * only tools on the default {@code tools/list}, with arguments and kinds the server accepts;
     * {@code McpSteeringTest} holds it to the registry.
     */
    public static final String SECTION = """
            ## JumpKick

            This project builds with **JumpKick** (`jk`), not Maven or Gradle: `jk` MCP tools first, the CLI as
            fallback. jk rewrites this section; put project rules outside the markers.

            | To | MCP | CLI |
            |---|---|---|
            | test, fix what the verdict names, rerun | `run()` | `jk -a test` |
            | one class / one suite | `run(classes=[C])` / `run(suites=[s])` | `-S C` / `-s s` |
            | build | `run(kind=build)` | `jk -a build` |
            | every error of the last run | `diagnostics()` | `jk results -A` |
            | add / remove / pin a dependency | `deps` | `jk add` / `jk remove` |
            | who uses `T.m`, `T.f`, `T`, `column:t.c` / rename or move it | `callers` / `rename` | `jk graph callers\\|rename` |
            | what a build would run | | `jk explain` |
            | finish: tests + guards | `run(kind=gate)` | `jk -a test -g` |
            | anything else | `skill(topic=…)` | `jk skill <topic>` |

            - Done when the gate is OK; `· up to date` means nothing was left to run. A `W` saying an input
              moved: rerun. A guard rule id: `jk guard explain <id>`.
            - `callers` and `rename` answers are compiled facts and final: do not grep, re-read or re-test to check;
              apply the uses `rename` lists with its `also`.
            - Never add `pom.xml` or Gradle files, run `mvn` or `./gradlew`, or edit `jk-guards-baseline.toml`.
            - `node = N` in `jk.toml` pins Node.js: `jk node exec -- <cmd>`, never a host `node` or `npm`.
            """;

    /**
     * {@link #SECTION} for a project whose {@code [format] enforce} has the gate format what changed.
     * A project without it keeps its own formatting: neither section asks for {@code jk format}.
     */
    public static final String ENFORCED_SECTION =
            SECTION.replace("| finish: tests + guards |", "| finish: format, tests + guards |");

    /**
     * The guide at a group root ({@link WorkspaceGroup}): the members are self-contained
     * workspaces, each with its own guide, and the root's verbs run over them. CLI only: an agent
     * does its work in a member, whose own section names the MCP tools.
     */
    public static final String GROUP_SECTION = """
            ## JumpKick

            This repository groups self-contained **JumpKick** (`jk`) workspaces: `[workspaces] members` in
            `jk.toml` lists them. jk rewrites this section; put repository rules outside the markers.

            - Work inside one member (`cd <member>`): its own `AGENTS.md` and `jk.toml` apply, and it builds
              the same with or without this root.
            - From here `jk build`, `jk test`, `jk install`, `jk guard`, `jk format`, `jk explain` and
              `jk clean` run over every member in dependency order; `-m <member>` runs one and what it
              depends on. A member left out by default prints why; `-p <profile>` adds it back.
            - `jk release` and `jk publish` run in the member being released; its tag is
              `<artifact>-v<version>`.
            - Never add `pom.xml` or Gradle files, or run `mvn` or `./gradlew`.
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
        return block(false);
    }

    /** As {@link #block()}, for a project whose {@code [format] enforce} is {@code enforce}. */
    public static String block(boolean enforce) {
        return BEGIN + "\n" + (enforce ? ENFORCED_SECTION : SECTION) + END + "\n";
    }

    /** A new {@code AGENTS.md}: a title and jk's section. */
    public static String newFile() {
        return newFile(false);
    }

    /** As {@link #newFile()}, for a project whose {@code [format] enforce} is {@code enforce}. */
    public static String newFile(boolean enforce) {
        return "# AGENTS.md\n\n" + block(enforce);
    }

    /** Whether {@code dir}'s {@code jk.toml} enforces formatting; {@code false} without one that parses. */
    static boolean enforces(Path dir) {
        Path manifest = ManifestPaths.manifestIn(dir);
        if (!Files.isRegularFile(manifest)) return false;
        try {
            return JkBuildParser.parse(manifest).format().enforce();
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * Create {@code dir/AGENTS.md}, insert jk's section into it, or bring that section up to date.
     *
     * @throws IOException on a read or write failure, or when the file holds a {@link #BEGIN} with no
     *     {@link #END} after it (or either marker twice), which jk will not guess at
     */
    public static Change sync(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        String block = blockFor(dir);
        if (!Files.exists(file)) {
            Files.writeString(file, "# AGENTS.md\n\n" + block, StandardCharsets.UTF_8);
            return Change.CREATED;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String updated = apply(text, file, block);
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
            updated = apply(text, file, blockFor(dir));
        } catch (IOException malformed) {
            return Change.UNCHANGED;
        }
        if (updated.equals(text)) return Change.UNCHANGED;
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return Change.UPDATED;
    }

    /**
     * Remove jk's section from {@code dir/AGENTS.md}; the file goes too when only its title is left.
     * Absent or unpaired markers leave the file alone.
     */
    public static Change remove(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) return Change.ABSENT;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int begin = text.indexOf(BEGIN);
        int end = text.indexOf(END, Math.max(begin, 0));
        if (begin < 0 || end < 0) return Change.ABSENT;
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        int after = end + END.length();
        if (text.startsWith(eol, after)) after += eol.length();
        if (text.startsWith(eol, after)) after += eol.length();
        String rest = (text.substring(0, begin) + text.substring(after)).strip();
        if (rest.isEmpty() || rest.equals("# " + FILE)) Files.delete(file);
        else Files.writeString(file, rest + eol, StandardCharsets.UTF_8);
        return Change.UPDATED;
    }

    /** jk's section with its markers for the project or group root at {@code dir}. */
    static String blockFor(Path dir) {
        if (WorkspaceGroup.declares(dir)) return BEGIN + "\n" + GROUP_SECTION + END + "\n";
        return block(enforces(dir));
    }

    /** {@code text} with jk's section current: replaced between the markers, or inserted. */
    static String apply(String text, Path file, boolean enforce) throws IOException {
        return apply(text, file, block(enforce));
    }

    /** {@code text} with {@code lfBlock} (jk's section, markers and all) current. */
    static String apply(String text, Path file, String lfBlock) throws IOException {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String block = lfBlock.replace("\n", eol);
        int begin = text.indexOf(BEGIN);
        if (begin < 0) {
            if (text.contains(END)) throw malformed(file, "has " + END + " with no " + BEGIN);
            return insert(text, block, eol);
        }
        int end = text.indexOf(END, begin);
        if (end < 0) throw malformed(file, "has " + BEGIN + " with no " + END + " after it");
        if (text.indexOf(BEGIN, begin + 1) >= 0 || text.indexOf(END, end + 1) >= 0) {
            throw malformed(file, "has JumpKick's markers more than once");
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
        return new IOException(file + " " + what + "; fix the markers by hand (JumpKick owns only the text"
                + " between " + BEGIN + " and " + END + ")");
    }
}
