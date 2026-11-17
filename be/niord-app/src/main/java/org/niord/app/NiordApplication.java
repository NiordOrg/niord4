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
package org.niord.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The generic Niord Spring Boot application — the English-language reference deployment of
 * the {@code niord-base} library, with no country content of its own.
 *
 * <p>Wiring is identical to a country deployable's application class and to the IT suite's
 * {@code org.niord.test.NiordTestApplication}: a deployable contributes classpath resources
 * and deployment-tier dependencies, never beans. {@code ApplicationWiringTest} asserts that
 * equivalence rather than leaving it to a comment.
 *
 * <p>Component-scans the whole {@code org.niord} tree, scans {@code @Entity} classes for JPA,
 * and enables the cross-cutting facilities that replace the corresponding Quarkus extensions:
 * {@code @EnableScheduling} (quarkus-scheduler) and {@code @EnableAsync} (the simplified-async
 * extension / EJB {@code @Asynchronous}).
 */
@SpringBootApplication(scanBasePackages = "org.niord")
@EntityScan("org.niord")
@EnableScheduling
@EnableAsync
public class NiordApplication {

    public static void main(String[] args) {
        SpringApplication.run(NiordApplication.class, args);
    }
}
