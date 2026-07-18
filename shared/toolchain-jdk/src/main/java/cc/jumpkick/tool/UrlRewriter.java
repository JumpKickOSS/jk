// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites forge page URLs (GitHub/GitLab/Bitbucket/Gist) to raw-content endpoints; other URLs pass
 * through. Trust checks use the user-typed URL before rewrite.
 */
public final class UrlRewriter {

    private static final Pattern GITHUB_BLOB = Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/blob/(.+)");
    private static final Pattern GIST_PAGE = Pattern.compile("https://gist\\.github\\.com/([^/]+)/([0-9a-fA-F]+)/?");
    private static final Pattern BITBUCKET_SRC = Pattern.compile("https://bitbucket\\.org/([^/]+)/([^/]+)/src/(.+)");

    private UrlRewriter() {}

    public static String rewrite(String url) {
        String u = url.trim();
        Matcher m = GITHUB_BLOB.matcher(u);
        if (m.matches()) {
            return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
        }
        if (u.contains("/-/blob/")) {
            return u.replaceFirst("/-/blob/", "/-/raw/");
        }
        m = BITBUCKET_SRC.matcher(u);
        if (m.matches()) {
            return "https://bitbucket.org/" + m.group(1) + "/" + m.group(2) + "/raw/" + m.group(3);
        }
        m = GIST_PAGE.matcher(u);
        if (m.matches()) {
            return "https://gist.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/raw";
        }
        return u;
    }
}
