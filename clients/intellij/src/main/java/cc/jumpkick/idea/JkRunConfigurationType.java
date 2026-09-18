// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

/** The run-configuration type behind a gutter Run or Debug on a JumpKick module: one {@code jk} command. */
public final class JkRunConfigurationType extends ConfigurationTypeBase {

    public static final String ID = "JumpKick";

    public JkRunConfigurationType() {
        super(ID, "JumpKick", "Run or debug through jk", NotNullLazyValue.createValue(() -> AllIcons.Actions.Execute));
        addFactory(new Factory(this));
    }

    public static JkRunConfigurationType getInstance() {
        return ConfigurationTypeUtil.findConfigurationType(JkRunConfigurationType.class);
    }

    public static ConfigurationFactory factory() {
        return getInstance().getConfigurationFactories()[0];
    }

    /** The one factory: every JumpKick configuration is a {@link JkRunConfiguration}. */
    private static final class Factory extends ConfigurationFactory {

        Factory(ConfigurationType type) {
            super(type);
        }

        @Override
        public @NotNull String getId() {
            return ID;
        }

        @Override
        public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
            return new JkRunConfiguration(project, this);
        }
    }
}
