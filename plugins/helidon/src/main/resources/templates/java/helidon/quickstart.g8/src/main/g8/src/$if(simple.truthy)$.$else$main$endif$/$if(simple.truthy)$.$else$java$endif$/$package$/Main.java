package $package$;

import io.helidon.config.Config;
import io.helidon.logging.common.LogConfig;
import io.helidon.service.registry.Services;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;

/** Starts the web server on the port {@code server.port} in {@code application.yaml} names. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Logging from logging.properties.
        LogConfig.configureRuntime();

        // application.yaml, overlaid by system properties and environment variables (SERVER_PORT).
        Config config = Services.get(Config.class);

        WebServer server = WebServer.builder()
                .config(config.get("server"))
                .routing(Main::routing)
                .build()
                .start();

        System.out.println("WEB server is up! http://localhost:" + server.port() + "/greet");
    }

    /** The routes; the observability endpoints under /observe join from the classpath. */
    static void routing(HttpRouting.Builder routing) {
        routing.register("/greet", new GreetService())$if(tracing.truthy)$
                .register("/traced", new TracedService())$endif$;
    }
}
