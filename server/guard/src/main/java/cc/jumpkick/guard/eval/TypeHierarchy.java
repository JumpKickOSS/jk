// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Supertypes of any class a rule can name, from three sources in order: the module's own facts,
 * the compile classpath (jars and class dirs, read {@code SKIP_CODE} on first use), and the JDK the
 * engine runs on (via the platform class loader's {@code jrt} resources). Resolved once per lane run
 * and memoised; a name none of the three knows is unresolvable — the typo a rule author made.
 */
public final class TypeHierarchy {

    /** Direct supertypes of one class. */
    public record Supers(String name, @Nullable String superName, List<String> interfaces) {}

    private final FactsIndex facts;
    private final Supplier<List<Path>> classpath;
    private final Map<String, Optional<Supers>> memo = new HashMap<>();
    private @Nullable List<ZipFile> jars;
    private List<Path> dirs = List.of();

    public TypeHierarchy(FactsIndex facts, Supplier<List<Path>> classpath) {
        this.facts = facts;
        this.classpath = classpath;
    }

    /** Whether {@code internalName} resolves anywhere. */
    public boolean exists(String internalName) {
        return supers(internalName).isPresent();
    }

    /** {@code internalName} and every supertype, nearest first; the class itself is first. */
    public Set<String> ancestors(String internalName) {
        Set<String> out = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>(List.of(internalName));
        while (!frontier.isEmpty()) {
            String n = frontier.remove(0);
            if (!out.add(n)) continue;
            Optional<Supers> s = supers(n);
            if (s.isEmpty()) continue;
            String sup = s.get().superName();
            if (sup != null) frontier.add(sup);
            frontier.addAll(s.get().interfaces());
        }
        return out;
    }

    /** Whether {@code internalName} is {@code ancestor} or a subtype of it. */
    public boolean isAssignableTo(String internalName, String ancestor) {
        return ancestors(internalName).contains(ancestor);
    }

    public synchronized Optional<Supers> supers(String internalName) {
        Optional<Supers> hit = memo.get(internalName);
        if (hit != null) return hit;
        Optional<Supers> s = lookup(internalName);
        memo.put(internalName, s);
        return s;
    }

    private Optional<Supers> lookup(String internalName) {
        ClassFacts own = facts.classes().get(internalName);
        if (own != null) return Optional.of(new Supers(internalName, own.superName(), own.interfaces()));
        byte[] bytes = classpathBytes(internalName);
        if (bytes == null) bytes = jdkBytes(internalName);
        if (bytes == null) return Optional.empty();
        return Optional.of(read(bytes));
    }

    private static Supers read(byte[] bytes) {
        Header h = new Header();
        new ClassReader(bytes).accept(h, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Supers(h.name, h.superName, h.interfaces);
    }

    private static final class Header extends ClassVisitor {
        String name = "";

        @Nullable
        String superName;

        final List<String> interfaces = new ArrayList<>();

        Header() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int v, int a, String name, @Nullable String sig, @Nullable String sup, String @Nullable [] ifs) {
            this.name = name;
            this.superName = sup;
            if (ifs != null) interfaces.addAll(List.of(ifs));
        }
    }

    private static byte @Nullable [] jdkBytes(String internalName) {
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private byte @Nullable [] classpathBytes(String internalName) {
        List<ZipFile> zips = openClasspath();
        String entry = internalName + ".class";
        for (Path dir : dirs) {
            Path f = dir.resolve(entry);
            if (Files.isRegularFile(f)) {
                try {
                    return Files.readAllBytes(f);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        for (ZipFile jar : zips) {
            ZipEntry e = jar.getEntry(entry);
            if (e != null) {
                try (InputStream in = jar.getInputStream(e)) {
                    return in.readAllBytes();
                } catch (IOException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<ZipFile> openClasspath() {
        List<ZipFile> open = jars;
        if (open != null) return open;
        List<ZipFile> zs = new ArrayList<>();
        List<Path> ds = new ArrayList<>();
        for (Path p : classpath.get()) {
            if (Files.isDirectory(p)) {
                ds.add(p);
            } else if (Files.isRegularFile(p)) {
                try {
                    zs.add(new ZipFile(p.toFile()));
                } catch (IOException ignored) {
                    // a classpath entry that is not a zip contributes no types
                }
            }
        }
        jars = zs;
        dirs = ds;
        return zs;
    }

    /** Close the jars opened for resolution. Idempotent. */
    public synchronized void close() {
        List<ZipFile> open = jars;
        if (open != null) {
            for (ZipFile z : open) {
                try {
                    z.close();
                } catch (IOException ignored) {
                    // nothing to do at close
                }
            }
            jars = List.of();
        }
    }

    /** {@code a.b.C} spelled as the source would, for messages. */
    public static String display(String internalName) {
        return Descriptors.binaryName(internalName);
    }
}
