// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.ToolDistribution;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.jsonl.MiniJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link PackageManagerSpec} into the registry tarball that installs it, pinned to the
 * registry's {@code dist.integrity} (sha512): a tarball that does not hash to it is refused before
 * it is unpacked.
 */
public final class PackageManagerResolver {

    private static final String ABBREVIATED = "application/vnd.npm.install-v1+json";

    private final Http http;
    private final URI registry;

    public PackageManagerResolver() {
        this(new Http(), NodeSources.registry());
    }

    public PackageManagerResolver(Http http, URI registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = NodeSources.directory(registry.toString());
    }

    /** The distribution of {@code spec} for {@code platform}; npm has none (it comes with Node). */
    public ToolDistribution resolve(PackageManagerSpec spec, NodePlatform platform)
            throws IOException, InterruptedException {
        BuildTool tool = spec.manager()
                .tool()
                .orElseThrow(() -> new IllegalArgumentException("npm comes with Node; it is not provisioned"));
        String version = spec.version();
        if (version.equals(PackageManagerSpec.LATEST)) {
            String versions = spec.manager().versionsPackage(platform);
            String latest = MiniJson.str(MiniJson.get(metadata(versions), "dist-tags"), "latest");
            if (latest == null) throw new IOException(versions + " has no latest version at " + registry);
            version = new PackageManagerSpec(spec.manager(), latest).version();
        }
        String pkg = spec.manager().registryPackage(platform, version);
        Object meta = metadata(pkg);
        Object dist = MiniJson.get(MiniJson.get(MiniJson.get(meta, "versions"), version), "dist");
        String tarball = MiniJson.str(dist, "tarball");
        if (tarball == null) throw new IOException(pkg + " publishes no version " + version + " at " + registry);
        return new ToolDistribution(tool, version, URI.create(tarball), "tar.gz", null, sha512Hex(pkg, version, dist));
    }

    /** The {@code latest} dist-tag of {@code pkg} on the registry. */
    public String latest(String pkg) throws IOException, InterruptedException {
        String latest = MiniJson.str(MiniJson.get(metadata(pkg), "dist-tags"), "latest");
        if (latest == null) throw new IOException(pkg + " has no latest version at " + registry);
        return latest;
    }

    private @Nullable Object metadata(String pkg) throws IOException, InterruptedException {
        URI uri =
                registry.resolve(URLEncoder.encode(pkg, StandardCharsets.UTF_8).replace("%40", "@"));
        HttpResponse<byte[]> response = http.get(uri, Map.of("Accept", ABBREVIATED));
        if (response.statusCode() != 200) {
            throw new IOException(pkg + " metadata " + uri + " returned HTTP " + response.statusCode());
        }
        return MiniJson.parse(new String(response.body(), StandardCharsets.UTF_8));
    }

    /** The hex sha512 an {@code integrity} of {@code sha512-<base64>} carries; refused without one. */
    static String sha512Hex(String pkg, String version, @Nullable Object dist) throws IOException {
        String integrity = MiniJson.str(dist, "integrity");
        if (integrity != null) {
            for (String part : integrity.trim().split("\\s+")) {
                if (part.startsWith("sha512-")) {
                    return Hashing.hex(Base64.getDecoder().decode(part.substring("sha512-".length())));
                }
            }
        }
        throw new IOException(
                pkg + " " + version + " publishes no sha512 integrity; refusing a tarball nothing vouches for");
    }
}
