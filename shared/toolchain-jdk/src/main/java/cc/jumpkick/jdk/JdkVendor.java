// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * JDK vendors jk recognises on disk, mapped to JetBrains feed / SDKMAN / foojay identifiers.
 * {@link #fromRelease} reads {@code $JAVA_HOME/release}; unknown → {@link #UNKNOWN}.
 */
public enum JdkVendor {

    //                  vendor               product         jbPrefix         sdkman      foojay
    TEMURIN("Eclipse", "Temurin", "temurin", "tem", "temurin"),
    ADOPT_OPENJDK("AdoptOpenJDK", "OpenJDK", null, "adpt", "aoj"),
    ORACLE_OPENJDK("Oracle", "OpenJDK", "openjdk", "open", "oracle_open_jdk"),
    ORACLE_GRAALVM("Oracle", "GraalVM", "graalvm", "graal", "graalvm"),
    GRAALVM_CE("GraalVM Community", "GraalVM CE", "graalvm-ce", "graalce", "graalvm_ce"),
    CORRETTO("Amazon", "Corretto", "corretto", "amzn", "corretto"),
    ZULU("Azul", "Zulu", "zulu", "zulu", "zulu"),
    ZULU_PRIME("Azul", "Zulu Prime", null, "zing", "zulu_prime"),
    LIBERICA("BellSoft", "Liberica", "liberica", "librca", "liberica"),
    SAPMACHINE("SAP", "SapMachine", "sapmachine", "sapmchn", "sap_machine"),
    SEMERU("IBM", "Semeru", "semeru", "sem", "semeru"),
    MICROSOFT("Microsoft", "OpenJDK", "microsoft", "ms", "microsoft"),
    DRAGONWELL("Alibaba", "Dragonwell", "dragonwell", null, "dragonwell"),
    JBR("JetBrains", "Runtime", "jbr", null, "jetbrains"),
    REDHAT("Red Hat", "OpenJDK", null, null, "redhat"),
    MANDREL("Red Hat", "Mandrel", null, null, "mandrel"),
    KONA("Tencent", "Kona", null, null, "kona"),
    BISHENG("Huawei", "Bisheng", null, null, "bisheng"),
    OJDKBUILD("ojdkbuild", "OpenJDK", null, null, "ojdk_build"),
    OPENLOGIC("OpenLogic", "OpenJDK", null, null, "openlogic"),
    DEBIAN("Debian", "OpenJDK", null, null, "debian"),
    UBUNTU("Ubuntu", "OpenJDK", null, null, "ubuntu"),
    HOMEBREW("Homebrew", "OpenJDK", null, null, "homebrew"),
    UNKNOWN("Unknown", "OpenJDK", null, null, null);

    private final String vendor;
    private final String product;
    private final @Nullable String jbPrefix;
    private final @Nullable String sdkmanSuffix;
    private final @Nullable String foojayDistro;

    JdkVendor(
            String vendor,
            String product,
            @Nullable String jbPrefix,
            @Nullable String sdkmanSuffix,
            @Nullable String foojayDistro) {
        this.vendor = vendor;
        this.product = product;
        this.jbPrefix = jbPrefix;
        this.sdkmanSuffix = sdkmanSuffix;
        this.foojayDistro = foojayDistro;
    }

    /** JetBrains feed {@code vendor} field (e.g. {@code "Eclipse"}, {@code "Oracle"}). */
    public String vendor() {
        return vendor;
    }

    /** JetBrains feed {@code product} field (e.g. {@code "Temurin"}, {@code "GraalVM"}). */
    public String product() {
        return product;
    }

    /** Vendor + product, joined for display (e.g. {@code "Eclipse Temurin"}). */
    public String displayName() {
        // Prefer vendor alone when it already carries the product family name
        // (e.g. "GraalVM Community" + "GraalVM CE" → "GraalVM Community").
        String v = vendor.toLowerCase(Locale.ROOT);
        String p = product.toLowerCase(Locale.ROOT);
        if (v.startsWith(p)) {
            return vendor;
        }
        int space = product.indexOf(' ');
        String family = space < 0 ? p : product.substring(0, space).toLowerCase(Locale.ROOT);
        if (v.startsWith(family) && v.length() > family.length()) {
            return vendor;
        }
        return vendor + " " + product;
    }

    /**
     * JetBrains {@code suggested_sdk_name} prefix, or empty when this vendor isn't in the JetBrains
     * feed. The full identifier for a specific install is {@code jbPrefix + "-" + version} (e.g.
     * {@code "temurin-21.0.5"}).
     */
    public Optional<String> jbPrefix() {
        return Optional.ofNullable(jbPrefix);
    }

    /**
     * SDKMAN candidate suffix, or empty when this vendor isn't on SDKMAN. The full identifier is
     * {@code version + "-" + sdkmanSuffix} (e.g. {@code "21.0.5-tem"}).
     */
    public Optional<String> sdkmanSuffix() {
        return Optional.ofNullable(sdkmanSuffix);
    }

    /** foojay Disco API {@code distro} name, or empty when not represented there. */
    public Optional<String> foojayDistro() {
        return Optional.ofNullable(foojayDistro);
    }

    /** {@code jbPrefix + "-" + version}, e.g. {@code "corretto-26.0.1"}; empty when no prefix. */
    public Optional<String> jbIdentifier(String version) {
        return jbPrefix().map(p -> p + "-" + version);
    }

    /** {@code version + "-" + sdkmanSuffix}, e.g. {@code "26.0.1-amzn"}; empty when no suffix. */
    public Optional<String> sdkmanIdentifier(String version) {
        return sdkmanSuffix().map(s -> version + "-" + s);
    }

    /**
     * Vendor preference for a vendor-unqualified spec (and for breaking ties): Eclipse Temurin, then
     * BellSoft Liberica, Oracle OpenJDK, Amazon Corretto. Any vendor not listed sorts after all
     * listed ones (see {@link #preferenceRank}).
     */
    public static final List<JdkVendor> PREFERENCE = List.of(TEMURIN, LIBERICA, ORACLE_OPENJDK, CORRETTO);

    /** GraalVM-flavour preference for the native / graal chain: Oracle GraalVM, then GraalVM CE. */
    public static final List<JdkVendor> GRAAL_PREFERENCE = List.of(ORACLE_GRAALVM, GRAALVM_CE);

    /** Lower is more preferred. Listed vendors get their index; others sort after, by enum order. */
    public int preferenceRank() {
        int i = PREFERENCE.indexOf(this);
        return i >= 0 ? i : PREFERENCE.size() + ordinal();
    }

    /** Comparator ordering vendors most-preferred first (by {@link #preferenceRank}). */
    public static Comparator<JdkVendor> byPreference() {
        return Comparator.comparingInt(JdkVendor::preferenceRank);
    }

    /** Map JetBrains feed {@code vendor}+{@code product} to an enum, or {@link #UNKNOWN}. */
    public static JdkVendor fromFeed(String vendor, String product) {
        if (vendor == null || product == null) return UNKNOWN;
        for (JdkVendor v : values()) {
            if (v != UNKNOWN && v.vendor.equalsIgnoreCase(vendor) && v.product.equalsIgnoreCase(product)) {
                return v;
            }
        }
        return UNKNOWN;
    }

    /**
     * Display-form vendor for raw feed {@code vendor}+{@code product} strings (e.g.
     * {@code "Eclipse"} + {@code "Temurin"} → {@code "Eclipse Temurin"}), the one form
     * {@link JdkInventory} records. Unrecognised feeds fall back to joining the
     * raw strings the same way {@link #displayName()} does, never to {@code "Unknown"}.
     */
    public static String displayNameFromFeed(String vendor, String product) {
        JdkVendor v = fromFeed(vendor, product);
        if (v != UNKNOWN) return v.displayName();
        String ven = vendor == null ? "" : vendor.trim();
        String prod = product == null ? "" : product.trim();
        if (ven.isEmpty()) return prod;
        if (prod.isEmpty() || ven.toLowerCase(Locale.ROOT).startsWith(prod.toLowerCase(Locale.ROOT))) {
            return ven;
        }
        return ven + " " + prod;
    }

    /** Read {@code home/release} and detect the vendor; missing/unknown → {@link #UNKNOWN}. */
    public static JdkVendor fromRelease(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) return UNKNOWN;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            props.load(in);
        } catch (IOException e) {
            return UNKNOWN;
        }
        return fromProperties(props);
    }

    /**
     * Detect vendor from pre-parsed {@code release} properties (Azul Zulu/Prime and Oracle
     * OpenJDK/GraalVM disambiguation via {@code IMPLEMENTOR_VERSION}).
     */
    public static JdkVendor fromProperties(Properties props) {
        String implementor = stripQuotes(props.getProperty("IMPLEMENTOR", ""));
        String implementorVersion = stripQuotes(props.getProperty("IMPLEMENTOR_VERSION", ""));

        JdkVendor base = byImplementor(implementor);

        // Azul: Zulu vs Zulu Prime
        if (base == ZULU && (implementorVersion.startsWith("Zing") || implementorVersion.startsWith("Prime"))) {
            return ZULU_PRIME;
        }

        // Oracle: OpenJDK vs Oracle GraalVM vs GraalVM Community
        if (base == ORACLE_OPENJDK) {
            String iv = implementorVersion.toLowerCase(Locale.ROOT);
            boolean hasGraalvmVersion = props.getProperty("GRAALVM_VERSION") != null;
            if (iv.contains("graalvm") || hasGraalvmVersion) {
                if (iv.contains("community") || iv.contains("ce")) return GRAALVM_CE;
                return ORACLE_GRAALVM;
            }
        }

        return base;
    }

    private static JdkVendor byImplementor(String implementor) {
        return switch (implementor) {
            case "Eclipse Foundation", "Eclipse Adoptium" -> TEMURIN;
            case "AdoptOpenJDK" -> ADOPT_OPENJDK;
            case "Amazon.com Inc." -> CORRETTO;
            case "Azul Systems, Inc." -> ZULU;
            case "BellSoft" -> LIBERICA;
            case "SAP SE" -> SAPMACHINE;
            case "International Business Machines Corporation", "IBM Corporation" -> SEMERU;
            case "Microsoft" -> MICROSOFT;
            case "Alibaba" -> DRAGONWELL;
            case "JetBrains s.r.o." -> JBR;
            case "Red Hat, Inc." -> REDHAT;
            case "mandrel" -> MANDREL;
            case "Tencent" -> KONA;
            case "Bisheng" -> BISHENG;
            case "ojdkbuild" -> OJDKBUILD;
            case "OpenLogic" -> OPENLOGIC;
            case "Debian" -> DEBIAN;
            case "Ubuntu" -> UBUNTU;
            case "Homebrew" -> HOMEBREW;
            case "Oracle Corporation" -> ORACLE_OPENJDK; // refined below if GraalVM markers present
            case "GraalVM Community" -> GRAALVM_CE;
            default -> UNKNOWN;
        };
    }

    private static String stripQuotes(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
