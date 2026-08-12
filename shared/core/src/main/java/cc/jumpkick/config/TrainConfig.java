// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Parsed {@code [train]} / {@code [[train.profile]]} from {@code jk.toml}.
 *
 * <p>See {@code docs/features/dynamic-surface.md}. Training is opt-in ({@code jk train}); default
 * {@code jk build} never runs it.
 */
public record TrainConfig(
        String command, String commitTo, boolean requireFresh, boolean aotCache, List<Profile> profiles) {

    public static final TrainConfig EMPTY = new TrainConfig(null, null, false, false, List.of());

    public TrainConfig {
        profiles = profiles == null || profiles.isEmpty() ? List.of() : List.copyOf(profiles);
    }

    /** One observation configuration: env, system properties, and process args. */
    public record Profile(String name, Map<String, String> env, Map<String, String> properties, List<String> args) {
        public Profile {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("profile name must not be blank");
            env = env == null || env.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(env));
            properties =
                    properties == null || properties.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(properties));
            args = args == null || args.isEmpty() ? List.of() : List.copyOf(args);
        }
    }

    /** Profiles to run: declared ones, or a single {@code default} when none are listed. */
    public List<Profile> effectiveProfiles() {
        if (!profiles.isEmpty()) return profiles;
        return List.of(new Profile("default", Map.of(), Map.of(), List.of()));
    }

    public boolean hasCommand() {
        return command != null && !command.isBlank();
    }

    public boolean hasCommitTo() {
        return commitTo != null && !commitTo.isBlank();
    }

    /** Stable fingerprint fragment for profile definitions. */
    public String profilesToken() {
        StringBuilder sb = new StringBuilder();
        for (Profile p : effectiveProfiles()) {
            sb.append(p.name()).append('|');
            // Sorted: Map.copyOf iteration order is salted per JVM, and a token that flaps
            // across engine restarts spuriously retrains (or, with require-fresh, fails builds).
            new TreeMap<>(p.env())
                    .forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            sb.append('|');
            new TreeMap<>(p.properties())
                    .forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            sb.append('|');
            sb.append(String.join(" ", p.args()));
            sb.append('\n');
        }
        if (hasCommand()) sb.append("cmd:").append(command).append('\n');
        sb.append("aot:").append(aotCache).append('\n');
        return sb.toString();
    }

    /** Filter to one profile by name, or all when {@code name} is null/blank. */
    public List<Profile> select(String name) {
        if (name == null || name.isBlank()) return effectiveProfiles();
        List<Profile> out = new ArrayList<>();
        for (Profile p : effectiveProfiles()) {
            if (p.name().equals(name)) out.add(p);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("unknown train profile `" + name + "` — declared: "
                    + effectiveProfiles().stream().map(Profile::name).toList());
        }
        return out;
    }
}
