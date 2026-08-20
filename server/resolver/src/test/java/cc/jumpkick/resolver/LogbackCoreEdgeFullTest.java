// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class LogbackCoreEdgeFullTest {
    @Test
    void micronaut_hello_shape_keeps_logback_core_edge(@TempDir Path tmp) throws Exception {
        String toml = """
                group = "com.example"
                name = "hello-http"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.hello.Application"
                assembly = true

                [micronaut]
                version = "5"

                [dependencies]
                micronaut-http-server-netty = { group = "io.micronaut", name = "micronaut-http-server-netty" }
                micronaut-jackson-databind  = { group = "io.micronaut", name = "micronaut-jackson-databind" }
                logback                     = { group = "ch.qos.logback", name = "logback-classic" }

                [processor-dependencies]
                micronaut-inject-java = { group = "io.micronaut", name = "micronaut-inject-java" }

                [test-dependencies]
                micronaut-test-junit5 = { group = "io.micronaut.test", name = "micronaut-test-junit5" }
                micronaut-http-client = { group = "io.micronaut", name = "micronaut-http-client" }
                junit-jupiter         = { group = "org.junit.jupiter", name = "junit-jupiter" }
                """;
        var project = JkBuildParser.parse(toml);
        Cas cas = new Cas(tmp.resolve("c"));
        MavenRepo central =
                new MavenRepo("central", URI.create("https://repo.maven.apache.org/maven2/"), new Http(), cas);
        Lockfile lock = new LockOrchestrator(RepoGroup.of(central)).lock(project, "test");
        var classic = lock.artifacts().stream()
                .filter(a -> a.name().contains("logback-classic"))
                .findFirst()
                .orElseThrow();
        System.err.println("CLASSIC DEPS=" + classic.deps());
        assertThat(classic.deps().stream().anyMatch(d -> d.contains("logback-core")))
                .as("deps=%s", classic.deps())
                .isTrue();
    }
}
