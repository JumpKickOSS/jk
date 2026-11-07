package $package$;

import io.helidon.logging.common.LogConfig;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;
import io.helidon.webserver.WebServer;

/**
 * Starts the service registry, which starts the web server and every endpoint in it.
 *
 * <p>{@code @Service.GenerateBinding} has the annotation processors write {@code ApplicationBinding}:
 * the application's services, bound at build time instead of discovered at startup.
 */
@Service.GenerateBinding
public final class Main {

    static {
        LogConfig.initClass();
    }

    private Main() {}

    public static void main(String[] args) {
        // Logging from logging.properties.
        LogConfig.configureRuntime();

        ServiceRegistryManager.start(ApplicationBinding.create());

        WebServer server = Services.get(WebServer.class);
        System.out.println("WEB server is up! http://localhost:" + server.port() + "/greet");
    }
}
