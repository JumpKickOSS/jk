// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.DomXml;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

/**
 * The SDK is only as good as its published closure. {@code cc.jumpkick:jk-plugin-sdk} shipped a POM
 * whose one dependency was {@code jk:host:unspecified} — a coordinate no repository can serve — so
 * every consumer failed in dependency resolution before compiling a line. Asserting on
 * POM text would have proved the string changed; this resolves the artifact the way a Maven
 * consumer does and then compiles against what came back.
 *
 * <p>The repository is the tree-local one the build publishes into (see {@code treeLocal} in
 * {@code jk.java-conventions}), staged by {@code test}'s task dependencies — no network, so this
 * stays in the fast tier. Nothing but the SDK coordinate is handed to the resolver: {@code :host}
 * has to arrive through the POM or not at all.
 */
class PublishedSdkConsumerTest {

    /** Where the build staged the publications. Set by {@code :plugin-sdk:test}. */
    private static final String REPO_PROPERTY = "jk.tree.local.repo";

    /**
     * The version the build published, handed over by {@code :plugin-sdk:test} from the {@code
     * version} it publishes under. Read from the build rather than a constant in {@code src/main}:
     * a constant can disagree with what was published and this test would still resolve a jar.
     */
    private static final String VERSION_PROPERTY = "jk.plugin.sdk.version";

    private static final String GROUP = "cc.jumpkick";
    private static final String SDK = "jk-plugin-sdk";
    private static final String HOST = "jk-host";

    /**
     * One class implementing {@code BuildPlugin}, the entry point a
     * third-party plugin author writes. It also encodes a value with the codec — the pairing
     * {@code ProtocolWriter}'s javadoc tells plugin authors to use — because that is the part the
     * broken POM made unreachable: the SPI is in {@code jk-plugin-sdk}, the codec is in
     * {@code jk-host}, and only the dependency closure connects them.
     */
    private static final String CONSUMER_SOURCE = """
            package consumer;

            import cc.jumpkick.jsonl.Jsonl;
            import cc.jumpkick.plugin.build.BuildPlugin;
            import cc.jumpkick.plugin.build.BuildPluginContext;
            import cc.jumpkick.plugin.protocol.ProtocolWriter;

            public final class ConsumerPlugin implements BuildPlugin {
                @Override
                public void register(BuildPluginContext ctx) {
                    ProtocolWriter out = new ProtocolWriter(System.out, "<<consumer>>");
                    out.emit("{\\"plugin\\":" + Jsonl.quote(ctx.project().name()) + "}");
                }
            }
            """;

    @Test
    void a_consumer_resolves_the_sdk_from_a_repository_and_compiles_against_it(@TempDir Path work) throws Exception {
        @Nullable String repoPath = System.getProperty(REPO_PROPERTY);
        // Gradle's :plugin-sdk:test always stages the repo and sets the property, so under the
        // gate this assumption cannot skip. The self-host tier (`jk test`) has no publish task to
        // stage it — there the honest report is SKIPPED, not a failure about a fixture the
        // harness cannot provide.
        Assumptions.assumeTrue(
                repoPath != null,
                "-D" + REPO_PROPERTY + " not set: no staged Maven repository in this harness"
                        + " (Gradle's :plugin-sdk:test stages it and always sets the property)");
        Path repo = Path.of(Objects.requireNonNull(repoPath, REPO_PROPERTY));

        @Nullable String configuredVersion = System.getProperty(VERSION_PROPERTY);
        String version = Objects.requireNonNull(configuredVersion, VERSION_PROPERTY);
        assertThat(version)
                .as("-D%s must carry the version :plugin-sdk publishes under", VERSION_PROPERTY)
                .isNotBlank();

        Map<String, Path> jars = new LinkedHashMap<>();
        List<String> unresolvable = new ArrayList<>();
        collect(repo, GROUP, SDK, version, jars, unresolvable);

        assertThat(unresolvable)
                .as(
                        "every coordinate the published POMs name must exist in %s — a consumer"
                                + " fetching %s:%s from a repository gets exactly this closure",
                        repo, GROUP, SDK)
                .isEmpty();
        assertThat(jars.keySet())
                .as("the SDK's compile closure, from its POM alone")
                .containsExactlyInAnyOrder(GROUP + ":" + SDK, GROUP + ":" + HOST);

        Path source = work.resolve("consumer/ConsumerPlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, CONSUMER_SOURCE);
        Path classes = Files.createDirectories(work.resolve("classes"));

        String classpath = jars.values().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        StringWriter diagnostics = new StringWriter();
        JavaCompiler javac = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "system Java compiler");
        boolean compiled;
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(source);
            List<String> options =
                    List.of("-classpath", classpath, "-d", classes.toString(), "-proc:none", "--release", "17");
            compiled = javac.getTask(diagnostics, files, null, options, null, units)
                    .call();
        }

        assertThat(compiled)
                .as(
                        "a class implementing BuildPlugin compiles against the resolved closure"
                                + " alone (classpath %s):%n%s",
                        classpath, diagnostics)
                .isTrue();
        assertThat(classes.resolve("consumer/ConsumerPlugin.class")).exists();
    }

    /**
     * Walk the compile-scope closure of {@code group:artifact:version} out of a Maven-layout
     * directory, exactly as a POM consumer would: read the POM, take its jar, recurse. Coordinates
     * with no POM or no jar in the repository land in {@code unresolvable} rather than throwing, so
     * the failure names every broken link at once.
     */
    private static void collect(
            Path repo, String group, String artifact, String version, Map<String, Path> jars, List<String> unresolvable)
            throws IOException {
        String coordinate = group + ":" + artifact + ":" + version;
        if (jars.containsKey(group + ":" + artifact)) return;

        Path dir = repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Path pom = dir.resolve(artifact + "-" + version + ".pom");
        Path jar = dir.resolve(artifact + "-" + version + ".jar");
        if (!Files.isRegularFile(pom)) {
            unresolvable.add(coordinate + " — no " + repo.relativize(pom));
            return;
        }
        if (!Files.isRegularFile(jar)) {
            unresolvable.add(coordinate + " — no " + repo.relativize(jar));
            return;
        }
        jars.put(group + ":" + artifact, jar);

        Element dependencies = DomXml.childElement(DomXml.parse(pom).getDocumentElement(), "dependencies");
        for (Element dependency : DomXml.childElements(dependencies, "dependency")) {
            @Nullable String scope = DomXml.childText(dependency, "scope");
            if (scope != null && !scope.isBlank() && !"compile".equals(scope)) continue;
            collect(
                    repo,
                    Objects.requireNonNull(DomXml.childText(dependency, "groupId"), "dependency.groupId"),
                    Objects.requireNonNull(DomXml.childText(dependency, "artifactId"), "dependency.artifactId"),
                    Objects.requireNonNull(DomXml.childText(dependency, "version"), "dependency.version"),
                    jars,
                    unresolvable);
        }
    }
}
