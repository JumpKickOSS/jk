// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The line a verdict carries when another live session's episode edited a file this episode
 * edited too. Each episode hears of one shared file from one other episode once; a verdict with
 * nothing new to say carries nothing.
 */
final class Overlaps {

    /** Episodes whose told files are remembered; the oldest is forgotten past it. */
    static final int KEPT = 512;

    /** The widest line a verdict carries. */
    static final int WIDTH = 78;

    /**
     * One live session's episode and the files its runs saw edited.
     *
     * @param who how a verdict names its session
     * @param working whether the episode is still open
     */
    record Open(String episode, String who, String project, boolean working, Set<String> edited) {}

    /** Another session's open episode and the files it shares with the one asking. */
    record Other(String episode, String who, Set<String> shared) {}

    /** Per episode, each {@code <other episode> \t <file>} it was told of. */
    private final Map<String, Set<String>> told = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
            return size() > KEPT;
        }
    };

    /**
     * The line for {@code episode}, one of {@code open}: what the other sessions' open episodes in
     * the same project ({@code projectKey} of their directories equal) edited that it edited too.
     */
    @Nullable
    String line(String episode, List<Open> open, UnaryOperator<String> projectKey) {
        Open own = null;
        for (Open o : open) if (o.episode().equals(episode)) own = o;
        if (own == null || own.edited().isEmpty()) return null;
        String key = projectKey.apply(own.project());
        String session = sessionOf(episode);
        List<Other> others = new ArrayList<>();
        for (Open o : open) {
            if (!o.working() || sessionOf(o.episode()).equals(session)) continue;
            if (!key.equals(projectKey.apply(o.project()))) continue;
            Set<String> shared = new TreeSet<>(o.edited());
            shared.retainAll(own.edited());
            if (!shared.isEmpty()) others.add(new Other(o.episode(), o.who(), shared));
        }
        return others.isEmpty() ? null : line(episode, others);
    }

    /** How a verdict names a session: its label, else its client, else its origin. */
    static String who(SessionRecord r) {
        if (r.label() != null && !r.label().isBlank()) return r.label();
        if (r.client() != null && !r.client().isBlank()) return r.client();
        return r.origin();
    }

    private static String sessionOf(String episode) {
        int dot = episode.lastIndexOf('.');
        return dot > 0 ? episode.substring(0, dot) : episode;
    }

    /**
     * {@code W <who> is also editing <types>} for the shared files {@code episode} was not told of
     * yet, marking them told; null when every one was.
     */
    synchronized @Nullable String line(String episode, List<Other> others) {
        Set<String> heard = told.computeIfAbsent(episode, k -> new HashSet<>());
        Set<String> who = new LinkedHashSet<>();
        Set<String> symbols = new LinkedHashSet<>();
        for (Other o : others) {
            for (String file : o.shared()) {
                if (!heard.add(o.episode() + "\t" + file)) continue;
                who.add(o.who());
                symbols.add(EditedFiles.symbol(file));
            }
        }
        if (who.isEmpty()) return null;
        String first = who.iterator().next();
        String subject = who.size() == 1
                ? first + " is"
                : first + " and " + (who.size() - 1) + (who.size() == 2 ? " more session are" : " more sessions are");
        return fit("W " + subject + " also editing ", new ArrayList<>(symbols));
    }

    /** {@code head} and as many of {@code names} as fit in {@link #WIDTH}, then how many more. */
    static String fit(String head, List<String> names) {
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < names.size(); i++) {
            String name = (i == 0 ? "" : ", ") + names.get(i);
            int left = names.size() - i - 1;
            String tail = left == 0 ? "" : " (+" + left + " more)";
            boolean fits = sb.length() + name.length() + tail.length() <= WIDTH;
            if (!fits && i > 0)
                return sb.append(" (+")
                        .append(names.size() - i)
                        .append(" more)")
                        .toString();
            sb.append(name);
        }
        return sb.toString();
    }
}
