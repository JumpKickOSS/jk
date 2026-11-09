// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What this machine answered about its setup, in {@code <state>/windows-host.toml}: the answer and
 * when, and from the last elevated check the folders Defender verified as excluded, the ones it
 * found not excluded, the ones the person left out, and the ones a policy refused, each with its
 * reason. Paths are joined with {@code |}, which no Windows path holds.
 *
 * @param answeredAt epoch millis of the answer; 0 when never asked
 * @param checkedAt epoch millis of the last elevated check; 0 when never checked
 * @param beforeMs the scan probe's time before the change; 0 when not measured
 * @param afterMs the scan probe's time after it; 0 when not measured
 */
public record SetupMemo(
        @Nullable Answer answer,
        long answeredAt,
        long checkedAt,
        List<String> verified,
        List<String> notExcluded,
        List<String> declined,
        Map<String, String> refused,
        long beforeMs,
        long afterMs) {

    /** How the person answered the setup decision. */
    public enum Answer {
        ACCEPTED,
        DECLINED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Whether a folder is excluded from Defender's real-time scan, as far as this machine knows. */
    public enum Exclusion {
        /** An elevated check found it excluded. */
        VERIFIED,
        /** An elevated check found it scanned, or the person declined the exclusion. */
        NOT_EXCLUDED,
        /** Never checked: a standard user cannot read Defender's exclusions. */
        UNKNOWN
    }

    public static final String FILE = "windows-host.toml";

    public static final SetupMemo NONE = new SetupMemo(null, 0, 0, List.of(), List.of(), List.of(), Map.of(), 0, 0);

    public SetupMemo {
        verified = List.copyOf(verified);
        notExcluded = List.copyOf(notExcluded);
        declined = List.copyOf(declined);
        refused = Map.copyOf(refused);
    }

    /** The memo file under {@code state}. */
    public static Path file(Path state) {
        return state.resolve(FILE);
    }

    /** The memo {@code file} holds; {@link #NONE} when it is missing or unreadable. */
    public static SetupMemo read(Path file) {
        if (!Files.isRegularFile(file)) return NONE;
        try {
            Map<String, String> m = MinimalToml.readFlat(Files.readString(file));
            Map<String, String> refused = new LinkedHashMap<>();
            for (String entry : list(m.get("refused"))) {
                int eq = entry.indexOf('=');
                if (eq > 0) refused.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
            return new SetupMemo(
                    answer(m.get("answer")),
                    number(m.get("answered")),
                    number(m.get("checked")),
                    list(m.get("verified")),
                    list(m.get("not-excluded")),
                    list(m.get("declined")),
                    refused,
                    number(m.get("before-ms")),
                    number(m.get("after-ms")));
        } catch (IOException | RuntimeException unreadable) {
            return NONE;
        }
    }

    public void write(Path file) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("answer", answer == null ? "" : answer.wire());
        m.put("answered", Long.toString(answeredAt));
        m.put("checked", Long.toString(checkedAt));
        m.put("verified", String.join("|", verified));
        m.put("not-excluded", String.join("|", notExcluded));
        m.put("declined", String.join("|", declined));
        List<String> refusals = new ArrayList<>();
        refused.forEach((path, why) -> refusals.add(path + "=" + why.replace('|', '/')));
        m.put("refused", String.join("|", refusals));
        m.put("before-ms", Long.toString(beforeMs));
        m.put("after-ms", Long.toString(afterMs));
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        AtomicWrites.replace(file, MinimalToml.writeFlat(m));
    }

    /** Whether {@code folder} is excluded, by the verified roots, the scanned ones and the declined ones. */
    public Exclusion exclusion(String folder) {
        for (String root : verified) if (WinPaths.under(folder, root)) return Exclusion.VERIFIED;
        for (String root : notExcluded) if (WinPaths.under(folder, root)) return Exclusion.NOT_EXCLUDED;
        for (String root : declined) if (WinPaths.under(folder, root)) return Exclusion.NOT_EXCLUDED;
        return answer == Answer.DECLINED ? Exclusion.NOT_EXCLUDED : Exclusion.UNKNOWN;
    }

    private static @Nullable Answer answer(@Nullable String wire) {
        for (Answer a : Answer.values()) if (a.wire().equals(wire)) return a;
        return null;
    }

    private static long number(@Nullable String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> list(@Nullable String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null) return out;
        for (String part : joined.split("\\|")) if (!part.isBlank()) out.add(part);
        return out;
    }
}
