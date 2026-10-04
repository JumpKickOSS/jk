// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jenkinstest;

import cc.jumpkick.http.Http;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.RepositoryRoute;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/**
 * Writes {@code test-dependencies/<artifactId>.jpi} for every Jenkins plugin in the test runtime
 * closure, and {@code test-dependencies/index} listing their artifactIds, which is the layout
 * JenkinsRule's {@code TestPluginManager} installs plugins from. A plugin is a jar whose manifest
 * names a {@code Short-Name} and a {@code Plugin-Version}; its {@code .hpi} is fetched beside the
 * jar from the module's routed repositories, because the archive bundles libraries the jar does not.
 */
final class TestPluginsStep {

    static final String DIR = "test-dependencies";

    private TestPluginsStep() {}

    static void run(TaskExec exec) throws Exception {
        Path dir = exec.outputDir(JenkinsTestPlugin.OUT).resolve(DIR);
        Files.createDirectories(dir);
        Set<String> excluded = Set.copyOf(exec.config().stringList("exclude"));
        Map<String, PackageIo.RuntimeEntry> plugins = plugins(exec.runtimeEntries(), excluded);
        exec.label("install " + plugins.size() + " Jenkins test plugin" + (plugins.size() == 1 ? "" : "s"));
        Http http = new Http();
        List<String> index = new ArrayList<>();
        for (Map.Entry<String, PackageIo.RuntimeEntry> e : plugins.entrySet()) {
            PackageIo.RuntimeEntry entry = e.getValue();
            fetch(http, exec.repositories(), entry, dir.resolve(e.getKey() + ".jpi"), exec.offline());
            index.add(e.getKey());
        }
        Files.writeString(dir.resolve("index"), String.join("\n", index) + (index.isEmpty() ? "" : "\n"));
    }

    /** The Jenkins plugins of {@code entries} by artifactId, in closure order, without {@code excluded}. */
    static Map<String, PackageIo.RuntimeEntry> plugins(List<PackageIo.RuntimeEntry> entries, Set<String> excluded)
            throws IOException {
        Map<String, PackageIo.RuntimeEntry> out = new LinkedHashMap<>();
        for (PackageIo.RuntimeEntry entry : entries) {
            Path jar = entry.jar();
            if (jar == null || entry.gav().isEmpty() || !Files.isRegularFile(jar)) continue;
            if (excluded.contains(entry.artifact()) || out.containsKey(entry.artifact())) continue;
            if (isPlugin(jar)) out.put(entry.artifact(), entry);
        }
        return out;
    }

    /** A Jenkins plugin's jar names its plugin in the manifest. */
    static boolean isPlugin(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) return false;
            var attrs = manifest.getMainAttributes();
            return attrs.getValue("Short-Name") != null && attrs.getValue("Plugin-Version") != null;
        }
    }

    /** {@code <group path>/<artifact>/<version>/<artifact>-<version>.hpi}. */
    static String hpiPath(PackageIo.RuntimeEntry entry) {
        return entry.group().replace('.', '/') + "/" + entry.artifact() + "/" + entry.version() + "/" + entry.artifact()
                + "-" + entry.version() + ".hpi";
    }

    private static void fetch(
            Http http, List<RepositoryRoute> routes, PackageIo.RuntimeEntry entry, Path to, boolean offline)
            throws IOException, InterruptedException {
        if (offline) {
            throw new IOException("the Jenkins plugin " + entry.gav() + " needs its .hpi for the tests, and the"
                    + " build is offline — run once online");
        }
        String path = hpiPath(entry);
        List<String> tried = new ArrayList<>();
        for (RepositoryRoute route : routes) {
            String base = route.url().toString();
            URI uri = URI.create(base.endsWith("/") ? base + path : base + "/" + path);
            String auth = authorization(route);
            HttpResponse<InputStream> response =
                    http.getStream(uri, auth == null ? Map.of() : Map.of("Authorization", auth));
            try (InputStream body = response.body()) {
                if (response.statusCode() == 200) {
                    Path part = to.resolveSibling(to.getFileName() + ".part");
                    Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(part, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return;
                }
            }
            tried.add(route.id() + " (" + response.statusCode() + ")");
        }
        throw new IOException("no repository serves the Jenkins plugin archive " + path + " — tried "
                + (tried.isEmpty() ? "no repositories" : String.join(", ", tried)));
    }

    private static @Nullable String authorization(RepositoryRoute route) {
        if (route.anonymous()) return null;
        if (route.bearer()) return "Bearer " + route.secret();
        String pair = route.username() + ":" + route.secret();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
