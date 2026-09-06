// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

/**
 * Per rule per run. One severity: anything but {@link #CLEAN} (and a fully baselined
 * {@link #VIOLATIONS}) is red, for the report and for the cache alike — a green verdict is stored
 * only for a lane where every rule came back clean.
 */
public enum Outcome {
    CLEAN("clean"),
    VIOLATIONS("violations"),
    /** The rule examined nothing: a population of zero is not evidence about the tree. */
    BLIND("blind"),
    /** The population fell under 80 % of what the baseline recorded. */
    SCOPE_SHRUNK("scope-shrunk"),
    /** The owner the rule points callers at does not exist or no longer exhibits the shape. */
    OWNER_MISSING("owner-missing"),
    /** The inputs this rule needs were not produced this build (compile failed, no artefact). */
    NOT_EVALUATED("not-evaluated"),
    /** The kind or the language has no evaluator yet; never reported as clean. */
    UNSUPPORTED("unsupported"),
    /** An {@code allow} entry matched nothing: an exemption that expired silently. */
    STALE_ALLOW("stale-allow"),
    /** The rule threw, timed out or overflowed; the engine is unaffected, the rule is red. */
    SCANNER_FAILED("scanner-failed");

    private final String id;

    Outcome(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Whether this outcome, with {@code freshViolations} new sites, fails the build. */
    public boolean red(boolean freshViolations) {
        return switch (this) {
            case CLEAN -> false;
            case VIOLATIONS -> freshViolations;
            default -> true;
        };
    }
}
