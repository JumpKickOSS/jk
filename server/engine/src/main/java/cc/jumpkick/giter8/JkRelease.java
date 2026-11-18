// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.model.JkVersion;
import cc.jumpkick.winhost.ReleasePin;
import cc.jumpkick.winhost.WslDownloads;
import java.io.IOException;

/**
 * {@code $jk_release$}: the published jk release a template pins what only a release publishes,
 * such as the plugin SDK ({@code cc.jumpkick:jk-plugin-sdk}). A release names itself, as {@code
 * $jk_version$} does. A {@code -SNAPSHOT} is never published, so a project naming one resolves it
 * nowhere but on the machine that built it, and the library pack's {@code no-snapshot-versions}
 * rule refuses it: a development build names the newest release at or below it, the release
 * {@link ReleasePin} picks for a Windows-from-guest install ({@code 0.52.1-SNAPSHOT} names {@code
 * 0.52.0}). Asked only when a rendered file names the property, so no other template reaches the
 * release site.
 */
public final class JkRelease {

    /** The template property. */
    public static final String PROPERTY = "jk_release";

    /** Where a render reads the release from. */
    @FunctionalInterface
    public interface Lookup {
        String release() throws IOException;
    }

    /** This jk's own version, unasked: what a render given no lookup names. */
    public static final Lookup SELF = () -> JkVersion.VERSION;

    private JkRelease() {}

    /**
     * The release site JumpKick updates from, for this jk; {@link #SELF} for a release, and offline,
     * where nothing could be asked.
     */
    public static Lookup live(boolean offline) {
        if (offline || !JkVersion.VERSION.endsWith("-SNAPSHOT")) return SELF;
        return () -> {
            WslDownloads site = WslDownloads.live();
            return of(JkVersion.VERSION, site::published, site::latest);
        };
    }

    /**
     * The release {@code version} names: itself for a release; for a {@code -SNAPSHOT} the newest
     * published release at or below it, or the snapshot itself when none fits or the site cannot say.
     */
    static String of(String version, ReleasePin.Published published, ReleasePin.Latest latest) {
        try {
            ReleasePin.Pin pin = ReleasePin.of(version, published, latest);
            return pin == null ? version : pin.version();
        } catch (IOException e) {
            return version;
        }
    }
}
