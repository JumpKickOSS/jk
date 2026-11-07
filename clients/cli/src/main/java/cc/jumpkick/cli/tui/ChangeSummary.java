// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.PathDisplay;
import java.nio.file.Path;
import java.util.List;

/**
 * The body a file-writing command prints above its one settle wedge: the files it changed as a tree
 * under the project's name, then its numbered notes.
 *
 * <pre>
 *  ● my-app
 *  ├─ Wrote jk.toml
 *  ╰─ Wrote core/jk.toml
 *
 * Import notes: 2 issues
 *  1) …
 *  2) …
 * </pre>
 */
public final class ChangeSummary {

    /** One file changed; {@code action} is the verb read before its path. */
    public record Change(String action, Path file) {}

    private ChangeSummary() {}

    /** The tree of {@code changes} under {@code project}, paths shown relative to {@code base}; nothing when empty. */
    public static void printChanges(String project, List<Change> changes, Path base) {
        if (changes.isEmpty()) return;
        Tree.Node root = Tree.node(
                RichText.parse("[bold bright-cyan]" + RichText.escape(Glyphs.pulse() + " " + project) + "[/]"));
        for (Change change : changes) {
            root.child(Tree.node(RichText.plain(change.action() + " ")
                    .plus(RichText.styled(PathDisplay.of(change.file(), base), "yellow"))));
        }
        Tree.untitled().gap(Tree.Gap.NONE).root(root).print();
    }

    /**
     * {@code <title>: N <noun>s} then each note numbered; nothing when there are none. A blank line
     * sets the block apart from what printed before it.
     */
    public static void printNotes(String title, String noun, List<String> notes) {
        if (notes.isEmpty()) return;
        CommandWedge.envelopeStart();
        CliOutput.out("");
        CliOutput.out(title + ": " + count(notes.size(), noun));
        for (int i = 0; i < notes.size(); i++) CliOutput.out(" " + (i + 1) + ") " + notes.get(i));
    }

    /** The name a project directory is shown by: its last segment. */
    public static String name(Path dir) {
        Path name = dir.toAbsolutePath().normalize().getFileName();
        return name == null ? dir.toString() : name.toString();
    }

    /** {@code 1 issue}, {@code 2 issues}. */
    public static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
