// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/** Read-only pipeline snapshot for listeners ({@code numerator}/{@code denominator} may grow). */
public record BuildPlanView(
        String pipelineName, long numerator, long denominator, int stepsTotal, int stepsComplete, boolean cancelled) {

    public double fraction() {
        if (denominator <= 0) return 0.0;
        double frac = (double) numerator / (double) denominator;
        if (frac < 0.0) return 0.0;
        if (frac > 1.0) return 1.0;
        return frac;
    }

    public int percent() {
        return (int) Math.round(fraction() * 100);
    }
}
