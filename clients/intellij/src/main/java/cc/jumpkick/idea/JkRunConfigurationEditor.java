// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

/** The form of a {@link JkRunConfiguration}: kind, workspace root, module, test class. */
final class JkRunConfigurationEditor extends SettingsEditor<JkRunConfiguration> {

    private final ComboBox<String> kind =
            new ComboBox<>(new String[] {JkCommandLines.KIND_TEST, JkCommandLines.KIND_RUN});
    private final JBTextField rootDir = new JBTextField();
    private final JBTextField moduleRel = new JBTextField();
    private final JBTextField className = new JBTextField();

    @Override
    protected void resetEditorFrom(@NotNull JkRunConfiguration s) {
        kind.setSelectedItem(s.kind());
        rootDir.setText(s.rootDir());
        moduleRel.setText(s.moduleRel());
        className.setText(s.className() == null ? "" : s.className());
    }

    @Override
    protected void applyEditorTo(@NotNull JkRunConfiguration s) {
        Object k = kind.getSelectedItem();
        String cls = className.getText().trim();
        s.setTarget(
                k == null ? JkCommandLines.KIND_TEST : k.toString(),
                rootDir.getText().trim(),
                moduleRel.getText().trim(),
                cls.isEmpty() ? null : cls);
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Command:", kind)
                .addLabeledComponent("Workspace root:", rootDir)
                .addLabeledComponent("Module (relative):", moduleRel)
                .addLabeledComponent("Test class:", className)
                .getPanel();
    }
}
