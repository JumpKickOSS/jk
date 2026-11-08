// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import cc.jumpkick.compat.AsyncApiTable;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.PluginConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The schema compiler plugins of a Gradle project as their jk tables: xjc's {@code [jaxb]}, Avro's
 * {@code [avro]}, scs-multiapi's AsyncAPI specs' {@code [asyncapi]}.
 */
final class GradleSchemaTables {

    /** The {@code [avro]} table's own defaults. */
    private static final String AVRO_DEFAULT_SRC = "src/main/avro";

    private static final String AVRO_DEFAULT_VERSION = "1.12.2";

    /** The {@code [jaxb]} table's own default schema directory. */
    private static final String JAXB_DEFAULT_SRC = "src/main/xsd";

    /** sngular's scs-multiapi Gradle plugin, which generates from AsyncAPI and OpenAPI specs. */
    static final String SCS_MULTIAPI = "com.sngular.scs-multiapi-gradle-plugin";

    private GradleSchemaTables() {}

    /** The scs-multiapi plugin's AsyncAPI specs as {@code [asyncapi]}; null without any in the tree. */
    static @Nullable PluginConfig asyncApi(GradleModel.Project p, ImportReport.Builder local) {
        if (p.asyncApiSpecs().isEmpty()) return null;
        List<AsyncApiTable.Spec> specs = new ArrayList<>();
        for (GradleModel.AsyncApiSpec s : p.asyncApiSpecs()) {
            specs.add(new AsyncApiTable.Spec(s.path(), s.modelPackages(), s.generatesCode(), s.artifact()));
        }
        return AsyncApiTable.of("scs-multiapi", specs, local);
    }

    /**
     * The xjc plugin's extension as the {@code [jaxb]} table: {@code xsdDir} is {@code src}, {@code
     * defaultPackage} is {@code package}, {@code bindingFiles} are {@code bindings} and {@code
     * options} are {@code arguments}.
     */
    static PluginConfig jaxb(GradleModel.Xjc xjc, ImportReport.Builder local) {
        Map<String, Object> table = new LinkedHashMap<>();
        String src = xjc.xsdDir() == null ? "src/main/resources" : xjc.xsdDir();
        if (!src.equals(JAXB_DEFAULT_SRC)) table.put("src", src);
        if (xjc.defaultPackage() != null) table.put("package", xjc.defaultPackage());
        if (!xjc.bindingFiles().isEmpty()) table.put("bindings", xjc.bindingFiles());
        if (!xjc.options().isEmpty()) table.put("arguments", xjc.options());
        local.warning(GradleModelImporter.XJC_PLUGIN + " became [jaxb] over the schemas in " + src
                + "; kept jakarta.xml.bind-api and jaxb-runtime");
        return new PluginConfig("jaxb", table);
    }

    /**
     * {@code [avro]}'s {@code src} and {@code version} where they differ from the defaults. A schema
     * directory under the build directory is another task's output: a row naming the task, unless
     * a rule already brought the module's schemas in through {@code [schema-registry]}; a directory
     * the registry plugin downloads into is left out, since the store hands those over. The plugin's
     * own IDL output is skipped: {@code [avro]} compiles IDL itself.
     */
    static PluginConfig avro(
            GradleModel.Project p,
            GradleModel.Avro avro,
            boolean fromRegistry,
            Set<String> downloaded,
            ImportReport.Builder local) {
        Map<String, Object> table = new LinkedHashMap<>();
        String build = avro.buildDir() == null ? null : avro.buildDir() + "/";
        List<String> sources = new ArrayList<>();
        for (String dir : avro.sources()) {
            String writers = p.writersOf(dir);
            if (GradleSchemaRegistry.downloaded(dir, downloaded)) continue;
            if (build == null || !(dir + "/").startsWith(build)) sources.add(dir);
            else if (fromRegistry || dir.matches(".*/generated-\\w+-avro-avpr")) continue;
            else if (writers != null) local.warning("Avro schemas come from " + writers + "; see [schema-registry]");
            else local.warning("Avro schemas are generated into " + dir + "; put them in src/main/avro");
        }
        if (!sources.isEmpty() && !sources.getFirst().equals(AVRO_DEFAULT_SRC)) table.put("src", sources.getFirst());
        if (sources.size() > 1) local.warning("Avro: imported " + sources.getFirst() + " only");
        String version = p.resolved().get("org.apache.avro:avro");
        if (version != null && !version.equals(AVRO_DEFAULT_VERSION)) table.put("version", version);
        return new PluginConfig("avro", table);
    }
}
