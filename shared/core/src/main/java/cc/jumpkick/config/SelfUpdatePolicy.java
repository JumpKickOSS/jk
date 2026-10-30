// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.version.Versions;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Whether jk updates itself, asks first, or leaves itself alone. One rule, read by the engine's
 * scheduler and by the client's prompt. The first row that applies decides:
 *
 * <ol>
 *   <li>a {@code -SNAPSHOT} build never updates itself;
 *   <li>a hold (a deliberate {@code jk self update <older>}) stops everything until a later {@code
 *       jk self update} clears it;
 *   <li>env {@code JK_SELF_UPDATE};
 *   <li>before 1.0, updates are compulsory: {@code auto};
 *   <li>{@code self-update} in {@code ~/.jk/config.toml};
 *   <li>the default: {@code ask} where a person answers at a terminal, {@code auto} on CI and
 *       wherever no one is there to ask.
 * </ol>
 */
public final class SelfUpdatePolicy {

    /** The env override of the {@code self-update} config field. */
    public static final String ENV = "JK_SELF_UPDATE";

    /** The root-level key in {@code ~/.jk/config.toml}. */
    public static final String KEY = "self-update";

    private SelfUpdatePolicy() {}

    /** A value of {@code self-update} / {@link #ENV}. */
    public enum Mode {
        AUTO,
        ASK,
        NEVER;

        /** The spelling in config and env. */
        public String spelling() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** {@code auto}, {@code ask} or {@code never}, case-insensitive; anything else is empty. */
        public static Optional<Mode> parse(@Nullable String raw) {
            if (raw == null) return Optional.empty();
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "ask" -> Optional.of(ASK);
                case "never" -> Optional.of(NEVER);
                default -> Optional.empty();
            };
        }
    }

    /** What to do: install unasked, download and ask, or nothing. */
    public enum Action {
        AUTO,
        ASK,
        OFF
    }

    /** The decision and the row that made it, for status lines. */
    public record Decision(Action action, String reason) {}

    /**
     * The decision for {@code version}.
     *
     * @param env {@code JK_SELF_UPDATE}, raw
     * @param configured {@code self-update} from the config file, raw
     * @param state this home's update state (the hold)
     * @param attended whether a person answers prompts here: a terminal for the client; for the
     *     engine, a person seen at one recently and not CI
     */
    public static Decision decide(
            String version,
            @Nullable String env,
            @Nullable String configured,
            SelfUpdateState state,
            boolean attended) {
        if (Versions.isSnapshot(version)) return new Decision(Action.OFF, "a -SNAPSHOT build never updates itself");
        if (state.isHeld()) {
            return new Decision(
                    Action.OFF, "held at " + state.held() + " after a downgrade; `jk self update` resumes updates");
        }
        Optional<Mode> fromEnv = Mode.parse(env);
        if (fromEnv.isPresent())
            return of(fromEnv.get(), ENV + "=" + fromEnv.get().spelling());
        if (preRelease(version)) return new Decision(Action.AUTO, "updates are compulsory before 1.0");
        Optional<Mode> fromConfig = Mode.parse(configured);
        if (fromConfig.isPresent())
            return of(fromConfig.get(), KEY + " = \"" + fromConfig.get().spelling() + "\"");
        return attended
                ? new Decision(Action.ASK, "the default at a terminal")
                : new Decision(Action.AUTO, "the default where no one is asked");
    }

    /** Whether {@code version} is before 1.0, where updates are compulsory. */
    public static boolean preRelease(String version) {
        return Versions.compare(version, "1.0.0") < 0;
    }

    /**
     * Whether a configured {@code ask} or {@code never} is being ignored for {@code version}: true
     * before 1.0 with no env override and no hold.
     */
    public static boolean configIgnored(String version, @Nullable String env, @Nullable String configured) {
        if (Versions.isSnapshot(version)
                || !preRelease(version)
                || Mode.parse(env).isPresent()) return false;
        return Mode.parse(configured).filter(m -> m != Mode.AUTO).isPresent();
    }

    private static Decision of(Mode mode, String reason) {
        return switch (mode) {
            case AUTO -> new Decision(Action.AUTO, reason);
            case ASK -> new Decision(Action.ASK, reason);
            case NEVER -> new Decision(Action.OFF, reason);
        };
    }
}
