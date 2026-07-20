// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.buildlogic;

/** One named, action-cached unit of project build logic. */
@FunctionalInterface
public interface BuildLogicTask {
    void run(BuildLogicContext ctx) throws Exception;
}
