// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Tier;
import cc.jumpkick.guard.facts.FactsIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeHierarchyTest {

    private static final FactsIndex NO_FACTS = new FactsIndex(Map.of(), Map.of(), "");

    @Test
    void a_jdk_class_resolves_without_opening_the_classpath() {
        AtomicInteger asked = new AtomicInteger();
        TypeHierarchy types = new TypeHierarchy(NO_FACTS, () -> {
            asked.incrementAndGet();
            return List.of();
        });

        assertThat(types.ancestors("java/util/ArrayList")).contains("java/util/AbstractList", "java/util/List");
        assertThat(types.bytesOf("java/lang/String")).isNotNull();
        assertThat(asked).as("the classpath was never listed").hasValue(0);
        assertThat(types.openJars()).isZero();
    }

    @Test
    void a_class_outside_the_jdk_still_resolves_from_the_classpath(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("tier.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("cc/jumpkick/guard/extract/fixture/Tier.class"));
            out.write(FixtureBytes.of(Tier.class));
            out.closeEntry();
        }
        TypeHierarchy types = new TypeHierarchy(NO_FACTS, () -> List.of(jar));
        try {
            assertThat(types.exists("cc/jumpkick/guard/extract/fixture/Tier")).isTrue();
            assertThat(types.exists("java/util/NoSuchJdkType")).isFalse();
        } finally {
            types.close();
        }
    }

    @Test
    void only_a_class_directory_holds_types_compiled_from_sources(@TempDir Path dir) throws Exception {
        String tier = "cc/jumpkick/guard/extract/fixture/Tier";
        Path jar = dir.resolve("tier.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(tier + ".class"));
            out.write(FixtureBytes.of(Tier.class));
            out.closeEntry();
        }
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve(tier).getParent());
        Files.write(classes.resolve(tier + ".class"), FixtureBytes.of(Tier.class));

        TypeHierarchy fromJar = new TypeHierarchy(NO_FACTS, () -> List.of(jar));
        TypeHierarchy fromDir = new TypeHierarchy(NO_FACTS, () -> List.of(classes));
        try {
            assertThat(fromJar.fromSources(tier)).as("a library jar's type").isFalse();
            assertThat(fromDir.fromSources(tier)).as("a sibling's classes tree").isTrue();
            assertThat(fromDir.fromSources("java/lang/String")).as("the JDK's").isFalse();
        } finally {
            fromJar.close();
            fromDir.close();
        }
    }
}
