// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JkWireModelTest {

    @Test
    public void parses_module_list_and_libs() {
        String json =
                """
                {"type":"ide-model-ack","error":null,"wsRoot":"/tmp/ws","rootName":"demo","workspace":true,\
                "moduleDirs":["/tmp/ws/api","/tmp/ws/app"],"names":["api","app"],\
                "javaReleases":["25","25"],"mainClasses":["","demo.Main"],\
                "libJars":["/cache/a.jar","/cache/b.jar"],"libSources":["/cache/a-sources.jar",""]}
                """;
        JkWireModel m = JkWireModel.parse(json);
        assertNull(m.error);
        assertEquals("/tmp/ws", m.wsRoot);
        assertEquals("demo", m.rootName);
        assertTrue(m.workspace);
        assertEquals(2, m.moduleCount());
        assertEquals("api", m.names.get(0));
        assertEquals("app", m.names.get(1));
        assertEquals(2, m.libJars.size());
        assertEquals("/cache/a-sources.jar", m.libSources.get(0));
    }

    @Test
    public void strips_leading_noise_before_json() {
        String raw = "some wedge line\n{\"type\":\"ide-model-ack\",\"error\":null,\"wsRoot\":\"/p\",\"rootName\":\"x\","
                + "\"workspace\":false,\"moduleDirs\":[\"/p\"],\"names\":[\"x\"],"
                + "\"javaReleases\":[\"25\"],\"mainClasses\":[\"\"],"
                + "\"libJars\":[],\"libSources\":[]}\n";
        JkWireModel m = JkWireModel.parse(raw);
        assertEquals("/p", m.wsRoot);
        assertEquals(1, m.moduleCount());
    }
}
