// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.compat.PriceTableMirror;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkConfigLoader;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.prices.PriceTable;
import cc.jumpkick.prices.PriceTables;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The engine's maintenance-cycle check for a newer model price table ({@link PriceTables}). One
 * conditional GET ({@code If-None-Match}, {@code If-Modified-Since}) of the published file, the next
 * source asked only when one fails. The request carries nothing about the user or the project: no
 * query, no cookie, no identifier, only the validators of the copy on disk (and a configured
 * mirror's own credential).
 *
 * <p>A fetched table is kept only when its signature verifies against the release key, it parses,
 * and it was published after the table in effect. Offline, or on any failure, nothing changes and
 * nothing is said.
 */
public final class PriceTableRefresh {

    private final Consumer<String> log;
    private final Supplier<Sources> sources;
    private final Path file;
    private final Supplier<ReleaseVerifier> verifier;
    private final BooleanSupplier offline;

    /** The URLs a pass asks, in order, and the client that asks them. */
    record Sources(Http http, List<URI> uris) {}

    public PriceTableRefresh(Consumer<String> log) {
        this(
                log,
                () -> {
                    // Read per pass: a [mirrors] prices added while the engine runs serves the next one.
                    PriceTableMirror mirror = PriceTableMirror.current();
                    return new Sources(mirror.authorized(new Http()), mirror.sources());
                },
                PriceTables.storeFile(),
                () -> ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
                JkConfigLoader::hostOffline);
    }

    /** Test seam: the sources, the store file, the trusted keys and the offline switch. */
    PriceTableRefresh(
            Consumer<String> log,
            Supplier<Sources> sources,
            Path file,
            Supplier<ReleaseVerifier> verifier,
            BooleanSupplier offline) {
        this.log = log != null ? log : s -> {};
        this.sources = Objects.requireNonNull(sources, "sources");
        this.file = Objects.requireNonNull(file, "file");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.offline = Objects.requireNonNull(offline, "offline");
    }

    /** One pass; never throws. */
    public void refreshQuietly() {
        try {
            if (offline.getAsBoolean()) return;
            // The table lives in the store: a store wipe must not overlap the write.
            try (var held = StoreWriteGate.write()) {
                if (StoreWriteGate.wipedSinceStart()) return;
                Sources asked = sources.get();
                Http client = asked.http().documents();
                for (URI source : asked.uris()) {
                    try {
                        fetch(client, source);
                        return;
                    } catch (IOException | RuntimeException e) {
                        Log.debug("price table refresh: " + source + " failed", e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.debug("price table refresh failed", e);
        }
    }

    /** Ask {@code source}; returns normally when it answered with nothing new or with a table kept or refused as older. */
    private void fetch(Http client, URI source) throws IOException, InterruptedException {
        Path validatorsFile = validatorsFile();
        Map<String, String> headers = new LinkedHashMap<>();
        if (Files.isRegularFile(file) && Files.size(file) > 0 && Files.isRegularFile(validatorsFile)) {
            List<String> lines = Files.readAllLines(validatorsFile, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).isBlank()) headers.put("If-None-Match", lines.get(0));
            if (lines.size() > 1 && !lines.get(1).isBlank()) headers.put("If-Modified-Since", lines.get(1));
        }
        HttpResponse<byte[]> response = client.get(source, headers);
        int status = response.statusCode();
        if (status == 304) return;
        if (status != 200) throw new IOException(source + " returned HTTP " + status);

        byte[] body = response.body();
        PriceTable.Signed signed = PriceTable.signed(body);
        verifier.get().verify(signed.content(), signed.signature());
        PriceTable table = PriceTable.parse(signed.content());

        Files.createDirectories(file.getParent());
        if (table.published().isAfter(PriceTables.current(file).published())) {
            AtomicWrites.replace(file, body);
            log.accept("jk engine: price table updated (published " + table.published() + ")");
        }
        String etag = response.headers().firstValue("ETag").orElse("");
        String modified = response.headers().firstValue("Last-Modified").orElse("");
        AtomicWrites.replace(validatorsFile, etag.strip() + "\n" + modified.strip() + "\n");
    }

    /** The ETag and Last-Modified of the last table fetched, one per line, beside the store copy. */
    private Path validatorsFile() {
        return file.resolveSibling("." + file.getFileName() + ".validators");
    }
}
