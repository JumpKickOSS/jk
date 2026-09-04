// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

/**
 * The engine's count of active build plans: a plan claims its slot in the same breath as the
 * shutdown check, so a drain can never observe zero plans for a job that is about to start.
 */
public interface PlanSlots {
    /** Claim a plan slot; {@code false} while the engine is draining. */
    boolean tryStartBuildPlan();

    /** Give back a claimed slot for a job that never ran. */
    void abandonBuildPlanSlot();

    /** Release a finished job's slot — before its request-finish event, so the count it carries is current. */
    void noteBuildPlanFinished();

    boolean draining();

    int activeBuildPlans();
}
