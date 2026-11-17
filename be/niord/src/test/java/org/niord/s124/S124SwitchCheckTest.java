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
package org.niord.s124;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.S124ExchangeSetService;
import org.niord.test.NiordTestApplication;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import jakarta.persistence.EntityManager;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The S-124 and SECOM switches ({@link S124SwitchCheck}, the S-124 opt-out): start-up refuses S-124 off
 * with SECOM on, with a message naming both keys, before the exchange-set bean can fail on the
 * absent generator; every other combination boots; and the helpers that {@code /rest/settings/web}
 * reports from agree with the bean conditions value for value.
 * <p>
 * Each case runs on a fresh throwaway context ({@link ApplicationContextRunner}), so the expected
 * start-up failure never enters the shared Spring test-context cache. The constructor-dependency
 * mocks are plain singletons, as in {@code S124ProducerCodeValidationTest}: a definition-managed
 * mock bean would have its annotated fields injected and fail first.
 */
class S124SwitchCheckTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton(
                            "mcpSecurityService", Mockito.mock(McpSecurityService.class));
                    context.getBeanFactory().registerSingleton(
                            "messageService", Mockito.mock(MessageService.class));
                    context.getBeanFactory().registerSingleton(
                            "messageSeriesService", Mockito.mock(MessageSeriesService.class));
                    context.getBeanFactory().registerSingleton(
                            "dictionaryService", Mockito.mock(DictionaryService.class));
                    context.getBeanFactory().registerSingleton(
                            "niordApp", Mockito.mock(NiordApp.class));
                    context.getBeanFactory().registerSingleton(
                            "entityManager", Mockito.mock(EntityManager.class));
                })
                .withUserConfiguration(S124SwitchCheck.class, S124Service.class, S124ExchangeSetService.class);
    }

    @Test
    void s124OffWithSecomOnIsRefusedNamingBothKeys() {
        runner()
                .withPropertyValues("niord.s124.enabled=false", "niord.secom.enabled=true",
                        "niord.s124.producer-code=DK00")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "S-124 off with SECOM on must fail start-up");
                    Throwable refusal = failureMentioning(failure, "niord.s124.enabled");
                    assertTrue(refusal.getMessage().contains("niord.secom.enabled"), refusal.getMessage());
                    for (Throwable t = failure; t != null; t = t.getCause()) {
                        assertTrue(!(t instanceof UnsatisfiedDependencyException),
                                "the readable refusal must come before the unsatisfied dependency: " + t);
                    }
                });
    }

    @Test
    void s124OffWithSecomOffBoots() {
        runner()
                .withPropertyValues("niord.s124.enabled=false", "niord.secom.enabled=false")
                .run(context -> assertNull(context.getStartupFailure()));
        runner()
                .withPropertyValues("niord.s124.enabled=false")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void bothPropertiesMissingBoots() {
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(S124Service.class).size() == 1, "S-124 is on by default");
            assertTrue(context.getBeansOfType(S124ExchangeSetService.class).isEmpty(), "SECOM is off by default");
        });
    }

    @Test
    void s124OnWithSecomOnIsNotRefused() {
        runner()
                .withPropertyValues("niord.s124.enabled=true", "niord.secom.enabled=true",
                        "niord.s124.producer-code=DK00")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(S124ExchangeSetService.class).size());
                });
    }

    @Test
    void theHelpersAgreeWithTheBeanConditions() {
        // null = the key is missing; "yes" is not true to @ConditionalOnProperty(havingValue = "true")
        for (String value : new String[] {null, "true", "TRUE", "false", "yes"}) {
            ApplicationContextRunner s124 = value == null ? runner() : runner().withPropertyValues("niord.s124.enabled=" + value);
            s124.run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(!context.getBeansOfType(S124Service.class).isEmpty(),
                        S124SwitchCheck.s124Enabled(context.getEnvironment()), "niord.s124.enabled=" + value);
            });

            ApplicationContextRunner secom = (value == null ? runner() : runner().withPropertyValues("niord.secom.enabled=" + value))
                    .withPropertyValues("niord.s124.producer-code=DK00");
            secom.run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(!context.getBeansOfType(S124ExchangeSetService.class).isEmpty(),
                        S124SwitchCheck.secomEnabled(context.getEnvironment()), "niord.secom.enabled=" + value);
            });
        }
    }

    @Test
    void theApplicationScanRegistersTheCheck() {
        // The cases above register the check by hand; the applications have only the org.niord
        // scan (NiordTestApplication here, NiordApplication held equal by ApplicationWiringTest)
        String[] scanned = NiordTestApplication.class.getAnnotation(SpringBootApplication.class).scanBasePackages();
        String pkg = S124SwitchCheck.class.getPackageName();
        assertTrue(Arrays.stream(scanned).anyMatch(base -> pkg.equals(base) || pkg.startsWith(base + ".")),
                "the application scan must cover " + pkg + ": " + Arrays.toString(scanned));
        assertTrue(new ClassPathScanningCandidateComponentProvider(true).findCandidateComponents(pkg).stream()
                        .anyMatch(definition -> S124SwitchCheck.class.getName().equals(definition.getBeanClassName())),
                "the component scan of " + pkg + " must find S124SwitchCheck");
    }

    /** The first exception in the failure's cause chain whose message mentions the fragment */
    private static Throwable failureMentioning(Throwable failure, String fragment) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(fragment)) {
                return t;
            }
            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
        }
        fail("no exception in the startup-failure chain mentions '" + fragment + "': " + chain);
        return null;
    }
}
