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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.niord.test.NiordTestApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.lang.annotation.Annotation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wiring of {@link NiordApplication} against the application class the integration-test
 * suite boots, {@code org.niord.test.NiordTestApplication} (supplied by niord-base's test-jar).
 *
 * <p><strong>Read what this does and does not buy, before trusting it.</strong> It compares four
 * named dimensions — {@code scanBasePackages}, {@code @EntityScan}, {@code @EnableScheduling},
 * {@code @EnableAsync} — and nothing else. It is a regression guard on the axes that have drifted
 * or would hurt most, <em>not</em> a proof of equivalence. Specifically, it does NOT catch:
 *
 * <ul>
 *   <li>an annotation added to one class but not the other ({@code @EnableCaching},
 *       {@code @ConfigurationPropertiesScan}, {@code @ImportRuntimeHints}, …) — the new one is
 *       simply not among the four things compared, so the test stays green;</li>
 *   <li>a change to any other {@code @SpringBootApplication} attribute, e.g. {@code exclude};</li>
 *   <li>{@code org.niord.dk.NiordDkApplication} at all. niord-app and niord-dk are sibling modules
 *       that both depend on niord-base, so neither is on the other's classpath. Two of the three
 *       application classes are covered here; the third is on human review.</li>
 * </ul>
 *
 * <p>Why the check lives here rather than in the base module's own suite: the ITs are in
 * {@code niord}, and {@code niord-app} depends on {@code niord}, so booting the real application
 * class from those tests would make the reactor cyclic ({@code niord -> niord-app -> niord}). That
 * is also why {@code NiordTestApplication} still exists at all and was not deleted.
 *
 * <p><strong>Intended replacement.</strong> Testing for drift is the weaker half of the fix; the
 * structural one is to make drift impossible — extract the shared wiring into a composed annotation
 * (or a {@code @Configuration} class) in niord-base main, and have all three application classes
 * carry only that. niord-base is already a dependency of every deployable, so this introduces no
 * cycle and needs no new test module. This test should be deleted in the same change.
 */
class ApplicationWiringTest {

    @Test
    @DisplayName("scans the same base packages as the application class the IT suite boots")
    void componentScanMatchesTestApplication() {
        SpringBootApplication app = annotation(NiordApplication.class, SpringBootApplication.class);
        SpringBootApplication testApp = annotation(NiordTestApplication.class, SpringBootApplication.class);

        assertThat(app.scanBasePackages())
                .as("component-scanned packages")
                .containsExactly("org.niord")
                .isEqualTo(testApp.scanBasePackages());
    }

    @Test
    @DisplayName("scans the same JPA entity packages as the application class the IT suite boots")
    void entityScanMatchesTestApplication() {
        EntityScan app = annotation(NiordApplication.class, EntityScan.class);
        EntityScan testApp = annotation(NiordTestApplication.class, EntityScan.class);

        assertThat(app.value())
                .as("@Entity-scanned packages")
                .containsExactly("org.niord")
                .isEqualTo(testApp.value());
    }

    @Test
    @DisplayName("enables the same cross-cutting facilities as the application class the IT suite boots")
    void crossCuttingFacilitiesMatchTestApplication() {
        // @EnableScheduling replaces quarkus-scheduler, @EnableAsync the simplified-async extension.
        // Dropping either from one class and not the other silently changes behaviour rather than
        // failing a build, which is exactly what this guards.
        assertThat(NiordApplication.class.isAnnotationPresent(EnableScheduling.class))
                .as("@EnableScheduling present")
                .isTrue()
                .isEqualTo(NiordTestApplication.class.isAnnotationPresent(EnableScheduling.class));

        assertThat(NiordApplication.class.isAnnotationPresent(EnableAsync.class))
                .as("@EnableAsync present")
                .isTrue()
                .isEqualTo(NiordTestApplication.class.isAnnotationPresent(EnableAsync.class));
    }

    private static <A extends Annotation> A annotation(Class<?> type, Class<A> annotationType) {
        A annotation = type.getAnnotation(annotationType);
        assertThat(annotation)
                .as("@%s on %s", annotationType.getSimpleName(), type.getSimpleName())
                .isNotNull();
        return annotation;
    }
}
