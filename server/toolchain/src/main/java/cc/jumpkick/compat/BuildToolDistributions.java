// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.mvn.MavenResolver;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeResolver;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.node.PackageManagerSpec;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Which distribution a {@link BuildTool} means, at a named version or at the version jk defaults
 * to.
 *
 * <p>Each tool owns its own URL shape — Maven's lives on {@code MavenResolver}, Gradle's on {@code
 * GradleResolver}, Kotlin's on {@code KotlinResolver} — because the three are unrelated release
 * layouts that happen to be fetched the same way. What this adds is the one place that turns "the
 * user said kotlin" into the right one of them, so a command and the engine's mid-build
 * provisioning cannot disagree about what {@code kotlin:latest} installs.
 *
 * <p>The switch is exhaustive over {@link BuildTool}: another tool is an enum constant plus a
 * compile error here, not a silently unhandled name at the CLI. Node's default is the newest LTS;
 * a package manager's is the registry's newest.
 */
public final class BuildToolDistributions {

    private BuildToolDistributions() {}

    /**
     * The distribution for {@code tool} at {@code version}. {@code null}, blank and {@link
     * BuildTool#LATEST} all mean the tool's default — the same one the engine provisions when a
     * build needs the tool and nothing has pinned it.
     */
    public static ToolDistribution of(BuildTool tool, @Nullable String version)
            throws IOException, InterruptedException {
        String v = version == null || version.isBlank() || BuildTool.LATEST.equalsIgnoreCase(version.trim())
                ? null
                : version;
        return switch (tool) {
            case MAVEN -> v == null ? MavenResolver.defaultDistribution() : MavenResolver.distributionFor(v);
            case GRADLE -> v == null ? GradleResolver.defaultDistribution() : GradleResolver.distributionFor(v);
            case KOTLIN -> v == null ? KotlinResolver.defaultDistribution() : KotlinResolver.distributionFor(v);
            case NODE -> {
                NodeCatalog catalog = new NodeCatalog();
                NodePlatform host = NodePlatform.host();
                yield new NodeResolver(catalog)
                        .resolve(NodeSpec.parse(v == null ? "lts" : v), host)
                        .distribution(host, catalog.distBase());
            }
            case PNPM -> manager(PackageManager.PNPM, v);
            case YARN -> manager(PackageManager.YARN, v);
            case BUN -> manager(PackageManager.BUN, v);
        };
    }

    private static ToolDistribution manager(PackageManager pm, @Nullable String version)
            throws IOException, InterruptedException {
        return new PackageManagerResolver()
                .resolve(
                        new PackageManagerSpec(pm, version == null ? PackageManagerSpec.LATEST : version),
                        NodePlatform.host());
    }
}
