// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.util.FileLocks;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * A text file of {@code [section]} headers, each followed by its lines in its owner's own grammar:
 * the shape of the host's and each project's estimate stores ({@link HostStore}, {@link
 * ProjectStore}). No line of a section starts with {@code [}; blank lines and {@code #} comments are
 * dropped. The file is rewritten whole, atomically, under one cross-process lock beside it, so
 * writers of different sections never drop each other's lines.
 */
public final class SectionedFile {

    private SectionedFile() {}

    /** One line of a section, as {@link #scan} hands it over. */
    public interface LineSink {
        void line(String section, String line);
    }

    /**
     * Hands every line of the sections in {@code only} ({@code null}: every section) to {@code
     * sink}, in file order, streaming: a reader of one section never holds the others. A missing
     * or unreadable file has no lines.
     */
    public static void scan(Path file, @Nullable Set<String> only, LineSink sink) {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            scan(in, only, sink);
        } catch (NoSuchFileException absent) {
            // nothing learned yet
        } catch (IOException e) {
            Log.debug("SectionedFile: unreadable " + file, e);
        }
    }

    private static void scan(BufferedReader in, @Nullable Set<String> only, LineSink sink) throws IOException {
        String section = null;
        boolean wanted = false;
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                wanted = only == null || only.contains(section);
            } else if (wanted && section != null && !line.isBlank() && !line.startsWith("#")) {
                sink.line(section, line);
            }
        }
    }

    /** The sections in {@code only} ({@code null}: every section) of {@code file}, in file order. */
    public static Map<String, List<String>> read(Path file, @Nullable Set<String> only) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        scan(file, only, (section, line) -> out.computeIfAbsent(section, s -> new ArrayList<>())
                .add(line));
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }

    /**
     * Replaces each section named in {@code edits} with its edit of that section's lines, every
     * edit in one locked read and rewrite; an edit's empty result drops its section. The file is
     * written only when its text changes. {@code header} is the comment line the file opens with.
     * A file whose bytes no longer decode is moved aside and replaced, logged once as a warning.
     */
    public static void update(Path file, String header, Map<String, UnaryOperator<List<String>>> edits)
            throws IOException {
        updateAll(file, header, all -> {
            for (Map.Entry<String, UnaryOperator<List<String>>> e : edits.entrySet()) {
                List<String> now = e.getValue().apply(new ArrayList<>(all.getOrDefault(e.getKey(), List.of())));
                if (now == null || now.isEmpty()) all.remove(e.getKey());
                else all.put(e.getKey(), now);
            }
            return all;
        });
    }

    /**
     * {@link #update} with one edit that reads and writes every section at once, for an owner whose
     * sections depend on each other: {@code edit} gets the sections in file order, mutable, and
     * returns them; a section with no lines is dropped.
     */
    public static void updateAll(Path file, String header, UnaryOperator<Map<String, List<String>>> edit)
            throws IOException {
        OwnerOnlyFiles.createDirectories(Objects.requireNonNull(file.getParent(), "a file has a directory"));
        FileLocks.withLock(ProjectBuilds.ledgerLock(file), () -> {
            String before = readText(file);
            Map<String, List<String>> all = new LinkedHashMap<>();
            if (before != null) {
                try (BufferedReader in = new BufferedReader(new StringReader(before))) {
                    scan(in, null, (section, line) -> all.computeIfAbsent(section, s -> new ArrayList<>())
                            .add(line));
                }
            }
            Map<String, List<String>> after = edit.apply(all);
            after.values().removeIf(lines -> lines == null || lines.isEmpty());
            String text = render(header, after);
            if (!text.equals(before)) AtomicWrites.replace(file, text);
        });
    }

    /**
     * The file's text; null when it is missing, or when its bytes no longer decode (a torn write, a
     * foreign editor): then it is moved aside to {@code <name>.unreadable}, replacing an older one,
     * so the next update starts from nothing and learns again.
     */
    private static @Nullable String readText(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (CharacterCodingException unreadable) {
            Path aside = file.resolveSibling(file.getFileName() + UNREADABLE);
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            Log.warn("jk: warning: " + file + ": unreadable, moved to " + aside.getFileName());
            return null;
        }
    }

    /** The suffix of the one copy of an unreadable file kept beside it ({@link #update}). */
    public static final String UNREADABLE = ".unreadable";

    private static String render(String header, Map<String, List<String>> all) {
        StringBuilder sb = new StringBuilder("# ").append(header).append('\n');
        for (Map.Entry<String, List<String>> e : all.entrySet()) {
            sb.append('[').append(e.getKey()).append("]\n");
            for (String line : e.getValue()) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
