// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.Interpolation;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * One way a module's manifest reads the caller's environment. {@link #of} lists every read a
 * manifest makes; the client ships the names they need ({@link VariantApply#envRefs}) and the
 * engine keys the values ({@code ModuleEnvKeys}), both from this list, so a vocabulary that reads
 * the environment is shipped and keyed or neither.
 */
public sealed interface EnvRead {

    /** The variables this read needs from the caller's shell. */
    List<String> names();

    /** An {@code [env]} entry: it reaches every worker the module forks, the test JVM included. */
    record Worker(EnvDecl decl) implements EnvRead {
        @Override
        public List<String> names() {
            return declNames(decl);
        }
    }

    /** {@code [env] inherit = true}: the caller's whole environment reaches every worker. */
    record WholeShell() implements EnvRead {
        @Override
        public List<String> names() {
            return List.of(VariantApply.WHOLE_SHELL);
        }
    }

    /** A {@code [test] env} entry: it reaches the test JVM alone. */
    record TestJvm(EnvDecl decl) implements EnvRead {
        @Override
        public List<String> names() {
            return declNames(decl);
        }
    }

    /**
     * {@code env:NAME} as a value in a plugin's table: resolved into the plugin's config (or its
     * secrets), which its steps and packager read.
     */
    record PluginValue(String name) implements EnvRead {
        @Override
        public List<String> names() {
            return List.of(name);
        }
    }

    /** Every read {@code build} makes, in manifest order, each plugin variable once. */
    static List<EnvRead> of(JkBuild build) {
        List<EnvRead> out = new ArrayList<>();
        if (build.build().env().inherit()) out.add(new WholeShell());
        for (EnvDecl decl : build.build().env().decls()) out.add(new Worker(decl));
        for (EnvDecl decl : build.build().testEnv()) out.add(new TestJvm(decl));
        LinkedHashSet<String> plugin = new LinkedHashSet<>();
        for (PluginConfig config : build.pluginConfigs().values()) collectPluginRefs(config.values(), plugin);
        for (String name : plugin) out.add(new PluginValue(name));
        return List.copyOf(out);
    }

    /** The prefix that makes a plugin table's string value a variable's name. */
    String PLUGIN_PREFIX = "env:";

    private static List<String> declNames(EnvDecl decl) {
        return switch (decl) {
            case EnvDecl.Forward forward -> List.of(forward.name());
            case EnvDecl.Set set -> {
                List<String> names = new ArrayList<>();
                for (String name : Interpolation.references(set.value())) {
                    if (!TestEnvValues.isPathToken(name)) names.add(name);
                }
                yield names;
            }
        };
    }

    private static void collectPluginRefs(Map<String, Object> values, LinkedHashSet<String> names) {
        for (Object v : values.values()) {
            if (v instanceof String s && s.startsWith(PLUGIN_PREFIX)) {
                names.add(s.substring(PLUGIN_PREFIX.length()).trim());
            } else if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) m;
                collectPluginRefs(nested, names);
            }
        }
    }
}
