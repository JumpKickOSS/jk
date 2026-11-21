// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/**
 * An Aho-Corasick automaton over a set of secrets: one pass over a text finds every occurrence of
 * every secret, however many there are, where a scan per secret costs the text's length once per
 * secret.
 *
 * <p>Each state is a prefix of some secret. Its transitions live in one open-addressed table keyed
 * by {@code (state, char)}, so a few thousand secrets cost a few arrays rather than a map per state.
 */
final class SecretAutomaton {

    private static final long EMPTY = -1L;

    /** Transition keys {@code state << 16 | char}, {@link #EMPTY} where free; its length a power of two. */
    private final long[] keys;

    private final int[] targets;

    /** Per state: the state of its longest proper suffix that is also a prefix of some secret. */
    private final int[] fail;

    /** Per state: the length of the longest secret that ends here, {@code 0} for none. */
    private final int[] longest;

    private int states = 1;

    SecretAutomaton(List<String> secrets) {
        int chars = 0;
        for (String s : secrets) chars += s.length();
        int capacity = Integer.highestOneBit(Math.max(16, chars * 2 + 2)) << 1;
        keys = new long[capacity];
        Arrays.fill(keys, EMPTY);
        targets = new int[capacity];
        fail = new int[chars + 1];
        longest = new int[chars + 1];
        for (String s : secrets) {
            int state = 0;
            for (int i = 0; i < s.length(); i++) {
                int next = step(state, s.charAt(i));
                if (next < 0) {
                    next = states++;
                    put(state, s.charAt(i), next);
                }
                state = next;
            }
            longest[state] = Math.max(longest[state], s.length());
        }
        link();
    }

    /** Breadth first, so a state's suffix link is final before its children take theirs from it. */
    private void link() {
        int[][] edges = edgesByState();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int slot : edges[0]) queue.add(targets[slot]);
        while (!queue.isEmpty()) {
            int state = queue.poll();
            for (int slot : edges[state]) {
                int child = targets[slot];
                char c = (char) keys[slot];
                int f = fail[state];
                while (f > 0 && step(f, c) < 0) f = fail[f];
                int to = state == 0 ? 0 : Math.max(0, step(f, c));
                fail[child] = to;
                longest[child] = Math.max(longest[child], longest[to]);
                queue.add(child);
            }
        }
    }

    /** Per state, the table slots of its outgoing transitions. */
    private int[][] edgesByState() {
        int[] count = new int[states];
        for (long key : keys) if (key != EMPTY) count[(int) (key >>> 16)]++;
        int[][] edges = new int[states][];
        for (int s = 0; s < states; s++) edges[s] = new int[count[s]];
        Arrays.fill(count, 0);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == EMPTY) continue;
            int from = (int) (keys[i] >>> 16);
            edges[from][count[from]++] = i;
        }
        return edges;
    }

    /** True when {@code text} holds any secret. */
    boolean matchesIn(String text) {
        int state = 0;
        for (int i = 0; i < text.length(); i++) {
            state = next(state, text.charAt(i));
            if (longest[state] > 0) return true;
        }
        return false;
    }

    /**
     * Every occurrence in {@code text}, as {@link Spans}: at each end position the longest secret
     * that ends there, which covers every shorter one ending at the same place.
     */
    void find(String text, Spans out) {
        int state = 0;
        for (int i = 0; i < text.length(); i++) {
            state = next(state, text.charAt(i));
            int length = longest[state];
            if (length > 0) out.add(i + 1 - length, i + 1);
        }
    }

    private int next(int state, char c) {
        while (true) {
            int to = step(state, c);
            if (to >= 0) return to;
            if (state == 0) return 0;
            state = fail[state];
        }
    }

    private int step(int state, char c) {
        long key = ((long) state << 16) | c;
        int mask = keys.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) return targets[i];
            if (k == EMPTY) return -1;
        }
    }

    private void put(int state, char c, int target) {
        long key = ((long) state << 16) | c;
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (keys[i] != EMPTY) i = (i + 1) & mask;
        keys[i] = key;
        targets[i] = target;
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }

    /**
     * Where secrets occur in one text, as {@code [start, end)} char ranges in any order. {@link #mask}
     * replaces each run of overlapping ranges with one mask, so a secret that overlaps another leaves
     * no piece of either; ranges that only touch keep a mask each.
     */
    static final class Spans {
        private static final long[] NONE = {};
        private long[] spans = NONE;
        private int size;

        void add(int start, int end) {
            if (size == spans.length) spans = Arrays.copyOf(spans, Math.max(4, size * 2));
            spans[size++] = ((long) start << 32) | end;
        }

        String mask(String text, String mask) {
            if (size == 0) return text;
            Arrays.sort(spans, 0, size);
            StringBuilder out = new StringBuilder(text.length());
            int written = 0;
            int i = 0;
            while (i < size) {
                int start = (int) (spans[i] >>> 32);
                int end = (int) spans[i];
                for (i++; i < size && (int) (spans[i] >>> 32) < end; i++) end = Math.max(end, (int) spans[i]);
                out.append(text, written, start).append(mask);
                written = end;
            }
            return out.append(text, written, text.length()).toString();
        }
    }
}
