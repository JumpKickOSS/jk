package $package$

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration

class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        // Packaged jars default to production, but the grails.env sysprop outranks
        // GRAILS_ENV in Grails' Environment — only set it when the user set neither,
        // or `GRAILS_ENV=development java -jar app.jar` still boots production (JK-1223).
        if (!System.getProperty('grails.env') && !System.getenv('GRAILS_ENV')) {
            System.setProperty('grails.env', 'production')
        }
        GrailsApp.run(Application, args)
    }
}
