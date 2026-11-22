// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.compat.UpdatesMirror;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.JkConfigLoader;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.ProxyEnvironment;
import cc.jumpkick.jdk.JdkCatalogClient;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.prices.PriceTable;
import cc.jumpkick.prices.PriceTables;
import cc.jumpkick.repo.ReleaseVerifier;
import cc.jumpkick.templates.OfficialTemplatesFreshen;
import cc.jumpkick.updates.UpdateManifest;
import cc.jumpkick.updates.UpdateState;
import cc.jumpkick.util.StoreWriteGate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The engine's check of the signed update manifest ({@link UpdateManifest}) and the data it lists.
 * {@link #check} asks for the manifest with one conditional GET ({@code If-None-Match}, {@code
 * If-Modified-Since}; the signature only when the manifest changed) and notes each kind whose
 * version differs from the one last applied; {@link #apply} downloads those files, refuses any whose
 * bytes do not match the manifest's SHA-256, and hands the rest to the kind's {@link Applier}. The
 * request carries nothing about the user or the project: no query, no cookie, no identifier, only
 * the validators of the manifest on disk (and a configured mirror's own credential).
 *
 * <p>Offline, or on any failure, the data on disk stays as it is and nothing is said; {@code jk
 * doctor} shows the last outcome ({@link UpdateState}).
 */
public final class UpdateCheck {

    /** One kind of data the check keeps current. */
    @FunctionalInterface
    interface Applier {
        /** Make {@code entry} the copy in use; {@code file} downloads and verifies the file it lists. */
        void apply(UpdateManifest.Entry entry, Download file) throws IOException, InterruptedException;
    }

    /** The bytes of an entry's file, already checked against the manifest's SHA-256. */
    @FunctionalInterface
    interface Download {
        byte[] bytes() throws IOException, InterruptedException;
    }

    /** The manifest URLs a check asks, in order, and the client that asks them. */
    record Sources(Http http, List<URI> manifests) {}

    private final Consumer<String> log;
    private final Supplier<Sources> sources;
    private final Path dir;
    private final Supplier<ReleaseVerifier> verifier;
    private final BooleanSupplier offline;
    private final Map<String, Applier> appliers;
    private final Consumer<UpdateManifest> answeredWith;
    private final Clock clock;

    /** The manifest URL that last answered, which the files it lists are fetched beside; null before. */
    private volatile @Nullable Answered answered;

    private record Answered(Http http, URI manifest) {}

    /** The check of this engine's home, with an applier for each kind of data JumpKick reads. */
    static UpdateCheck forEngine(Consumer<String> log) {
        return new UpdateCheck(
                log,
                () -> {
                    // Read per check: a [mirrors] updates added while the engine runs serves the next one.
                    UpdatesMirror mirror = UpdatesMirror.current();
                    return new Sources(mirror.authorized(new Http()), mirror.sources());
                },
                UpdateState.dir(),
                () -> ReleaseVerifier.current(GlobalConfig.releaseTrustedKeys()),
                JkConfigLoader::hostOffline,
                appliers(log),
                UpdateCheck::recordRelease,
                Clock.SYSTEM);
    }

    /**
     * Test seam: the sources, the state directory, the trusted keys, the offline switch, the
     * appliers, and what is told each manifest a source answered with.
     */
    UpdateCheck(
            Consumer<String> log,
            Supplier<Sources> sources,
            Path dir,
            Supplier<ReleaseVerifier> verifier,
            BooleanSupplier offline,
            Map<String, Applier> appliers,
            Consumer<UpdateManifest> answeredWith,
            Clock clock) {
        this.log = log != null ? log : s -> {};
        this.sources = Objects.requireNonNull(sources, "sources");
        this.dir = Objects.requireNonNull(dir, "dir");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.offline = Objects.requireNonNull(offline, "offline");
        this.appliers = Map.copyOf(appliers);
        this.answeredWith = Objects.requireNonNull(answeredWith, "answeredWith");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The kinds this JumpKick applies: the model price table, the library registry, the JDK catalog
     * and the official template catalog's revision. Each writes where its reader already looks.
     */
    static Map<String, Applier> appliers(Consumer<String> log) {
        return Map.of(
                "prices",
                (entry, file) -> applyPrices(file.bytes(), PriceTables.storeFile(), log),
                "libraries",
                (entry, file) -> applyLibraries(file.bytes(), JkDirs.libraryRegistry()),
                "jdks",
                (entry, file) -> applyJdks(file.bytes(), JdkCatalogClient.defaultCachePath()),
                "templates",
                (entry, file) -> OfficialTemplatesFreshen.freshenOfficialAt(
                        entry.version(), log, ProxyEnvironment.ambient()::gitHttpProxy));
    }

    /**
     * The JumpKick release the manifest names, as the latest this home's self-update has seen: the
     * engine's self-update downloads it, and {@code jk engine status} shows it.
     */
    static void recordRelease(UpdateManifest manifest) {
        manifest.release().ifPresent(release -> {
            try {
                SelfUpdateState.update(s -> s.withCheck(Clock.SYSTEM.instant(), release.version()));
            } catch (IOException e) {
                Log.debug("update check: jk " + release.version() + " not recorded", e);
            }
        });
    }

    /** Keep {@code bytes} as the store's price table when they parse and were published after the table in use. */
    static void applyPrices(byte[] bytes, Path file, Consumer<String> log) throws IOException {
        PriceTable table = PriceTable.parse(bytes);
        if (!table.published().isAfter(PriceTables.current(file).published())) return;
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, bytes);
        log.accept("jk engine: price table updated (published " + table.published() + ")");
    }

    /** Keep {@code bytes} as the library registry when they parse; the registry's own validator no longer matches. */
    static void applyLibraries(byte[] bytes, Path file) throws IOException {
        LibraryCatalog.parse(new String(bytes, StandardCharsets.UTF_8));
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, bytes);
        Files.deleteIfExists(LibraryCatalog.etagFileFor(file));
    }

    /** Keep {@code bytes} as the JDK catalog when they are JSON. */
    static void applyJdks(byte[] bytes, Path file) throws IOException {
        try {
            if (!(MiniJson.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof List<?>)) {
                throw new IOException("JDK catalog is not a JSON array");
            }
        } catch (RuntimeException e) {
            throw new IOException("JDK catalog is not JSON", e);
        }
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, bytes);
    }

    /**
     * Ask for the manifest and note which kinds wait to be applied. True when any kind does, after
     * this check or an earlier one. Never throws.
     */
    public boolean check() {
        try {
            if (offline.getAsBoolean()) return pending();
            // The manifest and the state live in the store: a store wipe must not overlap the writes.
            try (var held = StoreWriteGate.write()) {
                if (StoreWriteGate.wipedSinceStart()) return false;
                UpdateState state = UpdateState.read(dir);
                Sources asked = sources.get();
                Http client = asked.http().documents();
                String failure = "no source answered";
                for (URI source : asked.manifests()) {
                    try {
                        state = fetch(client, source, state);
                        answered = new Answered(client, source);
                        state.write(dir);
                        return !state.pending().isEmpty();
                    } catch (IOException | RuntimeException e) {
                        failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        Log.debug("update check: " + source + " failed", e);
                    }
                }
                state = state.checked(clock.instant(), "failed: " + failure);
                state.write(dir);
                return !state.pending().isEmpty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            Log.debug("update check failed", e);
        }
        return false;
    }

    /** Whether the state on disk has kinds waiting to be applied. */
    private boolean pending() {
        return !UpdateState.read(dir).pending().isEmpty();
    }

    /** Ask {@code source}; the state after it answered, with the kinds that now wait. */
    private UpdateState fetch(Http client, URI source, UpdateState state) throws IOException, InterruptedException {
        Path stored = dir.resolve(UpdateManifest.FILE);
        Map<String, String> headers = new LinkedHashMap<>();
        if (Files.isRegularFile(stored)) {
            if (!state.etag().isBlank()) headers.put("If-None-Match", state.etag());
            if (!state.lastModified().isBlank()) headers.put("If-Modified-Since", state.lastModified());
        }
        HttpResponse<byte[]> response = client.get(source, headers);
        int status = response.statusCode();
        UpdateManifest manifest;
        String outcome;
        if (status == 304) {
            manifest = UpdateManifest.parse(Files.readAllBytes(stored));
            outcome = "up to date";
        } else if (status == 200) {
            byte[] json = response.body();
            byte[] signature = body(client, signatureOf(source));
            manifest = UpdateManifest.verified(json, signature, verifier.get());
            if (Files.isRegularFile(stored)) {
                UpdateManifest held = UpdateManifest.parse(Files.readAllBytes(stored));
                if (manifest.published().isBefore(held.published())) {
                    throw new IOException(source + " serves a manifest older than the one held");
                }
            }
            Files.createDirectories(dir);
            AtomicWrites.replace(stored, json);
            state = state.withValidators(
                    response.headers().firstValue("ETag").orElse("").strip(),
                    response.headers().firstValue("Last-Modified").orElse("").strip());
            outcome = "manifest published " + manifest.published();
        } else {
            throw new IOException(source + " returned HTTP " + status);
        }
        answeredWith.accept(manifest);
        Map<String, String> applied = state.applied();
        Map<String, String> pending = new LinkedHashMap<>();
        manifest.data().forEach((kind, entry) -> {
            if (appliers.containsKey(kind) && !entry.version().equals(applied.get(kind))) {
                pending.put(kind, entry.version());
            }
        });
        return state.checked(clock.instant(), outcome).withPending(pending);
    }

    /**
     * Download, verify and apply each kind that waits. A kind whose file fails to download, verify or
     * parse stays waiting and its current copy stays in use. Returns true when any kind still waits.
     * Never throws.
     */
    public boolean apply() {
        Answered from = answered;
        try {
            if (from == null || offline.getAsBoolean()) return pending();
            try (var held = StoreWriteGate.write()) {
                if (StoreWriteGate.wipedSinceStart()) return false;
                UpdateState state = UpdateState.read(dir);
                Path stored = dir.resolve(UpdateManifest.FILE);
                if (state.pending().isEmpty() || !Files.isRegularFile(stored)) return false;
                UpdateManifest manifest = UpdateManifest.parse(Files.readAllBytes(stored));
                List<String> applied = new ArrayList<>();
                for (Map.Entry<String, String> waiting : state.pending().entrySet()) {
                    String kind = waiting.getKey();
                    UpdateManifest.Entry entry = manifest.entry(kind);
                    Applier applier = appliers.get(kind);
                    if (entry == null || applier == null || !entry.version().equals(waiting.getValue())) continue;
                    try {
                        applier.apply(entry, () -> download(from, entry));
                        state = state.withApplied(kind, entry.version());
                        applied.add(kind);
                    } catch (IOException | RuntimeException e) {
                        Log.debug("update check: " + kind + " " + entry.version() + " not applied", e);
                    }
                }
                if (!applied.isEmpty()) state = state.withOutcome("applied " + String.join(", ", applied));
                state.write(dir);
                return !state.pending().isEmpty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            Log.debug("update apply failed", e);
        }
        return false;
    }

    private static byte[] download(Answered from, UpdateManifest.Entry entry) throws IOException, InterruptedException {
        String file = entry.file();
        if (file == null) throw new IOException("the manifest lists no file for this entry");
        byte[] bytes = body(from.http(), from.manifest().resolve(file));
        entry.verify(bytes);
        return bytes;
    }

    private static byte[] body(Http client, URI uri) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = client.get(uri, Map.of());
        if (response.statusCode() != 200) throw new IOException(uri + " returned HTTP " + response.statusCode());
        return response.body();
    }

    private static URI signatureOf(URI manifest) {
        return URI.create(manifest + UpdateManifest.SIGNATURE_SUFFIX);
    }
}
