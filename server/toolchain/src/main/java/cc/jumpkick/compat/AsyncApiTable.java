// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.PluginConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [asyncapi]} table an AsyncAPI code generator's settings import as, Maven's or Gradle's:
 * its documents are {@code src} (left out when it is the preset's one default), the first model
 * package is {@code package}. What jk does not generate, consumer and producer code and a document
 * read from a published artifact, is a row naming the plugin.
 */
public final class AsyncApiTable {

    static final String DEFAULT_SRC = "src/main/asyncapi/asyncapi.yaml";

    /**
     * One document the plugin reads: its module-relative path (null when it comes from a published
     * artifact, named by {@code artifact}), the model packages its settings name, and whether the
     * plugin also generates consumer or producer code from it.
     */
    public record Spec(
            @Nullable String path,
            List<String> modelPackages,
            boolean generatesCode,
            @Nullable String artifact) {}

    private AsyncApiTable() {}

    /** The table, or null when no document is in the tree. */
    public static @Nullable PluginConfig of(String plugin, List<Spec> specs, ImportReport.Builder report) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> packages = new LinkedHashSet<>();
        boolean code = false;
        for (Spec spec : specs) {
            if (spec.path() == null) {
                report.warning(plugin + ": spec from " + spec.artifact() + " not imported; copy it into the tree");
                continue;
            }
            paths.add(spec.path());
            packages.addAll(spec.modelPackages());
            code |= spec.generatesCode();
        }
        if (code) report.warning(plugin + ": consumer and producer code not generated; [asyncapi] makes the models");
        if (paths.isEmpty()) return null;
        List<String> src = new ArrayList<>(paths);
        Map<String, Object> table = new LinkedHashMap<>();
        if (!src.equals(List.of(DEFAULT_SRC))) table.put("src", src.size() == 1 ? src.getFirst() : src);
        if (!packages.isEmpty()) table.put("package", packages.iterator().next());
        if (packages.size() > 1) report.warning(plugin + ": model packages " + packages + " differ; kept the first");
        return new PluginConfig("asyncapi", table);
    }
}
