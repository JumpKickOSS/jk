// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.repo.PomRuntimeClasspath;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The classpath a forked image worker launches on, walked from the POM this build wrote for it as
 * an install stages it: Jib's registry transport, Apache HttpClient 4, finds the commons-logging it
 * loads, which the google-http-client 2.x that sigstore-java brings excludes from its own edge.
 */
@Tag("integration")
class ImageWorkerClasspathE2eTest {

    @Test
    void the_image_worker_launches_with_the_logging_jibs_http_client_loads() throws Exception {
        String jar = System.getProperty("jk.image-builder.plugin.jar");
        assertThat(jar).as("the image builder this build produced").isNotBlank();

        List<URL> urls = new ArrayList<>();
        for (Path p : PomRuntimeClasspath.stage(Path.of(jar)))
            urls.add(p.toUri().toURL());
        try (URLClassLoader worker = new URLClassLoader(urls.toArray(URL[]::new), null)) {
            assertThat(loads(worker, "org.apache.http.impl.client.HttpClientBuilder"))
                    .as("Jib's HTTP client on %s", urls)
                    .isTrue();
            assertThat(loads(worker, "org.apache.commons.logging.LogFactory"))
                    .as("the logging it loads, on %s", urls)
                    .isTrue();
        }
    }

    private static boolean loads(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
