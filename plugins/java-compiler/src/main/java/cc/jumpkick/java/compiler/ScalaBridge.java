// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Classpaths;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import sbt.internal.inc.ScalaInstance;
import sbt.internal.inc.ZincUtil;
import sbt.internal.inc.javac.JavaTools;
import sbt.internal.inc.javac.Javadoc;
import scala.Option;
import xsbti.compile.ClasspathOptions;
import xsbti.compile.Compilers;
import xsbti.compile.JavaCompiler;
import xsbti.compile.ScalaCompiler;

/**
 * Standing up the compilers Zinc needs: a dummy scalac for a Java-only job, or a real Scala 3
 * compiler plus published sbt bridge for a mixed one.
 *
 * <p>The whole file is "which jars, loaded by which classloader" — artifact filename matching,
 * two {@link URLClassLoader}s and a {@link ScalaInstance}. None of it touches invalidation, the
 * analysis store, or a source file, and the Java-only path exists only so the mixed path can be
 * the same code with different jars. {@code mixed == null} is what "Java only" means, asked once
 * here instead of at each of the three sites that used to re-derive it.
 */
final class ScalaBridge {

    /** The Scala half of a mixed job: {@code null} for a Java-only compile. */
    record MixedScala(
            String version, List<Path> compilerClasspath, Path bridgeJar, Path libraryJar, Path compilerJar) {}

    /**
     * Cache the Scala compiler (ScalaInstance + classloaders + bridge) per compiler-classpath so a
     * multi-module job pays scalac warm-up once and does not leak an unclosed URLClassLoader per
     * module. Keyed by version + classpath; scoped to the per-job worker process, which exits at
     * job end, reclaiming the loaders.
     *
     * <p>The classpath half of the key comes from {@link Classpaths#join}, which absolutises — the
     * hand-rolled join it replaced did not, so two specs naming the same jars, one relatively and
     * one absolutely, got two entries and paid warm-up twice.
     */
    private static final Map<String, ScalaCompiler> SCALAC_CACHE = new ConcurrentHashMap<>();

    private ScalaBridge() {}

    /** The compilers for this job: a real scalac when {@code mixed} is present, a dummy when not. */
    static Compilers compilersFor(JavaCompiler javac, MixedScala mixed) {
        return mixed != null ? mixedCompilers(javac, mixed) : javaOnlyCompilers(javac);
    }

    /** Extra classpath entries scalac needs and the compile classpath may not carry. */
    static List<Path> extraClasspath(MixedScala mixed) {
        if (mixed == null) return List.of();
        List<Path> out = new ArrayList<>();
        for (File lib : stdlibJars(mixed)) out.add(lib.toPath());
        return out;
    }

    static String[] scalacOptions(MixedScala mixed, int release) {
        if (mixed == null || release <= 0) return new String[0];
        return new String[] {"-java-output-version", Integer.toString(release)};
    }

    private static Compilers javaOnlyCompilers(JavaCompiler javac) {
        File dummy = new File("");
        ScalaInstance dummyScala = new ScalaInstance(
                "", null, null, null, new File[] {dummy}, new File[] {dummy}, new File[0], Option.apply(""));
        ClasspathOptions cpOpts = ClasspathOptions.of(false, false, false, false, false);
        ScalaCompiler scalac = ZincUtil.scalaCompiler(dummyScala, dummy, cpOpts);
        return ZincUtil.compilers(JavaTools.apply(javac, javadoc()), scalac);
    }

    private static Compilers mixedCompilers(JavaCompiler javac, MixedScala mixed) {
        String key = mixed.version() + "\n" + Classpaths.join(mixed.compilerClasspath());
        ScalaCompiler scalac = SCALAC_CACHE.computeIfAbsent(key, k -> buildScalac(mixed));
        return ZincUtil.compilers(JavaTools.apply(javac, javadoc()), scalac);
    }

    private static xsbti.compile.Javadoc javadoc() {
        return Javadoc.local().isDefined() ? Javadoc.local().get() : Javadoc.fork(Option.empty());
    }

    private static ScalaCompiler buildScalac(MixedScala mixed) {
        File[] allJars = mixed.compilerClasspath().stream().map(Path::toFile).toArray(File[]::new);
        File[] libraryJars = stdlibJars(mixed);
        File compilerJar = firstJar(mixed.compilerJar(), allJars, "scala3-compiler_3");
        File bridge = firstJar(mixed.bridgeJar(), allJars, "scala3-sbt-bridge");
        if (libraryJars.length == 0 || compilerJar == null || bridge == null) {
            throw new IllegalArgumentException(
                    "Scala compiler classpath must include scala-library, scala3-compiler_3, and scala3-sbt-bridge");
        }
        if (findJar(allJars, "scala-library") == null && findJar(libraryJars, "scala-library") == null) {
            throw new IllegalArgumentException(
                    "Scala compiler classpath must include org.scala-lang:scala-library (the stdlib)");
        }
        String scalaVersion = mixed.version();
        URL[] libraryUrls = urls(libraryJars);
        File[] compilerOnly = without(allJars, libraryJars, bridge);
        URL[] compilerOnlyUrls = urls(compilerOnly);
        // The instance loaders never parent on this worker's app loader: it carries Zinc's own
        // transitive Scala stdlib, and a parent-first URLClassLoader resolved every scala.* class
        // from it, so the compiler ran against a stdlib it was not built for (scalac 3.9.0 died on
        // NoSuchMethodError: scala.Option.orNull() with the older Option loaded underneath it).
        // What the compiler does need from this side is xsbti.* — the callback types Zinc hands
        // the bridge must be the same class objects the compiler's sbt phases were linked
        // against — so the parent exposes exactly that package from the worker loader and the JDK
        // from the platform loader, as sbt's top loader does. The bridge jar stays out of the
        // instance: Zinc loads it itself, over its own xsbti/scala dual loader.
        ClassLoader root = new XsbtiTopLoader(ScalaBridge.class.getClassLoader());
        ClassLoader libraryLoader = new URLClassLoader(libraryUrls, root);
        ClassLoader compilerLoader = new URLClassLoader(compilerOnlyUrls, libraryLoader);
        ScalaInstance instance = new ScalaInstance(
                scalaVersion,
                compilerLoader,
                compilerLoader,
                libraryLoader,
                libraryJars,
                allJars,
                allJars,
                Option.apply(scalaVersion));
        // bootLibrary + autoBoot: Zinc appends libraryJars when the compile CP already has
        // the stdlib. filterLibrary stays off so JDK 9+ (no -bootclasspath) cannot drop it.
        ClasspathOptions cpOpts = ClasspathOptions.of(true, false, false, true, false);
        return ZincUtil.scalaCompiler(instance, bridge, cpOpts);
    }

    /**
     * Real stdlib jars for scalac: {@code scala-library} (2.13 or 3.8+) plus the
     * {@code scala3-library_3} stub when present. Artifact filenames matter.
     */
    private static File[] stdlibJars(MixedScala mixed) {
        File[] allJars = mixed.compilerClasspath().stream().map(Path::toFile).toArray(File[]::new);
        List<File> out = new ArrayList<>();
        File sl = findJar(allJars, "scala-library");
        File s3 = findJar(allJars, "scala3-library_3");
        if (sl != null) out.add(sl);
        if (s3 != null && !out.contains(s3)) out.add(s3);
        if (out.isEmpty() && mixed.libraryJar() != null)
            out.add(mixed.libraryJar().toFile());
        return out.toArray(File[]::new);
    }

    /** {@code all} minus {@code drop} and {@code also}, in order — the compiler-only half of the closure. */
    private static File[] without(File[] all, File[] drop, File also) {
        List<File> out = new ArrayList<>();
        List<File> dropped = new ArrayList<>(List.of(drop));
        dropped.add(also);
        for (File f : all) if (!dropped.contains(f)) out.add(f);
        return out.toArray(File[]::new);
    }

    /**
     * The top of the ScalaInstance loader chain: {@code xsbti.*} from the worker's loader (Zinc's
     * compiler interface, one set of class objects for the bridge, the compiler's sbt phases and
     * Zinc itself), everything else from the platform loader. No {@code scala.*} can come through
     * here, which is the point.
     */
    private static final class XsbtiTopLoader extends ClassLoader {
        private final ClassLoader xsbti;

        XsbtiTopLoader(ClassLoader xsbti) {
            super(ClassLoader.getPlatformClassLoader());
            this.xsbti = xsbti;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("xsbti.")) return xsbti.loadClass(name);
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return name.startsWith("xsbti/") ? xsbti.getResource(name) : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return name.startsWith("xsbti/") ? xsbti.getResources(name) : super.getResources(name);
        }
    }

    private static File firstJar(Path extra, File[] allJars, String artifactPrefix) {
        File named = findJar(allJars, artifactPrefix);
        if (named != null) return named;
        return extra != null ? extra.toFile() : null;
    }

    private static File findJar(File[] jars, String artifactPrefix) {
        for (File f : jars) {
            String n = f.getName();
            if (n.startsWith(artifactPrefix + "-") || n.startsWith(artifactPrefix + ".")) return f;
        }
        return null;
    }

    private static URL[] urls(File[] files) {
        URL[] out = new URL[files.length];
        for (int i = 0; i < files.length; i++) {
            try {
                out[i] = files[i].toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("bad classpath entry: " + files[i], e);
            }
        }
        return out;
    }
}
