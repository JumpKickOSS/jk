// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener;
import com.intellij.util.messages.Topic;

/** Link/unlink notifications for JumpKick projects; every method is a no-op default. */
public interface JkSettingsListener extends ExternalSystemSettingsListener<JkProjectSettings> {

    Topic<JkSettingsListener> TOPIC = Topic.create("JumpKick settings", JkSettingsListener.class);
}
