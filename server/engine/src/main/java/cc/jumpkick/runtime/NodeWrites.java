// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.task.ActionCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The files a module's node steps wrote outside every declared output, and the keys that must not
 * read them. A node step that names no inputs reads the node tree less {@code out} and the steps'
 * outputs; a tool that writes beside {@code out} (Angular's {@code 3rdpartylicenses.txt}, Next.js's
 * {@code next-env.d.ts}) would otherwise move the key of the very step that wrote it, and of a step
 * running beside it, so the next build of an unchanged tree runs them again.
 *
 * <p>A file that was absent when a step started and is there when it ends was written by the build:
 * its path joins the ledger under the module's {@code target/}, and every node key leaves it out from
 * then on ({@link NodeKeys#scoped}), the forecast's included. A record already stored this build
 * under a key that read such a file is stored again under the key the next build computes, so a step
 * that keyed the tree while another step was still writing it hits too. A file that existed and
 * changed while the step ran is a source that moved: the step stores nothing, as a declared step
 * always has.
 *
 * <p>One per plan's node build; the steps of one build call it from their own threads.
 */
final class NodeWrites {

    private final Path moduleDir;
    private final Path nodeDir;
    private final ActionCache cache;
    private final List<Stored> stored = new ArrayList<>();
    private final Set<String> aliased = new TreeSet<>();

    /** A step's key, taken again: the walk the next build makes, through the step's own key owner. */
    @FunctionalInterface
    interface Rekey {
        NodeKeys.Keyed key() throws IOException;
    }

    /** One record this build stored: the key it was stored under and how to take that key again. */
    private record Stored(NodeKeys.Keyed keyed, Rekey rekey) {}

    /** How a step's inputs moved while it ran: the files it created, and the ones that changed or went. */
    record Moves(Set<String> created, List<Path> moved) {}

    NodeWrites(Path moduleDir, Path nodeDir, ActionCache cache) {
        this.moduleDir = moduleDir;
        this.nodeDir = nodeDir;
        this.cache = cache;
    }

    /**
     * What moved between the inputs a step was keyed on and the same inputs read when it ended: the
     * files that appeared, and the ones that changed or disappeared.
     */
    static Moves moves(Map<String, String> before, Map<String, String> after) {
        Set<String> created = new TreeSet<>();
        List<Path> moved = new ArrayList<>();
        Set<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            String was = before.get(name);
            String now = after.get(name);
            if (Objects.equals(was, now)) continue;
            String file = NodeKeys.filePath(name);
            if (was == null && file != null) created.add(file);
            else moved.add(Path.of(file == null ? name : file));
        }
        return new Moves(created, moved);
    }

    /**
     * A step keyed {@code before} has ended and its inputs read again are {@code after}: the files
     * it created join the ledger, and what else moved is returned for the step to refuse its store
     * on. Both sides are read without the ledger as it stands now, since a step running beside this
     * one may have ledgered a file this one's first walk still saw.
     */
    synchronized Moves settle(NodeKeys.Keyed before, NodeKeys.Keyed after) throws IOException {
        Set<String> ledger = NodeKeys.written(moduleDir);
        Moves m = moves(
                NodeKeys.withoutWritten(before.inputs(), ledger), NodeKeys.withoutWritten(after.inputs(), ledger));
        wrote(m.created());
        return m;
    }

    /**
     * A step ended having written {@code created}: they join the ledger, and every record this build
     * stored that read one is stored again without it.
     */
    private void wrote(Set<String> created) throws IOException {
        if (created.isEmpty()) return;
        Set<String> ledger = new TreeSet<>(NodeKeys.written(moduleDir));
        if (!ledger.addAll(created)) return;
        ledger.removeIf(rel -> !Files.exists(nodeDir.resolve(rel)));
        Path file = NodeKeys.writtenLedger(moduleDir);
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, String.join("\n", ledger) + "\n");
        for (Stored s : stored) realias(s, ledger);
    }

    /** A step stored a record under {@code keyed}; stored again at once if it read a written file. */
    synchronized void stored(NodeKeys.Keyed keyed, Rekey rekey) throws IOException {
        Stored s = new Stored(keyed, rekey);
        stored.add(s);
        realias(s, NodeKeys.written(moduleDir));
    }

    /**
     * Store {@code s}'s record again under its key taken now, when the inputs it was keyed on held a
     * file the ledger names and nothing else has moved since: the key the next build computes.
     */
    private void realias(Stored s, Set<String> ledger) throws IOException {
        Map<String, String> expected = NodeKeys.withoutWritten(s.keyed().inputs(), ledger);
        if (expected.size() == s.keyed().inputs().size()) return;
        NodeKeys.Keyed next = s.rekey().key();
        if (!next.inputs().equals(expected) || !aliased.add(next.key())) return;
        cache.storeAlias(s.keyed().key(), next.key(), next.inputs());
    }
}
