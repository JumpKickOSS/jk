package $package$;

import io.helidon.common.context.Contexts;
import io.helidon.config.Config;
import io.helidon.dbclient.DbClient;
$if(health.truthy)$
import io.helidon.dbclient.health.DbClientHealthCheck;
$endif$
import io.helidon.logging.common.LogConfig;
import io.helidon.service.registry.Services;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
$if(health.truthy)$
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.health.HealthObserver;
$endif$

/** Starts the web server on the port {@code server.port} in {@code application.yaml} names. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // Logging from logging.properties.
        LogConfig.configureRuntime();

        // application.yaml, overlaid by system properties and environment variables (SERVER_PORT).
        Config config = Services.get(Config.class);

        // One database client for the application, from the db section; PokemonService finds it here.
        DbClient dbClient = DbClient.create(config.get("db"));
        Contexts.globalContext().register(dbClient);

        WebServer server = WebServer.builder()
                .config(config.get("server"))
$if(health.truthy)$
                // /observe/health also runs the db.health-check statement against the database.
                .addFeature(ObserveFeature.builder()
                        .addObserver(HealthObserver.builder()
                                .addCheck(DbClientHealthCheck.create(dbClient, config.get("db.health-check")))
                                .build())
                        .build())
$endif$
                .routing(Main::routing)
                .build()
                .start();

        System.out.println("WEB server is up! http://localhost:" + server.port() + "/db");
    }

    /** The routes; the observability endpoints under /observe join from the classpath. */
    static void routing(HttpRouting.Builder routing) {
        routing.register("/greet", new GreetService()).register("/db", new PokemonService());
    }
}
