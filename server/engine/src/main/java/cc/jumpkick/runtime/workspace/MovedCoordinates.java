// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.host.Log;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.repo.PomParser;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Where a declared {@code group:artifact} moved, for {@code jk outdated}: the {@code
 * <distributionManagement><relocation>} of its newest published POM, or else a {@link Rename} from
 * {@code renamed-coordinates.toml} for a publisher that renamed without one, taken only when the
 * repositories confirm it.
 */
final class MovedCoordinates {

    private MovedCoordinates() {}

    /**
     * A rename without a relocation POM: {@code from} ({@code group:artifact}, the artifact may be
     * {@code *}) became {@code to} (a {@code *} there takes the artifact) at version {@code since}.
     */
    record Rename(String from, String to, String since) {

        /** The coordinate {@code module} became under this rename, or null when it does not match. */
        @Nullable
        String target(String module) {
            int colon = from.indexOf(':');
            String group = from.substring(0, colon);
            String artifact = from.substring(colon + 1);
            int moduleColon = module.indexOf(':');
            if (moduleColon < 0 || !module.substring(0, moduleColon).equals(group)) return null;
            String moduleArtifact = module.substring(moduleColon + 1);
            if (artifact.equals("*")) return to.replace("*", moduleArtifact);
            return artifact.equals(moduleArtifact) ? to : null;
        }
    }

    private static final List<Rename> RENAMES = load();

    /**
     * The {@code group:artifact} {@code module} moved to, or null. {@code available} is every
     * version {@code module} publishes; {@code versionsOf} lists another coordinate's.
     */
    static @Nullable String target(String module, List<String> available, RepoGroup repos, VersionLister versionsOf) {
        Optional<String> newest = available.stream().max(Versions::compare);
        if (newest.isEmpty()) return null;
        String relocated = relocation(module, newest.get(), repos);
        if (relocated != null) return relocated;
        return renamed(module, newest.get(), RENAMES, versionsOf);
    }

    /** Lists the versions a {@code group:artifact} publishes. */
    @FunctionalInterface
    interface VersionLister {
        List<String> versions(String module);
    }

    /**
     * The coordinate {@code module} became under one of {@code renames}: its newest release is
     * below the rename and the new coordinate publishes a release at or past it.
     */
    static @Nullable String renamed(String module, String newest, List<Rename> renames, VersionLister versionsOf) {
        for (Rename r : renames) {
            String to = r.target(module);
            if (to == null || to.equals(module) || Versions.compare(newest, r.since()) >= 0) continue;
            if (versionsOf.versions(to).stream().anyMatch(v -> Versions.compare(v, r.since()) >= 0)) return to;
        }
        return null;
    }

    /** The other {@code group:artifact} the POM of {@code module} at {@code version} relocates to, or null. */
    private static @Nullable String relocation(String module, String version, RepoGroup repos) {
        Coordinate coord = Coordinate.ofModule(module, version);
        try {
            Optional<RepoGroup.RepoFetched> pom = repos.tryFetchPom(coord);
            if (pom.isEmpty()) return null;
            Pom parsed = PomParser.parse(Files.readAllBytes(pom.get().fetched().cachePath()));
            Pom.Relocation moved = parsed.relocation();
            if (moved == null) return null;
            Coordinate to = moved.applyTo(coord);
            String target = to.group() + ":" + to.artifact();
            return target.equals(module) ? null : target;
        } catch (IOException | RuntimeException e) {
            Log.debug("outdated: no relocation read for " + coord.toGav(), e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<Rename> load() {
        try (InputStream in = MovedCoordinates.class.getResourceAsStream("renamed-coordinates.toml")) {
            if (in == null) return List.of();
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code [[rename]]} rows of a renames file. */
    static List<Rename> parse(String text) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) throw new IllegalStateException("renamed-coordinates.toml: " + toml.errors());
        List<Rename> out = new ArrayList<>();
        TomlArray rows = toml.getArray("rename");
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            TomlTable row = rows.getTable(i);
            out.add(new Rename(
                    String.valueOf(row.getString("from")),
                    String.valueOf(row.getString("to")),
                    String.valueOf(row.getString("since"))));
        }
        return out;
    }
}
