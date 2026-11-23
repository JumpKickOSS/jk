// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The JaCoCo agent a suite JVM starts with and the execution data it appends to. Every JVM that
 * runs tests for a module shares one file; the agent appends and writes at exit, which is how
 * shard workers of one module land in one report.
 */
public record CoverageAgent(Path agentJar, Path execFile) {

    public CoverageAgent {
        Objects.requireNonNull(agentJar, "agentJar");
        Objects.requireNonNull(execFile, "execFile");
    }

    /** The {@code -javaagent:} flag for a test JVM. */
    public String agentArg() {
        return spell(agentJar.toString(), execFile.toString());
    }

    /**
     * The run-tests stamp's spelling of the flag, the jar and the execution data as stand-ins. A
     * coverage run stores its marker under the key a plain run looks up, so the flag is keyed
     * whether or not the run asks for coverage.
     */
    public static String token() {
        return spell("@jacoco-agent", "@exec");
    }

    private static String spell(String agentJar, String execFile) {
        return "-javaagent:" + agentJar + "=destfile=" + execFile + ",append=true,output=file";
    }
}
