// SPDX-License-Identifier: Apache-2.0
package demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot fixture: one MVC endpoint, packaged as a Boot jar and as flat fat jars. */
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
