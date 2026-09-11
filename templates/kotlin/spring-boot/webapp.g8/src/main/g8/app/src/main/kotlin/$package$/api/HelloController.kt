package $package$.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** The API lives under `/api`: the one prefix the SPA fallback never answers for. */
@RestController
@RequestMapping("/api")
class HelloController {

    data class Greeting(val message: String)

    @GetMapping("/hello")
    fun hello(@RequestParam(defaultValue = "world") name: String) = Greeting("Hello, " + name + "!")
}
