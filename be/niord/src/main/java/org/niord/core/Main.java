package org.niord.core;

/**
 * The Main Quarkus Runner.
 *
 * @author Nikolaos Vastardis (email: Nikolaos.Vastardis@gla-rad.org)
 */
// PORT: this was the Quarkus launcher (@QuarkusMain + Quarkus.run(NiordApp.class, args)).
// Under Spring Boot 4 the application entry point (the @SpringBootApplication class with
// SpringApplication.run(...)) lives in each deployable (niord-app, a country deployable), so the Quarkus launcher is
// reduced to a no-op utility here. NiordApp is now a plain Spring @Component, not a
// QuarkusApplication.
public class Main {

    private Main() {
    }

}
