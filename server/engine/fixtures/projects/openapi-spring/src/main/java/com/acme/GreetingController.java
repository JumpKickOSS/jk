package com.acme;

import com.acme.api.GreetingsApi;
import com.acme.api.model.Greeting;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The one operation the contract declares; the interface it implements is generated from api/openapi.yaml. */
@RestController
public class GreetingController implements GreetingsApi {

    @Override
    public ResponseEntity<Greeting> getGreeting(String name) {
        return ResponseEntity.ok(new Greeting().message("Hello, " + name + "!"));
    }
}
