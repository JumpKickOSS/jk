// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

/** An extractor that could not read its source: the rule is {@code scanner-failed}, not clean. */
final class ExtractorException extends Exception {
    ExtractorException(String message) {
        super(message);
    }
}
