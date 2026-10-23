// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a step's inputs were before its key was taken, held against what they are once the step has
 * run. A step's key names its inputs as they were when it was computed; the tool reads them while
 * it runs, and a directory it lists itself can gain, lose or rename an entry the key never saw. An
 * output written from bytes that moved in between must not be filed under the key, or every
 * checkout sharing the cache restores it.
 *
 * <p>Each path is read as {@link ClasspathFingerprint#entry} reads it — a file by content, a
 * directory by the name and content of every file under it — so an edit, an add, a delete and a
 * rename (a case-only one included) all move it; through {@link FileHashMemo} an untouched file
 * costs one stat. Capture before the key is computed: an edit landing between the two then reads
 * as moved, which costs one uncached step and never a wrong record. Not file-system notifications:
 * an event that did not arrive proves nothing.
 */
public final class InputGuard {

    private final Map<Path, String> before;

    private InputGuard(Map<Path, String> before) {
        this.before = before;
    }

    /** The current identity of every path in {@code inputs}: files, directories, or absent paths. */
    public static InputGuard of(Collection<Path> inputs) throws IOException {
        Map<Path, String> before = new LinkedHashMap<>();
        for (Path p : inputs) {
            Path abs = p.toAbsolutePath().normalize();
            if (!before.containsKey(abs)) before.put(abs, ClasspathFingerprint.entry(abs));
        }
        return new InputGuard(before);
    }

    /**
     * The identity this guard took of {@code input}, as {@link ClasspathFingerprint#entry} reads it:
     * what a key built from the same read should use, so the input is walked once before the step.
     */
    public String token(Path input) throws IOException {
        String t = before.get(input.toAbsolutePath().normalize());
        return t != null ? t : ClasspathFingerprint.entry(input);
    }

    /** A guard over nothing: for a step whose every input is already in its key's own re-check. */
    public static InputGuard none() {
        return new InputGuard(Map.of());
    }

    /** The paths whose identity is not what it was when this guard was taken, in capture order. */
    public List<Path> moved() throws IOException {
        List<Path> moved = new ArrayList<>();
        for (Map.Entry<Path, String> e : before.entrySet()) {
            if (!Objects.equals(e.getValue(), ClasspathFingerprint.entry(e.getKey()))) moved.add(e.getKey());
        }
        return moved;
    }

    /**
     * The sentence a step reports when {@code moved} is non-empty: what moved, and that its result is
     * kept for this build but not recorded.
     */
    public static String describe(String step, List<Path> moved) {
        StringBuilder names = new StringBuilder();
        for (Path p : moved) {
            if (!names.isEmpty()) names.append(", ");
            Path name = p.getFileName();
            names.append(name == null ? p.toString() : name.toString());
        }
        return (moved.size() == 1 ? "an input" : moved.size() + " inputs") + " of " + step + " changed while it ran ("
                + names + "); its result is not cached and the next build runs it again";
    }
}
