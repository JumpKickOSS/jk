// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import sbt.internal.inc.APIs;
import sbt.internal.inc.Analysis;
import sbt.internal.inc.Stamps;
import scala.Option;
import xsbti.VirtualFileRef;
import xsbti.api.AnalyzedClass;
import xsbti.compile.analysis.ReadStamps;

/**
 * The Zinc analyses of compile-classpath entries other jk compiles produced, keyed by the entry: a
 * sibling's jar or classes dir, or this module's own main classes under a test compile. Zinc's
 * per-entry lookup answers from here. That turns a dependency on such an entry from a
 * <em>library</em> dependency — one stamp for the whole jar, so any rewrite of it invalidates every
 * class that touched the jar — into an <em>external</em> dependency whose API Zinc compares one
 * producer class at a time: a consumer class that never referenced the changed class stays
 * compiled.
 *
 * <p>An analysis is trusted only while it describes the classes on disk: every product it records
 * must carry the stamp the file has now. A producer whose classes the action cache restored keeps
 * the analysis of some earlier compile, and comparing APIs against that would let a consumer skip
 * a recompile it needs. A missing, unreadable or stale analysis is "no analysis" — the consumer
 * then treats the entry as it treats every Maven jar — and is never deleted here: it is the
 * producer's file, and the producer's own compile is what repairs it.
 *
 * <p>Entries are read on first use and once per instance: an instance lives for one compile or
 * forecast, and Zinc asks for the whole classpath's analyses in one pass. The decode itself comes
 * from the worker's {@link ProducerAnalysisCache}, shared by every compile; the check against the
 * classes on disk is this instance's own.
 */
final class ClasspathAnalyses {

    private final Map<Path, Path> files;
    private final ReadStamps current;
    private final ProducerAnalysisCache cache;
    private final Map<Path, Optional<Analysis>> loaded = new HashMap<>();
    private @Nullable List<Analysis> all;

    private ClasspathAnalyses(Map<Path, Path> files, ReadStamps current, ProducerAnalysisCache cache) {
        this.files = files;
        this.current = current;
        this.cache = cache;
    }

    /**
     * @param files each classpath entry with its producer's analysis file
     * @param current the stamper the compile itself uses, so a product is judged by the same
     *     (mtime-cached) hash the compile would read for it
     * @param cache the worker's decoded analyses, which this instance reads through
     */
    static ClasspathAnalyses of(Map<Path, Path> files, ReadStamps current, ProducerAnalysisCache cache) {
        Map<Path, Path> normalized = new LinkedHashMap<>();
        for (Map.Entry<Path, Path> e : files.entrySet()) {
            normalized.put(e.getKey().toAbsolutePath().normalize(), e.getValue());
        }
        return new ClasspathAnalyses(normalized, current, cache);
    }

    boolean isEmpty() {
        return files.isEmpty();
    }

    /** The producer's analysis for {@code entry}, when it has one that describes the entry's classes now. */
    Optional<Analysis> analysis(Path entry) {
        Path key = entry.toAbsolutePath().normalize();
        Path file = files.get(key);
        if (file == null) return Optional.empty();
        return loaded.computeIfAbsent(key, k -> read(file, cache, current));
    }

    /**
     * The producer's view of the class named {@code binaryClassName}, from whichever entry's
     * analysis lists it as a product. Empty when no producer analysis knows the class — Zinc then
     * treats the class as a library class and compares stamps.
     */
    Option<AnalyzedClass> analyzedClass(String binaryClassName) {
        for (Analysis analysis : all()) {
            var classNames = analysis.relations()
                    .productClassName()
                    .reverse(binaryClassName)
                    .iterator();
            while (classNames.hasNext()) {
                Option<AnalyzedClass> api = analysis.apis().internal().get(classNames.next());
                if (api.isDefined()) return api;
            }
        }
        return Option.empty();
    }

    private static boolean definedOnlyByJava(Analysis analysis, String className) {
        var sources = analysis.relations().definesClass(className).iterator();
        if (!sources.hasNext()) return false;
        while (sources.hasNext()) {
            if (!sources.next().id().endsWith(".java")) return false;
        }
        return true;
    }

    /**
     * Whether the producer's current view of {@code binaryClassName} still has the hashes {@code
     * recorded} — the comparison Zinc makes to decide an external class changed, over the same
     * answer {@link #analyzedClass} gives Zinc. A class no producer analysis knows any more compares
     * as changed, as it does inside Zinc.
     */
    boolean sameApi(String binaryClassName, AnalyzedClass recorded) {
        Option<AnalyzedClass> now = analyzedClass(binaryClassName);
        if (now.isEmpty()) return false;
        AnalyzedClass c = now.get();
        return c.apiHash() == recorded.apiHash()
                && c.extraHash() == recorded.extraHash()
                && c.bytecodeHash() == recorded.bytecodeHash();
    }

    private List<Analysis> all() {
        List<Analysis> got = all;
        if (got == null) {
            got = new ArrayList<>();
            for (Path entry : files.keySet()) analysis(entry).ifPresent(got::add);
            all = got;
        }
        return got;
    }

    /**
     * One producer analysis, decoded or taken from the worker's cache, and trusted only when it
     * describes the classes on disk now: the cache keys the file's bytes, not the classes.
     */
    private static Optional<Analysis> read(Path file, ProducerAnalysisCache cache, ReadStamps current) {
        return cache.load(file).filter(analysis -> describesDisk(analysis, current));
    }

    /**
     * {@code analysis} with the bytecode hashes of every class defined only by Java sources cleared.
     * Java has no inline bodies, so a Java class's API and extra hashes are the whole of what a
     * consumer depends on, and a body-only edit to it must not invalidate the consumer. Zinc both
     * records an external class and compares it later from this analysis, and the forecast reads it
     * too, so every side carries the same cleared hashes.
     */
    static Analysis apiOnlyForJava(Analysis analysis) {
        APIs apis = analysis.apis();
        var internal = apis.internal().iterator();
        while (internal.hasNext()) {
            var entry = internal.next();
            if (!definedOnlyByJava(analysis, entry._1())) continue;
            AnalyzedClass c = entry._2();
            apis = apis.markInternalAPI(entry._1(), c.withBytecodeHash(0L).withTransitiveBytecodeHash(0L));
        }
        return (Analysis)
                analysis.copy(analysis.stamps(), apis, analysis.relations(), analysis.infos(), analysis.compilations());
    }

    /**
     * True when every product the analysis recorded still has the stamp it recorded. An analysis
     * that recorded no products describes nothing a consumer could depend on.
     */
    private static boolean describesDisk(Analysis analysis, ReadStamps current) {
        Stamps recorded = analysis.stamps();
        var products = recorded.allProducts().iterator();
        if (!products.hasNext()) return false;
        while (products.hasNext()) {
            VirtualFileRef product = products.next();
            if (ZincSetup.stampChanged(recorded.product(product), current.product(product))) return false;
        }
        return true;
    }
}
