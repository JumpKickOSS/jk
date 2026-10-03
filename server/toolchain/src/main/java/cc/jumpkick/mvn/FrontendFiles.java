// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files an import moves and rewrites to place a front end: moves first, then each rewrite at
 * its path after the moves. A dry run applies neither.
 */
public record FrontendFiles(List<Move> moves, Map<Path, String> rewrites) {

    public static final FrontendFiles NONE = new FrontendFiles(List.of(), Map.of());

    /** A file or directory that moves, as a whole, from {@code from} to {@code to}. */
    public record Move(Path from, Path to) {}

    public FrontendFiles {
        moves = List.copyOf(moves);
        rewrites = Map.copyOf(rewrites);
    }

    public boolean isEmpty() {
        return moves.isEmpty() && rewrites.isEmpty();
    }

    FrontendFiles plus(FrontendFiles other) {
        List<Move> m = new ArrayList<>(moves);
        m.addAll(other.moves);
        Map<Path, String> r = new LinkedHashMap<>(rewrites);
        r.putAll(other.rewrites);
        return new FrontendFiles(m, r);
    }
}
