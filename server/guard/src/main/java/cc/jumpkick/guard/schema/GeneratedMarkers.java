// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.schema;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where a {@code generated} block sits in a file: a {@code <name>:start} line, a {@code <name>:end}
 * line, and the body between them. The comment syntax around the markers is the file's own
 * ({@code <!-- -->}, {@code #}, {@code //}); only the tokens matter.
 */
public final class GeneratedMarkers {

    private GeneratedMarkers() {}

    /** The marker lines (0-based) and the body between them. */
    public record Block(int startLine, int endLine, List<String> body) {}

    public static @Nullable Block find(List<String> lines, String name) {
        Pattern start = Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + ":start(?![\\w-])");
        Pattern end = Pattern.compile("(?<![\\w-])" + Pattern.quote(name) + ":end(?![\\w-])");
        int s = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (s < 0 && start.matcher(lines.get(i)).find()) s = i;
            else if (s >= 0 && end.matcher(lines.get(i)).find()) return new Block(s, i, lines.subList(s + 1, i));
        }
        return null;
    }
}
