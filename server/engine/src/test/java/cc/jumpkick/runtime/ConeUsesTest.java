// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.test.ClassCones;
import cc.jumpkick.test.ClassReads;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the probe told which of a cone's classes ran in a test class's window, a class that did not
 * run keys by its bytes less its method bodies: an edit inside a body it never ran replays the test
 * class, while an edit to a body it ran, to a signature or to a static initialiser runs it, whether
 * the class sits in a class tree or in a sibling's jar. A test class whose own use the probe never
 * named keys every class by its bytes.
 */
class ConeUsesTest {

    private static final ClassDesc TEST = ClassDesc.of("demo.T");
    private static final ClassDesc DEP = ClassDesc.of("demo.Dep");

    @Test
    void a_body_the_test_class_never_ran_replays_it(@TempDir Path ws) throws Exception {
        Path classes = ws.resolve("classes");
        write(classes, "demo/T", testClass());
        write(classes, "demo/Dep", dep(1, CD_int, 0));
        ClassReads.Observation unused = observation("demo.T");
        ClassReads.Observation ran = observation("demo.T", "demo.Dep");
        String rowUnused = row(classes, ws, unused);
        String rowRan = row(classes, ws, ran);

        write(classes, "demo/Dep", dep(2, CD_int, 0));
        assertThat(whyRun(classes, ws, rowUnused))
                .as("its body changed and did not run")
                .isNull();
        assertThat(whyRun(classes, ws, rowRan)).as("its body changed and ran").isEqualTo("key");

        write(classes, "demo/Dep", dep(2, CD_long, 0));
        assertThat(whyRun(classes, ws, rowUnused))
                .as("a signature is what every caller sees")
                .isEqualTo("key");

        write(classes, "demo/Dep", dep(2, CD_int, 0));
        rowUnused = row(classes, ws, unused);
        write(classes, "demo/Dep", dep(2, CD_int, 5));
        assertThat(whyRun(classes, ws, rowUnused))
                .as("its static initialiser sets what a field read sees")
                .isEqualTo("key");
    }

    @Test
    void without_the_test_classs_own_use_every_class_keys_by_its_bytes(@TempDir Path ws) throws Exception {
        Path classes = ws.resolve("classes");
        write(classes, "demo/T", testClass());
        write(classes, "demo/Dep", dep(1, CD_int, 0));
        ClassCones.Cone cone = ClassCones.index(List.of(classes)).cone("demo.T");
        assertThat(ClassShadow.used(cone, observation("demo.Dep"), "demo.T"))
                .as("its JVM ran unmarked")
                .isNull();
        assertThat(ClassShadow.used(cone, null, "demo.T")).isNull();
        assertThat(ClassShadow.used(cone, observation("demo.T"), "demo.T")).isEqualTo("40");

        String row = row(classes, ws, observation("demo.Dep"));
        write(classes, "demo/Dep", dep(2, CD_int, 0));
        assertThat(whyRun(classes, ws, row)).isEqualTo("key");
    }

    @Test
    void a_siblings_jar_counts_as_a_class_tree_does(@TempDir Path ws) throws Exception {
        Path classes = ws.resolve("classes");
        write(classes, "demo/T", testClass());
        Path jar = ws.resolve("dep.jar");
        jar(jar, dep(1, CD_int, 0));
        ClassCones.Cone cone = ClassCones.index(List.of(classes, jar)).cone("demo.T");
        String unused = cone.used(c -> c.equals("demo.T"));
        String ran = cone.used(c -> true);
        assertThat(unused).as("a bit for each class, the jar's among them").isEqualTo("40");
        assertThat(ran).isEqualTo("c0");

        jar(jar, dep(2, CD_int, 0));
        ClassCones.Cone body = ClassCones.index(List.of(classes, jar)).cone("demo.T");
        assertThat(body.digest(unused))
                .as("a body in the jar the test class never ran")
                .isEqualTo(cone.digest(unused));
        assertThat(body.digest(ran)).as("a body in the jar it ran").isNotEqualTo(cone.digest(ran));

        jar(jar, dep(2, CD_long, 0));
        ClassCones.Cone signature = ClassCones.index(List.of(classes, jar)).cone("demo.T");
        assertThat(signature.digest(unused))
                .as("a signature in the jar is what every caller sees")
                .isNotEqualTo(cone.digest(unused));
    }

    private static ClassReads.Observation observation(String... used) {
        ClassReads.Observation o = ClassReads.Observation.empty();
        for (String u : used) o.addUsed(u);
        return o;
    }

    /** The row the class records for demo.T after a green run with {@code obs}. */
    private static String row(Path classes, Path ws, ClassReads.Observation obs) {
        ClassCones.Cone cone = ClassCones.index(List.of(classes)).cone("demo.T");
        String used = ClassShadow.used(cone, obs, "demo.T");
        String key = ClassKeys.key(
                        "",
                        cone,
                        used,
                        "suite",
                        ClassKeys.Observed.NONE,
                        Map.of(),
                        ClassKeys.Observed.NONE,
                        "module",
                        ClassKeys.SuiteReadSet.NONE,
                        ws,
                        null,
                        new HashMap<>())
                .key();
        return JsonFields.object()
                .string("launch", "")
                .string("outcome", "passed")
                .number("ranAt", 1_000)
                .string("classKey", key)
                .optionalString("coneUsed", used)
                .finish();
    }

    private static @Nullable String whyRun(Path classes, Path ws, String row) {
        ClassCones.Index index = ClassCones.index(List.of(classes));
        ClassShadow shadow = new ClassShadow(
                index,
                "suite",
                ws,
                ClassKeys.Observed.NONE,
                ClassKeys.Observed.NONE,
                Map.of(),
                ClassKeys.SuiteReadSet.NONE,
                ClassKeys.SuiteReadSet.NONE,
                Long.MAX_VALUE,
                false,
                "stamp");
        return shadow.whyRun(row, "", "demo.T");
    }

    private static byte[] testClass() {
        return ClassFile.of()
                .build(
                        TEST,
                        cb -> cb.withMethod(
                                "run",
                                MethodTypeDesc.of(CD_int),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                mb -> mb.withCode(c -> c.invokestatic(DEP, "f", MethodTypeDesc.of(CD_int))
                                        .ireturn())));
    }

    /** demo.Dep: {@code f()} returns {@code body} as {@code type}, and its static initialiser stores {@code init}. */
    private static byte[] dep(int body, ClassDesc type, int init) {
        return ClassFile.of().build(DEP, cb -> cb.withField("STATE", CD_int, ClassFile.ACC_STATIC)
                .withMethod(
                        "f",
                        MethodTypeDesc.of(type),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        mb -> mb.withCode(c -> {
                            c.ldc(body);
                            if (type.equals(CD_long)) c.i2l().lreturn();
                            else c.ireturn();
                        }))
                .withMethod(
                        "<clinit>",
                        MethodTypeDesc.of(ClassDesc.ofDescriptor("V")),
                        ClassFile.ACC_STATIC,
                        mb -> mb.withCode(
                                c -> c.ldc(init).putstatic(DEP, "STATE", CD_int).return_()))
                .withMethod(
                        "hold",
                        MethodTypeDesc.of(CD_Object),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        mb -> mb.withCode(c -> c.aconst_null().areturn())));
    }

    private static void write(Path root, String internalName, byte[] bytes) throws Exception {
        Path f = root.resolve(internalName + ".class");
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
    }

    private static void jar(Path jar, byte[] dep) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("demo/Dep.class"));
            out.write(dep);
            out.closeEntry();
        }
    }
}
