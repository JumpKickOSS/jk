// SPDX-License-Identifier: Apache-2.0
package demo;

import java.util.List;
import org.apache.commons.text.WordUtils;

/**
 * Title-cases its arguments with Apache Commons Text. Listed in
 * {@code META-INF/services/demo.Command}, a by-name index jk reads to derive its keep rule, so
 * {@code jk.toml} names no rule for it.
 */
public final class TitleCommand implements Command {

    @Override
    public String verb() {
        return "title";
    }

    @Override
    public String run(List<String> args) {
        return WordUtils.capitalizeFully(String.join(" ", args));
    }
}
