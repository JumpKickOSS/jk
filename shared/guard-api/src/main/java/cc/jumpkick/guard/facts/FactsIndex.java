// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.facts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * One source set's facts, keyed by class. Built by the engine's extractor, written and read through
 * {@link FactsFormat}, queried by rules and guard tests. Immutable.
 *
 * @param bodyDigest sha256 of the serialized class table — content-derived and portable, the
 *     material a lane's action key is built from
 * @param stamps per class file {@code relPath → size:mtimeNanos}, the local change detector that
 *     lets an unchanged module skip every read
 */
public record FactsIndex(Map<String, ClassFacts> classes, Map<String, String> stamps, String bodyDigest) {

    public static final FactsIndex EMPTY = new FactsIndex(Map.of(), Map.of(), "");

    public FactsIndex {
        classes = Collections.unmodifiableMap(new TreeMap<>(classes));
        stamps = Collections.unmodifiableMap(new TreeMap<>(stamps));
    }

    public Optional<ClassFacts> classNamed(String internalName) {
        return Optional.ofNullable(classes.get(internalName));
    }

    /** Sorted class facts. */
    public List<ClassFacts> classList() {
        return new ArrayList<>(classes.values());
    }

    public List<ClassFacts> classesWhere(Predicate<ClassFacts> p) {
        List<ClassFacts> out = new ArrayList<>();
        for (ClassFacts c : classes.values()) if (p.test(c)) out.add(c);
        return out;
    }

    /** Every call site with its origin, for rules that match on the target. */
    public List<OriginCall> calls() {
        List<OriginCall> out = new ArrayList<>();
        for (ClassFacts c : classes.values()) {
            for (MethodFacts m : c.methods()) for (CallSite s : m.calls()) out.add(new OriginCall(c, m, s));
        }
        return out;
    }

    public List<OriginFieldRef> fieldRefs() {
        List<OriginFieldRef> out = new ArrayList<>();
        for (ClassFacts c : classes.values()) {
            for (MethodFacts m : c.methods()) for (FieldRef r : m.fieldRefs()) out.add(new OriginFieldRef(c, m, r));
        }
        return out;
    }

    /** {@code static final} constants of {@code owner} (binary name): field name → value. */
    public Map<String, String> constants(String ownerBinaryName) {
        Map<String, String> out = new TreeMap<>();
        ClassFacts c = classes.get(Descriptors.internalName(ownerBinaryName));
        if (c == null) return out;
        for (FieldFacts f : c.fields()) if (f.constantValue() != null) out.put(f.name(), f.constantValue());
        return out;
    }

    /** Package → packages it refers to, within this index's own classes only. */
    public Map<String, Set<String>> packageEdges() {
        Set<String> own = new TreeSet<>();
        for (ClassFacts c : classes.values()) own.add(c.packageName());
        Map<String, Set<String>> out = new TreeMap<>();
        for (ClassFacts c : classes.values()) {
            Set<String> to = out.computeIfAbsent(c.packageName(), k -> new TreeSet<>());
            for (String ref : c.typeRefs()) {
                String p = Descriptors.packageOf(ref);
                if (own.contains(p) && !p.equals(c.packageName())) to.add(p);
            }
        }
        return out;
    }

    /** Packages declared by this index's classes. */
    public Set<String> packages() {
        Set<String> out = new TreeSet<>();
        for (ClassFacts c : classes.values()) out.add(c.packageName());
        return out;
    }

    public FactsIndex withStamps(Map<String, String> newStamps, String digest) {
        return new FactsIndex(classes, newStamps, digest);
    }

    public record OriginCall(ClassFacts origin, MethodFacts member, CallSite site) {
        /** {@code origin#member(desc) -> target}: the baseline fingerprint, line-independent. */
        public String fingerprint() {
            return Fingerprints.normalise(origin.binaryName() + "#" + member.member()) + " -> " + site.target();
        }
    }

    public record OriginFieldRef(ClassFacts origin, MethodFacts member, FieldRef ref) {
        public String fingerprint() {
            return Fingerprints.normalise(origin.binaryName() + "#" + member.member()) + " -> " + ref.target();
        }
    }
}
