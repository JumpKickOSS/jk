// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * POM {@code <license>} entries as SPDX identifiers. POMs name licences freely ("The Apache Software
 * License, Version 2.0", "EPL 2.0", a URL alone); the common spellings and URLs map to their SPDX id,
 * and anything else is kept as its name (or URL) so a policy can still match it by glob.
 */
public final class SpdxLicenses {

    private SpdxLicenses() {}

    /** Lower-cased URL fragments (no scheme, no trailing slash) to their licence. */
    private static final Map<String, String> URLS = Map.ofEntries(
            Map.entry("www.apache.org/licenses/license-2.0", "Apache-2.0"),
            Map.entry("apache.org/licenses/license-2.0", "Apache-2.0"),
            Map.entry("opensource.org/licenses/apache-2.0", "Apache-2.0"),
            Map.entry("opensource.org/licenses/mit", "MIT"),
            Map.entry("opensource.org/licenses/mit-license.php", "MIT"),
            Map.entry("opensource.org/licenses/bsd-2-clause", "BSD-2-Clause"),
            Map.entry("opensource.org/licenses/bsd-3-clause", "BSD-3-Clause"),
            Map.entry("www.eclipse.org/legal/epl-v10.html", "EPL-1.0"),
            Map.entry("www.eclipse.org/legal/epl-2.0", "EPL-2.0"),
            Map.entry("www.eclipse.org/legal/epl-v20.html", "EPL-2.0"),
            Map.entry("www.eclipse.org/org/documents/edl-v10.php", "BSD-3-Clause"),
            Map.entry("www.mozilla.org/mpl/2.0", "MPL-2.0"),
            Map.entry("www.gnu.org/licenses/lgpl-2.1.html", "LGPL-2.1-only"),
            Map.entry("www.gnu.org/licenses/lgpl-3.0.html", "LGPL-3.0-only"),
            Map.entry("www.gnu.org/licenses/gpl-2.0.html", "GPL-2.0-only"),
            Map.entry("www.gnu.org/licenses/gpl-3.0.html", "GPL-3.0-only"),
            Map.entry("www.gnu.org/licenses/agpl-3.0.html", "AGPL-3.0-only"),
            Map.entry("creativecommons.org/publicdomain/zero/1.0", "CC0-1.0"),
            Map.entry("unlicense.org", "Unlicense"));

    /** Lower-cased names, punctuation folded to spaces, to their licence. */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("apache 2 0", "Apache-2.0"),
            Map.entry("apache license 2 0", "Apache-2.0"),
            Map.entry("apache license version 2 0", "Apache-2.0"),
            Map.entry("the apache license version 2 0", "Apache-2.0"),
            Map.entry("the apache software license version 2 0", "Apache-2.0"),
            Map.entry("apache software license version 2 0", "Apache-2.0"),
            Map.entry("asl 2 0", "Apache-2.0"),
            Map.entry("mit", "MIT"),
            Map.entry("mit license", "MIT"),
            Map.entry("the mit license", "MIT"),
            Map.entry("bsd 2 clause", "BSD-2-Clause"),
            Map.entry("bsd 3 clause", "BSD-3-Clause"),
            Map.entry("new bsd license", "BSD-3-Clause"),
            Map.entry("revised bsd", "BSD-3-Clause"),
            Map.entry("eclipse distribution license v 1 0", "BSD-3-Clause"),
            Map.entry("edl 1 0", "BSD-3-Clause"),
            Map.entry("epl 1 0", "EPL-1.0"),
            Map.entry("eclipse public license 1 0", "EPL-1.0"),
            Map.entry("eclipse public license v1 0", "EPL-1.0"),
            Map.entry("epl 2 0", "EPL-2.0"),
            Map.entry("eclipse public license 2 0", "EPL-2.0"),
            Map.entry("eclipse public license v 2 0", "EPL-2.0"),
            Map.entry("mozilla public license 2 0", "MPL-2.0"),
            Map.entry("mpl 2 0", "MPL-2.0"),
            Map.entry("gnu lesser general public license v2 1", "LGPL-2.1-only"),
            Map.entry("lgpl 2 1", "LGPL-2.1-only"),
            Map.entry("gnu lesser general public license v3 0", "LGPL-3.0-only"),
            Map.entry("lgpl 3 0", "LGPL-3.0-only"),
            Map.entry("gnu general public license v2 0", "GPL-2.0-only"),
            Map.entry("gpl 2 0", "GPL-2.0-only"),
            Map.entry(
                    "gnu general public license version 2 with the classpath exception",
                    "GPL-2.0-with-classpath-exception"),
            Map.entry("gpl2 w cpe", "GPL-2.0-with-classpath-exception"),
            Map.entry(
                    "gnu general public license version 2 gpl2 with the classpath exception",
                    "GPL-2.0-with-classpath-exception"),
            Map.entry("gnu general public license v3 0", "GPL-3.0-only"),
            Map.entry("gpl 3 0", "GPL-3.0-only"),
            Map.entry("gnu affero general public license v3 0", "AGPL-3.0-only"),
            Map.entry("agpl 3 0", "AGPL-3.0-only"),
            Map.entry("cc0", "CC0-1.0"),
            Map.entry("cc0 1 0", "CC0-1.0"),
            Map.entry("public domain", "Public-Domain"),
            Map.entry("the unlicense", "Unlicense"),
            Map.entry("isc", "ISC"),
            Map.entry("bouncy castle licence", "MIT"),
            Map.entry("cddl 1 1", "CDDL-1.1"),
            Map.entry("common development and distribution license cddl v1 1", "CDDL-1.1"));

    /** The licences {@code licenses} names, each as its SPDX id where one fits, deduplicated, in order. */
    public static List<String> of(List<Pom.License> licenses) {
        Set<String> out = new LinkedHashSet<>();
        for (Pom.License l : licenses) {
            String id = id(l.name(), l.url());
            if (id != null) out.add(id);
        }
        return new ArrayList<>(out);
    }

    /** One licence's SPDX id, else its name, else its URL; null when it names nothing. */
    static @Nullable String id(@Nullable String name, @Nullable String url) {
        if (url != null) {
            String key = url.trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceFirst("^https?://", "")
                    .replaceFirst("/+$", "");
            String hit = URLS.get(key);
            if (hit != null) return hit;
            if (key.endsWith(".txt")) {
                hit = URLS.get(key.substring(0, key.length() - 4));
                if (hit != null) return hit;
            }
        }
        if (name != null && !name.isBlank()) {
            String folded = name.trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ")
                    .trim();
            String hit = NAMES.get(folded);
            return hit != null ? hit : name.trim();
        }
        return url == null || url.isBlank() ? null : url.trim();
    }
}
