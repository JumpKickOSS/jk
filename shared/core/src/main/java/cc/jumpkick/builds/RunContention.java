// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which runs under a builds root ran while another run did. A step wall measured while other
 * builds shared the machine is a contended sample; the harvest keeps such samples out of a row's
 * mean while the row has an uncontended one. Windows come from each run's {@code record.json}
 * ({@code startedAt} / {@code finishedAt}, epoch millis), across every project of the root.
 */
public final class RunContention {

    /** Per-run sidecar the harvest writes for a contended run: {@code overlapping-runs = N}. */
    public static final String SIDECAR = "contention.toml";

    private static final Pattern STARTED = Pattern.compile("\"startedAt\"\\s*:\\s*(\\d+)");
    private static final Pattern FINISHED = Pattern.compile("\"finishedAt\"\\s*:\\s*(\\d+)");

    private RunContention() {}

    /** {@code [startedAt, finishedAt]} of a finished run, or {@code null} when the record does not say. */
    static long @Nullable [] window(Path run) {
        Path record = run.resolve(ProjectBuilds.RECORD);
        if (!Files.isRegularFile(record)) return null;
        try {
            String json = Files.readString(record, StandardCharsets.UTF_8);
            Matcher s = STARTED.matcher(json);
            Matcher f = FINISHED.matcher(json);
            if (!s.find() || !f.find()) return null;
            long start = Long.parseLong(s.group(1));
            long end = Long.parseLong(f.group(1));
            if (end <= start) return null;
            return new long[] {start, end};
        } catch (IOException | NumberFormatException unreadable) {
            return null;
        }
    }

    /** For every run, how many other runs' windows overlap its own; zero for a run that ran alone. */
    static Map<Path, Integer> overlaps(Map<Path, long[]> windows) {
        Map<Path, Integer> out = new LinkedHashMap<>();
        List<Map.Entry<Path, long[]>> all = List.copyOf(windows.entrySet());
        for (var a : all) {
            int n = 0;
            for (var b : all) {
                if (a == b) continue;
                if (a.getValue()[0] < b.getValue()[1] && b.getValue()[0] < a.getValue()[1]) n++;
            }
            out.put(a.getKey(), n);
        }
        return out;
    }

    /** Write the sidecar for a contended run; a run that ran alone carries none. */
    static void writeSidecar(Path run, int overlapping) {
        Path file = run.resolve(SIDECAR);
        try {
            if (overlapping <= 0) {
                Files.deleteIfExists(file);
                return;
            }
            String text = "# runs whose window overlapped this one; the harvest prices from uncontended samples first\n"
                    + "overlapping-runs = " + overlapping + "\n";
            if (Files.isRegularFile(file) && text.equals(Files.readString(file, StandardCharsets.UTF_8))) return;
            AtomicWrites.replace(file, text);
        } catch (IOException ignored) {
            // A missing sidecar only costs a reader the explanation; the ledger is unaffected.
        }
    }
}
