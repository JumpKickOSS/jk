// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.repo.RepoArtifactStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The real coordinate a mistyped {@code group:artifact} most likely meant: a module the local
 * artifact store or the library catalog knows with the same artifact under a group that differs
 * only in punctuation or by a couple of characters ({@code com.ninjasquad} for {@code
 * com.ninja-squad}), or the same group with an artifact that differs only in punctuation. Local
 * only; nothing is fetched.
 */
public final class NearMisses {

    /** At most this many coordinates are named. */
    static final int MAX = 2;

    private NearMisses() {}

    /** Near misses of {@code group:artifact} among the store under {@code store} and {@code catalog}. */
    public static List<String> of(String group, String artifact, @Nullable Path store, LibraryCatalog catalog) {
        Set<String> known = new LinkedHashSet<>();
        if (store != null) {
            for (RepoArtifactStore.Module m : RepoArtifactStore.allModules(store)) known.add(m.moduleKey());
        }
        for (String name : catalog.names()) {
            catalog.lookup(name).ifPresent(m -> known.add(m.moduleKey()));
        }
        return among(group, artifact, known);
    }

    /** The near misses of {@code group:artifact} in {@code known} ({@code g:a} keys), closest first. */
    static List<String> among(String group, String artifact, Iterable<String> known) {
        String wantGroup = squash(group);
        String wantArtifact = squash(artifact);
        List<String> exact = new ArrayList<>();
        List<String> close = new ArrayList<>();
        for (String key : known) {
            int colon = key.indexOf(':');
            if (colon <= 0) continue;
            String g = key.substring(0, colon);
            String a = key.substring(colon + 1);
            if (g.equals(group) && a.equals(artifact)) continue;
            boolean sameArtifact = squash(a).equals(wantArtifact);
            boolean sameGroup = squash(g).equals(wantGroup);
            if (sameArtifact && sameGroup) {
                if (!exact.contains(key)) exact.add(key);
            } else if (sameArtifact && distance(squash(g), wantGroup) <= 2) {
                if (!close.contains(key)) close.add(key);
            }
        }
        List<String> out = new ArrayList<>(exact);
        for (String key : close) if (!out.contains(key)) out.add(key);
        return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : List.copyOf(out);
    }

    /** Lower case with {@code . - _} removed: {@code com.ninja-squad} and {@code com.ninjasquad} agree. */
    private static String squash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c != '.' && c != '-' && c != '_') sb.append(c);
        }
        return sb.toString();
    }

    /** Levenshtein distance between {@code a} and {@code b}. */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
