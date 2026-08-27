// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * A toolchain declaration from {@code jk.toml} — {@code jdk} / {@code jdk-vendor} /
 * {@code jdk-version}, or the {@code [native]} trio for GraalVM — split into what the project
 * <em>suggests</em> and what it <em>requires</em>.
 *
 * <p>A bare spec is a suggestion: it is recorded in the lock and its major becomes a floor, so a
 * later build on a newer JDK is fine and an older one is not. A leading {@code =} makes the spec
 * required: the vendor, the version, or both must match exactly, and jk installs that toolchain
 * rather than settling for what is on the machine. The two axes are independent —
 * {@code jdk-vendor = "=corretto"} with {@code jdk-version = 25} requires Corretto at any 25+.
 *
 * <p>{@code =} carries the same meaning here as in the dependency version grammar, where
 * {@code "=1.1.4"} nails one release instead of floating.
 *
 * <p>Vendors are lower-cased short ids ({@code temurin}, {@code graalvm-ce}); versions keep the
 * text as written, because a point release is exactly what a required pin is about.
 */
public record ToolchainSpec(
        String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion) {

    /** Nothing declared. */
    public static final ToolchainSpec NONE = new ToolchainSpec("", "", "", "");

    public ToolchainSpec {
        suggestedVendor = vendorId(suggestedVendor);
        requiredVendor = vendorId(requiredVendor);
        suggestedVersion = blankToEmpty(suggestedVersion);
        requiredVersion = blankToEmpty(requiredVersion);
    }

    public boolean isEmpty() {
        return suggestedVendor.isEmpty()
                && suggestedVersion.isEmpty()
                && requiredVendor.isEmpty()
                && requiredVersion.isEmpty();
    }

    /** The vendor to honour: the required one when there is one, else the suggestion ("" for none). */
    public String vendor() {
        return requiredVendor.isEmpty() ? suggestedVendor : requiredVendor;
    }

    /** The version to honour: the required one when there is one, else the suggestion ("" for none). */
    public String version() {
        return requiredVersion.isEmpty() ? suggestedVersion : requiredVersion;
    }

    /**
     * The bare {@code <vendor>-<major>} spec the JDK resolver has always taken — a projection of
     * this record, so the two can never drift. Empty when nothing was declared.
     */
    public String resolverSpec() {
        String v = version();
        if (isKeyword(v)) return v;
        int major = Project.majorOf(v);
        String ven = vendor();
        if (major == 0) return ven;
        return ven.isEmpty() ? Integer.toString(major) : ven + "-" + major;
    }

    /** As {@link #resolverSpec()}, but borrowing {@code fallbackMajor} when only a vendor was named. */
    public String resolverSpec(int fallbackMajor) {
        String spec = resolverSpec();
        if (spec.isEmpty() || isKeyword(spec) || Project.majorOf(spec) > 0 || fallbackMajor <= 0) return spec;
        return spec + "-" + fallbackMajor;
    }

    /**
     * Merges the two ways of writing one toolchain: the combined {@code jdk} form and the
     * {@code jdk-vendor} / {@code jdk-version} pair. Naming both is an error — they would have to
     * be reconciled, and any rule for that would be a guess at what the author meant.
     *
     * @param label the key being read, for error text ({@code "jdk"}, {@code "[native].graal"})
     */
    public static ToolchainSpec of(String label, String combined, String vendorRaw, String versionRaw) {
        boolean hasCombined = combined != null && !combined.isBlank();
        boolean hasParts = notBlank(vendorRaw) || notBlank(versionRaw);
        if (hasCombined && hasParts) {
            throw new IllegalArgumentException(label
                    + " and "
                    + label
                    + "-vendor/"
                    + label
                    + "-version both set — use one or the other");
        }
        if (hasCombined) return parse(label, combined);
        if (!hasParts) return NONE;
        Part vendor = part(label + "-vendor", vendorRaw, false);
        Part version = part(label + "-version", versionRaw, true);
        return new ToolchainSpec(
                vendor.required ? "" : vendor.text,
                version.required ? "" : version.text,
                vendor.required ? vendor.text : "",
                version.required ? version.text : "");
    }

    /**
     * Parses the combined form: {@code "temurin-25"}, {@code "=temurin-25.0.4"} (IntelliJ order) or
     * {@code "=25.2.4-graalce"} (SDKMAN order), plus the bare keywords.
     */
    public static ToolchainSpec parse(String label, String raw) {
        Part whole = part(label, raw, true);
        if (whole.text.isEmpty()) return NONE;
        if (isKeyword(whole.text)) return new ToolchainSpec("", whole.text, "", "");

        String[] tok = whole.text.split("-");
        int at = -1;
        for (int i = 0; i < tok.length; i++) {
            if (!tok[i].isEmpty() && Character.isDigit(tok[i].charAt(0))) {
                at = i;
                break;
            }
        }
        String version = at < 0 ? "" : tok[at];
        String vendor = at < 0 ? whole.text : join(tok, 0, at);
        // SDKMAN writes the vendor after the version (25.2.4-graalce); IntelliJ before it.
        if (vendor.isEmpty() && at >= 0) vendor = join(tok, at + 1, tok.length);

        // An = pin needs something exact to hold on to. A bare major has no point release, so the
        // major stays a floor and the = binds the vendor — but with no vendor either, = says nothing.
        boolean exactVersion = whole.required && version.indexOf('.') >= 0;
        if (whole.required && !exactVersion && vendor.isEmpty()) {
            throw new IllegalArgumentException(label
                    + " = \"=" + whole.text + "\" pins nothing — an = version needs a point release"
                    + " (e.g. \"=25.0.4\"), or name a vendor to pin (e.g. \"=temurin-25\")");
        }
        return new ToolchainSpec(
                whole.required ? "" : vendor,
                exactVersion ? "" : version,
                whole.required ? vendor : "",
                exactVersion ? version : "");
    }

    /** Keywords the resolver reads directly ({@code lts}, {@code stable}, {@code latest}, {@code native}). */
    public static boolean isKeyword(String spec) {
        if (spec == null) return false;
        String n = spec.toLowerCase(Locale.ROOT);
        return n.equals("lts") || n.equals("stable") || n.equals("latest") || n.equals("native");
    }

    private record Part(String text, boolean required) {}

    private static Part part(String label, String raw, boolean allowVersion) {
        if (raw == null) return new Part("", false);
        String s = raw.trim();
        boolean required = s.startsWith("=");
        if (required) s = s.substring(1).trim();
        if (s.isEmpty() && required) {
            throw new IllegalArgumentException(label + " = \"=\" names nothing to pin");
        }
        if (!allowVersion && !s.isEmpty() && Character.isDigit(s.charAt(0))) {
            throw new IllegalArgumentException(label + " = \"" + s + "\" must name a vendor, not a version");
        }
        return new Part(s.toLowerCase(Locale.ROOT), required);
    }

    private static String join(String[] tok, int from, int to) {
        return String.join("-", Arrays.copyOfRange(tok, from, Math.max(from, to)));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToEmpty(String s) {
        return s == null || s.isBlank() ? "" : s.trim();
    }

    private static String vendorId(String s) {
        return s == null || s.isBlank() ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
