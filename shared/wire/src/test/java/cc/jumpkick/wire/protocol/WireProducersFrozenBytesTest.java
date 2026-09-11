// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.model.Sidecar;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The bytes the ack, report and session producers wrote by hand before they built through the one
 * JSON writer, spelled out from the old templates. The wire is frozen pre-1.0: a reordered field or
 * a changed null spelling is a different line to every client, and a round trip cannot see that.
 */
class WireProducersFrozenBytesTest {
    @Test
    void project_info_error_and_a_populated_line_round_trip_byte_for_byte() {
        assertThat(ProjectInfo.error("boom").encode())
                .isEqualTo(
                        "{\"type\":\"project-info-ack\",\"error\":\"boom\",\"group\":\"\",\"name\":\"\",\"version\":\"\","
                                + "\"jdk\":\"\",\"javaRelease\":0,\"kotlin\":false,\"kotlinVersion\":\"\",\"groovy\":false,"
                                + "\"groovyVersion\":\"\",\"layoutSimple\":true,\"workspaceRoot\":false,\"workspaceRootDir\":\"\","
                                + "\"modules\":{},\"application\":false,\"mainClass\":\"\",\"assembly\":false,\"applicationConfig\":\"\","
                                + "\"nativeMode\":\"DISABLED\",\"graal\":\"\",\"springBoot\":false,\"springBootVersion\":\"\","
                                + "\"formatStyle\":\"\",\"formatJava\":\"\",\"formatKotlin\":\"\",\"hasLock\":false,\"lockJdk\":\"\","
                                + "\"mainJarPath\":\"\",\"assemblyJarPath\":\"\",\"nativeBinPath\":\"\",\"nativeLibPath\":\"\","
                                + "\"pathDeps\":[],\"sourcesJarPath\":\"\",\"javadocJarPath\":\"\",\"envRefs\":[],\"sourceCount\":0,"
                                + "\"testCount\":0,\"nativeExplicitlyDisabled\":false,\"classesDir\":\"\",\"testClassesDir\":\"\","
                                + "\"kotlinClassesDir\":\"\",\"groovyClassesDir\":\"\",\"testResultsDir\":\"\",\"testIncludeTags\":[],"
                                + "\"testExcludeTags\":[],\"lockStale\":false,\"scala\":false,\"scalaVersion\":\"\","
                                + "\"coordinatorOnly\":false,\"productLib\":\"\"}");
        // A populated line in the old template's order, with the three tri-state format flags present
        // and a null error: decode then encode must give the same bytes back.
        String line = "{\"type\":\"project-info-ack\",\"error\":null,\"group\":\"g\",\"name\":\"n\",\"version\":\"1\","
                + "\"jdk\":\"25\",\"javaRelease\":25,\"kotlin\":true,\"kotlinVersion\":\"2.4\",\"groovy\":false,"
                + "\"groovyVersion\":\"\",\"layoutSimple\":false,\"workspaceRoot\":true,\"workspaceRootDir\":\"/w\","
                + "\"modules\":{\"a/b\":\"a\",\"c\":\"\"},\"application\":true,\"mainClass\":\"M\",\"assembly\":true,"
                + "\"applicationConfig\":\"app.toml\",\"nativeMode\":\"AUTO\",\"graal\":\"25\",\"springBoot\":true,"
                + "\"springBootVersion\":\"4.1\",\"formatStyle\":\"palantir\",\"formatJava\":\"x\",\"formatKotlin\":\"y\","
                + "\"formatOptimizeImports\":true,\"formatImportOrder\":false,\"formatRemoveUnusedImports\":true,"
                + "\"hasLock\":true,\"lockJdk\":\"25\",\"mainJarPath\":\"/m.jar\",\"assemblyJarPath\":\"/a.jar\","
                + "\"nativeBinPath\":\"/bin\",\"nativeLibPath\":\"/lib\",\"pathDeps\":[\"../x\"],\"sourcesJarPath\":\"/s.jar\","
                + "\"javadocJarPath\":\"/j.jar\",\"envRefs\":[\"HOME\"],\"sourceCount\":3,\"testCount\":4,"
                + "\"nativeExplicitlyDisabled\":true,\"classesDir\":\"/c\",\"testClassesDir\":\"/tc\","
                + "\"kotlinClassesDir\":\"/kc\",\"groovyClassesDir\":\"/gc\",\"testResultsDir\":\"/tr\","
                + "\"testIncludeTags\":[\"fast\"],\"testExcludeTags\":[\"slow\",\"bench\"],\"lockStale\":true,\"scala\":true,"
                + "\"scalaVersion\":\"3.9\",\"coordinatorOnly\":false,\"productLib\":\"lib\"}";
        assertThat(ProjectInfo.decode(line).encode()).isEqualTo(line);
    }

    @Test
    void exec_plan_and_ide_model_keep_their_field_order() {
        ExecPlan plan = new ExecPlan(
                null,
                "issue",
                "run",
                List.of("java", "-jar"),
                "/w",
                "disp",
                "/jdk",
                true,
                false,
                List.of("src"),
                List.of(),
                List.of(),
                "/l",
                "/l.sh",
                "/bin",
                true,
                "/m.jar",
                "T1",
                "Main",
                List.of("a"),
                List.of("/a.jar"),
                "deploy",
                List.of(new ExecPlan.Sidecar(
                        "web",
                        List.of("npm", "run", "dev"),
                        "/w/web",
                        Map.of("PORT", "5173"),
                        "http://localhost:5173",
                        "",
                        60000L,
                        true,
                        Sidecar.Restart.NEVER)));
        assertThat(plan.encode())
                .isEqualTo("{\"type\":\"exec-plan-ack\",\"error\":null,\"mainIssue\":\"issue\",\"kind\":\"run\","
                        + "\"argv\":[\"java\",\"-jar\"],\"workingDir\":\"/w\",\"display\":\"disp\",\"javaHome\":\"/jdk\","
                        + "\"hotReload\":true,\"devtoolsInjected\":false,\"watchRoots\":[\"src\"],\"linkSrcs\":[],"
                        + "\"linkDests\":[],\"launcherPath\":\"/l\",\"launcherScript\":\"/l.sh\",\"binPath\":\"/bin\","
                        + "\"boot\":true,\"mainJar\":\"/m.jar\",\"tier\":\"T1\",\"mainClass\":\"Main\",\"libNames\":[\"a\"],"
                        + "\"libPaths\":[\"/a.jar\"],\"deployCommand\":\"deploy\","
                        + "\"sidecars\":[{\"name\":\"web\",\"command\":[\"npm\",\"run\",\"dev\"],\"cwd\":\"/w/web\","
                        + "\"env\":{\"PORT\":\"5173\"},\"ready\":\"http://localhost:5173\",\"readyPattern\":\"\","
                        + "\"readyTimeoutMillis\":60000,\"frontDoor\":true,\"restart\":\"never\"}]}");
        assertThat(ExecPlan.decode(plan.encode())).isEqualTo(plan);
        String ide =
                "{\"type\":\"ide-model-ack\",\"error\":\"e\",\"wsRoot\":\"/w\",\"rootName\":\"r\",\"workspace\":true,"
                        + "\"moduleDirs\":[\"a\"],\"names\":[\"n\"],\"javaReleases\":[\"25\"],\"mainClasses\":[\"M\"],"
                        + "\"classesDirs\":[\"c\"],\"testClassesDirs\":[\"tc\"],\"jdtClassesDirs\":[\"jc\"],"
                        + "\"jdtTestClassesDirs\":[\"jtc\"],\"genSrcDirs\":[\"g\"],\"genTestSrcDirs\":[\"gt\"],\"libNames\":[\"l\"],"
                        + "\"libFiles\":[\"lf\"],\"libJars\":[\"lj\"],\"libSources\":[\"ls\"],\"siblingRefs\":[\"s\"],"
                        + "\"libEntries\":[\"le\"],\"processorJars\":[\"p\"],\"sdkStableNames\":[\"sn\"],\"sdkNames\":[\"sN\"],"
                        + "\"sdkLevels\":[\"17\"],\"sdkHomes\":[\"/sh\"],\"sdkVersions\":[\"17.0\"],\"defSdkStableName\":\"d\","
                        + "\"defSdkName\":\"dn\",\"defSdkLevel\":25,\"defSdkHome\":\"/dh\",\"defSdkVersion\":\"25.0\","
                        + "\"sdkEntries\":[\"se\"]}";
        assertThat(IdeWireModel.decode(ide).encode()).isEqualTo(ide);
    }

    @Test
    void reports_and_acks_spell_null_errors_and_pipe_joined_rows_as_before() {
        assertThat(new CacheInventoryAck(
                                "e", "q", List.of("s1"), 10L, 20L, List.of("en"), List.of("l1", "l2"), 1, 2, 3L, 4L)
                        .encode())
                .isEqualTo("{\"type\":\"cache-inventory-ack\",\"error\":\"e\",\"query\":\"q\",\"stats\":[\"s1\"],"
                        + "\"totalFiles\":10,\"totalBytes\":20,\"entries\":[\"en\"],\"lines\":[\"l1\",\"l2\"],\"evicted\":1,"
                        + "\"missed\":2,\"files\":3,\"bytes\":4}");
        assertThat(new AffectedTestsReport(
                                false, null, "", 5, 7, List.of(new AffectedTestsReport.Row(9, "a.B", "dirty")))
                        .encode())
                .isEqualTo(
                        "{\"type\":\"affected-tests-ack\",\"refused\":false,\"error\":null,\"refuseCode\":\"\",\"cap\":5,"
                                + "\"candidateCount\":7,\"rows\":[\"9|a.B|dirty\"]}");
        assertThat(new WhyReport(null, List.of("g:a"), List.of("1.0"), List.of("0"), List.of("root>g:a")).encode())
                .isEqualTo("{\"type\":\"why-ack\",\"error\":null,\"matchNames\":[\"g:a\"],\"matchVersions\":[\"1.0\"],"
                        + "\"pathOwners\":[\"0\"],\"paths\":[\"root>g:a\"]}");
        assertThat(new PluginCommandReport("oops", false, 3, List.of("x")).encode())
                .isEqualTo(
                        "{\"type\":\"plugin-command-ack\",\"error\":\"oops\",\"found\":false,\"exit\":3,\"output\":[\"x\"]}");
        assertThat(new OutdatedReport(
                                null,
                                true,
                                List.of(new OutdatedReport.Row("m", "g:a", "a", "compile", "1.0", "1.1", "2.0", null)))
                        .encode())
                .isEqualTo("{\"type\":\"outdated-ack\",\"error\":null,\"workspace\":true,"
                        + "\"rows\":[\"m|g:a|a|compile|1.0|1.1|2.0|null\"]}");
        assertThat(new GuardFreezeAck("e", 1, 2).encode())
                .isEqualTo("{\"type\":\"guard-freeze-ack\",\"error\":\"e\",\"accepted\":1,\"total\":2}");
        assertThat(new GeneratedFiles(null, List.of("a.java"), List.of("class A {}"), List.of("note")).encode())
                .isEqualTo(
                        "{\"type\":\"generate-ack\",\"error\":null,\"paths\":[\"a.java\"],\"contents\":[\"class A {}\"],"
                                + "\"notes\":[\"note\"]}");
        assertThat(new DenyReport("e", 2, List.of("g:a"), List.of("1"), List.of("banned")).encode())
                .isEqualTo("{\"type\":\"deny-check-ack\",\"error\":\"e\",\"checked\":2,\"modules\":[\"g:a\"],"
                        + "\"versions\":[\"1\"],\"reasons\":[\"banned\"]}");
        assertThat(new CatalogReadAck(
                                null,
                                List.of("w"),
                                List.of("L"),
                                List.of(new CatalogReadAck.Entry("n", "g", "a", "L", List.of("x", "y"))))
                        .encode())
                .isEqualTo("{\"type\":\"catalog-read-ack\",\"error\":null,\"warnings\":[\"w\"],\"layerNames\":[\"L\"],"
                        + "\"entries\":[\"n|g|a|L|x,y\"]}");
        assertThat(new NewProjectAck(null, "/p", "id", 4).encode())
                .isEqualTo(
                        "{\"type\":\"new-project-ack\",\"error\":null,\"path\":\"/p\",\"projectId\":\"id\",\"filesWritten\":4}");
        assertThat(new GuardExplainAck("e", "t", "{}").encode())
                .isEqualTo("{\"type\":\"guard-explain-ack\",\"error\":\"e\",\"text\":\"t\",\"json\":\"{}\"}");
        assertThat(new ModuleGraphAck(null, "a -> b").encode())
                .isEqualTo("{\"type\":\"module-graph-ack\",\"error\":null,\"graph\":\"a -> b\"}");
        assertThat(new GuardTestAck(null, "ok", 0).encode())
                .isEqualTo("{\"type\":\"guard-test-ack\",\"error\":null,\"text\":\"ok\",\"failures\":0}");
        assertThat(new GuardCommitMsgAck("e", "t", 2).encode())
                .isEqualTo("{\"type\":\"guard-commit-msg-ack\",\"error\":\"e\",\"text\":\"t\",\"failures\":2}");
    }

    @Test
    void session_terminals_and_splices_keep_their_bytes() {
        assertThat(ProtoSession.planFinishTool("d", true, null, "M", List.of("a.jar")))
                .isEqualTo(
                        "{\"type\":\"buildplan-finish\",\"kind\":\"tool\",\"dir\":\"d\",\"success\":true,\"toolCoord\":null,"
                                + "\"toolMainClass\":\"M\",\"toolClasspath\":[\"a.jar\"]}");
        assertThat(ProtoSession.planFinishScript("d", false, "S", List.of(), "/c", null, "/std.jar"))
                .isEqualTo("{\"type\":\"buildplan-finish\",\"kind\":\"script\",\"dir\":\"d\",\"success\":false,"
                        + "\"scriptMainClass\":\"S\",\"scriptClasspath\":[],\"scriptClassesDir\":\"/c\","
                        + "\"scriptKotlincBin\":null,\"scriptStdlib\":\"/std.jar\"}");
        assertThat(ProtoSession.pruneWait(2, true))
                .isEqualTo("{\"type\":\"prune-wait\",\"plans\":2,\"external\":true}");
        assertThat(ProtoSession.planFinishCache("d", false, 3, 4))
                .isEqualTo("{\"type\":\"buildplan-finish\",\"kind\":\"cache\",\"dir\":\"d\",\"success\":false,"
                        + "\"cacheFiles\":3,\"cacheBytes\":4}");
        PluginTuning tuning = new PluginTuning(75.0, "G1", true, List.of("-Xss1m"));
        assertThat(ProtoSession.withSession("{\"type\":\"x\"}", "v", Map.of("A", "1"), tuning, true, false, "fat"))
                .isEqualTo(
                        "{\"type\":\"x\",\"rebuild\":true,\"variant\":\"v\",\"env\":{\"A\":\"1\"},\"assemblyOverride\":\"fat\","
                                + "\"jvmMaxRam\":\"75.0\",\"jvmGc\":\"G1\",\"jvmStringDedup\":\"true\",\"jvmArgs\":[\"-Xss1m\"]}");
        assertThat(ProtoSession.withSession("{\"type\":\"x\"}", " ", Map.of(), PluginTuning.NONE))
                .as("nothing to add leaves the request alone")
                .isEqualTo("{\"type\":\"x\"}");
        assertThat(ProtoSession.withSession("{}", null, null, null, false, true))
                .isEqualTo("{\"noTimeline\":true}");
        assertThat(ProtoSession.withToolchain("{\"type\":\"x\"}", "17", null, "  "))
                .isEqualTo("{\"type\":\"x\",\"jdk\":\"17\"}");
        assertThat(ProtoSession.withToolchain("{\"type\":\"x\"}", null, "", null))
                .isEqualTo("{\"type\":\"x\"}");
        assertThat(ProtoSession.withTrigger("{\"type\":\"x\"}", "manual"))
                .isEqualTo("{\"type\":\"x\",\"trigger\":\"manual\"}");
        assertThat(ProtoSession.withTrigger("{\"type\":\"x\"}", "")).isEqualTo("{\"type\":\"x\"}");
    }

    @Test
    void read_acks_keep_their_bytes() {
        assertThat(ProtoReads.explainModule("d", "g:a", 3, 4, true, false, null))
                .isEqualTo(
                        "{\"type\":\"explain-module\",\"dir\":\"d\",\"coord\":\"g:a\",\"sourceCount\":3,\"testCount\":4,"
                                + "\"producesJar\":true,\"producesImage\":false,\"reason\":null}");
        assertThat(ProtoReads.explainModule("d", "g:a", 0, 0, false, false, "rebuilt because x"))
                .endsWith("\"producesImage\":false,\"reason\":\"rebuilt because x\"}");
        assertThat(ProtoReads.explainStep("d", "compile-java", "stale", "3 sources", null))
                .isEqualTo("{\"type\":\"explain-task\",\"dir\":\"d\",\"name\":\"compile-java\",\"status\":\"stale\","
                        + "\"text\":\"3 sources\",\"key\":null}");
        assertThat(ProtoReads.explainEdge("a", "b"))
                .isEqualTo("{\"type\":\"explain-edge\",\"dir\":\"a\",\"dependsOnDir\":\"b\"}");
        assertThat(ProtoReads.treeAck(null, null))
                .isEqualTo("{\"type\":\"tree-ack\",\"error\":null,\"rendered\":\"\"}");
        assertThat(ProtoReads.treeAck("e", "root"))
                .isEqualTo("{\"type\":\"tree-ack\",\"error\":\"e\",\"rendered\":\"root\"}");
        assertThat(ProtoReads.editAck(true, null)).isEqualTo("{\"type\":\"edit-ack\",\"changed\":true,\"error\":null}");
        assertThat(ProtoReads.editAck(false, "e", "d"))
                .isEqualTo("{\"type\":\"edit-ack\",\"changed\":false,\"error\":\"e\",\"detail\":\"d\"}");
        assertThat(ProtoReads.freshenCatalogAck(true, null))
                .isEqualTo("{\"type\":\"freshen-catalog-ack\",\"ok\":true,\"error\":null}");
        assertThat(ProtoReads.forecastAck(List.of("a"), true, false, List.of()))
                .isEqualTo(
                        "{\"type\":\"forecast-ack\",\"dirtyDirs\":[\"a\"],\"lockStale\":true,\"empty\":false,\"errors\":[]}");
        assertThat(ProtoReads.explainDone(4, 9))
                .isEqualTo("{\"type\":\"explain-done\",\"maxReadyWidth\":4,\"moduleCount\":9}");
    }
}
