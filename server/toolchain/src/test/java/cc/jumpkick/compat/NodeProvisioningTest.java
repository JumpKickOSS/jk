// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeResolution;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.node.PackageManagerSpec;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** Node and its package managers installed into the tools store, verified before they unpack. */
@DisabledOnOs(OS.WINDOWS)
class NodeProvisioningTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private static final NodePlatform LINUX = new NodePlatform("linux", "x64", false);

    private static final byte[] NODE = tarGz(new String[][] {
        {"node-v24.21.0-linux-x64/", null},
        {"node-v24.21.0-linux-x64/bin/node", "#!/bin/sh\necho v24.21.0\n"},
        {"node-v24.21.0-linux-x64/lib/node_modules/npm/bin/npm-cli.js", "// npm\n"},
        {"node-v24.21.0-linux-x64/bin/npm", null, "../lib/node_modules/npm/bin/npm-cli.js"},
    });

    private static final byte[] PNPM = tarGz(new String[][] {
        {"package/", null},
        {"package/package.json", "{\"name\":\"pnpm\",\"bin\":{\"pnpm\":\"bin/pnpm.cjs\",\"pnpx\":\"bin/pnpx.cjs\"}}"},
        {"package/bin/pnpm.cjs", "// pnpm\n"},
        {"package/bin/pnpx.cjs", "// pnpx\n"},
    });

    private NodeProvisioning provisioning(Path tools, Path userHome) {
        return new NodeProvisioning(
                new ToolRegistry(tools),
                new Http(),
                new NodeDiscovery(name -> null, userHome, List.of()),
                http.base(),
                new PackageManagerResolver(new Http(), http.base()),
                LINUX);
    }

    private static NodeResolution resolution(String sha) {
        return new NodeResolution("24.21.0", "11.6.0", "Krypton", Map.of("linux-x64", sha));
    }

    @Test
    void a_locked_release_downloads_once_verified_against_its_sha(@TempDir Path tmp) throws Exception {
        http.served().put("/v24.21.0/node-v24.21.0-linux-x64.tar.gz", NODE);
        NodeProvisioning p = provisioning(tmp.resolve("tools"), tmp.resolve("home"));

        NodeHome home =
                p.ensure(resolution(Hashing.sha256Hex(NODE)), NodeProvisioning.Policy.DEFAULT, ToolProgress.NONE);

        assertThat(home.home()).isEqualTo(tmp.resolve("tools/node/24.21.0"));
        assertThat(home.source()).isEqualTo("jk");
        assertThat(home.node()).exists().isExecutable();
        assertThat(home.npm()).exists();
        assertThat(home.managerCommand())
                .containsExactly(
                        home.node().toString(),
                        home.home()
                                .resolve("lib/node_modules/npm/bin/npm-cli.js")
                                .toString());

        p.ensure(resolution(Hashing.sha256Hex(NODE)), NodeProvisioning.Policy.DEFAULT, ToolProgress.NONE);
        assertThat(http.requestsFor("/v24.21.0/node-v24.21.0-linux-x64.tar.gz")).isEqualTo(1);
    }

    @Test
    void an_archive_that_does_not_match_the_lock_is_refused_before_it_unpacks(@TempDir Path tmp) {
        http.served().put("/v24.21.0/node-v24.21.0-linux-x64.tar.gz", NODE);
        NodeProvisioning p = provisioning(tmp.resolve("tools"), tmp.resolve("home"));

        assertThatThrownBy(
                        () -> p.ensure(resolution("0".repeat(64)), NodeProvisioning.Policy.DEFAULT, ToolProgress.NONE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
        assertThat(tmp.resolve("tools/node/24.21.0")).doesNotExist();
    }

    @Test
    void an_install_of_the_exact_version_another_manager_made_is_used_unless_discovery_is_off(@TempDir Path tmp)
            throws Exception {
        Path nvm = tmp.resolve("home/.nvm/versions/node/v24.21.0");
        Files.createDirectories(nvm.resolve("bin"));
        Files.writeString(nvm.resolve("bin/node"), "");
        http.served().put("/v24.21.0/node-v24.21.0-linux-x64.tar.gz", NODE);
        NodeProvisioning p = provisioning(tmp.resolve("tools"), tmp.resolve("home"));

        NodeHome found =
                p.ensure(resolution(Hashing.sha256Hex(NODE)), NodeProvisioning.Policy.DEFAULT, ToolProgress.NONE);
        assertThat(found.home()).isEqualTo(nvm);
        assertThat(found.source()).isEqualTo("nvm");
        assertThat(http.requested()).isEmpty();

        NodeHome managed =
                p.ensure(resolution(Hashing.sha256Hex(NODE)), new NodeProvisioning.Policy(true), ToolProgress.NONE);
        assertThat(managed.source()).isEqualTo("jk");
    }

    @Test
    void a_package_manager_installs_from_the_registry_with_its_shims(@TempDir Path tmp) throws Exception {
        servePnpm(PNPM);
        http.served().put("/v24.21.0/node-v24.21.0-linux-x64.tar.gz", NODE);
        NodeProvisioning p = provisioning(tmp.resolve("tools"), tmp.resolve("home"));
        NodeHome node =
                p.ensure(resolution(Hashing.sha256Hex(NODE)), NodeProvisioning.Policy.DEFAULT, ToolProgress.NONE);

        NodeHome withPnpm = p.withManager(node, PackageManagerSpec.parse("pnpm@10.18.1"), ToolProgress.NONE);

        Path pnpmHome = tmp.resolve("tools/pnpm/10.18.1");
        assertThat(withPnpm.packageManager()).isEqualTo(PackageManager.PNPM);
        assertThat(withPnpm.managerCommand())
                .containsExactly(
                        node.node().toString(), pnpmHome.resolve("bin/pnpm.cjs").toString());
        assertThat(pnpmHome.resolve("jk-bin/pnpm")).isExecutable();
        assertThat(pnpmHome.resolve("jk-bin/pnpx.cmd")).exists();
        assertThat(withPnpm.pathPrefix()).containsExactly(pnpmHome.resolve("jk-bin"), node.binDir());
    }

    @Test
    void a_tarball_that_does_not_match_the_registry_s_integrity_is_refused(@TempDir Path tmp) {
        servePnpm(PNPM);
        http.served().put("/pnpm/-/pnpm-10.18.1.tgz", tarGz(new String[][] {{"package/bin/pnpm.cjs", "evil"}}));
        NodeProvisioning p = provisioning(tmp.resolve("tools"), tmp.resolve("home"));
        NodeHome node = new NodeHome(tmp.resolve("node"), "24.21.0", "jk", null);

        assertThatThrownBy(() -> p.withManager(node, PackageManagerSpec.parse("pnpm@10.18.1"), ToolProgress.NONE))
                .hasMessageContaining("sha512 mismatch");
        assertThat(tmp.resolve("tools/pnpm/10.18.1")).doesNotExist();
    }

    @Test
    void a_download_reports_its_total_and_progress_then_the_unpack(@TempDir Path tmp) throws Exception {
        http.served().put("/v24.21.0/node-v24.21.0-linux-x64.tar.gz", NODE);
        List<String> events = new ArrayList<>();
        ToolProgress recording = new ToolProgress() {
            @Override
            public void downloading(String name, long readBytes, long totalBytes) {
                events.add("download " + name + " " + readBytes + "/" + totalBytes);
            }

            @Override
            public void installing(String name) {
                events.add("install " + name);
            }
        };

        provisioning(tmp.resolve("tools"), tmp.resolve("home"))
                .ensure(resolution(Hashing.sha256Hex(NODE)), NodeProvisioning.Policy.DEFAULT, recording);

        assertThat(events)
                .as("the total is known from the first event, so a percentage can be drawn at once")
                .startsWith("download Node.js 24.21.0 0/" + NODE.length)
                .contains("download Node.js 24.21.0 " + NODE.length + "/" + NODE.length)
                .endsWith("install Node.js 24.21.0");
    }

    @Test
    void a_cancelled_download_leaves_no_tree_and_no_scratch_in_the_store(@TempDir Path tmp) {
        http.served().put("/v24.21.0/node-v24.21.0-linux-x64.tar.gz", NODE);
        Path tools = tmp.resolve("tools");
        ToolProgress cancelling = new ToolProgress() {
            @Override
            public void downloading(String name, long readBytes, long totalBytes) {
                if (readBytes > 0) throw new CancellationException(name + " download cancelled");
            }
        };

        assertThatThrownBy(() -> provisioning(tools, tmp.resolve("home"))
                        .ensure(resolution(Hashing.sha256Hex(NODE)), NodeProvisioning.Policy.DEFAULT, cancelling))
                .isInstanceOf(CancellationException.class);

        assertThat(tools.resolve("node/24.21.0")).doesNotExist();
        assertThat(ToolInstaller.reapInFlight()).as("nothing left in flight").isZero();
        try (var left = Files.walk(tools)) {
            assertThat(left.filter(p -> p.getFileName().toString().startsWith("jk-tool-"))
                            .toList())
                    .as("no staging tree")
                    .isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void servePnpm(byte[] tarball) {
        String integrity = "sha512-"
                + Base64.getEncoder()
                        .encodeToString(Hashing.newDigest("SHA-512").digest(tarball));
        http.serve("/pnpm", """
                {"dist-tags":{"latest":"10.18.1"},"versions":{"10.18.1":{"dist":{"tarball":"%s","integrity":"%s"}}}}
                """.formatted(http.base() + "/pnpm/-/pnpm-10.18.1.tgz", integrity));
        http.served().put("/pnpm/-/pnpm-10.18.1.tgz", tarball);
    }

    /** {@code {name, body}} files, {@code {name, null}} directories, {@code {name, null, target}} links. */
    private static byte[] tarGz(String[][] entries) {
        try {
            ByteArrayOutputStream tar = new ByteArrayOutputStream();
            for (String[] e : entries) {
                String link = e.length > 2 ? e[2] : null;
                byte[] data = e[1] == null ? new byte[0] : e[1].getBytes(StandardCharsets.UTF_8);
                boolean dir = e[1] == null && link == null;
                byte[] h = new byte[512];
                byte[] name = e[0].getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(name, 0, h, 0, Math.min(name.length, 99));
                octal(h, 100, 8, dir || e[0].contains("/bin/") ? 0755 : 0644);
                octal(h, 108, 8, 0);
                octal(h, 116, 8, 0);
                octal(h, 124, 12, data.length);
                octal(h, 136, 12, 0);
                h[156] = (byte) (link != null ? '2' : dir ? '5' : '0');
                if (link != null) {
                    byte[] l = link.getBytes(StandardCharsets.US_ASCII);
                    System.arraycopy(l, 0, h, 157, l.length);
                }
                System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
                h[263] = ' ';
                Arrays.fill(h, 148, 156, (byte) ' ');
                int sum = 0;
                for (byte b : h) sum += b & 0xFF;
                octal(h, 148, 8, sum);
                tar.write(h);
                tar.write(data);
                if (data.length % 512 != 0) tar.write(new byte[512 - data.length % 512]);
            }
            tar.write(new byte[1024]);
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
                out.write(tar.toByteArray());
            }
            return gz.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void octal(byte[] buf, int off, int len, long value) {
        String s = Long.toOctalString(value);
        byte[] b = ("0".repeat(Math.max(0, len - 1 - s.length())) + s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, Math.min(b.length, len - 1));
    }
}
