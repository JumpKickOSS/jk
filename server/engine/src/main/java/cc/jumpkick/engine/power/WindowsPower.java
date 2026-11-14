// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.power;

import org.jspecify.annotations.Nullable;

/**
 * Windows: the power source from {@code GetSystemPowerStatus}, and a hold made with {@code
 * PowerCreateRequest} and {@code PowerSetRequest(PowerRequestSystemRequired)}, which {@code powercfg
 * /requests} lists under SYSTEM while it is held. Both are kernel32 calls made through FFM ({@link
 * Kernel32Power}).
 */
record WindowsPower(Native calls) implements PowerHost {

    WindowsPower() {
        this(new Kernel32Power());
    }

    /** The two Win32 facts this needs: a fake in tests. */
    interface Native {
        /** {@code {ACLineStatus, BatteryFlag}} of {@code SYSTEM_POWER_STATUS}; null when the call failed. */
        int @Nullable [] powerStatus();

        /** A system-required power request with reason {@code why}, held until closed; null when refused. */
        @Nullable
        Hold systemRequired(String why);
    }

    /** {@code BatteryFlag}: no system battery. */
    static final int NO_SYSTEM_BATTERY = 128;

    /** {@code BatteryFlag}: the status is unknown. */
    static final int UNKNOWN_STATUS = 255;

    @Override
    public Source source() {
        int[] s = calls.powerStatus();
        return s == null ? Source.UNKNOWN : source(s[0], s[1]);
    }

    /** {@code ACLineStatus} 1 is A/C, 0 battery, 255 unknown; a machine with no battery is on A/C. */
    static Source source(int acLineStatus, int batteryFlag) {
        if (acLineStatus == 1) return Source.AC;
        boolean noBattery = batteryFlag != UNKNOWN_STATUS && (batteryFlag & NO_SYSTEM_BATTERY) != 0;
        if (noBattery) return Source.AC;
        return acLineStatus == 0 ? Source.BATTERY : Source.UNKNOWN;
    }

    @Override
    public @Nullable Hold hold() {
        return calls.systemRequired("JumpKick: a build is running");
    }
}
