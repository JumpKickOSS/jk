package $package$.note

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.validation.constraints.NotBlank

// `open` + default constructor by hand — no kotlin-jpa/no-arg compiler plugins required.
@Entity
open class Note() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @field:NotBlank
    open var text: String = ""

    constructor(text: String) : this() {
        this.text = text
    }
}
