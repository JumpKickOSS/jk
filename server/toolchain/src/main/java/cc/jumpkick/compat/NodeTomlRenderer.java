// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.util.MinimalToml;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The node toolchain, the {@code [node]} table and {@code [env]} as {@link JkBuildRenderer} writes
 * them. {@code node = "…"} is a top-level key only when there is no {@code [node]} table, which
 * then carries the version itself.
 */
final class NodeTomlRenderer {

    private NodeTomlRenderer() {}

    /** {@code node = "…"} among the project keys, when the module has no {@code [node]} table. */
    static void renderNodeKey(StringBuilder sb, JkBuild build) {
        ToolchainSpec spec = build.project().nodeSpec();
        if (spec.isEmpty() || hasTable(build)) return;
        sb.append("node     = ").append(quote(spec(spec))).append('\n');
    }

    /** {@code [node]} with the version, when the module has settings, and {@code [[node.steps]]}. */
    static void renderNode(StringBuilder sb, JkBuild build) {
        if (!hasTable(build)) return;
        NodeTable t = build.node();
        sb.append("\n[node]\n");
        ToolchainSpec spec = build.project().nodeSpec();
        if (!spec.isEmpty()) sb.append("version = ").append(quote(spec(spec))).append('\n');
        string(sb, "package-manager", t.packageManager());
        string(sb, "framework", t.framework());
        string(sb, "dir", t.dir());
        string(sb, "install", t.install());
        if (t.build() != null) {
            NodeTable.Command c = t.build();
            sb.append("build = ")
                    .append(
                            c.kind() == NodeTable.Command.Kind.RUN
                                    ? quote(c.value())
                                    : "{ " + c.kind().key() + " = " + quote(c.value()) + " }")
                    .append('\n');
        }
        string(sb, "test", t.test());
        string(sb, "dev", t.dev());
        string(sb, "start", t.start());
        string(sb, "out", t.out());
        string(sb, "classpath-root", t.classpathRoot());
        string(sb, "webapp-root", t.webappRoot());
        if (t.envPrefixes() != null)
            sb.append("env-prefixes = ")
                    .append(JkBuildRenderer.list(t.envPrefixes()))
                    .append('\n');
        if (t.devPort() != null) sb.append("dev-port = ").append(t.devPort()).append('\n');
        if (!t.inputs().isEmpty())
            sb.append("inputs = ").append(JkBuildRenderer.list(t.inputs())).append('\n');
        if (t.skip()) sb.append("skip = true\n");

        if (!t.exports().isEmpty()) {
            List<String> parts = new ArrayList<>();
            t.exports().forEach((k, v) -> parts.add(JkBuildRenderer.safeKey(k) + " = " + quote(v)));
            sb.append("exports = { ").append(String.join(", ", parts)).append(" }\n");
        }
        for (NodeTable.Step step : t.steps()) {
            sb.append("\n[[node.steps]]\n");
            sb.append("name = ").append(quote(step.name())).append('\n');
            sb.append(step.command().kind().key())
                    .append(" = ")
                    .append(quote(step.command().value()))
                    .append('\n');
            if (step.before() != NodeTable.Before.BUILD) {
                sb.append("before = ")
                        .append(quote(step.before().name().toLowerCase(Locale.ROOT)))
                        .append('\n');
            }
            if (step.tier() != NodeTable.Tier.BUILD) {
                sb.append("tier = ")
                        .append(quote(step.tier().name().toLowerCase(Locale.ROOT)))
                        .append('\n');
            }
            if (!step.inputs().isEmpty())
                sb.append("inputs = ")
                        .append(JkBuildRenderer.list(step.inputs()))
                        .append('\n');
            if (!step.outputs().isEmpty()) {
                sb.append("outputs = ")
                        .append(JkBuildRenderer.list(step.outputs()))
                        .append('\n');
            }
            if (step.allowUnlocked()) sb.append("allow-unlocked = true\n");
        }
    }

    /** {@code [env]}: {@code inherit} and {@code vars}, a bare name forwarding and a table setting. */
    static void renderEnv(StringBuilder sb, EnvConfig env) {
        if (env.isEmpty()) return;
        sb.append("\n[env]\n");
        if (env.inherit()) sb.append("inherit = true\n");
        if (env.vars().isEmpty()) return;
        List<String> parts = new ArrayList<>();
        for (EnvDecl d : env.vars()) {
            parts.add(
                    switch (d) {
                        case EnvDecl.Forward f -> quote(f.name());
                        case EnvDecl.Set s ->
                            "{ " + JkBuildRenderer.safeKey(s.name()) + " = " + quote(s.value()) + " }";
                    });
        }
        sb.append("vars = [").append(String.join(", ", parts)).append("]\n");
    }

    private static boolean hasTable(JkBuild build) {
        return !build.node().equals(NodeTable.EMPTY);
    }

    private static String spec(ToolchainSpec spec) {
        return spec.requiredVersion().isEmpty() ? spec.suggestedVersion() : "=" + spec.requiredVersion();
    }

    private static void string(StringBuilder sb, String key, @Nullable String value) {
        if (value != null) sb.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static String quote(String s) {
        return MinimalToml.quote(s);
    }
}
