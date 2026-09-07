// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.runtime;

import cc.jumpkick.guard.api.Annotated;
import cc.jumpkick.guard.api.CallSite;
import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.FieldAccess;
import cc.jumpkick.guard.api.On;
import cc.jumpkick.guard.api.Origin;
import cc.jumpkick.guard.api.Sig;
import cc.jumpkick.guard.api.TaggedClass;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link Facts} over one merged index (and the test index, for tagged classes). */
public final class FactsView implements Facts {

    private static final String TAG = "org.junit.jupiter.api.Tag";
    private static final Set<String> TEST_METHOD_ANNOTATIONS = Set.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.TestTemplate",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.Test");

    private final FactsIndex main;
    private final FactsIndex test;
    private final List<Path> classDirs;

    public FactsView(FactsIndex main, FactsIndex test, List<Path> classDirs) {
        this.main = main;
        this.test = test;
        this.classDirs = List.copyOf(classDirs);
    }

    @Override
    public List<ClassFacts> classes() {
        return main.classList();
    }

    @Override
    public List<CallSite> calls(Sig sig) {
        List<CallSite> out = new ArrayList<>();
        for (FactsIndex.OriginCall c : main.calls()) {
            if (sig.matches(c.site().owner(), c.site().name(), c.site().desc())) {
                out.add(new CallSite(new Origin(c.origin(), c.member()), c.site()));
            }
        }
        return out;
    }

    @Override
    public List<FieldAccess> fieldRefs() {
        List<FieldAccess> out = new ArrayList<>();
        for (FactsIndex.OriginFieldRef r : main.fieldRefs())
            out.add(new FieldAccess(new Origin(r.origin(), r.member()), r.ref()));
        return out;
    }

    @Override
    public List<Annotated> annotations(On on) {
        FactsIndex idx = on == On.TEST_CLASS ? test : main;
        List<Annotated> out = new ArrayList<>();
        for (ClassFacts c : idx.classList()) {
            switch (on) {
                case PACKAGE -> {
                    if (c.isPackageInfo()) out.add(new Annotated(c, null, null, -1, c.annotations()));
                }
                case CLASS -> {
                    if (!c.isPackageInfo()) out.add(new Annotated(c, null, null, -1, c.annotations()));
                }
                case TEST_CLASS -> {
                    if (isTestClass(c)) out.add(new Annotated(c, null, null, -1, c.annotations()));
                }
                case METHOD -> {
                    for (MethodFacts m : c.methods()) out.add(new Annotated(c, m, null, -1, m.annotations()));
                }
                case FIELD -> {
                    for (FieldFacts f : c.fields()) out.add(new Annotated(c, null, f, -1, f.annotations()));
                }
                case PARAMETER -> {
                    for (MethodFacts m : c.methods()) {
                        int i = 0;
                        for (List<AnnotationFacts> pa : m.parameterAnnotations())
                            out.add(new Annotated(c, m, null, i++, pa));
                    }
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, String> constants(String ownerBinaryName) {
        return main.constants(ownerBinaryName);
    }

    @Override
    public Map<String, Set<String>> packageEdges() {
        return main.packageEdges();
    }

    @Override
    public List<TaggedClass> testClasses() {
        List<TaggedClass> out = new ArrayList<>();
        for (ClassFacts c : test.classList()) {
            if (c.isPackageInfo() || !isTestClass(c)) continue;
            Set<String> tags = new LinkedHashSet<>();
            for (AnnotationFacts a : c.annotations()) if (a.typeName().equals(TAG)) tags.addAll(a.value());
            for (MethodFacts m : c.methods())
                for (AnnotationFacts a : m.annotations()) if (a.typeName().equals(TAG)) tags.addAll(a.value());
            out.add(new TaggedClass(c, tags));
        }
        return out;
    }

    @Override
    public List<Path> classDirs() {
        return classDirs;
    }

    static boolean isTestClass(ClassFacts c) {
        for (MethodFacts m : c.methods()) {
            for (AnnotationFacts a : m.annotations()) if (TEST_METHOD_ANNOTATIONS.contains(a.typeName())) return true;
        }
        return false;
    }
}
