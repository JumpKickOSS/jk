// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jspecify.annotations.Nullable;

/**
 * Keeps {@code graphify-out/graph.json} and {@code GRAPH_REPORT.md} at the workspace root equal to
 * jk's, so graphify's skill (which looks only there) answers from jk's graph. The marker {@code
 * graphify-out/.jk-graph} records the size, modification time and sha-256 of the {@code graph.json}
 * jk last wrote: a file that no longer matches was rewritten by someone else, typically {@code
 * graphify update}, and jk writes its graph back and says so.
 */
final class GraphifyMirror {

    private GraphifyMirror() {}

    static final String DIR = "graphify-out";
    static final String MARKER = ".jk-graph";

    /**
     * Brings the mirror up to date with {@code out}'s graph; {@code changed} says whether that graph
     * was rewritten by this refresh. Returns a note for the user when jk replaced a graph it had not
     * written, {@code null} otherwise.
     */
    static @Nullable String sync(Path root, Path out, boolean changed) throws IOException {
        Path source = out.resolve(GraphOutputs.JSON);
        if (!Files.isRegularFile(source)) return null;
        Path dir = root.resolve(DIR);
        Path target = dir.resolve(GraphOutputs.JSON);
        Path marker = dir.resolve(MARKER);
        Marker had = Marker.read(marker);
        boolean present = Files.isRegularFile(target);
        if (present && had != null && had.matches(target) && !changed) return null;
        String note = null;
        if (present && (had == null || !had.sha().equals(sha(target)))) {
            note = had == null
                    ? DIR + "/graph.json was not jk's; replaced it with jk's graph ([graph] graphify = true)"
                    : DIR + "/graph.json changed since jk wrote it (graphify update?); wrote jk's graph back";
        }
        Files.createDirectories(dir);
        Path tmp = AtomicWrites.staging(target);
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        AtomicWrites.moveInto(tmp, target);
        Path report = out.resolve(GraphOutputs.REPORT);
        if (Files.isRegularFile(report)) {
            AtomicWrites.replaceIfChanged(dir.resolve(GraphOutputs.REPORT), Files.readAllBytes(report));
        }
        Marker.of(target, sha(target)).write(marker);
        return note;
    }

    /** What jk last wrote: {@code size \t mtimeMillis \t sha256}. */
    private record Marker(long size, long mtime, String sha) {

        static @Nullable Marker read(Path file) {
            try {
                if (!Files.isRegularFile(file)) return null;
                String[] f =
                        Files.readString(file, StandardCharsets.UTF_8).strip().split("\t");
                return f.length == 3 ? new Marker(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2]) : null;
            } catch (IOException | NumberFormatException unreadable) {
                return null;
            }
        }

        static Marker of(Path target, String sha) throws IOException {
            return new Marker(
                    Files.size(target), Files.getLastModifiedTime(target).toMillis(), sha);
        }

        boolean matches(Path target) throws IOException {
            return Files.size(target) == size
                    && Files.getLastModifiedTime(target).toMillis() == mtime;
        }

        void write(Path file) throws IOException {
            AtomicWrites.replace(file, size + "\t" + mtime + "\t" + sha + "\n");
        }
    }

    private static String sha(Path file) throws IOException {
        return Hashing.sha256Hex(file);
    }
}
