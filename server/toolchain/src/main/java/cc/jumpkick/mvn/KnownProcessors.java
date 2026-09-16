// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.Pom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Annotation processors a Maven module commonly declares as plain dependencies and lets javac
 * discover from the compile classpath. A POM without {@code <annotationProcessorPaths>} that
 * declares one has these written into {@code [processor-dependencies]} as well, so the jk module
 * says which processors run instead of leaving it to whatever the classpath registers.
 */
final class KnownProcessors {

    /** Maven module → the processor's product name, as the import report spells it. */
    static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("org.projectlombok:lombok", "Lombok"),
            Map.entry("org.mapstruct:mapstruct-processor", "MapStruct"),
            Map.entry("com.google.auto.value:auto-value", "AutoValue"),
            Map.entry("com.google.dagger:dagger-compiler", "Dagger"),
            Map.entry("org.immutables:value", "Immutables"),
            Map.entry("io.micronaut:micronaut-inject-java", "Micronaut inject-java"),
            Map.entry("org.hibernate.orm:hibernate-jpamodelgen", "Hibernate jpamodelgen"),
            Map.entry("org.hibernate:hibernate-jpamodelgen", "Hibernate jpamodelgen"));

    private KnownProcessors() {}

    /** True for a compile, provided or test dependency on a module in {@link #MODULES}. */
    static boolean recognizes(Pom.Dep dep) {
        if (!MODULES.containsKey(dep.module())) return false;
        return DependencyMapping.scope(dep.scope()) != Scope.RUNTIME;
    }

    /**
     * Write {@code recognized} into the processor scope and say so once, naming the products; a
     * module already present in that scope is not written twice.
     */
    static void write(List<Pom.Dep> recognized, Map<Scope, List<Dependency>> byScope, ImportReport.Builder report) {
        if (recognized.isEmpty()) return;
        List<Dependency> processors = byScope.computeIfAbsent(Scope.PROCESSOR, s -> new ArrayList<>());
        TreeSet<String> names = new TreeSet<>();
        for (Pom.Dep dep : recognized) {
            Dependency d = DependencyMapping.toDependency(dep);
            boolean present = processors.stream().anyMatch(p -> p.module().equals(d.module()));
            if (!present) processors.add(d);
            names.add(MODULES.get(dep.module()));
        }
        report.warning(String.join(", ", names)
                + (names.size() == 1 ? " is" : " are")
                + " declared as plain dependencies and run as annotation processors from the classpath;"
                + " written to `[processor-dependencies]` too, so the module says what runs.");
    }
}
