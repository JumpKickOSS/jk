// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.actions.LazyRunConfigurationProducer;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiMethodUtil;
import com.intellij.psi.util.PsiTreeUtil;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The gutter's Run and Debug on a JumpKick module route through {@code jk}: a class under a test
 * root becomes {@code jk test --class <fqcn>}, an annotated method in it {@code --class
 * <fqcn>#<method>}, a class with a {@code main} method under a source root becomes {@code jk
 * run}. Preferred over the bundled JUnit and Application producers on these modules, which keep
 * their own configurations in the list.
 */
public final class JkRunConfigurationProducer extends LazyRunConfigurationProducer<JkRunConfiguration> {

    /**
     * What a location resolves to: the command, where it runs, and the selector it names — a test
     * class, or {@code Class#method} for one method of it.
     */
    record Target(
            String kind,
            String rootDir,
            String moduleRel,
            @Nullable String className,
            PsiElement element) {

        boolean matches(JkRunConfiguration c) {
            return kind.equals(c.kind())
                    && rootDir.equals(c.rootDir())
                    && moduleRel.equals(c.moduleRel())
                    && (className == null ? c.className() == null : className.equals(c.className()));
        }
    }

    @Override
    public @NotNull ConfigurationFactory getConfigurationFactory() {
        return JkRunConfigurationType.factory();
    }

    @Override
    protected boolean setupConfigurationFromContext(
            @NotNull JkRunConfiguration configuration,
            @NotNull ConfigurationContext context,
            @NotNull Ref<PsiElement> sourceElement) {
        Target target = targetOf(context);
        if (target == null) return false;
        configuration.setTarget(target.kind(), target.rootDir(), target.moduleRel(), target.className());
        configuration.setGeneratedName();
        sourceElement.set(target.element());
        return true;
    }

    @Override
    public boolean isConfigurationFromContext(
            @NotNull JkRunConfiguration configuration, @NotNull ConfigurationContext context) {
        Target target = targetOf(context);
        return target != null && target.matches(configuration);
    }

    @Override
    public boolean isPreferredConfiguration(ConfigurationFromContext self, ConfigurationFromContext other) {
        return true;
    }

    /** The target at {@code context}'s location, or null when it is not a class of a JumpKick module. */
    static @Nullable Target targetOf(ConfigurationContext context) {
        PsiElement at = context.getPsiLocation();
        Module module = context.getModule();
        if (at == null || module == null || !ExternalSystemApiUtil.isExternalSystemAwareModule(JkSystem.ID, module)) {
            return null;
        }
        PsiClass cls = topLevelClass(at);
        if (cls == null || cls.getQualifiedName() == null) return null;
        PsiFile file = cls.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        if (vf == null) return null;
        String moduleDir = ExternalSystemApiUtil.getExternalProjectPath(module);
        String rootDir = ExternalSystemApiUtil.getExternalRootProjectPath(module);
        if (moduleDir == null || rootDir == null) return null;
        String rel = relative(rootDir, moduleDir);
        if (ProjectFileIndex.getInstance(module.getProject()).isInTestSourceContent(vf)) {
            PsiMethod method = testMethod(at);
            if (method != null) {
                return new Target(
                        JkCommandLines.KIND_TEST,
                        rootDir,
                        rel,
                        selector(cls.getQualifiedName(), method.getName()),
                        method);
            }
            return new Target(JkCommandLines.KIND_TEST, rootDir, rel, cls.getQualifiedName(), cls);
        }
        if (PsiMethodUtil.hasMainMethod(cls)) {
            return new Target(JkCommandLines.KIND_RUN, rootDir, rel, null, cls);
        }
        return null;
    }

    /** {@code --class}'s spelling of one method of a class. */
    static String selector(String className, String methodName) {
        return className + "#" + methodName;
    }

    /**
     * The test method the location is in: a non-static method carrying an annotation — {@code
     * @Test}, {@code @ParameterizedTest}, TestNG's {@code @Test} — whose class the gutter marks.
     * Null for a location outside a method or on a helper, which run the class.
     */
    private static @Nullable PsiMethod testMethod(PsiElement at) {
        PsiMethod method = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
        if (method == null || method.isConstructor() || method.hasModifierProperty(PsiModifier.STATIC)) return null;
        return method.getModifierList().getAnnotations().length == 0 ? null : method;
    }

    /** The class the location is in, up to the top level; a file's first class when the caret is outside one. */
    private static @Nullable PsiClass topLevelClass(PsiElement at) {
        PsiClass cls = PsiTreeUtil.getParentOfType(at, PsiClass.class, false);
        if (cls == null && at.getContainingFile() instanceof PsiJavaFile java && java.getClasses().length > 0) {
            cls = java.getClasses()[0];
        }
        while (cls != null && cls.getContainingClass() != null) cls = cls.getContainingClass();
        return cls;
    }

    /** {@code moduleDir} relative to {@code rootDir} with forward slashes; {@code ""} when equal. */
    static String relative(String rootDir, String moduleDir) {
        Path root = Path.of(rootDir).toAbsolutePath().normalize();
        Path dir = Path.of(moduleDir).toAbsolutePath().normalize();
        if (dir.equals(root) || !dir.startsWith(root)) return "";
        return root.relativize(dir).toString().replace('\\', '/');
    }
}
