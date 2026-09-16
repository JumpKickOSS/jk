// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

/** Locked Java JDK. See {@link ToolchainPin}. */
public record JdkPin(String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion)
        implements ToolchainPin {
    public JdkPin {
        suggestedVendor = ToolchainPin.blankToEmpty(suggestedVendor);
        suggestedVersion = ToolchainPin.blankToEmpty(suggestedVersion);
        requiredVendor = ToolchainPin.blankToEmpty(requiredVendor);
        requiredVersion = ToolchainPin.blankToEmpty(requiredVersion);
    }

    /** A pin that only records what built the lock — the shape every pin had before pinning existed. */
    public static JdkPin suggested(String vendor, String version) {
        return new JdkPin(vendor, version, "", "");
    }
}
