// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

/**
 * What a person's build does about the machine-setup decision: nothing while the decision is already
 * out or when every recommended row has an answer, else what {@code [windows] fix} says, for the rows
 * never answered. Each row is asked once; an unanswered decision that expires is recorded as declined,
 * so the next build does not ask again.
 */
public enum SetupOffer {
    /** Every row answered, a decision is out, or there is nothing to change. */
    NONE,
    /** Raise the decision as a notification; never a terminal prompt. */
    ASK,
    /** Apply every recommended change; Windows still shows its one prompt. */
    APPLY,
    /** Record a decline without asking. */
    DECLINE;

    /** The offer for the rows {@code unanswered} holds, with {@code outstanding} decisions out. */
    public static SetupOffer of(SetupConsent consent, SetupPlan unanswered, boolean outstanding) {
        if (outstanding || unanswered.isEmpty()) return NONE;
        return switch (consent) {
            case ASK -> ASK;
            case APPLY -> APPLY;
            case NEVER -> DECLINE;
        };
    }
}
