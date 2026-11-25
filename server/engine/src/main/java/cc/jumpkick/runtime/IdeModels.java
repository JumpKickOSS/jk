// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.runtime.base.IdeOps;
import cc.jumpkick.runtime.base.ProvidedJars;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The engine's IDE model with the plugins' provided jars as libraries: the one operation the
 * {@code ide-model} socket verb ({@code jk ide}, the IntelliJ plugin, {@code jk bsp}), the MCP
 * {@code ide} tool and {@code GET /api/project/model} read.
 */
public final class IdeModels {

    private IdeModels() {}

    /** {@link IdeOps#ideModel} for {@code dir}; {@code fetchMissing} fetches jars the store lacks. */
    public static IdeWireModel hosted(Path dir, Path cache, @Nullable Path jdksDir, boolean fetchMissing) {
        return IdeOps.ideModel(dir, cache, jdksDir, fetchMissing ? IdeModels::dependencySync : null, providedJars());
    }

    private static CacheSync dependencySync(Path root, JkBuild project, Cas cas) throws IOException {
        return new CacheSync(cas, new Http(), project.project().m2integration())
                .withRepositories(LockPipeline.repositoriesForLock(project, root, null, cas));
    }

    /** Each module's provided jars, as the model lists them. */
    private static ProvidedJars providedJars() {
        return ProvidedClasspath.providedJars(JkStores.storeCas());
    }
}
