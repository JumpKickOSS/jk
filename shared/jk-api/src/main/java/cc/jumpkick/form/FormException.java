// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

/** A form, a field or a condition that cannot be built; the message names the field and the fault. */
public final class FormException extends IllegalArgumentException {

    public FormException(String message) {
        super(message);
    }
}
