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
     * The {@link ToolchainSpec} a table states. Without {@code required}: a suggestion (vendor, and a
     * major or an exact build). With it, every axis it names is required: the vendor, and the
     * version as written ({@code "21"}: that major; {@code "25.0.4.1"}: that build; {@code
     * ">=25.0.5"}: that patch or later within its major), which {@link #admits} reads.
     *
     * @throws IllegalArgumentException for a version that is none of the three forms, or a floor
     *     without {@code required} (a floor that binds nothing says nothing)
     */
    public static ToolchainSpec spec(@Nullable String vendor, @Nullable String version, boolean required) {
        String v = version == null ? "" : version.strip().replace(" ", "");
        String ven = vendor == null ? "" : vendor.strip().toLowerCase(Locale.ROOT);
        if (!ven.isEmpty() && Character.isDigit(ven.charAt(0))) {
            throw new IllegalArgumentException("jdk.vendor = \"" + vendor + "\" must name a vendor, not a version");
        }
        if (!v.isEmpty() && major(v) <= 0) throw badVersion(v);
        boolean floor = v.startsWith(">=");
        if (floor && !required) {
            throw new IllegalArgumentException("jdk.version = \"" + v + "\" is a patch floor, which only binds:"
                    + " add required = true, or write version = \"" + major(v) + "\" for a suggestion");
        }
        if (required) return new ToolchainSpec("", "", ven, v);
        return new ToolchainSpec(ven, v, "", "");
    }

    /**
     * Whether a JDK of {@code hitVersion} ({@code 25.0.5}, {@code 25.0.5+11-LTS}) meets a required
     * {@code version}: the same major for {@code "25"}, that patch or later within its major for
     * {@code ">=25.0.5"}, exactly that build otherwise. An empty requirement admits every JDK.
     */
    public static boolean admits(@Nullable String hitVersion, String required) {
        if (required.isEmpty()) return true;
        if (hitVersion == null) return false;
        String hit = hitVersion.contains("+") ? hitVersion.substring(0, hitVersion.indexOf('+')) : hitVersion;
        if (required.startsWith(">=")) {
            String floor = required.substring(2);
            return leading(hit) == leading(floor) && compareDotted(hit, floor) >= 0;
        }
        if (required.indexOf('.') < 0) return leading(hit) == leading(required);
        return hit.equals(required);
    }

    private static int leading(String v) {
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        return end == 0 ? -1 : Integer.parseInt(v.substring(0, end));
    }

    private static int compareDotted(String a, String b) {
        String[] x = a.split("[.]");
        String[] y = b.split("[.]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(part(x, i), part(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static int part(String[] p, int i) {
        if (i >= p.length) return 0;
        int n = leading(p[i]);
        return Math.max(n, 0);
    }

    /**
     * The {@code <vendor>-<major>} spec the JDK resolver takes for a table's {@code vendor} and
     * {@code version} ({@link ToolchainSpec#resolverSpec}); null when neither is given or the version
     * is none of the table's forms.
     */
    public static @Nullable String resolverSpec(@Nullable String vendor, @Nullable String version) {
        if ((vendor == null || vendor.isBlank()) && (version == null || version.isBlank())) return null;
        try {
            String spec = spec(vendor, version, true).resolverSpec();
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
