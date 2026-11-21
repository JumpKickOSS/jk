// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The {@code dep:} anchor of {@code usages}: {@code dep:<module>@<version>} lists the public API
 * of a locked dependency that would be gone at {@code version}, up or down from the locked one, and
 * every place in this project that uses it. {@code <module>} is {@code group:artifact}, or the
 * artifact alone when one locked module has that name. Answered in the engine, which holds the
 * lock, the store and the repositories, not by the graph worker.
 */
public final class DependencyUsages {

    private DependencyUsages() {}

    /** The address prefix that asks about a dependency's version. */
    public static final String PREFIX = "dep:";

    /** Characters per row of an answer, to fit its rows to a token budget. */
    private static final int CHARS_PER_ROW = 60;

    /** The address that lists what moving {@code module} ({@code group:artifact}) to {@code version} breaks. */
    public static String address(String module, String version) {
        return PREFIX + module + "@" + version;
    }

    /** Whether {@code r} asks {@code usages} of a {@code dep:} address. */
    static boolean asks(GraphRequest r) {
        return "usages".equals(r.op())
                && !r.args().isEmpty()
                && r.args().getFirst().strip().startsWith(PREFIX);
    }

    /** The answer to {@code r} for the workspace at {@code root}, an error naming what is wrong when it has none. */
    static GraphAck answer(Path root, GraphRequest r) throws IOException, InterruptedException {
        String address = r.args().getFirst().strip();
        String rest = address.substring(PREFIX.length());
        int at = rest.lastIndexOf('@');
        if (at <= 0 || at == rest.length() - 1)
            return GraphAck.error(address + ": write dep:<group:artifact>@<version>");
        String version = rest.substring(at + 1).strip();
        Path lockDir = LockPlans.lockScope(root).lockDir();
        String module;
        try {
            module = module(lockDir, rest.substring(0, at).strip());
        } catch (IllegalArgumentException refused) {
            return GraphAck.error(String.valueOf(refused.getMessage()));
        }
        Cas cas = JkStores.storeCas();
        ApiBreaks.Report report = ApiBreaks.of(
                lockDir, module, version, repositories(lockDir, null, cas), cas, ApiBreaks.workspace(lockDir));
        if (report == null)
            return GraphAck.error(module + "@" + version + ": no jar to compare; `jk build` fetches the locked one");
        String text = report.text(Math.max(10, r.budget() * 3 / CHARS_PER_ROW));
        return new GraphAck(null, text, 0, 0, 0, 0, 0, 0, "", null, List.of());
    }

    /**
     * The locked module {@code name} names: itself when it is {@code group:artifact} and locked,
     * else the one locked module of that artifact name.
     *
     * @throws IllegalArgumentException naming why none, when none
     */
    private static String module(Path lockDir, String name) throws IOException {
        Path lock = lockDir.resolve(ManifestNames.LOCK);
        if (!Files.isRegularFile(lock))
            throw new IllegalArgumentException(lockDir + ": no " + ManifestNames.LOCK + "; run `jk lock`");
        TreeSet<String> locked = new TreeSet<>();
        for (Lockfile.Artifact a : LockfileReader.read(lock).artifacts()) {
            String[] parts = a.name().split(":", -1);
            if (parts.length >= 2) locked.add(parts[0] + ":" + parts[1]);
        }
        if (locked.contains(name)) return name;
        List<String> named = name.contains(":")
                ? List.of()
                : locked.stream().filter(m -> m.endsWith(":" + name)).toList();
        if (named.size() == 1) return named.getFirst();
        if (named.isEmpty()) throw new IllegalArgumentException(name + ": not a locked dependency");
        throw new IllegalArgumentException(
                name + ": several locked modules have that name: " + String.join(", ", named));
    }

    /** The repositories the manifest at {@code lockDir} declares, {@code repoUrl} first when set. */
    public static RepoGroup repositories(Path lockDir, @Nullable URI repoUrl, Cas cas) throws IOException {
        return RepoGroupBuilder.buildFor(JkBuildParser.parse(ManifestPaths.manifestIn(lockDir)), repoUrl, cas);
    }
}
