// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A node module whose test script is jest, with no reporter package of its own: {@code jk test}
 * counts each test and names the one that failed. jest comes from the npm registry, hence the tag.
 */
@Tag("network")
@DisabledOnOs(OS.WINDOWS)
class JestCountsNetworkTest {

    @Test
    void jest_tests_are_counted_without_a_reporter_package(@TempDir Path dir) throws Exception {
        Path web = Files.createDirectories(dir.resolve("web"));
        Files.writeString(web.resolve("jk.toml"), """
                name = "web"
                group = "com.acme"
                version = "1.0.0"
                node = 24

                [test]
                failures = "report"
                """);
        Files.writeString(web.resolve("package.json"), """
                {"name":"web","version":"1.0.0","private":true,
                 "scripts":{"build":"node -e \\"require('fs').mkdirSync('dist',{recursive:true})\\"","test":"jest"},
                 "devDependencies":{"jest":"^30"}}
                """);
        Files.writeString(web.resolve("sum.test.js"), """
                test('sum adds', () => expect(1 + 1).toBe(2));
                test('sum carries', () => expect(1 + 2).toBe(4));
                test.skip('sum later', () => {});
                """);
        assertThat(run("node", "exec", "-C", web.toString(), "--", "npm", "install", "--no-audit", "--no-fund"))
                .as("npm install under the pinned Node.js")
                .isZero();

        assertThat(run("test", "-C", web.toString()))
                .as("failures are reported, not failing")
                .isZero();

        String results = Files.readString(web.resolve("target/jk-results.md"));
        assertThat(results)
                .as(results)
                .contains("**1 failed**, 1 passed, 1 skipped (3 total)")
                .contains("sum carries");
    }
}
