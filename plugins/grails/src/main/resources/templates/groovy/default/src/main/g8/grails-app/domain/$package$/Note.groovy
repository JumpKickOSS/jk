package $package$

class Note {
    String title
    String body

    static constraints = {
        title blank: false
        body nullable: true
    }
}
