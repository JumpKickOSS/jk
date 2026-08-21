package $package$.data.note

import java.time.LocalDateTime

data class Note(val id: Long, val body: String, val createdAt: LocalDateTime)
