// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The Hilt superclass transform, over fixture classes generated with ASM — no Hilt, no Android
 * jar, no KSP run. The rewritten classes are asserted by <em>loading</em> them: a superclass swap
 * whose constructor still calls the old base's {@code <init>} is a {@code VerifyError}
 * ({@code invokespecial <init>} must target the direct superclass), so instantiation is the proof
 * that the re-own arm ran, where a byte diff could not tell a linked class from a broken one.
 *
 * <p>The {@code android.*} classes here are empty stand-ins generated like every other fixture:
 * the transform never resolves them, but the test's class loader must, to execute
 * {@code onReceive}.
 */
class HiltTransformStepTest {

    private static final String ENTRY_POINT = "Ldagger/hilt/android/AndroidEntryPoint;";
    private static final String ON_RECEIVE_DESC = "(Landroid/content/Context;Landroid/content/Intent;)V";

    /** The headline path: the annotated activity re-parents onto its generated base and links. */
    @Test
    void an_annotated_activitys_superclass_is_rewritten_to_the_generated_base_and_links(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        writeClass(exec, "android/app/Activity", plainClass("android/app/Activity", "java/lang/Object", false));
        writeClass(exec, "app/Hilt_MainActivity", plainClass("app/Hilt_MainActivity", "android/app/Activity", false));
        writeClass(exec, "app/MainActivity", plainClass("app/MainActivity", "android/app/Activity", true));

        HiltTransformStep.run(exec);

        Path out = exec.scratch().resolve("classes");
        assertThat(new ClassReader(Files.readAllBytes(out.resolve("app/MainActivity.class"))).getSuperName())
                .isEqualTo("app/Hilt_MainActivity");
        try (URLClassLoader loader = loaderOver(out)) {
            Object activity = loader.loadClass("app.MainActivity")
                    .getDeclaredConstructor()
                    .newInstance();
            assertThat(activity.getClass().getSuperclass().getName()).isEqualTo("app.Hilt_MainActivity");
        }
        assertThat(exec.labels()).contains("hilt transform (1 class)");
    }

    /**
     * The receiver shape: the generated base declares {@code onReceive}, so the subclass's own
     * override gets {@code super.onReceive(context, intent)} injected at its start — running the
     * base's injection before the app's code, which is the whole point of the transform.
     */
    @Test
    void a_receivers_override_calls_up_into_the_generated_bases_on_receive(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        writeClass(exec, "android/content/Context", plainClass("android/content/Context", "java/lang/Object", false));
        writeClass(exec, "android/content/Intent", plainClass("android/content/Intent", "java/lang/Object", false));
        writeClass(exec, "app/BaseReceiver", plainClass("app/BaseReceiver", "java/lang/Object", false));
        writeClass(exec, "app/Hilt_MyReceiver", receiverClass("app/Hilt_MyReceiver", "app/BaseReceiver", false));
        writeClass(exec, "app/MyReceiver", receiverClass("app/MyReceiver", "app/BaseReceiver", true));

        HiltTransformStep.run(exec);

        try (URLClassLoader loader = loaderOver(exec.scratch().resolve("classes"))) {
            Class<?> receiver = loader.loadClass("app.MyReceiver");
            Object instance = receiver.getDeclaredConstructor().newInstance();
            receiver.getMethod(
                            "onReceive",
                            loader.loadClass("android.content.Context"),
                            loader.loadClass("android.content.Intent"))
                    .invoke(instance, null, null);
            assertThat(loader.loadClass("app.Hilt_MyReceiver")
                            .getField("CALLED")
                            .getBoolean(null))
                    .as("the injected super call ran the generated base's onReceive")
                    .isTrue();
            assertThat(receiver.getField("CALLED").getBoolean(null))
                    .as("the subclass's own body still runs after it")
                    .isTrue();
        }
    }

    /** An annotated class whose {@code Hilt_*} base never generated names the missing processor. */
    @Test
    void an_annotated_class_with_no_generated_base_is_refused_with_the_processor_hint(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        writeClass(exec, "android/app/Activity", plainClass("android/app/Activity", "java/lang/Object", false));
        writeClass(exec, "app/Lonely", plainClass("app/Lonely", "android/app/Activity", true));

        assertThatThrownBy(() -> HiltTransformStep.run(exec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.Lonely")
                .hasMessageContaining("app.Hilt_Lonely")
                .hasMessageContaining("[processor-dependencies]");
    }

    /**
     * The two skips and the copy: a nested ({@code $}) entry point keeps the plugin-less spelling,
     * a class already extending {@code Hilt_*} is already in that spelling, and a resource file is
     * not bytecode — all three land in the output byte-identical.
     */
    @Test
    void nested_and_already_rewritten_entry_points_and_resources_pass_through_untouched(@TempDir Path tmp)
            throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);
        writeClass(exec, "android/app/Activity", plainClass("android/app/Activity", "java/lang/Object", false));
        byte[] nested = plainClass("app/Outer$Inner", "android/app/Activity", true);
        writeClass(exec, "app/Outer$Inner", nested);
        byte[] already = plainClass("app/Already", "app/Hilt_Already", true);
        writeClass(exec, "app/Already", already);
        Files.writeString(exec.classesDir().resolve("app.properties"), "key=value");

        HiltTransformStep.run(exec);

        Path out = exec.scratch().resolve("classes");
        assertThat(Files.readAllBytes(out.resolve("app/Outer$Inner.class"))).isEqualTo(nested);
        assertThat(Files.readAllBytes(out.resolve("app/Already.class"))).isEqualTo(already);
        assertThat(out.resolve("app.properties")).hasContent("key=value");
        assertThat(exec.labels()).contains("hilt transform (0 classes)");
    }

    // ---- fixtures -----------------------------------------------------------------------

    /** Write generated fixture bytes where the step's input walk will find them. */
    private static void writeClass(FakeBuildIo exec, String internalName, byte[] bytes) throws IOException {
        Path file = exec.classesDir().resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** A class with one no-arg constructor calling {@code super.<init>}, optionally annotated. */
    private static byte[] plainClass(String name, String superName, boolean entryPoint) {
        ClassWriter writer = start(name, superName, entryPoint);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * As {@link #plainClass}, plus a {@code public static boolean CALLED} the class's own
     * {@code onReceive(Context, Intent)} sets — how a test observes which bodies ran.
     */
    private static byte[] receiverClass(String name, String superName, boolean entryPoint) {
        ClassWriter writer = start(name, superName, entryPoint);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CALLED", "Z", null, null)
                .visitEnd();
        MethodVisitor onReceive = writer.visitMethod(Opcodes.ACC_PUBLIC, "onReceive", ON_RECEIVE_DESC, null, null);
        onReceive.visitCode();
        onReceive.visitInsn(Opcodes.ICONST_1);
        onReceive.visitFieldInsn(Opcodes.PUTSTATIC, name, "CALLED", "Z");
        onReceive.visitInsn(Opcodes.RETURN);
        onReceive.visitMaxs(1, 3);
        onReceive.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter start(String name, String superName, boolean entryPoint) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (entryPoint) {
            // CLASS retention, so invisible — the shape the real Hilt annotation arrives in.
            writer.visitAnnotation(ENTRY_POINT, false).visitEnd();
        }
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        return writer;
    }

    private static URLClassLoader loaderOver(Path classes) throws Exception {
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, HiltTransformStepTest.class.getClassLoader());
    }
}
