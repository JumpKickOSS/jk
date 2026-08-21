package $package$.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "$package$")
@EntityScan("$package$.data")
@EnableJpaRepositories("$package$.data")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
