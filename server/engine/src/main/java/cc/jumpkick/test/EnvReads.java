// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The environment reads a suite's classes make, found in their bytecode: each {@code
 * System.getenv("NAME")} with a constant name, each read whose name is computed, each read of the
 * whole map, and each JUnit condition on a variable. The same facts the guard extracts ({@link
 * FactsExtractor}), read for one question.
 *
 * <p>A diagnostic signal and nothing more: what a suite reads never enters its key. A dependency
 * jar is read once per content hash and its answer kept in jk's cache.
 */
public final class EnvReads {

    /** How a read names its variable. */
    public enum Kind {
        /** A constant name: {@code System.getenv("NAME")} or a JUnit condition. */
        NAMED,
        /** A name computed at run time: no check can say which variable it reads. */
        COMPUTED,
        /** {@code System.getenv()}: the whole map, which can read any variable. */
        WHOLE_MAP
    }

    /**
     * One read: {@code owner} is the reading class's binary name, {@code name} the variable for a
     * {@link Kind#NAMED} read and empty otherwise.
     */
    public record Site(Kind kind, String owner, String name) {}

    private static final String SYSTEM = "java/lang/System";
    private static final String BY_NAME = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String WHOLE = "()Ljava/util/Map;";
    private static final Set<String> CONDITIONS = Set.of(
            "org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable",
            "org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvReads() {}

    /** The reads one class file makes. */
    public static List<Site> ofClass(ClassFacts facts) {
        Set<Site> out = new LinkedHashSet<>();
        String owner = facts.binaryName();
        conditions(facts.annotations(), owner, out);
        for (MethodFacts method : facts.methods()) {
            conditions(method.annotations(), owner, out);
            for (CallSite call : method.calls()) {
                if (!SYSTEM.equals(call.owner()) || !"getenv".equals(call.name())) continue;
                if (WHOLE.equals(call.desc())) {
                    out.add(new Site(Kind.WHOLE_MAP, owner, ""));
                } else if (BY_NAME.equals(call.desc())) {
                    named(call, owner, out);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * A by-name call site, merged per method: one invoke names the constant loaded right before
     * it; several name every constant-shaped literal in their argument windows, and the first one
     * without a constant marks the site computed.
     */
    private static void named(CallSite call, String owner, Set<Site> out) {
        if (call.count() == 1) {
            String literal = call.literalBefore();
            out.add(literal != null ? new Site(Kind.NAMED, owner, literal) : new Site(Kind.COMPUTED, owner, ""));
            return;
        }
        boolean any = false;
        for (String literal : call.literals()) {
            if (NAME.matcher(literal).matches()) {
                out.add(new Site(Kind.NAMED, owner, literal));
                any = true;
            }
        }
        if (!any || call.literalBefore() == null) out.add(new Site(Kind.COMPUTED, owner, ""));
    }

    private static void conditions(List<AnnotationFacts> annotations, String owner, Set<Site> out) {
        for (AnnotationFacts a : annotations) {
            if (!CONDITIONS.contains(a.typeName())) continue;
            for (String name : a.values().getOrDefault("named", List.of())) {
                out.add(new Site(Kind.NAMED, owner, name));
            }
        }
    }

    /** The reads of every class file under {@code classesDir}; empty when it does not exist. */
    public static List<Site> ofDirectory(Path classesDir) {
        if (!Files.isDirectory(classesDir)) return List.of();
        List<Site> out = new ArrayList<>();
        try {
            PathUtil.forEachRegularFile(classesDir, (file, attrs) -> {
                String name = file.getFileName().toString();
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    out.addAll(ofBytes(Files.readAllBytes(file)));
                }
            });
        } catch (IOException | RuntimeException e) {
            Log.debug("EnvReads: " + classesDir + " read in part", e);
        }
        return out;
    }

    /**
     * The reads of every class in {@code jar}, from {@code cacheDir} when this jar's content and
     * this extractor were read before. Touches the cached answer, so its age is its last use.
     */
    public static List<Site> ofJar(Path jar, Path cacheDir) {
        try {
            String key =
                    Hashing.sha256Hex(jar) + "-" + FactsExtractor.identity().substring(0, 16);
            Path cached = cacheDir.resolve(key + ".reads");
            if (Files.isRegularFile(cached)) {
                Files.setLastModifiedTime(cached, FileTime.fromMillis(Clock.SYSTEM.millis()));
                return decode(Files.readString(cached));
            }
            List<Site> out = new ArrayList<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                    try (InputStream in = zip.getInputStream(e)) {
                        out.addAll(ofBytes(in.readAllBytes()));
                    }
                }
            }
            Files.createDirectories(cacheDir);
            AtomicWrites.replace(cached, encode(out).getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (IOException | RuntimeException e) {
            Log.debug("EnvReads: " + jar + " not read", e);
            return List.of();
        }
    }

    private static List<Site> ofBytes(byte[] classBytes) {
        try {
            return ofClass(FactsExtractor.extract(classBytes));
        } catch (RuntimeException unreadable) {
            return List.of(); // a class ASM cannot read is not a read we can name
        }
    }

    /** One site per line: {@code KIND<TAB>owner<TAB>name}. */
    static String encode(List<Site> sites) {
        StringBuilder sb = new StringBuilder();
        for (Site s : sites)
            sb.append(s.kind())
                    .append('\t')
                    .append(s.owner())
                    .append('\t')
                    .append(s.name())
                    .append('\n');
        return sb.toString();
    }

    static List<Site> decode(String text) {
        List<Site> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length == 3) out.add(new Site(Kind.valueOf(f[0]), f[1], f[2]));
        }
        return out;
    }
}
