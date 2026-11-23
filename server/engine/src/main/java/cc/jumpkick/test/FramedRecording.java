// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One recording after the first pass of {@link ClassReads#attribute}: its class windows, the files
 * it wrote that could be a class's input, how to read it again, the JDK parser's dump when it was
 * read through one, and the launch its windows tell (null until told, or when they tell none).
 */
record FramedRecording(
        Path jfr,
        List<ClassReads.Window> windows,
        Set<Path> written,
        ClassReads.Pass pass,
        @Nullable Path lines,
        @Nullable String launch) {

    FramedRecording told(@Nullable String launch) {
        return new FramedRecording(jfr, windows, written, pass, lines, launch);
    }

    /** The first pass over {@code jfr}: its windows and the data files it wrote. */
    static FramedRecording read(Path jfr, PathVerdicts verdicts) throws IOException {
        List<ClassReads.Window> windows = new ArrayList<>();
        Set<Path> written = new HashSet<>();
        RecordingReader.Sink frame = (name, start, end, v) -> {
            if (name.equals(ClassReads.WINDOW_EVENT)) {
                if (v[0] != null && !v[0].isBlank()) windows.add(new ClassReads.Window(v[0], start, end));
            } else if (v[0] != null && !v[0].isBlank() && written.size() < ClassReads.WRITTEN_KEPT) {
                // Only a file that could be a class's input needs telling from the run's own.
                PathVerdicts.Verdict w = verdicts.of(v[0]);
                if (w != null && w.where() == ClassReads.Where.DATA) written.add(w.path());
            }
        };
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            RecordingReader.read(jfr, ClassReads.FRAME, frame);
            return new FramedRecording(
                    jfr, windows, written, (wanted, sink) -> RecordingReader.read(jfr, wanted, sink), null, null);
        } catch (IOException declined) {
            // A file that does not even begin as a recording is no recording at all.
            if (!RecordingReader.framed(jfr)) throw new IOException("not a recording", declined);
            Log.info("class reads: " + jfr.getFileName() + " read through the JDK's parser in a child JVM: "
                    + declined.getMessage());
        }
        windows.clear();
        written.clear();
        Path dumped = RecordingDump.dump(jfr, ClassReads.ALL);
        ClassReads.Pass pass = (wanted, sink) -> RecordingDump.stream(dumped, wanted, sink);
        try (RecordingReader.Permit permit = RecordingReader.Permit.acquire()) {
            pass.run(ClassReads.FRAME, frame);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(dumped);
            throw e;
        }
        return new FramedRecording(jfr, windows, written, pass, dumped, null);
    }

    /** Removes the dump, if there is one. Best-effort: a dump left behind is only logged. */
    void removeDump() {
        if (lines == null) return;
        try {
            Files.deleteIfExists(lines);
        } catch (IOException kept) {
            Log.debug("class reads: " + lines + " not removed", kept);
        }
    }
}
