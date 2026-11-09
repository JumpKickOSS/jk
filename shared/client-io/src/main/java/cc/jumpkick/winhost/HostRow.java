// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One row of the Windows section: {@code id} is stable for machines ({@code smart-app-control},
 * {@code defender}, …), {@code label} is what a person reads, and {@code fix} is the command or
 * setting that clears a warning, null when there is nothing to do.
 */
public record HostRow(
        String id,
        String label,
        Status status,
        String detail,
        @Nullable String fix) {

    public enum Status {
        OK,
        WARN,
        FAIL;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The row as a JSON object: {@code id}, {@code label}, {@code status}, {@code detail} and {@code fix}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("status", status.wire());
        m.put("detail", detail);
        if (fix != null) m.put("fix", fix);
        return m;
    }

    static HostRow ok(String id, String label, String detail) {
        return new HostRow(id, label, Status.OK, detail, null);
    }

    static HostRow warn(String id, String label, String detail, @Nullable String fix) {
        return new HostRow(id, label, Status.WARN, detail, fix);
    }
}
