package $package$

import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin

val appModule = module {
    single { NoteRepository() }
}

fun Application.module() {
    install(Koin) { modules(appModule) }
    install(ContentNegotiation) { jackson() }
    val notes = getKoin().get<NoteRepository>()
    notes.init()
    noteRoutes(notes)
}
