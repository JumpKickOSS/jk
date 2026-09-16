// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes sure every SDK the model names exists in IntelliJ's JDK table before the module SDK data
 * is applied: an unknown name would leave the module without a JDK. Entries are registered under
 * the stable {@code jk-<vendor>-<level>} name at the stable home the engine reports.
 */
final class JkSdkTable {

    private JkSdkTable() {}

    /** Register the missing entries; returns the names added (empty when all were known). */
    static List<String> ensure(List<JkWireModel.SdkEntry> entries) {
        List<String> added = new ArrayList<>();
        ApplicationManager.getApplication()
                .invokeAndWait(() -> WriteAction.run(() -> {
                    ProjectJdkTable table = ProjectJdkTable.getInstance();
                    for (JkWireModel.SdkEntry e : entries) {
                        if (e.name().isEmpty() || table.findJdk(e.name()) != null) continue;
                        if (!Files.isDirectory(Path.of(e.home()))) continue;
                        Sdk sdk = JavaSdk.getInstance().createJdk(e.name(), e.home(), false);
                        table.addJdk(sdk);
                        added.add(e.name());
                    }
                }));
        return added;
    }
}
