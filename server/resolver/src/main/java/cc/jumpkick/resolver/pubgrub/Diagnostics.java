// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.model.PackageId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * English explanation of an {@link UnsatisfiableException} incompatibility DAG (post-order leaves
 * then "therefore" derivations; shared nodes labeled {@code (#n)}). Optional ANSI coloring is
 * self-contained (no CLI theme dependency).
 */
public final class Diagnostics {

    /**
     * ANSI color strings for one rendered diagnostic. Callers in the CLI layer build this from the
     * live theme (so updating {@code coordVersion()} in {@code JkDarkTheme} propagates here
     * automatically); the {@link #DEFAULT} is used when no palette is supplied.
     *
     * <p>Each field is either a 24-bit SGR opener ({@code "\033[38;2;R;G;Bm"}) when color is
     * enabled, or an empty string when it is not.
     *
     * @param reset  SGR reset sequence
     * @param header color for the {@code ‼} error header (NORMAL_RED)
     * @param rail   color for the {@code │} rail glyph (BRIGHT_BLACK)
     * @param group  color for the group segment of a {@code group:artifact} coord (NORMAL_CYAN)
     * @param name   color for the artifact segment (BRIGHT_CYAN)
     * @param version color for version constraints (coordVersion — midpoint cyan/white)
     */
    public record Palette(String reset, String header, String rail, String group, String name, String version) {

        /** Default palette — hardcoded to match {@code JkDarkTheme}. Used when no palette is injected. */
        public static final Palette DEFAULT = fromRgb(
                0x00, 0xD4, 0xE0, // NORMAL_CYAN  — coordGroup
                0x00, 0xF0, 0xFF, // BRIGHT_CYAN  — coordName
                0xC1, 0xFB, 0xFC, // COORD_VERSION — coordVersion (#C1FBFC)
                0xFF, 0x33, 0x66, // NORMAL_RED   — ‼ header
                0x54, 0x6E, 0x7A); // BRIGHT_BLACK — │ rail

        /** Plain palette — no colors. */
        public static final Palette PLAIN = new Palette("", "", "", "", "", "");

        /** Build a palette from raw RGB components supplied by the CLI theme layer. */
        public static Palette fromRgb(
                int groupR,
                int groupG,
                int groupB,
                int nameR,
                int nameG,
                int nameB,
                int verR,
                int verG,
                int verB,
                int headerR,
                int headerG,
                int headerB,
                int railR,
                int railG,
                int railB) {
            return new Palette(
                    "\033[m",
                    rgb(headerR, headerG, headerB),
                    rgb(railR, railG, railB),
                    rgb(groupR, groupG, groupB),
                    rgb(nameR, nameG, nameB),
                    rgb(verR, verG, verB));
        }

        private static String rgb(int r, int g, int b) {
            return "\033[38;2;" + r + ";" + g + ";" + b + "m";
        }
    }

    /** The synthetic root package injected by PubGrubResolver — not meaningful to users. */
    private static final String ROOT_PKG = "<root>";

    private Diagnostics() {}

    public static String render(Incompatibility rootCause) {
        return render(rootCause, Palette.PLAIN);
    }

    /** Compatibility overload; {@code rootDepNames} ignored. */
    public static String render(Incompatibility rootCause, Map<String, String> rootDepNames) {
        return render(rootCause, Palette.PLAIN);
    }

    /** Compatibility overload; {@code rootDepNames} ignored. */
    public static String render(Incompatibility rootCause, Map<String, String> rootDepNames, boolean ansi) {
        return render(rootCause, ansi ? Palette.DEFAULT : Palette.PLAIN);
    }

    public static String render(Incompatibility rootCause, Palette palette) {
        Map<Incompatibility, Integer> incomingEdges = new HashMap<>();
        countIncomingEdges(rootCause, incomingEdges, new HashSet<>());

        // Anything referenced from more than one parent gets a number so
        // its second mention is a back-reference instead of a re-rendering.
        Map<Incompatibility, Integer> numbered = new LinkedHashMap<>();
        int nextNumber = 1;
        for (Map.Entry<Incompatibility, Integer> e : incomingEdges.entrySet()) {
            if (e.getValue() > 1) {
                numbered.put(e.getKey(), nextNumber++);
            }
        }

        StringBuilder out = new StringBuilder();
        boolean ansi = !palette.reset().isEmpty();
        String bang = ansi ? palette.header() + "‼" + palette.reset() : "‼";
        out.append(bang).append(" Cannot resolve dependencies:\n");
        Set<Incompatibility> emitted = new HashSet<>();
        String rail = ansi ? palette.rail() + "  │" + palette.reset() + " " : "  │ ";
        renderInco(rootCause, out, rail, emitted, numbered, palette);
        out.append('\n');
        appendSuggestions(rootCause, out, palette);
        return out.toString();
    }

    /**
     * Best-effort actionable next steps (pin / relax). Offline; only uses info already on the
     * incompatibility DAG (e.g. NoVersions.available samples). At most three lines.
     */
    static void appendSuggestions(Incompatibility rootCause, StringBuilder out, Palette palette) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        collectSuggestions(rootCause, lines, new HashSet<>());
        if (lines.isEmpty()) return;
        out.append("Suggestions:\n");
        int n = 0;
        for (String line : lines) {
            if (n++ >= 3) break;
            out.append("  • ").append(line).append('\n');
        }
    }

    private static void collectSuggestions(
            Incompatibility inco, LinkedHashSet<String> lines, Set<Incompatibility> visited) {
        if (inco == null || !visited.add(inco)) return;
        switch (inco.cause()) {
            case Incompatibility.Cause.NoVersions nv -> {
                if (nv.unknownPackage()) {
                    // Honest: do not invent a pin for a package that does not exist.
                    return;
                }
                if (nv.available().isEmpty()) return;
                String display = displayPkg(nv.pkg());
                String candidate = nv.available().getFirst();
                // Highest-first sample from the solver — first entry is the best advertised pin.
                lines.add("Pin " + display + " to " + candidate + " (e.g. `" + pinExample(display, candidate) + "`)");
                int n = Math.min(3, nv.available().size());
                lines.add("Or relax the version constraint on "
                        + display
                        + " so one of ["
                        + String.join(", ", nv.available().subList(0, n))
                        + "] is allowed");
            }
            case Incompatibility.Cause.Dependency dep -> {
                if (ROOT_PKG.equals(dep.from().pkg())
                        && !ROOT_PKG.equals(dep.to().pkg())) {
                    lines.add("Relax or remove the project constraint on "
                            + displayPkg(dep.to().pkg()));
                }
            }
            case Incompatibility.Cause.Derived d -> {
                collectSuggestions(d.a(), lines, visited);
                collectSuggestions(d.b(), lines, visited);
            }
            default -> {}
        }
    }

    private static String displayPkg(String pkg) {
        if (pkg == null || pkg.isBlank() || ROOT_PKG.equals(pkg)) return pkg;
        if (PackageId.isMavenPackageKey(pkg)) {
            try {
                return PackageId.parse(pkg).display();
            } catch (RuntimeException ignored) {
                return pkg;
            }
        }
        return pkg;
    }

    private static String pinExample(String display, String version) {
        // Prefer a coord-shaped example when display is g:a or g:a:classifier.
        if (display.contains(":")) {
            return display + ":" + version;
        }
        return display + " = \"" + version + "\"";
    }

    private static void countIncomingEdges(
            Incompatibility inco, Map<Incompatibility, Integer> counts, Set<Incompatibility> visited) {
        if (!visited.add(inco)) return;
        if (inco.cause() instanceof Incompatibility.Cause.Derived d) {
            counts.merge(d.a(), 1, Integer::sum);
            counts.merge(d.b(), 1, Integer::sum);
            countIncomingEdges(d.a(), counts, visited);
            countIncomingEdges(d.b(), counts, visited);
        }
    }

    private static void renderInco(
            Incompatibility inco,
            StringBuilder out,
            String prefix,
            Set<Incompatibility> emitted,
            Map<Incompatibility, Integer> numbered,
            Palette palette) {
        boolean ansi = !palette.reset().isEmpty();

        if (emitted.contains(inco)) {
            Integer ref = numbered.get(inco);
            if (ref != null) {
                out.append(prefix).append("(see #").append(ref).append(")\n");
            }
            return;
        }
        emitted.add(inco);
        String label = numbered.containsKey(inco) ? "#" + numbered.get(inco) + " " : "";

        switch (inco.cause()) {
            case Incompatibility.Cause.Derived d -> {
                renderInco(d.a(), out, prefix, emitted, numbered, palette);
                renderInco(d.b(), out, prefix, emitted, numbered, palette);
                out.append(prefix)
                        .append(label)
                        .append("Therefore, ")
                        .append(describeConclusion(inco, palette))
                        .append('\n');
            }
            case Incompatibility.Cause.Dependency dep -> {
                // A coordinate keeps its case at the head of the sentence: ch.qos.logback is a
                // name, and capitalising it spells a group id nothing publishes.
                String from = isRoot(dep.from()) ? "The project" : describe(dep.from(), palette);
                // A negative target is a constraint: it bounds the package to the complement of
                // its set without requiring it, so it reads as what it allows.
                boolean constraint = !dep.to().positive();
                Term to = constraint ? Term.positive(dep.to().pkg(), dep.to().effectiveVersions()) : dep.to();
                out.append(prefix)
                        .append(label)
                        .append(from)
                        .append(constraint ? " constrains " : " depends on ")
                        .append(describe(to, palette))
                        .append('\n');
            }
            case Incompatibility.Cause.NoVersions nv -> {
                if (nv.unknownPackage()) {
                    out.append(prefix)
                            .append(label)
                            .append("Package ")
                            .append(colorPkg(nv.pkg(), palette))
                            .append(" was not found in any repository\n");
                } else {
                    out.append(prefix)
                            .append(label)
                            .append("No versions of ")
                            .append(colorPkg(nv.pkg(), palette))
                            .append(" match ")
                            .append(colorVersion(stripBraces(nv.requested().toString()), palette))
                            .append('\n');
                    if (!nv.available().isEmpty()) {
                        out.append(prefix)
                                .append("  available: ")
                                .append(colorVersion(String.join(", ", nv.available()), palette));
                        // Hint that the list may be truncated (solver samples highest-first).
                        if (nv.available().size() >= 12) {
                            out.append(", …");
                        }
                        out.append('\n');
                    }
                }
                for (String note : nv.notes()) {
                    out.append(prefix).append("  ").append(note).append('\n');
                }
            }
            case Incompatibility.Cause.Unavailable u ->
                out.append(prefix)
                        .append(label)
                        .append(colorPkg(u.pkg(), palette))
                        .append(' ')
                        .append(colorVersion(u.version(), palette))
                        .append(
                                u.declaredOnly()
                                        ? " is named by a POM but no declared repository has it — skipped\n"
                                        : " is advertised but not fetchable (half-published release?) — skipped\n");
            case Incompatibility.Cause.BudgetExceeded b ->
                out.append(prefix)
                        .append(label)
                        .append("Resolution budget exceeded: ")
                        .append(b.reason())
                        .append('\n');
            case Incompatibility.Cause.Root r ->
                out.append(prefix).append(label).append("The project\n");
        }
    }

    private static String describeConclusion(Incompatibility inco, Palette palette) {
        boolean ansi = !palette.reset().isEmpty();
        List<Term> terms = inco.terms();
        if (terms.isEmpty()) return "this combination is impossible";
        // All-root conclusion: plain prose instead of exposing the synthetic <root> token.
        boolean allRoot = terms.stream().allMatch(t -> ROOT_PKG.equals(t.pkg()));
        if (allRoot) return "the project's requirements cannot be resolved";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sb.append(" and ");
            sb.append(describeForConclusion(terms.get(i), palette));
        }
        sb.append(" cannot be resolved");
        return sb.toString();
    }

    /** Compact user-facing version of {@link Term#toString()}. */
    private static String describe(Term term, Palette palette) {
        boolean ansi = !palette.reset().isEmpty();
        if (isRoot(term)) return "the project";
        String prefix = term.positive() ? "" : "not ";
        VersionSet vs = term.versions();
        if (vs instanceof VersionSet.Range r
                && r.min() != null
                && r.max() != null
                && r.minInclusive()
                && r.maxInclusive()
                && r.min().equals(r.max())) {
            return prefix + colorPkg(term.pkg(), palette) + " " + colorVersion(r.min(), palette);
        }
        return prefix + colorPkg(term.pkg(), palette) + " " + colorVersion(stripBraces(vs.toString()), palette);
    }

    /** Like {@link #describe} but used inside conclusion sentences (already lower-case context). */
    private static String describeForConclusion(Term term, Palette palette) {
        boolean ansi = !palette.reset().isEmpty();
        if (isRoot(term)) return "the project";
        return describe(term, palette);
    }

    /** True when the term refers to the synthetic PubGrub root package. */
    private static boolean isRoot(Term term) {
        return ROOT_PKG.equals(term.pkg());
    }

    private static boolean isRoot(Incompatibility.Cause.Dependency dep) {
        return ROOT_PKG.equals(dep.from().pkg());
    }

    /**
     * Color a package coordinate. {@code group:artifact} gets group in coordGroup and artifact in
     * coordName; bare/synthetic names fall back to coordName.
     */
    private static String colorPkg(String pkg, Palette palette) {
        boolean ansi = !palette.reset().isEmpty();
        String shown = displayPkg(pkg);
        if (!ansi) return shown;
        int colon = shown.indexOf(':');
        if (colon < 0) return palette.name() + shown + palette.reset();
        // group:artifact — color first segment only (classifier/!type stay with name color)
        return palette.group()
                + shown.substring(0, colon)
                + palette.reset()
                + ":"
                + palette.name()
                + shown.substring(colon + 1)
                + palette.reset();
    }

    private static String colorVersion(String version, Palette palette) {
        boolean ansi = !palette.reset().isEmpty();
        if (!ansi) return version;
        return palette.version() + version + palette.reset();
    }

    /** Strip PubGrub's {@code {…}} wrapper from single-version VersionSet strings. */
    private static String stripBraces(String s) {
        if (s.length() >= 2 && s.charAt(0) == '{' && s.charAt(s.length() - 1) == '}') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
