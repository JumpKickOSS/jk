// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jenkinstest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.RepositoryRoute;
import cc.jumpkick.plugin.testing.FakeBuildIo;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The test closure's Jenkins plugins land as {@code test-dependencies/<artifactId>.jpi} plus an index. */
class TestPluginsStepTest {

    @TempDir
    Path tmp;

    @Test
    void the_plugins_of_the_closure_are_fetched_and_indexed_and_the_rest_left_out() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("ci:s3cret".getBytes(StandardCharsets.UTF_8));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repo/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = ("hpi of " + path).getBytes(StandardCharsets.UTF_8);
            int status = !auth.equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    ? 401
                    : path.endsWith(".hpi") ? 200 : 404;
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        try {
            FakeBuildIo io = new FakeBuildIo(tmp.resolve("io"), "jenkins-test").offline(false);
            io.entry(entry("org.jenkins-ci.plugins", "bouncycastle-api", "2.30", true));
            io.entry(entry("org.example", "plain-lib", "1.0", false));
            io.entry(entry("org.jenkins-ci.plugins", "credentials", "1511", true));
            io.entry(entry("org.jenkins-ci.plugins", "structs", "362", true));
            io.config(Map.of("exclude", List.of("structs")));
            io.repository(new RepositoryRoute(
                    "jenkins",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repo"),
                    "ci",
                    "s3cret"));

            TestPluginsStep.run(io);

            Path dir = io.scratch().resolve(JenkinsTestPlugin.OUT).resolve(TestPluginsStep.DIR);
            assertThat(dir.resolve("index")).hasContent("bouncycastle-api\ncredentials\n");
            assertThat(dir.resolve("bouncycastle-api.jpi"))
                    .hasContent("hpi of /repo/org/jenkins-ci/plugins/bouncycastle-api/2.30/bouncycastle-api-2.30.hpi");
            assertThat(dir.resolve("credentials.jpi")).exists();
            assertThat(dir.resolve("structs.jpi")).as("excluded").doesNotExist();
            assertThat(dir.resolve("plain-lib.jpi")).as("not a plugin").doesNotExist();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_plugin_no_repository_serves_names_the_archive_and_each_answer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            FakeBuildIo io = new FakeBuildIo(tmp.resolve("io"), "jenkins-test").offline(false);
            io.entry(entry("org.jenkins-ci.plugins", "mailer", "534", true));
            io.repository(RepositoryRoute.anonymous(
                    "jenkins",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort())));

            assertThatThrownBy(() -> TestPluginsStep.run(io))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("org/jenkins-ci/plugins/mailer/534/mailer-534.hpi")
                    .hasMessageContaining("jenkins (404)");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void offline_refuses_with_the_plugin_named() throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp.resolve("io"), "jenkins-test");
        io.entry(entry("org.jenkins-ci.plugins", "mailer", "534", true));
        io.offline(true);

        assertThatThrownBy(() -> TestPluginsStep.run(io))
                .hasMessageContaining("org.jenkins-ci.plugins:mailer:534")
                .hasMessageContaining("offline");
    }

    private PackageIo.RuntimeEntry entry(String group, String artifact, String version, boolean plugin)
            throws IOException {
        Path jar = tmp.resolve("jars").resolve(artifact + "-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (plugin) {
            attrs.putValue("Short-Name", artifact);
            attrs.putValue("Plugin-Version", version);
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // a manifest is all the step reads
        }
        return new PackageIo.RuntimeEntry(jar.getFileName().toString(), jar, false, null, group, artifact, version);
    }
}
