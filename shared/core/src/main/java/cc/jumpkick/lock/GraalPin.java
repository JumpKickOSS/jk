// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

/** Locked GraalVM; omit the table when Graal was not in play. See {@link ToolchainPin}. */
public record GraalPin(String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion)
        implements ToolchainPin {
    public GraalPin {
        suggestedVendor = ToolchainPin.blankToEmpty(suggestedVendor);
        suggestedVersion = ToolchainPin.blankToEmpty(suggestedVersion);
        requiredVendor = ToolchainPin.blankToEmpty(requiredVendor);
        requiredVersion = ToolchainPin.blankToEmpty(requiredVersion);
    }

    /** A pin that only records what built the lock. */
    public static GraalPin suggested(String vendor, String version) {
        return new GraalPin(vendor, version, "", "");
    }
}
