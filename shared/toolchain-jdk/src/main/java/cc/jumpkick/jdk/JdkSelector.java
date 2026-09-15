// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Picks the best {@link JdkCatalog.Entry} for a {@link JdkSpec} on a given host. Strategy:
 *
 * <ol>
 *   <li>Filter to entries whose {@link JdkCatalog.Entry#os() os} and {@link JdkCatalog.Entry#arch()
 *       arch} match the host.
 *   <li>Keep entries where the spec matches the entry's {@code shared_index_aliases} or {@code
 *       suggested_sdk_name} (case-insensitive).
 *   <li>For bare-version specs ({@code 21}), keep entries flagged {@code default: true} when any
 *       exist; otherwise keep all matches.
 *   <li>Prefer non-preview; then highest {@code jdk_version}.
 * </ol>
 */
public final class JdkSelector {

    private JdkSelector() {}

    /**
     * {@link #select} with jk's vendor-preference bias: when {@code rawSpec} names no vendor (a bare
     * major / version like {@code 26} or {@code 25.0.3}), the vendors in {@link JdkVendor#PREFERENCE}
     * (Temurin, Liberica, Oracle OpenJDK, Corretto) are tried in order, preferring the first that
     * satisfies the spec on this host over the feed's {@code default:true} entry-for-major. Falls
     * back to the unbiased {@link #select} when none match (or when the spec already names a vendor,
     * e.g. {@code corretto-25}).
     *
     * <p>This is the entry point every <em>install</em> path should use so the preference is
     * consistent across {@code jk jdk install <ver>}, {@code jk jdk ensure <ver>}, and the build
     * plan's auto-install — the keyword path ({@code lts}/{@code latest}) is already
     * Temurin-biased via {@link JdkKeywords#resolveToMajorSpec}. Range specs ({@code >=21}) skip the
     * bias and resolve via {@link #selectFlexible}'s vendor-ranked tie-break.
     */
    public static Optional<JdkCatalog.Entry> selectPreferred(
            JdkCatalog catalog, @Nullable String rawSpec, String os, String arch) {
        if (rawSpec == null || rawSpec.isBlank()) return Optional.empty();
        FlexibleQuery q = parseFlexible(rawSpec);
        // Bias only when the user named no vendor AND gave a concrete major/version.
        if (q.hints().isEmpty() && q.lowerBoundOpt().isEmpty() && q.majorOpt().isPresent()) {
            for (JdkVendor v : JdkVendor.PREFERENCE) {
                String prefix = v.jbPrefix().orElse(null);
                if (prefix == null) continue;
                Optional<JdkCatalog.Entry> preferred =
                        select(catalog, JdkSpec.parse(prefix + "-" + rawSpec.trim()), os, arch);
                if (preferred.isPresent()) return preferred;
            }
        }
        return select(catalog, JdkSpec.parse(rawSpec), os, arch);
    }

    public static Optional<JdkCatalog.Entry> select(JdkCatalog catalog, JdkSpec spec, String os, String arch) {
        String token = spec.normalized();
        List<JdkCatalog.Entry> matches = new ArrayList<>();
        for (JdkCatalog.Entry entry : catalog.entries()) {
            if (!entry.os().equals(os)) continue;
            if (!entry.arch().equals(arch)) continue;
            if (!matchesSpec(entry, token)) continue;
            matches.add(entry);
        }
        if (matches.isEmpty()) {
            // Strict alias / suggested-sdk-name match failed. Fall through to
            // the flexible parser so callers don't have to know the exact feed
            // vocabulary — `25-graal`, `java-17-openjdk`, `temurin-25` all
            // resolve here.
            return selectFlexible(catalog, spec.value(), os, arch);
        }

        if (spec.bareVersion()) {
            List<JdkCatalog.Entry> defaults = new ArrayList<>();
            for (JdkCatalog.Entry entry : matches) {
                if (entry.defaultForMajor()) defaults.add(entry);
            }
            if (!defaults.isEmpty()) matches = defaults;
        }

        matches.sort(Comparator.comparing(JdkCatalog.Entry::preview)
                .thenComparing((JdkCatalog.Entry e) -> versionKey(e.version()), Comparator.reverseOrder()));
        return Optional.of(matches.getFirst());
    }

    /**
     * Permissive selector: parses denormalized identifiers like {@code 25-graal}, {@code temurin-25},
     * {@code 17.0.19}, {@code java-17-openjdk}, {@code corretto-21} into a {@code (major,
     * exactVersion?, hints[])} tuple and picks the highest-ranked entry whose feed metadata satisfies
     * it.
     *
     * <p>Scoring: each token is worth one point if it matches the entry's vendor / product /
     * suggestedSdkName / alias (case-insensitive); exact version match adds a heavy weight;
     * default-for-major wins ties; latest version breaks remaining ties. Returns empty if nothing
     * satisfies the version constraint on the target host.
     */
    public static Optional<JdkCatalog.Entry> selectFlexible(JdkCatalog catalog, String raw, String os, String arch) {
        var query = parseFlexible(raw);

        List<Scored> scored = new ArrayList<>();
        for (JdkCatalog.Entry entry : catalog.entries()) {
            if (!entry.os().equals(os)) continue;
            if (!entry.arch().equals(arch)) continue;
            // A range bound (">=21") is a hard filter; otherwise an exact major
            // (when present) is hard — else we'd offer a JDK 21 for input "graal"
            // which the user almost certainly didn't ask for. Exact-version too.
            if (query.lowerBoundOpt().isPresent()) {
                if (!query.lowerBoundOpt().get().satisfiedBy(entry.majorVersion())) continue;
            } else if (query.majorOpt().isPresent()
                    && entry.majorVersion() != query.majorOpt().get()) {
                continue;
            }
            if (query.exactVersionOpt().isPresent()
                    && !entry.version().startsWith(query.exactVersionOpt().get())) continue;
            int score = scoreHints(entry, query.hints());
            // Reject entries that satisfy zero hints when the user supplied any
            // — they're meaningfully off-target.
            if (!query.hints().isEmpty() && score == 0) continue;
            scored.add(new Scored(entry, score));
        }
        if (scored.isEmpty()) return Optional.empty();

        Comparator<Scored> order;
        if (query.lowerBoundOpt().isPresent()) {
            // Range: the LOWEST major satisfying the bound wins (">=21" → 21, not
            // the newest), then vendor preference, default-for-major, GA, version.
            order = Comparator.comparingInt((Scored s) -> s.entry.majorVersion())
                    .thenComparingInt(s -> -s.score)
                    .thenComparingInt(s -> vendorRank(s.entry))
                    .thenComparing(s -> s.entry.defaultForMajor() ? 0 : 1)
                    .thenComparing(s -> s.entry.preview() ? 1 : 0)
                    .thenComparing((Scored s) -> versionKey(s.entry.version()), Comparator.reverseOrder());
        } else {
            order = Comparator
                    // Higher hint score first.
                    .comparingInt((Scored s) -> s.score)
                    .reversed()
                    // Vendor preference breaks ties a hint alone can't — e.g. "graalvm"
                    // matches both Oracle GraalVM and GraalVM CE feed entries equally;
                    // JdkVendor#preferenceRank ranks Oracle GraalVM ahead of GraalVM CE
                    // (and any other vendor a hint can't disambiguate) by enum order.
                    .thenComparingInt((Scored s) -> vendorRank(s.entry))
                    // Default-for-major preferred when there are no hint scores to
                    // separate (esp. bare-major inputs like "25").
                    .thenComparing(s -> s.entry.defaultForMajor() ? 0 : 1)
                    // Non-preview preferred.
                    .thenComparing(s -> s.entry.preview() ? 1 : 0)
                    // Highest version wins remaining ties.
                    .thenComparing((Scored s) -> versionKey(s.entry.version()), Comparator.reverseOrder());
        }
        scored.sort(order);
        return Optional.of(scored.getFirst().entry);
    }

    /** Outcome of {@link #parseFlexible} — the tokens we extracted from raw input. */
    public record FlexibleQuery(
            @Nullable Integer major,
            @Nullable String exactVersion,
            List<String> hints,
            @Nullable Bound lowerBound) {

        /** Three-arg form (no range bound). */
        public FlexibleQuery(@Nullable Integer major, @Nullable String exactVersion, List<String> hints) {
            this(major, exactVersion, hints, null);
        }

        public Optional<Integer> majorOpt() {
            return Optional.ofNullable(major);
        }

        public Optional<String> exactVersionOpt() {
            return Optional.ofNullable(exactVersion);
        }

        public Optional<Bound> lowerBoundOpt() {
            return Optional.ofNullable(lowerBound);
        }

        /** A {@code >N} / {@code >=N} lower bound on the major version. */
        public record Bound(int major, boolean inclusive) {
            public boolean satisfiedBy(int candidateMajor) {
                return inclusive ? candidateMajor >= major : candidateMajor > major;
            }
        }
    }

    /**
     * Token-walker that handles the denormalized forms callers care about. Splits on {@code -} /
     * {@code _}; numeric-leading tokens become version info, everything else lands in {@code hints}.
     * {@code "java"} is dropped (it's a noise word in inputs like {@code java-17-openjdk}); {@code
     * "jdk"} gets the same treatment.
     */
    public static FlexibleQuery parseFlexible(@Nullable String raw) {
        if (raw == null) return new FlexibleQuery(null, null, List.of());
        var tokens = raw.toLowerCase(Locale.ROOT).split("[-_]");
        Integer major = null;
        String exact = null;
        FlexibleQuery.Bound bound = null;
        var hints = new ArrayList<String>();
        for (var tok : tokens) {
            if (tok.isEmpty()) continue;
            if (tok.charAt(0) == '>') {
                // Range bound: ">N" (exclusive) or ">=N" (inclusive).
                boolean inclusive = tok.length() > 1 && tok.charAt(1) == '=';
                String num = tok.substring(inclusive ? 2 : 1);
                try {
                    int m = Integer.parseInt(num);
                    if (bound == null) bound = new FlexibleQuery.Bound(m, inclusive);
                } catch (NumberFormatException ignored) {
                    // not a clean ">N" — ignore the token
                }
                continue;
            }
            if (Character.isDigit(tok.charAt(0))) {
                int dot = tok.indexOf('.');
                if (dot < 0) {
                    try {
                        int m = Integer.parseInt(tok);
                        if (major == null) major = m;
                    } catch (NumberFormatException ignored) {
                        hints.add(tok);
                    }
                } else {
                    // Dotted: take everything as exact-version prefix, derive major.
                    if (exact == null) exact = tok;
                    try {
                        int m = Integer.parseInt(tok.substring(0, dot));
                        if (major == null) major = m;
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (tok.equals("java") || tok.equals("jdk")) {
                // Noise words — they don't disambiguate (every entry is a JDK).
            } else {
                hints.add(tok);
            }
        }
        return new FlexibleQuery(major, exact, List.copyOf(hints), bound);
    }

    /**
     * +1 per hint that matches the entry's vendor / product / suggestedSdkName / any alias
     * (case-insensitive substring), or that names the entry's vendor through another catalog's
     * identifier ({@link #namesVendorOf}). Substring rather than equality so {@code "graal"} hits
     * {@code "graalvm-jdk-25"}.
     */
    private static int scoreHints(JdkCatalog.Entry entry, List<String> hints) {
        if (hints.isEmpty()) return 0;
        var haystack = (entry.vendor()
                        + " "
                        + entry.product()
                        + " "
                        + entry.suggestedSdkName()
                        + " "
                        + String.join(" ", entry.aliases()))
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (var h : hints) {
            if (haystack.contains(h) || namesVendorOf(entry, haystack, h)) score++;
        }
        return score;
    }

    /**
     * Whether {@code hint} is a vendor identifier from another catalog's vocabulary — the SDKMAN
     * suffix {@code graalce}, the foojay distro {@code graalvm_ce} — for the vendor {@code entry}
     * belongs to. The feed spells a vendor its own way ({@code GraalVM Community}, {@code
     * graalvm-ce-25}), so such a token never appears in the haystack; it matches through {@link
     * JdkVendor#fromAlias} instead, against the feed's own reading of the entry or the vendor's
     * JetBrains prefix in the entry's names.
     */
    private static boolean namesVendorOf(JdkCatalog.Entry entry, String haystack, String hint) {
        Optional<JdkVendor> vendor = JdkVendor.fromAlias(hint);
        if (vendor.isEmpty()) return false;
        if (JdkVendor.fromFeed(entry.vendor(), entry.product()) == vendor.get()) return true;
        return vendor.get()
                .jbPrefix()
                .map(prefix -> haystack.contains(prefix.toLowerCase(Locale.ROOT)))
                .orElse(false);
    }

    private record Scored(JdkCatalog.Entry entry, int score) {}

    /** Vendor-preference rank of a catalog entry (lower = more preferred). */
    private static int vendorRank(JdkCatalog.Entry e) {
        return JdkVendor.fromFeed(e.vendor(), e.product()).preferenceRank();
    }

    private static boolean matchesSpec(JdkCatalog.Entry entry, String token) {
        if (entry.suggestedSdkName().equalsIgnoreCase(token)) return true;
        for (String alias : entry.aliases()) {
            if (alias.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    /**
     * Sortable key for a JDK version string. Splits on {@code .}, {@code +}, {@code -}, pads numeric
     * parts to a fixed width so lexicographic comparison agrees with numeric ordering ({@code 21.0.9}
     * &lt; {@code 21.0.10}).
     */
    public static String versionKey(String version) {
        if (version == null) return "";
        StringBuilder sb = new StringBuilder();
        StringBuilder run = new StringBuilder();
        boolean numericRun = false;
        for (int i = 0; i <= version.length(); i++) {
            char c = i < version.length() ? version.charAt(i) : '.';
            boolean isDigit = c >= '0' && c <= '9';
            if (isDigit) {
                if (!numericRun && run.length() > 0) {
                    sb.append(run).append('|');
                    run.setLength(0);
                }
                numericRun = true;
                run.append(c);
            } else {
                if (numericRun) {
                    sb.append(pad(run.toString())).append('|');
                    run.setLength(0);
                }
                numericRun = false;
                if (i < version.length()) run.append(c);
            }
        }
        if (run.length() > 0) sb.append(run);
        return sb.toString();
    }

    private static String pad(String numeric) {
        // 10-digit pad covers anything realistic.
        int needed = 10 - numeric.length();
        if (needed <= 0) return numeric;
        StringBuilder out = new StringBuilder(10);
        for (int i = 0; i < needed; i++) out.append('0');
        out.append(numeric);
        return out.toString();
    }
}
