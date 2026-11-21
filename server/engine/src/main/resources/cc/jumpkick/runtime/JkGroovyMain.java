// SPDX-License-Identifier: Apache-2.0

import groovy.lang.GroovyClassLoader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.StackTraceUtils;

/**
 * Runs a {@code jk tool run} Groovy script from its compiled classes, with the command line as its
 * {@code args}. The classpath holds a directory with {@code META-INF/jk-script}, naming the script
 * class, beside {@code script.jar}. The jar is loaded through a GroovyClassLoader rather than the
 * classpath, which is what {@code @Grab} needs to add jars at run time.
 */
public final class JkGroovyMain {

    private JkGroovyMain() {}

    public static void main(String[] args) throws IOException, URISyntaxException {
        ClassLoader app = JkGroovyMain.class.getClassLoader();
        URL marker = app.getResource("META-INF/jk-script");
        if (marker == null) {
            System.err.println("jk: no compiled Groovy script on the classpath");
            System.exit(70);
            return;
        }
        String main;
        try (InputStream in = marker.openStream()) {
            main = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        Path jar = Path.of(marker.toURI()).getParent().resolveSibling("script.jar");
        GroovyClassLoader loader = new GroovyClassLoader(app);
        loader.addURL(jar.toUri().toURL());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            InvokerHelper.runScript(loader.loadClass(main), args);
        } catch (Throwable t) {
            System.err.println("Caught: " + t);
            StackTraceUtils.deepSanitize(t).printStackTrace();
            System.exit(1);
        }
    }
}
