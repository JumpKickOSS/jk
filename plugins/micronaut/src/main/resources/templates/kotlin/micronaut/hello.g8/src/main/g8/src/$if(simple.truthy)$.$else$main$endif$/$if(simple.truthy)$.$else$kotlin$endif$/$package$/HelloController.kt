package $package$

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

@Controller("/hello")
class HelloController {
    @Get
    fun index(): String = "Hello from Micronaut"
}
