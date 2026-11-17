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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterMessagePromulgationVo;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap integration tests for the <em>read / query</em> side of
 * {@link PromulgationTypeService} — specifically the methods that are exercised
 * at message-promulgation time but were not covered by the sibling
 * {@code PromulgationTypeServiceITTest} (which focuses on CRUD).
 *
 * <p>Covered methods:
 * <ul>
 *   <li>{@link PromulgationTypeService#getActivePromulgationTypes(Type)} — active types for the
 *       current (here: null) domain, ordered by ascending priority, with the in-code
 *       message-type filter ({@code messageType == null || messageTypes.isEmpty() ||
 *       messageTypes.contains(messageType)}).</li>
 *   <li>{@link PromulgationTypeService#getPromulgationTypes(SystemMessageVo, boolean)} — the
 *       {@code onlyPromulgating} flag and the empty-input short-circuits.</li>
 * </ul>
 *
 * <p>The seed database (see {@code niord/src/test/resources/db/niord-test-seed.sql}) contains
 * exactly three promulgation types: {@code navtex} (priority 1) and {@code audio} (priority 50) active,
 * {@code twitter} (priority 100) inactive since the owner's ruling of 2026-11-04. None of the seeded types declare any
 * {@code messageTypes}, so the in-code message-type filter never excludes them. These tests
 * read those rows and additionally create their own {@code it-}-prefixed types.
 *
 * <p>Each test is wrapped in {@link TestTransaction}, so every write is rolled back, keeping the
 * shared seed database pristine and the tests independent. Because the tests run without an
 * authenticated domain context, {@code domainService.currentDomain()} resolves to {@code null}
 * and the domain join in {@code getActivePromulgationTypes} is not applied — exactly the
 * behaviour under test here.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PromulgationTypeServiceGapITTest extends ITTest {

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached {@link PromulgationType} from a {@link PromulgationTypeVo} with the given fields. */
    private PromulgationType newType(String typeId, String serviceId, String name, int priority,
                                     Boolean active, PromulgationType.Requirement requirement) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setServiceId(serviceId);
        vo.setName(name);
        vo.setPriority(priority);
        vo.setActive(active);
        vo.setRequirement(requirement);
        // A non-blank language keeps updatePromulgationType from nulling it via defaultIfBlank.
        vo.setLanguage("en");
        return new PromulgationType(vo);
    }

    /** A message carrying a single navtex promulgation with the given promulgate flag. */
    private SystemMessageVo messageWithNavtexPromulgation(boolean promulgate) {
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("navtex");
        NavtexMessagePromulgationVo navtex = new NavtexMessagePromulgationVo(type);
        navtex.setPromulgate(promulgate);

        SystemMessageVo msg = new SystemMessageVo();
        msg.checkCreatePromulgations().add(navtex);
        return msg;
    }

    // ------------------------------------------------------------------------------------------- //
    // getActivePromulgationTypes(Type)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getActivePromulgationTypesWithNullTypeReturnsActiveSeedAscendingPriority() {
        // messageType == null short-circuits the in-code filter, so every active type is returned.
        List<String> typeIds = promulgationTypeService.getActivePromulgationTypes((Type) null).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertTrue(typeIds.contains("navtex"), "active query must include seeded 'navtex'");
        assertTrue(typeIds.contains("audio"), "active query must include seeded 'audio'");
        assertFalse(typeIds.contains("twitter"),
                "active query must leave out seeded 'twitter', inactive since the owner's ruling of 2026-11-04");

        // Priorities are navtex(1) < audio(50): ordering must follow priority ascending.
        assertTrue(typeIds.indexOf("navtex") < typeIds.indexOf("audio"),
                "navtex (priority 1) must order before audio (priority 50)");
    }

    @Test
    @Transactional
    void getActivePromulgationTypesExcludesInactiveTypes() {
        // An inactive type must never be returned, even when messageType is null.
        PromulgationType inactive = newType("it-gap-inactive", "navtex", "IT Gap Inactive", 25,
                Boolean.FALSE, PromulgationType.Requirement.OPTIONAL);
        promulgationTypeService.createPromulgationType(inactive);
        em.flush();

        List<String> typeIds = promulgationTypeService.getActivePromulgationTypes((Type) null).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertFalse(typeIds.contains("it-gap-inactive"),
                "an inactive promulgation type must be excluded from getActivePromulgationTypes");
        assertTrue(typeIds.contains("navtex"), "active seeded types must still be present");
    }

    @Test
    @Transactional
    void getActivePromulgationTypesFiltersOnMessageTypeMembership() {
        // Active type whose messageTypes is non-empty but does NOT contain the requested type -> excluded.
        PromulgationType restricted = newType("it-gap-restricted", "navtex", "IT Gap Restricted", 30,
                Boolean.TRUE, PromulgationType.Requirement.OPTIONAL);
        restricted.getMessageTypes().add(Type.LOCAL_WARNING);
        promulgationTypeService.createPromulgationType(restricted);

        // Active type with empty messageTypes -> always included regardless of requested type.
        PromulgationType unrestricted = newType("it-gap-unrestricted", "navtex", "IT Gap Unrestricted", 31,
                Boolean.TRUE, PromulgationType.Requirement.OPTIONAL);
        promulgationTypeService.createPromulgationType(unrestricted);
        em.flush();

        List<String> typeIds = promulgationTypeService.getActivePromulgationTypes(Type.COASTAL_WARNING).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertFalse(typeIds.contains("it-gap-restricted"),
                "a type whose non-empty messageTypes lacks COASTAL_WARNING must be filtered out");
        assertTrue(typeIds.contains("it-gap-unrestricted"),
                "a type with empty messageTypes must be included for any requested message type");

        // The seeded active types declare no messageTypes, so they are included too; the seeded twitter is
        // inactive (the owner's ruling of 2026-11-04) and stays out whatever its messageTypes.
        assertTrue(typeIds.contains("navtex"), "seeded 'navtex' (no messageTypes) must be included");
        assertTrue(typeIds.contains("audio"), "seeded 'audio' (no messageTypes) must be included");
        assertFalse(typeIds.contains("twitter"), "seeded 'twitter' is inactive and must be left out");
    }

    @Test
    @Transactional
    void getActivePromulgationTypesIncludesMatchingMessageType() {
        // A type explicitly listing the requested message type must be included.
        PromulgationType matching = newType("it-gap-matching", "navtex", "IT Gap Matching", 32,
                Boolean.TRUE, PromulgationType.Requirement.OPTIONAL);
        matching.getMessageTypes().add(Type.COASTAL_WARNING);
        promulgationTypeService.createPromulgationType(matching);
        em.flush();

        List<String> typeIds = promulgationTypeService.getActivePromulgationTypes(Type.COASTAL_WARNING).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertTrue(typeIds.contains("it-gap-matching"),
                "a type whose messageTypes contains COASTAL_WARNING must be included");
    }

    // ------------------------------------------------------------------------------------------- //
    // getPromulgationTypes(SystemMessageVo, onlyPromulgating)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getPromulgationTypesOnlyPromulgatingTrueReturnsPromulgatedTypesOnly() {
        // Message has a single navtex promulgation flagged promulgate=true.
        SystemMessageVo msg = messageWithNavtexPromulgation(true);

        List<String> typeIds = promulgationTypeService.getPromulgationTypes(msg, true).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertEquals(List.of("navtex"), typeIds,
                "onlyPromulgating=true must resolve exactly the promulgate=true type 'navtex'");
    }

    @Test
    @Transactional
    void getPromulgationTypesOnlyPromulgatingTrueExcludesNonPromulgatedTypes() {
        // Same single navtex promulgation, but promulgate=false.
        SystemMessageVo msg = messageWithNavtexPromulgation(false);

        List<PromulgationType> result = promulgationTypeService.getPromulgationTypes(msg, true);

        assertTrue(result.isEmpty(),
                "onlyPromulgating=true must exclude a promulgate=false promulgation, leaving no typeIds");
    }

    @Test
    @Transactional
    void getPromulgationTypesOnlyPromulgatingFalseReturnsTypeRegardlessOfFlag() {
        // promulgate=false, but onlyPromulgating=false collects the type anyway.
        SystemMessageVo msg = messageWithNavtexPromulgation(false);

        List<String> typeIds = promulgationTypeService.getPromulgationTypes(msg, false).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertEquals(List.of("navtex"), typeIds,
                "onlyPromulgating=false must resolve the type even when promulgate=false");
    }

    @Test
    @Transactional
    void getPromulgationTypesReturnsEmptyForNullPromulgations() {
        // No promulgations set at all -> message.getPromulgations() is null -> empty list.
        SystemMessageVo msg = new SystemMessageVo();

        assertTrue(promulgationTypeService.getPromulgationTypes(msg, false).isEmpty(),
                "a message with null promulgations must yield an empty list (onlyPromulgating=false)");
        assertTrue(promulgationTypeService.getPromulgationTypes(msg, true).isEmpty(),
                "a message with null promulgations must yield an empty list (onlyPromulgating=true)");
    }

    @Test
    @Transactional
    void getPromulgationTypesReturnsEmptyWhenNoTypeIdsSurviveFilter() {
        // A single promulgate=false promulgation under onlyPromulgating=true leaves an empty typeId set,
        // which must short-circuit to an empty list (no DB query, no resolved types).
        SystemMessageVo msg = messageWithNavtexPromulgation(false);

        assertTrue(promulgationTypeService.getPromulgationTypes(msg, true).isEmpty(),
                "an empty surviving typeId set must yield an empty list");
    }

    @Test
    @Transactional
    void getPromulgationTypesResolvesMultiplePromulgatedTypes() {
        // Two promulgations (navtex + twitter), both promulgate=true: both seeded types must resolve.
        PromulgationTypeVo navtexType = new PromulgationTypeVo();
        navtexType.setTypeId("navtex");
        NavtexMessagePromulgationVo navtex = new NavtexMessagePromulgationVo(navtexType);
        navtex.setPromulgate(true);

        PromulgationTypeVo twitterType = new PromulgationTypeVo();
        twitterType.setTypeId("twitter");
        TwitterMessagePromulgationVo twitter = new TwitterMessagePromulgationVo(twitterType);
        twitter.setPromulgate(true);

        SystemMessageVo msg = new SystemMessageVo();
        msg.checkCreatePromulgations().add(navtex);
        msg.checkCreatePromulgations().add(twitter);

        List<String> typeIds = promulgationTypeService.getPromulgationTypes(msg, true).stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertEquals(2, typeIds.size(),
                "both promulgated types must be resolved against the seed");
        assertTrue(typeIds.contains("navtex"), "the navtex promulgation must resolve its seeded type");
        assertTrue(typeIds.contains("twitter"), "the twitter promulgation must resolve its seeded type");
    }
}
