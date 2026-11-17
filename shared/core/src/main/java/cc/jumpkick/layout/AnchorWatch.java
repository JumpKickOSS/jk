// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Settled;
import cc.jumpkick.host.StatIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * The files a hand edit could leave an anchor stale through, as the last graph refresh saw them:
 * every file that defines or uses an anchor in that graph, and every file the stale record names.
 * One row each, {@code sha256 \t identity \t rel}, in {@code target/graph/anchors/watch}. A build's
 * verdict asks {@link #unchanged} before it waits for the refresh the build started: when no such
 * file changed, that refresh cannot change what the record says, so the verdict reads it at once.
 */
public final class AnchorWatch {

    private AnchorWatch() {}

    /** One file as the refresh saw it. */
    public record Entry(String sha, StatIdentity identity, String rel) {}

    private static final String HEADER = "# jk anchor watch 1";

    /** {@code <graph dir>/anchors/watch}. */
    public static Path file(Path graphDir) {
        return graphDir.resolve("anchors").resolve("watch");
    }

    /** Writes the list (an empty one too: a graph with no anchors watches nothing). */
    public static void write(Path graphDir, Collection<Entry> entries) throws IOException {
        StringBuilder s = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            s.append(e.sha())
                    .append('\t')
                    .append(e.identity().encode())
                    .append('\t')
                    .append(e.rel())
                    .append('\n');
        }
        Path f = file(graphDir);
        Files.createDirectories(f.getParent());
        AtomicWrites.replaceIfChanged(f, s.toString());
    }

    /**
     * Whether every file the list under {@code graphDir} names is as the refresh that wrote it saw
     * it: its stat the same and settled, else its bytes hashed the same. False when there is no
     * list (a graph an older jk wrote, or none), or a file is gone or unreadable, so the caller
     * waits for the refresh as before.
     */
    public static boolean unchanged(Path root, Path graphDir) {
        List<String> rows;
        try {
            rows = Files.readAllLines(file(graphDir));
        } catch (IOException noList) {
            return false;
        }
        if (rows.isEmpty() || !rows.getFirst().equals(HEADER)) return false;
        for (String row : rows.subList(1, rows.size())) {
            String[] f = row.split("\t", 3);
            if (f.length != 3) return false;
            StatIdentity was = StatIdentity.decode(f[1]);
            Path p = root.resolve(f[2]);
            try {
                StatIdentity is = StatIdentity.of(p);
                if (is.equals(was) && Settled.is(is)) continue;
                if (!Hashing.sha256Hex(p).equals(f[0])) return false;
            } catch (IOException | RuntimeException goneOrUnreadable) {
                return false;
            }
        }
        return true;
    }
}
