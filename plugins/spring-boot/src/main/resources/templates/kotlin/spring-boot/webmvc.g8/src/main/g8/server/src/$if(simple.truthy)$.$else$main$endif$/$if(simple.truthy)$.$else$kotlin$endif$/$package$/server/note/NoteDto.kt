package $package$.server.note

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class NoteView(val id: Long, val body: String, val createdAt: LocalDateTime)

data class NewNote(
    @field:NotBlank @field:Size(max = 2000) val body: String,
)
