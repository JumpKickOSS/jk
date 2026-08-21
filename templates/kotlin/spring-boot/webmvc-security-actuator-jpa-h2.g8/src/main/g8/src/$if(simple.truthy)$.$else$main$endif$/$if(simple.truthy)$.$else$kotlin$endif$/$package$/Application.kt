package $package$

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// `open`: @SpringBootApplication is a @Configuration Spring subclasses at runtime —
// no kotlin-spring compiler plugin needed when the class opts in itself.
@SpringBootApplication
open class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
