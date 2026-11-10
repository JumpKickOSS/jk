// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import cc.jumpkick.config.SelfUpdateState;
import cc.jumpkick.config.WhatsNew;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The bell's one "JumpKick was updated to X (from Y)" after an install: raised by the first engine
 * of the installed version and never again, however often that engine restarts. It links to the
 * dashboard's "What's new" dialog.
 */
public final class UpdatedNotice {

    /** The notice kind. */
    public static final String KIND = "updated";

    /** Where it links: the dialog over whatever page is showing. */
    public static final String HREF = "#whats-new";

    private UpdatedNotice() {}

    /** Raise the notice when the last install was to {@code version} and the bell has not said so; true if raised. */
    public static boolean announce(Notices notices, String version, Path stateFile) {
        AtomicReference<String> from = new AtomicReference<>();
        try {
            SelfUpdateState.update(stateFile, s -> {
                String updatedFrom = s.updatedFrom();
                if (updatedFrom == null || !s.announceDue(version, s.announcedWeb())) return s;
                from.set(updatedFrom);
                return s.withAnnouncedWeb(version);
            });
        } catch (IOException e) {
            Log.debug("UpdatedNotice: state not read", e);
            return false;
        }
        String updatedFrom = from.get();
        if (updatedFrom == null) return false;
        notices.add(KIND, WhatsNew.headline(updatedFrom, version), HREF, null);
        return true;
    }
}
