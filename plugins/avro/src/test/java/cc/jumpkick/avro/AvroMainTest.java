// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.avro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main over the real Avro compiler (on this test's classpath): a record schema generates its
 * class, a schema naming a type another file defines compiles whatever order the files come in, a
 * protocol and an IDL file generate their interfaces, the options reach the compiler, and an
 * undefined name is the parser's own failure.
 */
class AvroMainTest {

    private static final String USER = """
            {"type": "record", "name": "User", "namespace": "com.acme.model",
             "fields": [{"name": "name", "type": "string"}, {"name": "role", "type": "com.acme.model.Role"}]}
            """;

    private static final String ROLE = """
            {"type": "enum", "name": "Role", "namespace": "com.acme.model", "symbols": ["ADMIN", "USER"]}
            """;

    private static final String PROTOCOL = """
            {"protocol": "Greeter", "namespace": "com.acme.rpc",
             "types": [{"type": "record", "name": "Greeting", "fields": [{"name": "text", "type": "string"}]}],
             "messages": {"greet": {"request": [{"name": "who", "type": "string"}], "response": "Greeting"}}}
            """;

    private static final String IDL = """
            @namespace("com.acme.idl")
            protocol Ping {
              record Pong { long at; }
              Pong ping();
            }
            """;

    @Test
    void schemas_compile_in_any_order_when_one_names_a_type_another_defines(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src/main/avro");
        Path user = write(src.resolve("User.avsc"), USER);
        Path role = write(src.resolve("types/Role.avsc"), ROLE);
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = AvroMain.run(
                new String[] {"--out", out.toString(), "--string-type", "String", user.toString(), role.toString()});

        assertThat(exit).isZero();
        Path pkg = out.resolve("com/acme/model");
        assertThat(pkg.resolve("User.java"))
                .content()
                .contains("package com.acme.model;")
                .contains("java.lang.String getName()")
                .contains("public void setName(");
        assertThat(pkg.resolve("Role.java")).content().contains("enum Role");
    }

    @Test
    void the_options_reach_the_compiler(@TempDir Path tmp) throws Exception {
        Path role = write(tmp.resolve("Role.avsc"), ROLE);
        Path user = write(tmp.resolve("User.avsc"), USER);
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = AvroMain.run(new String[] {
            "--out",
            out.toString(),
            "--string-type",
            "CharSequence",
            "--field-visibility",
            "PUBLIC",
            "--no-setters",
            user.toString(),
            role.toString()
        });

        assertThat(exit).isZero();
        assertThat(out.resolve("com/acme/model/User.java"))
                .content()
                .contains("java.lang.CharSequence getName()")
                .contains("public java.lang.CharSequence name;")
                .doesNotContain("public void setName(");
    }

    @Test
    void a_protocol_and_an_idl_file_generate_their_interfaces(@TempDir Path tmp) throws Exception {
        Path protocol = write(tmp.resolve("Greeter.avpr"), PROTOCOL);
        Path idl = write(tmp.resolve("Ping.avdl"), IDL);
        Path out = Files.createDirectories(tmp.resolve("out"));

        int exit = AvroMain.run(new String[] {"--out", out.toString(), protocol.toString(), idl.toString()});

        assertThat(exit).isZero();
        assertThat(out.resolve("com/acme/rpc/Greeter.java")).content().contains("interface Greeter");
        assertThat(out.resolve("com/acme/rpc/Greeting.java")).isRegularFile();
        assertThat(out.resolve("com/acme/idl/Ping.java")).content().contains("interface Ping");
        assertThat(out.resolve("com/acme/idl/Pong.java")).isRegularFile();
    }

    @Test
    void an_undefined_name_is_the_parsers_own_failure(@TempDir Path tmp) throws Exception {
        Path user = write(tmp.resolve("User.avsc"), USER);
        Path out = Files.createDirectories(tmp.resolve("out"));

        assertThatThrownBy(() -> AvroMain.run(new String[] {"--out", out.toString(), user.toString()}))
                .hasMessageContaining("com.acme.model.Role");
    }

    @Test
    void schemas_are_ordered_by_definition() throws Exception {
        assertThat(AvroMain.parseSchemas(AvroMain.class.getClassLoader(), List.of()))
                .isEmpty();
    }

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
