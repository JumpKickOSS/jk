// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The {@code jdk} table of {@code jk.toml}, the one spelling of a project's JDK vendor and version:
 * {@code jdk = { vendor = "corretto", version = "21", required = true }}. {@code vendor} and {@code
 * version} are each optional; {@code version} is a major ({@code "21"}), an exact build ({@code
 * "25.0.4.1"}) or a floor within one major ({@code ">=25.0.5"}); {@code required} (default false)
 * binds them for the build and the cache, where by default they are a suggestion. "Any JDK at or
 * above a level" is what {@code java = N} means, so a range across majors is refused here.
 */
public final class JdkTable {

    private JdkTable() {}

    /**
     * The {@link ToolchainSpec} a table states: a suggestion, or with {@code required} the vendor and
     * an exact build as required axes (a major or a floor keeps its major as the version to resolve).
     *
     * @throws IllegalArgumentException for a version that is none of the three forms
     */
    public static ToolchainSpec spec(@Nullable String vendor, @Nullable String version, boolean required) {
        String v = version == null ? "" : version.strip();
        String ven = vendor == null ? "" : vendor.strip().toLowerCase(Locale.ROOT);
        if (!ven.isEmpty() && Character.isDigit(ven.charAt(0))) {
            throw new IllegalArgumentException("jdk.vendor = \"" + vendor + "\" must name a vendor, not a version");
        }
        String resolve = "";
        boolean exact = false;
        if (!v.isEmpty()) {
            int major = major(v);
            if (major <= 0) throw badVersion(v);
            exact = !v.startsWith(">=") && v.chars().filter(c -> c == '.').count() >= 2;
            resolve = exact ? v : Integer.toString(major);
        }
        if (!required) return new ToolchainSpec(ven, resolve, "", "");
        return new ToolchainSpec("", exact ? "" : resolve, ven, exact ? resolve : "");
    }

    /**
     * The {@code <vendor>-<major>} spec the JDK resolver takes for a table's {@code vendor} and
     * {@code version} ({@link ToolchainSpec#resolverSpec}); null when neither is given or the version
     * is none of the table's forms.
     */
    public static @Nullable String resolverSpec(@Nullable String vendor, @Nullable String version) {
        if ((vendor == null || vendor.isBlank()) && (version == null || version.isBlank())) return null;
        try {
            String spec = spec(vendor, version, false).resolverSpec();
            return spec.isEmpty() ? null : spec;
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /**
     * The major a {@code version} names: {@code 21} of {@code "21"}, {@code "21.0.4"} or {@code
     * ">=21.0.4"}; -1 for anything else (a range across majors among them).
     */
    public static int major(String version) {
        String v = version.strip();
        boolean floor = v.startsWith(">=");
        if (floor) v = v.substring(2).strip();
        if (!v.matches("\\d+(\\.\\d+)*")) return -1;
        // A floor on a bare major (">=21") is every JDK from 21 up: what java = 21 already says.
        if (floor && v.indexOf('.') < 0) return -1;
        return Integer.parseInt(v.contains(".") ? v.substring(0, v.indexOf('.')) : v);
    }

    private static IllegalArgumentException badVersion(String v) {
        if (v.contains(",") || v.contains("<") || v.contains("[") || v.contains("(") || v.matches(">=\\s*\\d+")) {
            return new IllegalArgumentException("jdk.version = \"" + v + "\" spans Java majors: \"any JDK at or above a"
                    + " level\" is what java = N means, and cache hits across majors are [cache] jdk-match = \"level\";"
                    + " jdk.version is a major (\"21\"), an exact build (\"25.0.4.1\") or a floor within one major"
                    + " (\">=25.0.5\")");
        }
        return new IllegalArgumentException("jdk.version = \"" + v + "\" must be a major (\"21\"), an exact build"
                + " (\"25.0.4.1\") or a floor within one major (\">=25.0.5\")");
    }

    /**
     * The table to write for a retired string form: {@code "temurin-25"} → {@code jdk = { vendor =
     * "temurin", version = "25" }}, {@code "=corretto-21"} → the same with {@code required = true},
     * {@code "=temurin-25.0.4"} → an exact build, required.
     */
    public static String fromRetired(String old) {
        ToolchainSpec spec;
        try {
            spec = ToolchainSpec.parse("jdk", old);
        } catch (IllegalArgumentException unparseable) {
            return "jdk = { vendor = \"…\", version = \"…\" }";
        }
        boolean required =
                !spec.requiredVendor().isEmpty() || !spec.requiredVersion().isEmpty();
        return render(spec.vendor(), spec.version(), required);
    }

    /** The table a parsed {@link ToolchainSpec} came from; null for {@link ToolchainSpec#NONE}. */
    public static @Nullable String render(ToolchainSpec spec) {
        if (spec.isEmpty()) return null;
        boolean required =
                !spec.requiredVendor().isEmpty() || !spec.requiredVersion().isEmpty();
        return render(spec.vendor(), spec.version(), required);
    }

    /** {@code jdk = { vendor = "…", version = "…", required = true }}, each part only when given. */
    public static String render(@Nullable String vendor, @Nullable String version, boolean required) {
        List<String> parts = new ArrayList<>();
        if (vendor != null && !vendor.isBlank()) parts.add("vendor = \"" + vendor + "\"");
        if (version != null && !version.isBlank()) parts.add("version = \"" + version + "\"");
        if (required) parts.add("required = true");
        return "jdk = { " + String.join(", ", parts) + " }";
    }
}
