// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.host.Errors;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.repo.LibraryRegistryClient;
import cc.jumpkick.repo.LibraryRegistrySync;
import cc.jumpkick.templates.OfficialTemplatesFreshen;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.FreshenCatalogRequest;
import cc.jumpkick.wire.protocol.ProtoReads;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

public final class FreshenCatalogVerb implements HostedVerb {

    private final VerbHost host;

    public FreshenCatalogVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.FRESHEN_CATALOG_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("freshen-catalog");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-freshen-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            FreshenCatalogRequest req = FreshenCatalogRequest.decode(requestLine);
            String catalog = req.catalog();
            boolean offline = req.offline();
            String url = req.url();
            String cacheFile = req.cacheFile();
            String error = null;
            try {
                switch (String.valueOf(catalog)) {
                    case "templates" -> {
                        if (!offline) {
                            OfficialTemplatesFreshen.refreshNow(msg -> {});
                            Giter8TemplateIndex.invalidate();
                        }
                    }
                    case "libraries" -> {
                        URI src = url != null ? URI.create(url) : LibraryRegistryClient.DEFAULT_SOURCE;
                        Path dest = cacheFile != null ? Path.of(cacheFile) : JkDirs.libraryRegistry();
                        if (req.force()) {
                            LibraryRegistrySync.refreshNow(src, dest);
                        } else {
                            LibraryRegistrySync.ensurePresent(offline, src, dest);
                        }
                    }
                    case "jdks" -> {
                        if (!offline) {
                            JdkCatalogClient client = url != null
                                    ? new JdkCatalogClient(
                                            new Http(),
                                            URI.create(url),
                                            cacheFile != null
                                                    ? Path.of(cacheFile)
                                                    : JdkCatalogClient.defaultCachePath(),
                                            Duration.ZERO)
                                    : new JdkCatalogClient();
                            client.onWarning(msg -> {}).fetch(true);
                        }
                    }
                    default -> error = "unknown catalog: " + catalog;
                }
            } catch (Exception e) {
                error = Errors.text(e);
            }
            host.sendQuiet(writer, ProtoReads.freshenCatalogAck(error == null, error));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
