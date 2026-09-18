// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jaxb;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.generate.GeneratorEntry;
import cc.jumpkick.plugin.PluginConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The arguments the preset shapes, run through the real xjc (on this test's classpath) over a
 * schema directory: the classes land under the package, a binding file is honoured, and the
 * generated sources carry no timestamp header.
 */
class XjcRunTest {

    private static final String SCHEMA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="http://acme.com/order" xmlns="http://acme.com/order"
                       elementFormDefault="qualified">
              <xs:element name="order">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="id" type="xs:string"/>
                    <xs:element name="quantity" type="xs:int"/>
                  </xs:sequence>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;

    private static final String BINDING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jaxb:bindings xmlns:jaxb="https://jakarta.ee/xml/ns/jaxb" xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           version="3.0">
              <jaxb:globalBindings generateIsSetMethod="true"/>
            </jaxb:bindings>
            """;

    @Test
    void the_presets_arguments_compile_a_schema_directory(@TempDir Path tmp) throws Exception {
        Path xsd = Files.createDirectories(tmp.resolve("src/main/xsd"));
        Files.writeString(xsd.resolve("order.xsd"), SCHEMA);
        Path xjb = Files.createDirectories(tmp.resolve("src/main/xjb"));
        Files.writeString(xjb.resolve("global.xjb"), BINDING);
        Path out = Files.createDirectories(tmp.resolve("out"));
        GeneratorEntry entry = JaxbPreset.entry(
                new PluginConfig("jaxb", Map.of("package", "com.acme.order", "bindings", List.of("src/main/xjb"))));

        int exit = xjc(expand(entry.args(), tmp, out));

        assertThat(exit).isZero();
        Path pkg = out.resolve("com/acme/order");
        assertThat(pkg.resolve("Order.java"))
                .content()
                .contains("package com.acme.order;")
                .contains("public boolean isSetId()")
                .doesNotContain("Generated on:");
        assertThat(pkg.resolve("ObjectFactory.java")).isRegularFile();
    }

    /** {@code ${out}} and {@code ${module.dir}} as the generator step expands them. */
    private static String[] expand(List<String> args, Path moduleDir, Path out) {
        List<String> expanded = new ArrayList<>();
        for (String arg : args) {
            expanded.add(arg.replace("${out}", out.toString()).replace("${module.dir}", moduleDir.toString()));
        }
        return expanded.toArray(String[]::new);
    }

    /** xjc's {@code Driver.run(args, status, out)} — the main's body without its {@code System.exit}. */
    private static int xjc(String[] args) throws Exception {
        Class<?> driver = Class.forName(JaxbPreset.MAIN);
        Method run = driver.getMethod("run", String[].class, PrintStream.class, PrintStream.class);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
        int exit = (int) run.invoke(null, args, stream, stream);
        if (exit != 0) throw new AssertionError("xjc exited " + exit + ":\n" + captured);
        return exit;
    }
}
