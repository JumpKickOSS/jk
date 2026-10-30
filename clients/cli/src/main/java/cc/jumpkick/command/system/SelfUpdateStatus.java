// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.PreparedUpdate;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.config.SelfUpdatePolicy;
import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** The {@code Updates} row of {@code jk engine status}: what this home's self-update does next. */
final class SelfUpdateStatus {

    private SelfUpdateStatus() {}

    /** The row for this home, as the engine's scheduler would decide it now. */
    static String describe() {
        Instant now = Clock.SYSTEM.instant();
        SelfUpdateState state = SelfUpdateState.read();
        boolean ci = EnvValues.isCi(System::getenv);
        SelfUpdatePolicy.Decision decision = SelfUpdatePolicy.decide(
                JkVersion.VERSION,
                System.getenv(SelfUpdatePolicy.ENV),
                GlobalConfig.selfUpdate().orElse(null),
                state,
                SelfUpdatePolicy.attended(state, now, ci));
        return describe(
                JkVersion.VERSION,
                decision,
                state,
                PreparedUpdate.read(EngineInstall.current().engineHome()),
                now);
    }

    /**
     * {@code auto (updates are compulsory before 1.0); checked 3 h ago, up to date}, {@code ask (…);
     * checked 20 min ago, 0.17.0 downloaded, offered at your next command at a terminal}, or {@code
     * off: held at 0.16.2 …}.
     */
    static String describe(
            String version,
            SelfUpdatePolicy.Decision decision,
            SelfUpdateState state,
            Optional<PreparedUpdate> prepared,
            Instant now) {
        if (decision.action() == SelfUpdatePolicy.Action.OFF) return "off: " + decision.reason();
        boolean auto = decision.action() == SelfUpdatePolicy.Action.AUTO;
        StringBuilder sb = new StringBuilder(auto ? "auto" : "ask")
                .append(" (")
                .append(decision.reason())
                .append(')');
        Instant checked = state.lastCheck();
        if (checked == null) {
            sb.append("; not checked yet");
        } else {
            sb.append("; checked ").append(ago(Duration.between(checked, now)));
            String latest = state.latest();
            boolean newer = latest != null && Versions.compare(latest, version) > 0;
            if (!newer) sb.append(", up to date");
            else if (prepared.filter(p -> p.version().equals(latest)).isEmpty())
                sb.append(", ").append(latest).append(" is out");
        }
        prepared.filter(p -> p.newerThan(version)).ifPresent(p -> sb.append(", ")
                .append(p.version())
                .append(" downloaded, ")
                .append(auto ? "installs when the engine is quiet" : "offered at your next command at a terminal"));
        return sb.toString();
    }

    /** {@code 12 min ago}, {@code 5 h ago}, {@code 3 days ago}. */
    static String ago(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 60) return minutes + " min ago";
        long hours = d.toHours();
        if (hours < 48) return hours + " h ago";
        return d.toDays() + " days ago";
    }
}
