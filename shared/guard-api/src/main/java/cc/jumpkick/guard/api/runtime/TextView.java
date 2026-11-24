// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.OutputDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** {@link Text} over the source roots jk allowed; paths are workspace-relative. */
public final class TextView implements Text {

    private final Path root;
    private final List<Path> sources;
    private final boolean fixture;
    private final @Nullable String outputDir;
    private final boolean keepWalk;
    // One instance serves every guard of a run, and every guard walks and reads the same tree: the walk
    // happens once, a file is read once, and a blanked view is computed once per mode.
    private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> views = new ConcurrentHashMap<>();
    private volatile TreeWalks.@Nullable Listing listing;
    private volatile @Nullable Recorder recorder;

    /**
     * Hears every glob a guard lists and every file whose text it takes, before any cache answers, and
     * answers a per-file scan from the last run when it can.
     */
    interface Recorder {
        void glob(String glob);

        void file(String path);

        List<String> perFile(String path, String salt, Function<String, List<String>> scan);
    }

    public TextView(Path root, List<Path> sources) {
        this(root, sources, false, null);
    }

    /**
     * {@code fixture}: a {@code jk guard test} run, where a glob is matched against file names only — a
     * fixture has no source tree shape. {@code outputDir}: the root-level directory jk writes its output
     * under (where the run's report lives), which a walk from the root never enters.
     */
    public TextView(Path root, List<Path> sources, boolean fixture, @Nullable String outputDir) {
        this(root, sources, fixture, outputDir, false);
    }

    private TextView(Path root, List<Path> sources, boolean fixture, @Nullable String outputDir, boolean keepWalk) {
        this.root = root.toAbsolutePath().normalize();
        this.sources = List.copyOf(sources);
        this.fixture = fixture;
        this.outputDir = outputDir;
        this.keepWalk = keepWalk;
    }

    /**
     * A view whose walk and glob matches are the process's last over the same tree while no directory
     * they listed has changed ({@link TreeWalks}): for a caller that asks again and again whether the
     * tree still lists as it did.
     */
    static TextView keepingWalk(Path root, List<Path> sources, @Nullable String outputDir) {
        return new TextView(root, sources, false, outputDir, true);
    }

    /** Report every read to {@code r} from now on. */
    void recordTo(Recorder r) {
        recorder = r;
    }

    @Override
    public List<String> files(String glob) {
        Recorder r = recorder;
        if (r != null) r.glob(glob);
        return unrecordedFiles(glob);
    }

    /** {@link #files} without telling the recorder: the ledger's own checks. */
    List<String> unrecordedFiles(String glob) {
        TreeWalks.Listing l = listing();
        return l.matches.computeIfAbsent(glob, g -> {
            Pattern p = globPattern(fixture ? g.substring(g.lastIndexOf('/') + 1) : g);
            List<String> out = new ArrayList<>();
            for (String rel : l.files) {
                if (p.matcher(fixture ? rel.substring(rel.lastIndexOf('/') + 1) : rel)
                        .matches()) out.add(rel);
            }
            return List.copyOf(out);
        });
    }

    /** Every regular file under the sources, sorted, walked once per instance. */
    private TreeWalks.Listing listing() {
        TreeWalks.Listing l = listing;
        if (l != null) return l;
        synchronized (this) {
            if (listing != null) return listing;
            List<Path> dirs = sources.stream()
                    .map(src -> (src.isAbsolute() ? src : root.resolve(src))
                            .toAbsolutePath()
                            .normalize())
                    .toList();
            Predicate<Path> skip = d -> skipped(TreeWalks.rel(root, d));
            l = keepWalk
                    ? TreeWalks.listing(root, dirs, outputDir, skip, this::pruneProbe)
                    : TreeWalks.walk(root, dirs, skip);
            listing = l;
            return l;
        }
    }

    @Override
    public String blanked(String path, Blank mode) {
        if (mode == Blank.NONE) return read(path);
        return views.computeIfAbsent(mode.name() + ":" + path, k -> {
            String text = read(path);
            return switch (mode) {
                case NONE -> text;
                case COMMENTS -> Blanker.blank(text, true, false, false);
                case COMMENTS_AND_STRINGS -> Blanker.blank(text, true, true, false);
                case CODE -> Blanker.blank(text, false, true, true);
            };
        });
    }

    @Override
    public List<String> literals(String path) {
        return Blanker.literals(read(path));
    }

    @Override
    public List<String> lines(String path) {
        return read(path).lines().toList();
    }

    @Override
    public List<String> perFile(String path, String salt, Function<String, List<String>> scan) {
        Recorder r = recorder;
        return r == null ? scan.apply(path) : r.perFile(path, salt, scan);
    }

    private String read(String path) {
        Recorder r = recorder;
        if (r != null) r.file(path);
        return text(path);
    }

    /** The file's text without telling the recorder, or {@code null} when it does not read. */
    @Nullable
    String unrecordedText(String path) {
        try {
            return text(path);
        } catch (UncheckedIOException unreadable) {
            return null;
        }
    }

    private String text(String path) {
        String cached = texts.get(path);
        if (cached != null) return cached;
        Path p = root.resolve(path);
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            texts.put(path, text);
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("Text: " + path + " is not readable under " + root, e);
        }
    }

    /**
     * Dot-directories and foreign build output are not the source tree — except {@code .github} and
     * {@code .jk}, which are the repository's own text. jk's own output tree at the root is skipped by
     * where the report lives, not by a name this library would have to know; a module's {@code target/}
     * and Gradle's {@code build/} by the manifest or script beside it ({@link OutputDirs#isBuildOutputDir}),
     * so a package named {@code build} or {@code target} is read like any other.
     */
    boolean skipped(String rel) {
        if (outputDir != null && (rel.equals(outputDir) || rel.startsWith(outputDir + "/"))) return true;
        Path dir = root;
        for (String seg : rel.split("/")) {
            dir = dir.resolve(seg);
            if (seg.equals("node_modules")) return true;
            if ((seg.equals("build") || seg.equals(OutputDirs.TARGET)) && OutputDirs.isBuildOutputDir(dir)) return true;
            if (seg.startsWith(".") && seg.length() > 1 && !seg.equals(".github") && !seg.equals(".jk")) return true;
        }
        return false;
    }

    /**
     * For a directory {@link #skipped} prunes, the file whose appearance would read it again: the
     * manifest that makes a {@code build/} or {@code target/} a module of its own. The rest are pruned
     * by name or position alone.
     */
    private @Nullable Path pruneProbe(Path dir) {
        String rel = TreeWalks.rel(root, dir);
        if (outputDir != null && (rel.equals(outputDir) || rel.startsWith(outputDir + "/"))) return null;
        return OutputDirs.isBuildOutputDir(dir) ? dir.resolve(ManifestNames.MANIFEST) : null;
    }

    /** {@code **} across segments, {@code *} within one, {@code ?} one character. */
    static Pattern globPattern(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        re.append("(?:.*/)?");
                        i += 2;
                    } else {
                        re.append(".*");
                        i++;
                    }
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
