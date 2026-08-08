// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.surface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The parts of a program that static analysis cannot see: reflection, proxies, resources,
 * serialization, service loading, JNI. R8 and GraalVM {@code native-image} both need this
 * information and neither can derive it, so jk models it once and emits it in both formats.
 *
 * <p>Entries carry a {@link Kind}, not a format. The two emitters disagree about what several
 * kinds mean — a {@link Kind#GENERIC_REFLECTION} type is a class-level keep for R8 and nothing at
 * all for native-image — and a converter between the two file formats could not express that.
 *
 * <p>Merging is a union with deterministic ordering, so profiles and sources combine without a
 * precedence fight. See {@code docs/features/dynamic-surface.md}.
 */
public record DynamicSurface(List<Entry> entries) {

    public DynamicSurface {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    public static DynamicSurface empty() {
        return new DynamicSurface(List.of());
    }

    public static DynamicSurface of(Entry... entries) {
        return new DynamicSurface(List.of(entries));
    }

    /** Why a name is in the surface. Determines what each emitter writes, or whether it writes. */
    public enum Kind {
        /** The type is looked up or instantiated reflectively. */
        REFLECTIVE_TYPE,
        /** A specific field or method is accessed reflectively; {@code members} names it. */
        REFLECTIVE_MEMBER,
        /**
         * The type's own generic signature is read at runtime ({@code Class#getTypeParameters}).
         * R8 in {@code --classfile} mode keeps a class's signature only when the class itself is
         * kept, so this needs a class-level rule. Graal retains signatures unasked, so the
         * native-image emitter skips it.
         */
        GENERIC_REFLECTION,
        /** A {@code java.lang.reflect.Proxy} interface. */
        PROXY_INTERFACE,
        /** A resource loaded by name or pattern. {@code name} is the resource path or regex. */
        RESOURCE,
        /** The type crosses a serialization boundary. */
        SERIALIZATION_TYPE,
        /** An implementation named by a service file or marker index. */
        SERVICE_IMPLEMENTATION,
        /** The type is reached from native code. */
        JNI_TYPE
    }

    /**
     * One dynamic entry point.
     *
     * @param kind why {@code name} is in the surface
     * @param name a fully-qualified class name, or a resource path for {@link Kind#RESOURCE}
     * @param members member names for {@link Kind#REFLECTIVE_MEMBER}; empty means the whole type
     * @param origin where this came from ({@code index}, {@code library}, {@code train:<profile>},
     *     {@code user}) — carried so a surprising rule can be traced back
     */
    public record Entry(Kind kind, String name, Set<String> members, String origin) implements Comparable<Entry> {

        public Entry {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            // Sorted and order-preserving: emitted rules must be byte-stable across runs, and
            // Set.copyOf would discard the ordering.
            members = members == null
                    ? Set.of()
                    : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(members)));
            origin = origin == null ? "" : origin;
        }

        public static Entry type(Kind kind, String name, String origin) {
            return new Entry(kind, name, Set.of(), origin);
        }

        /** Same entry from a different origin — the identity used when merging. */
        boolean sameTarget(Entry other) {
            return kind == other.kind && name.equals(other.name);
        }

        @Override
        public int compareTo(Entry other) {
            int byKind = kind.compareTo(other.kind);
            return byKind != 0 ? byKind : name.compareTo(other.name);
        }
    }

    /**
     * Union of {@code this} and {@code others}. Entries with the same kind and name collapse into
     * one whose members are the union of both and whose origin lists every contributor, so a rule
     * observed by two profiles is one rule that credits both.
     */
    public DynamicSurface merge(DynamicSurface... others) {
        List<Entry> all = new ArrayList<>(entries);
        for (DynamicSurface other : others) {
            if (other != null) all.addAll(other.entries());
        }
        return new DynamicSurface(collapse(all));
    }

    /** Every entry of {@code kind}, in sorted order. */
    public List<Entry> of(Kind kind) {
        return entries.stream().filter(e -> e.kind() == kind).toList();
    }

    /**
     * Sort, then fold adjacent entries with the same kind and name.
     *
     * <p>Sorting first is what makes same-target entries neighbours, so one pass collapses them.
     * A sorted set would be wrong here: {@link Entry#compareTo} orders by kind and name only, so
     * a set would discard the second entry instead of merging its members into the first.
     */
    private static List<Entry> collapse(Collection<Entry> input) {
        List<Entry> sorted = new ArrayList<>(input);
        java.util.Collections.sort(sorted);
        List<Entry> merged = new ArrayList<>(sorted.size());
        for (Entry candidate : sorted) {
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).sameTarget(candidate)) {
                Entry existing = merged.get(last);
                Set<String> members = new TreeSet<>(existing.members());
                members.addAll(candidate.members());
                merged.set(
                        last, new Entry(existing.kind(), existing.name(), members, joinOrigins(existing, candidate)));
            } else {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private static String joinOrigins(Entry a, Entry b) {
        Set<String> origins = new LinkedHashSet<>();
        for (String origin : a.origin().split(",")) {
            if (!origin.isBlank()) origins.add(origin.trim());
        }
        for (String origin : b.origin().split(",")) {
            if (!origin.isBlank()) origins.add(origin.trim());
        }
        return String.join(",", new TreeSet<>(origins));
    }
}
