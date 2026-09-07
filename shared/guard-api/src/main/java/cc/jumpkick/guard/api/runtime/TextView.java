// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Blank;
import cc.jumpkick.guard.api.Text;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** {@link Text} over the source roots jk allowed; paths are workspace-relative. */
public final class TextView implements Text {

    private final Path root;
    private final List<Path> sources;

    public TextView(Path root, List<Path> sources) {
        this.root = root.toAbsolutePath().normalize();
        this.sources = List.copyOf(sources);
    }

    @Override
    public List<String> files(String glob) {
        Pattern p = globPattern(glob);
        TreeSet<String> out = new TreeSet<>();
        for (Path src : sources) {
            Path dir = src.isAbsolute() ? src : root.resolve(src);
            if (!Files.isDirectory(dir)) continue;
            try {
                PathUtil.forEachRegularFile(dir, d -> skipped(rel(d)), (f, attrs) -> {
                    String rel = rel(f);
                    if (p.matcher(rel).matches()) out.add(rel);
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ArrayList<>(out);
    }

    private String rel(Path f) {
        return root.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    @Override
    public String blanked(String path, Blank mode) {
        String text = read(path);
        return switch (mode) {
            case NONE -> text;
            case COMMENTS -> Blanker.blank(text, true, false, false);
            case COMMENTS_AND_STRINGS -> Blanker.blank(text, true, true, false);
            case CODE -> Blanker.blank(text, false, true, true);
        };
    }

    @Override
    public List<String> literals(String path) {
        return Blanker.literals(read(path));
    }

    @Override
    public List<String> lines(String path) {
        return read(path).lines().toList();
    }

    private String read(String path) {
        Path p = root.resolve(path);
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Text: " + path + " is not readable under " + root, e);
        }
    }

    /** Dot-directories and foreign build output are not the source tree; jk's own output never sits under a source root. */
    static boolean skipped(String rel) {
        for (String seg : rel.split("/")) {
            if (seg.equals("build") || seg.equals("node_modules") || (seg.startsWith(".") && seg.length() > 1))
                return true;
        }
        return false;
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
