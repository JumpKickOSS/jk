// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.DomXml;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;

/**
 * JaCoCo for a coverage run: the agent a suite JVM starts with and the command-line tool that turns
 * its execution data into the XML the {@code coverage.*} guard measures and the results file read,
 * and the HTML report beside it. Both come from the
 * project's repositories at the newest stable JaCoCo — coverage is an inventory, not a build input,
 * so the version is not a lock's business — and live in the CAS like any other fetched artifact.
 */
final class CoverageTools {

    /** The agent and the CLI of one JaCoCo release. */
    record Jacoco(String version, Path agentJar, Path cliJar) {}

    private static final String GROUP = "org.jacoco";
    private static final String AGENT = "org.jacoco.agent";
    private static final String CLI = "org.jacoco.cli";

    private CoverageTools() {}

    static Jacoco resolve(JkBuild project, Cas cas) throws IOException, InterruptedException {
        RepoGroup repos = RepoGroupBuilder.buildFor(project, null, cas);
        Coordinate agent = PluginBuild.resolveCoordinate(repos, GROUP + ":" + AGENT + ":latest:runtime");
        Coordinate cli = new Coordinate(GROUP, CLI, agent.version(), "nodeps", "jar");
        return new Jacoco(agent.version(), fetch(repos, agent), fetch(repos, cli));
    }

    private static Path fetch(RepoGroup repos, Coordinate coord) throws IOException, InterruptedException {
        return repos.tryFetchArtifact(coord)
                .orElseThrow(() -> new IOException("cannot fetch " + coord + " — JaCoCo must be reachable from a"
                        + " declared repository for a coverage run"))
                .fetched()
                .cachePath();
    }

    /** Whole-report {@code LINE} and {@code BRANCH} counters read off a JaCoCo XML report. */
    record Counters(long linesCovered, long linesMissed, long branchesCovered, long branchesMissed) {
        static final Counters NONE = new Counters(0, 0, 0, 0);
    }

    /**
     * {@code java -jar org.jacoco.cli report <exec> --classfiles … --sourcefiles … --xml <out> --html
     * <dir>}: the report over every class directory that exists, with the source roots that exist so
     * the HTML shows lines. A module without a source root for a language it compiled is common;
     * the counters the guard and the results file read do not need sources.
     */
    static List<String> reportCommand(
            Path javaHome,
            Path cliJar,
            Path exec,
            List<Path> classDirs,
            List<Path> sourceDirs,
            Path xml,
            Path html,
            String name) {
        List<String> cmd = new ArrayList<>();
        cmd.add(JdkFingerprint.java(javaHome).toString());
        cmd.add("-jar");
        cmd.add(cliJar.toString());
        cmd.add("report");
        cmd.add(exec.toString());
        for (Path dir : classDirs) {
            if (!Files.isDirectory(dir)) continue;
            cmd.add("--classfiles");
            cmd.add(dir.toString());
        }
        for (Path dir : sourceDirs) {
            if (!Files.isDirectory(dir)) continue;
            cmd.add("--sourcefiles");
            cmd.add(dir.toString());
        }
        cmd.add("--xml");
        cmd.add(xml.toString());
        cmd.add("--html");
        cmd.add(html.toString());
        cmd.add("--name");
        cmd.add(name);
        return cmd;
    }

    /**
     * Write {@code xml} and the HTML report under {@code html} from {@code exec}; the tool's own
     * output is the failure message when it exits non-zero.
     */
    static void writeReport(
            Path javaHome,
            Jacoco tools,
            Path exec,
            List<Path> classDirs,
            List<Path> sourceDirs,
            Path xml,
            Path html,
            String name)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(exec)) {
            throw new IOException("no execution data at " + exec + " — did any suite JVM start under the agent?");
        }
        Files.createDirectories(xml.toAbsolutePath().getParent());
        Files.createDirectories(html);
        Process process = JavaHomes.underJdk(
                        new ProcessBuilder(
                                reportCommand(javaHome, tools.cliJar(), exec, classDirs, sourceDirs, xml, html, name)),
                        javaHome)
                .redirectErrorStream(true)
                .start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (exit != 0 || !Files.isRegularFile(xml)) {
            throw new IOException("jacoco report exited " + exit + " for " + name + ":\n" + output.strip());
        }
    }

    /**
     * The report-level counters of a JaCoCo XML report: the {@code <counter>} children of
     * {@code <report>}. A kind the report lacks (a resources-only module has no BRANCH row) reads
     * as zero covered, zero missed.
     */
    static Counters counters(Path xml) throws IOException {
        // JaCoCo writes a DOCTYPE naming its report DTD; the hardened parser refuses any DOCTYPE.
        String text = Files.readString(xml).replaceFirst("<!DOCTYPE[^>]*>", "");
        Element root = DomXml.parse(text).getDocumentElement();
        long lc = 0, lm = 0, bc = 0, bm = 0;
        for (Element c : DomXml.childElements(root, "counter")) {
            long missed = Long.parseLong(c.getAttribute("missed"));
            long covered = Long.parseLong(c.getAttribute("covered"));
            switch (c.getAttribute("type")) {
                case "LINE" -> {
                    lc = covered;
                    lm = missed;
                }
                case "BRANCH" -> {
                    bc = covered;
                    bm = missed;
                }
                default -> {}
            }
        }
        return new Counters(lc, lm, bc, bm);
    }
}
