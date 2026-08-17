// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

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
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String catalog = Jsonl.str(requestLine, "catalog");
            boolean offline = Jsonl.bool(requestLine, "offline", false);
            String url = Jsonl.str(requestLine, "url");
            String cacheFile = Jsonl.str(requestLine, "cacheFile");
            String error = null;
            try {
                switch (String.valueOf(catalog)) {
                    case "templates" -> {
                        if (!offline) cc.jumpkick.templates.OfficialTemplatesFreshen.refreshNow(msg -> {});
                    }
                    case "libraries" ->
                        cc.jumpkick.repo.LibraryRegistrySync.ensurePresent(
                                offline,
                                url != null ? URI.create(url) : cc.jumpkick.repo.LibraryRegistryClient.DEFAULT_SOURCE,
                                cacheFile != null
                                        ? Path.of(cacheFile)
                                        : cc.jumpkick.library.LibraryCatalog.downloadedFile());
                    case "jdks" -> {
                        if (!offline) {
                            cc.jumpkick.jdk.JdkCatalogClient client = url != null
                                    ? new cc.jumpkick.jdk.JdkCatalogClient(
                                            new cc.jumpkick.http.Http(),
                                            URI.create(url),
                                            cacheFile != null
                                                    ? Path.of(cacheFile)
                                                    : cc.jumpkick.jdk.JdkCatalogClient.defaultCachePath(),
                                            Duration.ZERO)
                                    : new cc.jumpkick.jdk.JdkCatalogClient();
                            client.onWarning(msg -> {}).fetch(true);
                        }
                    }
                    default -> error = "unknown catalog: " + catalog;
                }
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            host.sendQuiet(writer, ProtoReads.freshenCatalogAck(error == null, error));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
