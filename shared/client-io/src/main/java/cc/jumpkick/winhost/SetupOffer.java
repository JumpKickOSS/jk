// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

/**
 * What a person's build does about the machine-setup decision: nothing once the machine answered or
 * while the decision is already out, else what {@code [windows] fix} says. Asked once; an unanswered
 * decision that expires is recorded as declined, so the next build does not ask again.
 */
public enum SetupOffer {
    /** Answered already, a decision is out, or there is nothing to change. */
    NONE,
    /** Raise the decision as a notification; never a terminal prompt. */
    ASK,
    /** Apply every recommended change; Windows still shows its one prompt. */
    APPLY,
    /** Record a decline without asking. */
    DECLINE;

    /** The offer for a machine whose memo is {@code memo}, with {@code outstanding} decisions out. */
    public static SetupOffer of(SetupConsent consent, SetupMemo memo, boolean outstanding, boolean nothingToChange) {
        if (!memo.mayAsk() || outstanding || nothingToChange) return NONE;
        return switch (consent) {
            case ASK -> ASK;
            case APPLY -> APPLY;
            case NEVER -> DECLINE;
        };
    }
}
