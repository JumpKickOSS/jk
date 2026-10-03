// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.http.Http;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class PackageManagerTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private static final byte[] TARBALL = "a pnpm tarball".getBytes(StandardCharsets.UTF_8);

    private static String integrity(byte[] bytes) {
        return "sha512-"
                + Base64.getEncoder()
                        .encodeToString(Hashing.newDigest("SHA-512").digest(bytes));
    }

    private void serveMetadata(String path, String version, String tarball) {
        http.serve(path, """
                {"name":"x","dist-tags":{"latest":"%s"},"versions":{"%s":{"version":"%s",
                 "dist":{"tarball":"%s","integrity":"%s","shasum":"abc"}}}}
                """.formatted(version, version, version, tarball, integrity(TARBALL)));
    }

    @Test
    void a_package_manager_field_names_the_manager_and_its_version() {
        assertThat(PackageManagerSpec.parse("pnpm@10.18.1+sha512.abcdef"))
                .isEqualTo(new PackageManagerSpec(PackageManager.PNPM, "10.18.1"));
        assertThat(PackageManagerSpec.parse("yarn@4.18.0").toString()).isEqualTo("yarn@4.18.0");
        assertThatThrownBy(() -> PackageManagerSpec.parse("cnpm@9.0.0")).hasMessageContaining("does not run");
        assertThatThrownBy(() -> PackageManagerSpec.parse("pnpm")).hasMessageContaining("<name>@<version>");
    }

    @Test
    void yarn_1_is_refused_with_the_migration() {
        assertThatThrownBy(() -> PackageManagerSpec.parse("yarn@1.22.22"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Yarn 1")
                .hasMessageContaining("yarn set version stable");
    }

    @Test
    void a_manager_resolves_to_its_registry_tarball_pinned_to_its_integrity() throws Exception {
        serveMetadata("/pnpm", "10.18.1", http.base() + "/pnpm/-/pnpm-10.18.1.tgz");
        ToolDistribution dist = new PackageManagerResolver(new Http(), http.base())
                .resolve(PackageManagerSpec.parse("pnpm@10.18.1"), NodePlatform.of("Linux", "amd64", "glibc"));

        assertThat(dist.tool()).isEqualTo(BuildTool.PNPM);
        assertThat(dist.version()).isEqualTo("10.18.1");
        assertThat(dist.archiveType()).isEqualTo("tar.gz");
        assertThat(dist.sha512()).isEqualTo(Hashing.hashHex("SHA-512", TARBALL));
        assertThat(http.headersFor("/pnpm")).get().satisfies(h -> assertThat(h.toString())
                .contains("vnd.npm.install-v1+json"));
    }

    @Test
    void latest_is_the_registry_s_newest_and_scoped_and_platform_packages_are_found() throws Exception {
        serveMetadata("/@yarnpkg/cli-dist", "4.18.0", http.base() + "/yarn.tgz");
        serveMetadata("/@oven/bun-darwin-aarch64", "1.3.2", http.base() + "/bun.tgz");
        PackageManagerResolver resolver = new PackageManagerResolver(new Http(), http.base());

        ToolDistribution yarn = resolver.resolve(
                new PackageManagerSpec(PackageManager.YARN, PackageManagerSpec.LATEST),
                NodePlatform.of("Linux", "amd64", "glibc"));
        assertThat(yarn.version()).isEqualTo("4.18.0");
        ToolDistribution bun = resolver.resolve(
                new PackageManagerSpec(PackageManager.BUN, "1.3.2"), NodePlatform.of("Mac OS X", "aarch64", "libc"));
        assertThat(bun.tool()).isEqualTo(BuildTool.BUN);
    }

    @Test
    void a_version_with_no_sha512_integrity_is_refused() {
        http.serve("/pnpm", """
                {"dist-tags":{"latest":"9.0.0"},"versions":{"9.0.0":{"dist":{"tarball":"http://x/p.tgz","shasum":"abc"}}}}
                """);
        assertThatThrownBy(() -> new PackageManagerResolver(new Http(), http.base())
                        .resolve(PackageManagerSpec.parse("pnpm@9.0.0"), NodePlatform.of("Linux", "amd64", "glibc")))
                .hasMessageContaining("publishes no sha512 integrity");
    }

    @Test
    void shims_run_a_js_manager_s_bins_under_the_node_on_path(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("package.json"), """
                {"name":"@yarnpkg/cli-dist","bin":{"yarn":"./bin/yarn.js","yarnpkg":"./bin/yarn.js"}}
                """);
        PackageManagerShims.write(BuildTool.YARN, home);

        Path shim = home.resolve("jk-bin/yarn");
        assertThat(Files.readString(shim))
                .isEqualTo("#!/bin/sh\nexec node \"$(dirname \"$0\")/..\"/bin/yarn.js \"$@\"\n");
        assertThat(Files.readString(home.resolve("jk-bin/yarnpkg.cmd")))
                .isEqualTo("@node \"%~dp0..\"\\bin\\yarn.js %*\r\n");

        PackageManagerShims.write(BuildTool.BUN, home);
        assertThat(Files.readString(home.resolve("jk-bin/bunx")))
                .contains("/bin/" + BuildTool.BUN.binaryName() + " x \"$@\"");
    }

    @Test
    void pnpm_from_12_comes_from_its_platform_package(@TempDir Path home) throws Exception {
        serveMetadata("/pnpm", "12.8.1", http.base() + "/unused.tgz");
        serveMetadata("/@pnpm/exe.linux-arm64-musl", "12.8.1", http.base() + "/pnpm-exe.tgz");
        serveMetadata("/@pnpm/exe.darwin-x64", "11.28.3", http.base() + "/unused.tgz");
        PackageManagerResolver resolver = new PackageManagerResolver(new Http(), http.base());

        ToolDistribution latest = resolver.resolve(
                new PackageManagerSpec(PackageManager.PNPM, PackageManagerSpec.LATEST),
                NodePlatform.of("Linux", "aarch64", "musl"));
        assertThat(latest.version()).isEqualTo("12.8.1");
        assertThat(latest.downloadUri().toString()).endsWith("/pnpm-exe.tgz");

        Files.writeString(home.resolve("package.json"), "{\"name\":\"@pnpm/exe.linux-x64\"}");
        Path exe = home.resolve(Os.isWindows() ? "pnpm.exe" : "pnpm");
        Files.writeString(exe, "");
        PackageManagerShims.write(BuildTool.PNPM, home);
        assertThat(Files.readString(home.resolve("jk-bin/pnpx"))).contains("/pnpm dlx \"$@\"");
        assertThat(PackageManagerShims.entry(BuildTool.PNPM, home)).isEqualTo(exe);
        assertThat(PackageManagerShims.isScript(exe)).isFalse();
    }
}
