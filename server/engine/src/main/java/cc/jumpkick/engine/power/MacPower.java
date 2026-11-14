// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import cc.jumpkick.host.Processes;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * macOS: the power source from {@code pmset -g batt}, and a hold kept by {@code caffeinate -i -s -w
 * <engine pid>}: {@code -i} keeps idle sleep away, {@code -s} system sleep while on A/C, and {@code -w}
 * ends it with the engine. A closed lid still sleeps.
 */
record MacPower(Supplier<@Nullable String> pmset, ChildHold.Starter starter, long enginePid) implements PowerHost {

    static MacPower system() {
        return new MacPower(
                () -> Processes.outputIfOk(List.of("pmset", "-g", "batt"), Duration.ofSeconds(5)),
                ChildHold.PROCESSES,
                ProcessHandle.current().pid());
    }

    @Override
    public Source source() {
        return parse(pmset.get());
    }

    /** {@code Now drawing from 'AC Power'} is A/C, {@code 'Battery Power'} battery. */
    static Source parse(@Nullable String batt) {
        if (batt == null) return Source.UNKNOWN;
        if (batt.contains("'AC Power'")) return Source.AC;
        if (batt.contains("'Battery Power'")) return Source.BATTERY;
        return Source.UNKNOWN;
    }

    /** The hold's argv for an engine with pid {@code pid}. */
    static List<String> caffeinate(long pid) {
        return List.of("caffeinate", "-i", "-s", "-w", Long.toString(pid));
    }

    @Override
    public @Nullable Hold hold() {
        return ChildHold.start(starter, caffeinate(enginePid));
    }
}
