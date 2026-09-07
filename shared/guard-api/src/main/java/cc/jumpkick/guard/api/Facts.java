// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.ClassFacts;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The facts index of the suite's scope — one module's compiled classes, or every module's — as the
 * engine's own rules read it. Nothing here touches source or bytecode again: the index is what
 * {@code compile-main} left behind, so a guard costs what a map lookup costs.
 */
public interface Facts {

    /** Every class in scope, package-info classes included. */
    List<ClassFacts> classes();

    /** Every invocation matching {@code sig}, with the class and method it is in. */
    List<CallSite> calls(Sig sig);

    /** Every field read or write, with the class and method it is in. */
    List<FieldAccess> fieldRefs();

    /** Every element of that kind with its annotations. */
    List<Annotated> annotations(On on);

    /** {@code static final} constants of a class — the owner-side truth a vocabulary rule reads — by field name. */
    Map<String, String> constants(String ownerBinaryName);

    /** Package → packages it references, from type references. */
    Map<String, Set<String>> packageEdges();

    /** Test classes in scope with their tags; empty when the scope has no test index. */
    List<TaggedClass> testClasses();

    /** The class directories the index was built from, for tools that read class files themselves. */
    List<Path> classDirs();
}
