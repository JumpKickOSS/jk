// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.LocatableConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import java.nio.file.Path;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One {@code jk} command as a run configuration: the workspace root it runs in, the module
 * directory relative to it, and for a test the class — or {@code Class#method} — to select. Debug
 * runs the same command with a JDWP listener the IDE attaches to ({@link JkCommandState}).
 */
public final class JkRunConfiguration extends LocatableConfigurationBase<RunConfigurationOptions> {

    private String kind = JkCommandLines.KIND_TEST;
    private String rootDir = "";
    private String moduleRel = "";
    private @Nullable String className;

    JkRunConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory) {
        super(project, factory, "");
    }

    /** {@link JkCommandLines#KIND_TEST} or {@link JkCommandLines#KIND_RUN}. */
    public String kind() {
        return kind;
    }

    /** The workspace (or standalone project) root the command runs in. */
    public String rootDir() {
        return rootDir;
    }

    /** The module directory relative to {@link #rootDir()}; {@code ""} when the root is the module. */
    public String moduleRel() {
        return moduleRel;
    }

    /** The test class, or {@code Class#method}, the run selects; null for an application run. */
    public @Nullable String className() {
        return className;
    }

    public void setTarget(String kind, String rootDir, String moduleRel, @Nullable String className) {
        this.kind = kind;
        this.rootDir = rootDir;
        this.moduleRel = moduleRel;
        this.className = className;
    }

    @Override
    public @Nullable String suggestedName() {
        if (JkCommandLines.KIND_RUN.equals(kind)) {
            String what = moduleRel.isEmpty() ? String.valueOf(Path.of(rootDir).getFileName()) : moduleRel;
            return "jk run " + what;
        }
        String cls = className == null ? "" : className;
        int hash = cls.indexOf('#');
        String qualified = hash < 0 ? cls : cls.substring(0, hash);
        return "jk test " + qualified.substring(qualified.lastIndexOf('.') + 1) + (hash < 0 ? "" : cls.substring(hash));
    }

    @Override
    public @NotNull SettingsEditor<? extends JkRunConfiguration> getConfigurationEditor() {
        return new JkRunConfigurationEditor();
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (rootDir.isBlank()) throw new RuntimeConfigurationError("No workspace root");
        if (JkCommandLines.KIND_TEST.equals(kind) && (className == null || className.isBlank())) {
            throw new RuntimeConfigurationError("No test class");
        }
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) {
        return new JkCommandState(environment, this);
    }

    @Override
    public void readExternal(@NotNull Element element) {
        super.readExternal(element);
        kind = orDefault(JDOMExternalizerUtil.readField(element, "kind"), JkCommandLines.KIND_TEST);
        rootDir = orDefault(JDOMExternalizerUtil.readField(element, "rootDir"), "");
        moduleRel = orDefault(JDOMExternalizerUtil.readField(element, "moduleRel"), "");
        className = JDOMExternalizerUtil.readField(element, "className");
    }

    @Override
    public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        JDOMExternalizerUtil.writeField(element, "kind", kind);
        JDOMExternalizerUtil.writeField(element, "rootDir", rootDir);
        JDOMExternalizerUtil.writeField(element, "moduleRel", moduleRel);
        if (className != null) JDOMExternalizerUtil.writeField(element, "className", className);
    }

    private static String orDefault(@Nullable String value, String fallback) {
        return value == null ? fallback : value;
    }
}
