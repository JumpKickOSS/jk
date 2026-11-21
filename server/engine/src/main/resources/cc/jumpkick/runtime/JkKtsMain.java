// SPDX-License-Identifier: Apache-2.0

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Runs a {@code jk tool run} Kotlin script from its compiled classes, with the command line as its
 * {@code args}. {@code META-INF/jk-script} on the classpath names the script class, a
 * {@code ScriptTemplateWithArgs} whose body runs in its {@code (String[])} constructor. The compiled
 * class's own {@code main} needs metadata only a scripting host writes, so it is not used.
 */
public final class JkKtsMain {

    private JkKtsMain() {}

    public static void main(String[] args) throws Throwable {
        ClassLoader app = JkKtsMain.class.getClassLoader();
        URL marker = app.getResource("META-INF/jk-script");
        if (marker == null) {
            System.err.println("jk: no compiled Kotlin script on the classpath");
            System.exit(70);
            return;
        }
        String main;
        try (InputStream in = marker.openStream()) {
            main = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        try {
            Class.forName(main, true, app).getConstructor(String[].class).newInstance((Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
