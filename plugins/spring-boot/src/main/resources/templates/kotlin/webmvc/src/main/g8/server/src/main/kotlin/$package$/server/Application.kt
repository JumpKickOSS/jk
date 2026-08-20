package $package$.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["$package$"])
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
