/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.promulgation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationServiceVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterMessagePromulgationVo;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PromulgationManager} message life-cycle orchestration, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>This is the gap-coverage sibling of {@link PromulgationTypeServiceITTest}: that class exercises the
 * persistence-level {@link PromulgationTypeService}; this class exercises the higher-level
 * {@link PromulgationManager}, which fans message value objects out to the registered promulgation
 * services (NAVTEX, Audio, Twitter, SafetyNET) and sorts/filters the resulting promulgation list.
 *
 * <p>The seed database (see {@code niord/src/test/resources/db/niord-test-seed.sql}) contains exactly
 * three promulgation types — {@code navtex} (priority 1) and {@code audio} (priority 50) <em>active</em>,
 * {@code twitter} (priority 100) <em>inactive</em> since the owner's ruling of 2026-11-04 — each with an
 * empty message-type filter and {@code requirement = NULL}.
 * Tests that need other shapes (inactive types, explicit requirements, a failing script resource) create
 * their own {@code it-}-prefixed types via {@link PromulgationTypeService}.
 *
 * <p>The {@link PromulgationManager} is CDI-driven: the four promulgation services register themselves at
 * application startup through a {@code @Observes StartupEvent} hook, so the service registry only exists
 * inside a running Quarkus container. That is why every behavioural method here is a {@code @QuarkusTest}
 * rather than a plain unit test.
 *
 * <p>Tests that write to the database are wrapped in {@link TestTransaction} so all writes are rolled back,
 * keeping the shared seed database pristine and the tests independent; {@code it-}-prefixed type ids do not
 * occur in the seed data. Tests that only build in-memory value objects (or only read the service registry)
 * need no transaction.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PromulgationManagerGapITTest extends ITTest {

    private static final String NAVTEX = "navtex";
    private static final String AUDIO = "audio";
    private static final String TWITTER = "twitter";

    @Autowired
    PromulgationManager promulgationManager;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds and persists an {@code it-}-prefixed promulgation type via the (transactional) service. */
    private PromulgationType createType(String typeId, String serviceId, int priority,
                                        boolean active, PromulgationType.Requirement requirement,
                                        List<String> scriptResourcePaths) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setServiceId(serviceId);
        vo.setName(typeId);
        vo.setPriority(priority);
        vo.setActive(active);
        vo.setRequirement(requirement);
        vo.setLanguage("en");
        if (scriptResourcePaths != null) {
            vo.setScriptResourcePaths(new ArrayList<>(scriptResourcePaths));
        }
        return promulgationTypeService.createPromulgationType(new PromulgationType(vo));
    }

    /**
     * Builds a detached message-promulgation value object that carries a {@link PromulgationTypeVo}
     * with the given {@code typeId}/{@code priority}. The concrete subtype is unimportant for the
     * manager methods exercised here — they only read the type id, priority and requirement.
     */
    private TwitterMessagePromulgationVo promulgationVo(String typeId, int priority) {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(typeId);
        typeVo.setPriority(priority);
        TwitterMessagePromulgationVo p = new TwitterMessagePromulgationVo(typeVo);
        return p;
    }

    private List<String> typeIds(SystemMessageVo message) {
        if (message.getPromulgations() == null) {
            return List.of();
        }
        return message.getPromulgations().stream()
                .map(p -> p.getType().getTypeId())
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // onLoadSystemMessage  (strict filtering)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void onLoadSystemMessageStrictRemovesInactiveTypeButKeepsActiveType() throws PromulgationException {
        // An it- type that is NOT active: it will never appear in the active-type set for the domain,
        // so a strict load must drop any promulgation referencing it.
        createType("it-load-inactive", TWITTER, 200, false, null, null);

        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        List<BaseMessagePromulgationVo> promulgations = new ArrayList<>();
        promulgations.add(promulgationVo(NAVTEX, 1));               // seeded + active -> kept
        promulgations.add(promulgationVo("it-load-inactive", 200)); // inactive       -> removed
        message.setPromulgations(promulgations);

        promulgationManager.onLoadSystemMessage(message, true);

        List<String> ids = typeIds(message);
        assertFalse(ids.contains("it-load-inactive"),
                "strict onLoadSystemMessage must remove a promulgation whose type is not active");
        assertTrue(ids.contains(NAVTEX),
                "strict onLoadSystemMessage must keep a promulgation whose type is active (navtex)");
    }

    // ------------------------------------------------------------------------------------------- //
    // onLoadSystemMessage  (priority sorting)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void onLoadSystemMessageSortsPromulgationsAscendingByTypePriority() throws PromulgationException {
        // Start with the promulgations deliberately out of priority order. After a strict load, the
        // two seeded active services (navtex=1, audio=50) each ensure a promulgation exists, the
        // promulgation of the seeded inactive twitter (priority 100; inactive since the owner's ruling of
        // 2026-11-04) is dropped, and the manager sorts the list ascending by PromulgationType.priority.
        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        List<BaseMessagePromulgationVo> promulgations = new ArrayList<>();
        promulgations.add(promulgationVo(TWITTER, 100));
        promulgations.add(promulgationVo(NAVTEX, 1));
        message.setPromulgations(promulgations);

        promulgationManager.onLoadSystemMessage(message, true);

        List<String> ids = typeIds(message);
        assertTrue(ids.contains(NAVTEX) && ids.contains(AUDIO),
                "strict load must materialise a promulgation for every active seeded type");
        assertFalse(ids.contains(TWITTER),
                "strict load must drop the promulgation of the seeded inactive twitter");
        assertTrue(ids.indexOf(NAVTEX) < ids.indexOf(AUDIO),
                "navtex (priority 1) must be sorted before audio (priority 50)");
    }

    // ------------------------------------------------------------------------------------------- //
    // checkDefaultPromulgationRequirements
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void checkDefaultPromulgationRequirementsTurnsOnMandatoryAndDefaultButNotOptional() throws PromulgationException {
        createType("it-req-mandatory", TWITTER, 301, true, PromulgationType.Requirement.MANDATORY, null);
        createType("it-req-default", TWITTER, 302, true, PromulgationType.Requirement.DEFAULT, null);
        createType("it-req-optional", TWITTER, 303, true, PromulgationType.Requirement.OPTIONAL, null);

        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        List<BaseMessagePromulgationVo> promulgations = new ArrayList<>();
        promulgations.add(promulgationVo("it-req-mandatory", 301));
        promulgations.add(promulgationVo("it-req-default", 302));
        promulgations.add(promulgationVo("it-req-optional", 303));
        message.setPromulgations(promulgations);

        promulgationManager.checkDefaultPromulgationRequirements(message);

        assertTrue(message.promulgation("it-req-mandatory").isPromulgate(),
                "a MANDATORY type must default to promulgate=true");
        assertTrue(message.promulgation("it-req-default").isPromulgate(),
                "a DEFAULT type must default to promulgate=true");
        assertFalse(message.promulgation("it-req-optional").isPromulgate(),
                "an OPTIONAL type must default to promulgate=false");
    }

    // ------------------------------------------------------------------------------------------- //
    // generateMessagePromulgation  (replace + re-sort)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateMessagePromulgationReplacesExistingSameTypeAndReSorts() throws PromulgationException {
        // The message already holds a navtex promulgation (priority 1) and a stale twitter
        // promulgation (priority 100). Generating twitter twice must leave exactly one twitter
        // promulgation, and the list must be re-sorted ascending by priority (navtex, then twitter).
        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        List<BaseMessagePromulgationVo> promulgations = new ArrayList<>();
        promulgations.add(promulgationVo(NAVTEX, 1));
        TwitterMessagePromulgationVo stale = promulgationVo(TWITTER, 100);
        stale.setTweet("stale tweet");
        promulgations.add(stale);
        message.setPromulgations(promulgations);

        BaseMessagePromulgationVo first = promulgationManager.generateMessagePromulgation(TWITTER, message);
        assertNotNull(first, "generateMessagePromulgation must return the generated promulgation");
        BaseMessagePromulgationVo second = promulgationManager.generateMessagePromulgation(TWITTER, message);

        long twitterCount = message.getPromulgations().stream()
                .filter(p -> TWITTER.equals(p.getType().getTypeId()))
                .count();
        assertEquals(1L, twitterCount,
                "regenerating twitter must replace - not duplicate - the existing twitter promulgation");

        // The surviving twitter promulgation is the most recently generated one (not the stale tweet).
        TwitterMessagePromulgationVo surviving = (TwitterMessagePromulgationVo)
                message.promulgation(TWITTER);
        assertEquals(second, surviving,
                "the surviving twitter promulgation must be the one returned by the last generate call");
        assertNull(surviving.getTweet(),
                "the regenerated twitter promulgation must not carry the stale tweet text");

        List<String> ids = typeIds(message);
        assertTrue(ids.indexOf(NAVTEX) < ids.indexOf(TWITTER),
                "after regeneration the list must be re-sorted: navtex (1) before twitter (100)");
    }

    // ------------------------------------------------------------------------------------------- //
    // generateMessagePromulgation  (script-resource failure fails Generate, PROM15)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateMessagePromulgationFailsWhenAScriptResourceFails() {
        // An it- twitter-backed type that points at a non-existent script resource. The script adjusts the
        // broadcast text, so its failure fails Generate for the type, naming it, with the script's error as cause
        createType("it-script", TWITTER, 305, true, null,
                List.of("it/does-not-exist.js"));

        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        message.setPromulgations(new ArrayList<>());

        PromulgationException e = assertThrows(PromulgationException.class,
                () -> promulgationManager.generateMessagePromulgation("it-script", message));
        assertTrue(e.getMessage().contains("it-script"), "the error names the type: " + e.getMessage());
        assertNotNull(e.getCause(), "the script's own error is the cause");
    }

    @Test
    @Transactional
    void generateMessagePromulgationNamesATypeThatDoesNotExist() {
        // The lookup answers null for an unknown type id; the manager refuses it by name rather than
        // failing on the null with a NullPointerException. The refusal is unchecked, as the
        // NullPointerException was, so the transaction of a message save still rolls back on it
        SystemMessageVo message = new SystemMessageVo();
        message.setPromulgations(new ArrayList<>());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> promulgationManager.generateMessagePromulgation("it-no-such-type", message));
        assertTrue(e.getMessage().contains("it-no-such-type"), e.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // A type whose service is not registered
    // ------------------------------------------------------------------------------------------- //

    /**
     * A promulgation type whose service id names no registered service (a type row that outlived its service, or one
     * imported from a deployment with a service of its own). The reset before and the clean-up after a template
     * execution skip it with a warning, as saving the message does, and Generate leaves the message's promulgation of
     * the type as it was. FAILS on the code that called the missing service: each of the three failed with a
     * NullPointerException, so every template execution on a message carrying the type answered 500.
     */
    @Test
    @Transactional
    void aTypeWhoseServiceIsNotRegisteredIsSkippedWithAWarning() throws PromulgationException {
        createType("it-no-service", "it-no-such-service", 310, true, null, null);

        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        List<BaseMessagePromulgationVo> promulgations = new ArrayList<>();
        TwitterMessagePromulgationVo orphan = promulgationVo("it-no-service", 310);
        orphan.setTweet("it-kept");
        promulgations.add(orphan);
        message.setPromulgations(promulgations);

        Logger logger = (Logger) LoggerFactory.getLogger(PromulgationManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        BaseMessagePromulgationVo generated;
        try {
            assertDoesNotThrow(() -> promulgationManager.resetMessagePromulgations(message), "the reset");
            assertDoesNotThrow(() -> promulgationManager.messagePromulgationGenerated(message), "the clean-up");
            generated = promulgationManager.generateMessagePromulgation("it-no-service", message);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(orphan, generated, "Generate answers the type's promulgation as it was");
        assertEquals("it-kept", ((TwitterMessagePromulgationVo) message.promulgation("it-no-service")).getTweet(),
                "and leaves it unchanged");
        assertEquals(List.of("it-no-service"), typeIds(message), "the message keeps its promulgation");
        long warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage()
                        .equals("Unable to instantiate promulgation service for type it-no-service"))
                .count();
        assertEquals(3L, warnings, "each of the three logs the type it skipped");
    }

    // ------------------------------------------------------------------------------------------- //
    // onCopyMessage  (id reset)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void onCopyMessageResetsAllPromulgationIdsToNull() throws PromulgationException {
        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        List<BaseMessagePromulgationVo> promulgations = new ArrayList<>();
        TwitterMessagePromulgationVo navtexP = promulgationVo(NAVTEX, 1);
        navtexP.setId(111);
        TwitterMessagePromulgationVo twitterP = promulgationVo(TWITTER, 100);
        twitterP.setId(222);
        promulgations.add(navtexP);
        promulgations.add(twitterP);
        message.setPromulgations(promulgations);

        promulgationManager.onCopyMessage(message);

        assertTrue(message.getPromulgations().stream().allMatch(p -> p.getId() == null),
                "onCopyMessage must reset the id of every copied promulgation to null");
    }

    // ------------------------------------------------------------------------------------------- //
    // promulgationServices  (registry -> human service names)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void promulgationServicesReturnsHumanServiceNamesPerServiceId() {
        List<PromulgationServiceVo> services = promulgationManager.promulgationServices();

        Map<String, String> nameByServiceId = services.stream()
                .collect(Collectors.toMap(PromulgationServiceVo::getServiceId,
                        PromulgationServiceVo::getServiceName));

        assertEquals("NAVTEX mailing list", nameByServiceId.get(NAVTEX),
                "the navtex service must expose its human-readable name");
        assertEquals("Twitter", nameByServiceId.get(TWITTER),
                "the twitter service must expose its human-readable name");
        assertEquals("SafetyNET mailing list", nameByServiceId.get("safetynet"),
                "the safetynet service must expose its human-readable name");
        assertEquals("Audio mailing list", nameByServiceId.get(AUDIO),
                "the audio service must expose its human-readable name");
    }
}
