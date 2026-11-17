/*
 * Copyright 2026 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Test-side twin of a deployable's Spring Boot application class, booted by the
 * integration-test suite ({@link ITTest}). The niord-base library is not bootable on
 * its own — deployables such as {@code niord-dk} supply the {@code @SpringBootApplication}
 * class — so the IT suite supplies this stand-in with the exact same wiring as
 * {@code org.niord.dk.NiordDkApplication}: component-scan of the whole {@code org.niord}
 * tree, JPA {@code @Entity} scanning, and the cross-cutting scheduling/async facilities.
 * Keep the two annotation sets in lock-step, so what the ITs boot is bean-for-bean what
 * the deployable runs (a deployable adds only classpath resources, no beans).
 *
 * <p>Outside the suite, one caller boots this class: {@link SeedRegen} (run by
 * {@code niord/scripts/regen-test-seed-postgres.sh}) starts it on the test classpath, without its
 * {@code defaults/} (the stand-in da pack), its root-level {@code *.properties} and niord.json, with
 * {@code SpringApplication.run} to bootstrap the database
 * the fixture is dumped from (so the fixture is made by the application the suite boots, with no
 * deployable involved). Neither SeedRegen nor the
 * suite calls {@code main} — {@code @SpringBootTest} starts the suite's context.
 */
@SpringBootApplication(scanBasePackages = "org.niord")
@EntityScan("org.niord")
@EnableScheduling
@EnableAsync
public class NiordTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(NiordTestApplication.class, args);
    }
}
