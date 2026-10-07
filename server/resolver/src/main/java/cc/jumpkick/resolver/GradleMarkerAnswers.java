// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Whether Gradle published module metadata beside a POM, answered once per POM content and kept
 * beside the metadata cache in {@value #FILE}: one {@code <sha256> 1|0} line per POM digest,
 * appended as answers are found. A fresh engine reads that file once instead of scanning every
 * POM head again; a changed POM has a new digest and is scanned.
 */
final class GradleMarkerAnswers {

    static final String FILE = "gradle-markers";

    /** Each answers file as read, then grown in step with what this process appends. */
    private static final Map<Path, Map<String, Boolean>> LOADED = new ConcurrentHashMap<>();

    /** POM heads this process scanned: the seam that proves a recorded answer is not scanned again. */
    static final LongAdder SCANS = new LongAdder();

    private GradleMarkerAnswers() {}

    /** Whether the POM {@code hit} served carries Gradle's published-with-metadata marker. */
    static boolean marked(RepoGroup.RepoFetched hit) throws IOException {
        Path pom = hit.fetched().cachePath();
        String sha256 = hit.fetched().sha256().toLowerCase(Locale.ROOT);
        if (!Hashing.isHex(sha256, 64)) return scan(pom);
        Path file = hit.repo().metadataDir().resolve(FILE);
        Map<String, Boolean> known;
        try {
            known = LOADED.computeIfAbsent(file, GradleMarkerAnswers::load);
        } catch (UncheckedIOException e) {
            return scan(pom);
        }
        Boolean recorded = known.get(sha256);
        if (recorded != null) return recorded;
        boolean marked = scan(pom);
        if (known.putIfAbsent(sha256, marked) == null) append(file, sha256 + (marked ? " 1\n" : " 0\n"));
        return marked;
    }

    /** Forget what was read, so the next answer reloads its file; for the idle engine. */
    static int drop() {
        int n = 0;
        for (Map<String, Boolean> answers : LOADED.values()) n += answers.size();
        LOADED.clear();
        return n;
    }

    private static boolean scan(Path pom) throws IOException {
        SCANS.increment();
        return KmpRedirects.pomHasGradleMetadataMarker(pom);
    }

    private static Map<String, Boolean> load(Path file) {
        Map<String, Boolean> answers = new ConcurrentHashMap<>();
        if (!Files.isRegularFile(file)) return answers;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int space = line.indexOf(' ');
                if (space != 64 || line.length() != 66) continue; // a torn or foreign line
                char verdict = line.charAt(65);
                if (verdict == '1' || verdict == '0') answers.putIfAbsent(line.substring(0, 64), verdict == '1');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return answers;
    }

    /** One whole line per write: an append of a few dozen bytes lands in one piece beside another engine's. */
    private static void append(Path file, String line) {
        try {
            Files.createDirectories(Objects.requireNonNull(file.getParent(), "the answers file has a directory"));
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.debug("GradleMarkerAnswers: the answers file is a shortcut; the scan stands without it", e);
        }
    }
}
