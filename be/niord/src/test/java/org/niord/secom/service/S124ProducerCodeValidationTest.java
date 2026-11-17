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
package org.niord.secom.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.s124.S124Service;
import org.niord.secom.pki.McpSecurityService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Producer-code validation gate: {@code niord.s124.producer-code} has no core default. A
 * malformed value fails every application at startup ({@link S124Service} validates it at
 * construction); a missing one fails a SECOM-enabled application at startup, because
 * {@link S124ExchangeSetService} resolves the code when it is created, and a SECOM-disabled
 * application only at the first S-124 generation, so a deployment without S-124 still boots.
 * With {@code niord.s124.enabled=false} neither service exists, so the code is neither read nor
 * validated, whatever it is.
 * <p>
 * Each intentional-failure case runs on a fresh throwaway context ({@link ApplicationContextRunner})
 * so the expected startup failures never enter the shared Spring test-context cache. The positive
 * case — {@code DK00} supplied by the real DK artifact — lives in the DK integration tests
 * ({@code CoreDefaultsPrecedenceITTest}, plus the S-124/SECOM ITs asserting the
 * {@code agencyResponsibleForProduction} element and the {@code 124DK00…} file names).
 */
class S124ProducerCodeValidationTest {

    private ApplicationContextRunner secomEnabledRunner() {
        return baseRunner().withPropertyValues("niord.secom.enabled=true");
    }

    /**
     * The constructor-dependency mocks are registered as plain singletons rather than bean
     * definitions: a Mockito mock of the real class still carries its {@code @Value}/
     * {@code @PersistenceContext} field annotations, so a definition-managed mock bean would
     * have the annotation post-processors try (and fail) to inject the mock's fields before
     * the producer-code validation is ever reached. Manually registered singletons skip
     * population entirely while still resolving as constructor dependencies. The two services
     * under test are real, so the property binding and the validation are exercised.
     */
    private ApplicationContextRunner baseRunner() {
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
                    // BaseService's @Inject EntityManager field, populated after the constructor
                    // has run - so it is only reached by the valid-code case
                    context.getBeanFactory().registerSingleton(
                            "entityManager", Mockito.mock(EntityManager.class));
                })
                .withUserConfiguration(S124Service.class, S124ExchangeSetService.class);
    }

    @Test
    void missingProducerCodeFailsSecomEnabledStartup() {
        secomEnabledRunner().run(context -> {
            assertNotNull(context.getStartupFailure(),
                    "SECOM-enabled startup must fail without a producer code");
            assertFailureChainMentions(context.getStartupFailure(), "niord.s124.producer-code is required");
        });
    }

    @Test
    void blankProducerCodeFailsSecomEnabledStartup() {
        secomEnabledRunner()
                .withPropertyValues("niord.s124.producer-code=   ")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "SECOM-enabled startup must fail on a blank producer code");
                    assertFailureChainMentions(context.getStartupFailure(), "niord.s124.producer-code is required");
                });
    }

    @Test
    void invalidProducerCodesFailStartup() {
        // Lowercase, wrong lengths, whitespace, punctuation and non-ASCII: rejected, never repaired.
        for (String invalid : new String[] {"dk00", "DK0", "DK000", "DK 0", "DK-0", "DKØ0"}) {
            secomEnabledRunner()
                    .withPropertyValues("niord.s124.producer-code=" + invalid)
                    .run(context -> {
                        assertNotNull(context.getStartupFailure(),
                                "SECOM-enabled startup must fail for producer code '" + invalid + "'");
                        assertFailureChainMentions(context.getStartupFailure(),
                                "not a valid IHO S-62 producer code");
                    });
        }
    }

    /** A malformed code is a configuration error whether or not SECOM is on: the GML endpoint uses it too. */
    @Test
    void invalidProducerCodeFailsStartupEvenWithSecomDisabled() {
        baseRunner()
                .withPropertyValues("niord.s124.producer-code=dk00")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "a malformed producer code must fail startup even with SECOM disabled");
                    assertFailureChainMentions(context.getStartupFailure(),
                            "not a valid IHO S-62 producer code");
                });
    }

    /**
     * SECOM-disabled applications never instantiate the exchange-set bean and boot without a
     * producer code; the S-124 service then refuses to generate, with the same actionable error.
     */
    @Test
    void secomDisabledContextBootsWithoutAProducerCodeButCannotGenerate() {
        baseRunner()
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "a SECOM-disabled context must start without a producer code");
                    assertTrue(context.getBeansOfType(S124ExchangeSetService.class).isEmpty(),
                            "the @ConditionalOnProperty bean must not exist when SECOM is disabled");
                    IllegalStateException e = assertThrows(IllegalStateException.class,
                            () -> context.getBean(S124Service.class).producerCode());
                    assertTrue(e.getMessage().contains("niord.s124.producer-code is required"), e.getMessage());
                });
    }

    /**
     * With S-124 off neither service exists, so a malformed code is never read: a deployment
     * without S-124 needs no producer code and is not held to one it carries.
     */
    @Test
    void invalidProducerCodeIsNotCheckedWhenS124IsOff() {
        baseRunner()
                .withPropertyValues("niord.s124.enabled=false", "niord.s124.producer-code=dk00")
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "with niord.s124.enabled=false a malformed producer code must not fail startup");
                    assertTrue(context.getBeansOfType(S124Service.class).isEmpty(),
                            "the S-124 generator must not exist with niord.s124.enabled=false");
                    assertTrue(context.getBeansOfType(S124ExchangeSetService.class).isEmpty(),
                            "the exchange-set bean must not exist with SECOM off");
                });
    }

    /** A valid code is used verbatim (no trimming, casing or other repair) by both services. */
    @Test
    void validProducerCodeIsUsedVerbatim() {
        secomEnabledRunner()
                .withPropertyValues("niord.s124.producer-code=DK00")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("DK00", context.getBean(S124Service.class).producerCode());
                    assertEquals("DK00", context.getBean(S124ExchangeSetService.class).producerCode,
                            "the exchange sets must use the very code the datasets carry");
                });
    }

    private static void assertFailureChainMentions(Throwable failure, String fragment) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(fragment)) {
                return;
            }
            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
        }
        fail("no exception in the startup-failure chain mentions '" + fragment + "': " + chain);
    }
}
