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
import org.niord.core.NiordApp;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo;
import org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the promulgation-generation and area-resolution behaviour of
 * {@link SafetyNetPromulgationService}, run against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>This is a sibling of {@code SafetyNetPromulgationServiceITTest}, which covers the CRUD
 * area-handling API. This class instead exercises:
 * <ul>
 *     <li>{@link SafetyNetPromulgationService#generateMessagePromulgation} — the SafetyNET text is
 *         uppercased and (unlike NAVTEX) is NOT wrapped to 40-character lines, and the
 *         {@code promulgate} flag falls back to whether the type is {@code MANDATORY} when the
 *         message has no matching-language text.</li>
 *     <li>{@link SafetyNetPromulgationService#checkSafetyNetArea} — area auto-selection,
 *         message-type filtering, and the persisted-vs-transient retention of a selected area that
 *         no longer matches.</li>
 *     <li>{@link SafetyNetPromulgationService#messagePromulgationGenerated} — blank-line collapsing
 *         and uppercasing of an already-set text, again without 40-character wrapping.</li>
 *     <li>{@link SafetyNetPromulgationService#onLoadSystemMessage} — a fresh SafetyNET promulgation
 *         VO plus its selectable-area list is attached to a message that has none.</li>
 * </ul>
 *
 * <p>The seed data (niord/src/test/resources/db/niord-test-seed.sql) defines three promulgation
 * types ({@code navtex}, {@code audio} and {@code twitter}) and leaves the {@code SafetyNetArea}
 * table empty. Every test that needs distribution areas creates the exact rows it needs and attaches
 * them to the seeded {@code navtex} type (the service resolves the type by typeId only). Because
 * SafetyNET service methods (e.g. {@code findAreasByMessageType}) resolve areas by the promulgation
 * type id, the in-memory VOs built here carry {@code typeId = "navtex"} so that area lookups match.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. Area names use an {@code IT-SNG-} prefix
 * that does not occur in the seed data and is distinct from the sibling's {@code IT-SN-} prefix, so
 * name lookups stay isolated. Names are unique within each test, and rollback removes them between
 * tests, so collisions are not a concern. Tests pass alone and in the full suite.
 *
 * <p>The configured set of model languages is read from settings rather than hard-coded: the
 * promulgation type's language and the message description language are both set to the value
 * {@link NiordApp#getLanguage(String)} resolves for {@code "en"}, so the language match in
 * {@code generateMessagePromulgation} holds regardless of the deployment's language configuration.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SafetyNetPromulgationGenerationITTest extends ITTest {

    static final String NAVTEX = "navtex";

    @Autowired
    SafetyNetPromulgationService safetyNetPromulgationService;

    @Autowired
    NiordApp app;

    @PersistenceContext
    EntityManager em;

    /** Builds the seeded 'navtex' promulgation type as a detached reference for attaching areas. */
    private PromulgationType navtexType() {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        return new PromulgationType(typeVo);
    }

    /** Builds a detached circular SafetyNET area attached to the seeded 'navtex' type. */
    private SafetyNetCircularArea newCircularArea(String name, boolean active, int sortOrder,
                                                  String center, String radius) {
        SafetyNetCircularArea a = new SafetyNetCircularArea();
        a.setName(name);
        a.setActive(active);
        a.setSortOrder(sortOrder);
        a.setCenter(center);
        a.setRadius(radius);
        a.setPromulgationType(navtexType());
        return a;
    }

    /** Builds a detached NAVAREA SafetyNET area attached to the seeded 'navtex' type. */
    private SafetyNetNavareaArea newNavareaArea(String name, SafetyNetNavareaArea.NAVAREA navarea) {
        SafetyNetNavareaArea a = new SafetyNetNavareaArea();
        a.setName(name);
        a.setActive(true);
        a.setSortOrder(50);
        a.setNavarea(navarea);
        a.setPromulgationType(navtexType());
        return a;
    }

    /** The model language used for matching message descriptions, resolved against settings. */
    private String matchingLanguage() {
        return app.getLanguage("en");
    }

    /**
     * Builds a {@link PromulgationTypeVo} whose typeId resolves SafetyNET areas to the seeded
     * 'navtex' type, whose language matches {@link #matchingLanguage()} (so the generator picks up
     * the supplied description), and whose requirement is as given.
     */
    private PromulgationTypeVo generationTypeVo(Requirement requirement) {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        typeVo.setServiceId(SafetyNetMessagePromulgation.SERVICE_ID);
        typeVo.setName("SafetyNET");
        typeVo.setLanguage(matchingLanguage());
        typeVo.setRequirement(requirement);
        return typeVo;
    }

    /** Builds a single-part message of the given type carrying one description detail. */
    private SystemMessageVo messageWithDetails(Type messageType, String lang, String details) {
        SystemMessageVo message = new SystemMessageVo();
        message.setType(messageType);

        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang(lang);
        desc.setDetails(details);

        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        List<MessagePartDescVo> descs = new ArrayList<>();
        descs.add(desc);
        part.setDescs(descs);

        List<MessagePartVo> parts = new ArrayList<>();
        parts.add(part);
        message.setParts(parts);

        return message;
    }

    /** Returns the names of the selectable areas held by a SafetyNET promulgation VO. */
    private List<String> areaNames(SafetyNetMessagePromulgationVo safetynet) {
        return safetynet.getAreas().stream()
                .map(SafetyNetAreaVo::getName)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // generateMessagePromulgation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateMessagePromulgation_uppercasesTextWithoutWrappingLongLines() throws Exception {
        // A single description line well over 40 characters. NAVTEX would split this into 40-char
        // chunks; SafetyNET explicitly does not (html2safetynet leaves the line intact).
        String longLine = "vessel reported aground near the harbour entrance please keep clear at all times";
        SystemMessageVo message =
                messageWithDetails(Type.COASTAL_WARNING, matchingLanguage(), longLine);

        PromulgationType type = new PromulgationType(generationTypeVo(Requirement.OPTIONAL));

        SafetyNetMessagePromulgationVo result = (SafetyNetMessagePromulgationVo)
                safetyNetPromulgationService.generateMessagePromulgation(message, type);

        assertNotNull(result, "a SafetyNET promulgation VO should be generated");
        assertTrue(result.isPromulgate(),
                "promulgate should be true when matching-language text was produced");
        String text = result.getText();
        assertNotNull(text, "generated text should not be null when description text exists");

        // The generated text is uppercased.
        assertEquals(text.toUpperCase(), text, "generated text should be uppercase");

        // The long line is preserved as a single line, NOT wrapped into 40-character segments.
        // (Superfluous words such as "the" are stripped by html2safetynet, so assert on the
        // line length / single-line property rather than on exact content.)
        String[] lines = text.split("\\R");
        assertEquals(1, lines.length,
                "SafetyNET text must not be wrapped into multiple lines; got: " + text);
        assertTrue(lines[0].length() > 40,
                "the single line must stay longer than 40 chars (not NAVTEX-wrapped); got length "
                        + lines[0].length() + ": " + lines[0]);
        // The text actually carries the (uppercased, the-stripped) content.
        assertTrue(text.contains("VESSEL REPORTED AGROUND"),
                "generated text should carry the uppercased description content; got: " + text);
    }

    @Test
    @Transactional
    void generateMessagePromulgation_noMatchingLanguage_mandatoryPromulgatesButLeavesTextEmpty()
            throws Exception {
        // The message description is in a language that does not match the type's language, so no
        // text is collected. For a MANDATORY type, promulgate must still be true.
        SystemMessageVo message =
                messageWithDetails(Type.COASTAL_WARNING, "zz-nonexistent-lang", "Some details");

        PromulgationType type = new PromulgationType(generationTypeVo(Requirement.MANDATORY));

        SafetyNetMessagePromulgationVo result = (SafetyNetMessagePromulgationVo)
                safetyNetPromulgationService.generateMessagePromulgation(message, type);

        assertNotNull(result, "a SafetyNET promulgation VO should be generated");
        assertTrue(result.isPromulgate(),
                "MANDATORY type must promulgate even with no matching-language text");
        // No text was produced because the only description was in a non-matching language.
        assertTrue(result.getText() == null || result.getText().isEmpty(),
                "no SafetyNET text should be produced when no description matches the language; got: "
                        + result.getText());
    }

    @Test
    @Transactional
    void generateMessagePromulgation_noMatchingLanguage_optionalDoesNotPromulgate() throws Exception {
        SystemMessageVo message =
                messageWithDetails(Type.COASTAL_WARNING, "zz-nonexistent-lang", "Some details");

        PromulgationType type = new PromulgationType(generationTypeVo(Requirement.OPTIONAL));

        SafetyNetMessagePromulgationVo result = (SafetyNetMessagePromulgationVo)
                safetyNetPromulgationService.generateMessagePromulgation(message, type);

        assertNotNull(result, "a SafetyNET promulgation VO should be generated");
        assertFalse(result.isPromulgate(),
                "OPTIONAL type must NOT promulgate when no matching-language text was produced");
        assertTrue(result.getText() == null || result.getText().isEmpty(),
                "no SafetyNET text should be produced when no description matches the language; got: "
                        + result.getText());
    }

    // ------------------------------------------------------------------------------------------- //
    // checkSafetyNetArea
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void checkSafetyNetArea_autoSelectsSoleActiveMatchingArea_whenAreaNameBlank() {
        // Exactly one active circular area matches COASTAL_WARNING for the navtex type.
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SNG-Sole", true, 50, "0 0", "100"));
        em.flush();
        em.clear();

        SafetyNetMessagePromulgationVo safetynet =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        safetynet.setAreaName(null); // blank -> eligible for auto-selection

        safetyNetPromulgationService.checkSafetyNetArea(safetynet, Type.COASTAL_WARNING);

        assertEquals("IT-SNG-Sole", safetynet.getAreaName(),
                "the sole matching active area must be auto-selected when areaName is blank");
        assertTrue(areaNames(safetynet).contains("IT-SNG-Sole"),
                "the selectable-area list must contain the matching area; got " + areaNames(safetynet));
    }

    @Test
    @Transactional
    void checkSafetyNetArea_doesNotAutoSelect_whenTwoOrMoreMatch() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SNG-A", true, 10, "0 0", "100"));
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SNG-B", true, 20, "1 1", "200"));
        em.flush();
        em.clear();

        SafetyNetMessagePromulgationVo safetynet =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        safetynet.setAreaName(null);

        safetyNetPromulgationService.checkSafetyNetArea(safetynet, Type.COASTAL_WARNING);

        assertNull(safetynet.getAreaName(),
                "no area must be auto-selected when 2+ matching areas exist");
        List<String> names = areaNames(safetynet);
        assertTrue(names.contains("IT-SNG-A") && names.contains("IT-SNG-B"),
                "both matching areas must be offered as selectable; got " + names);
    }

    @Test
    @Transactional
    void checkSafetyNetArea_filtersSelectableAreasByMessageType() {
        // One circular area (matches COASTAL_WARNING only) and one NAVAREA area (matches
        // NAVAREA_WARNING only), both active and attached to navtex.
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SNG-Circular", true, 10, "0 0", "100"));
        safetyNetPromulgationService.createArea(
                newNavareaArea("IT-SNG-Navarea", SafetyNetNavareaArea.NAVAREA.I));
        em.flush();
        em.clear();

        // COASTAL_WARNING: circular is offered, NAVAREA is excluded.
        SafetyNetMessagePromulgationVo coastal =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        safetyNetPromulgationService.checkSafetyNetArea(coastal, Type.COASTAL_WARNING);
        List<String> coastalNames = areaNames(coastal);
        assertTrue(coastalNames.contains("IT-SNG-Circular"),
                "circular area must be selectable for COASTAL_WARNING; got " + coastalNames);
        assertFalse(coastalNames.contains("IT-SNG-Navarea"),
                "NAVAREA area must NOT be selectable for COASTAL_WARNING; got " + coastalNames);

        // NAVAREA_WARNING: NAVAREA is offered, circular is excluded.
        SafetyNetMessagePromulgationVo navarea =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        safetyNetPromulgationService.checkSafetyNetArea(navarea, Type.NAVAREA_WARNING);
        List<String> navareaNames = areaNames(navarea);
        assertTrue(navareaNames.contains("IT-SNG-Navarea"),
                "NAVAREA area must be selectable for NAVAREA_WARNING; got " + navareaNames);
        assertFalse(navareaNames.contains("IT-SNG-Circular"),
                "circular area must NOT be selectable for NAVAREA_WARNING; got " + navareaNames);
    }

    @Test
    @Transactional
    void checkSafetyNetArea_clearsSelectedAreaName_forTransientPromulgationWhenAreaNoLongerMatches() {
        // A NAVAREA area exists (matches NAVAREA_WARNING only). A transient (id==null) promulgation
        // has it selected by name, but we now resolve against COASTAL_WARNING, where it does not
        // match. The selected name must be cleared and the area must not be force-added.
        safetyNetPromulgationService.createArea(
                newNavareaArea("IT-SNG-Stale", SafetyNetNavareaArea.NAVAREA.II));
        em.flush();
        em.clear();

        SafetyNetMessagePromulgationVo safetynet =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        // id stays null (transient)
        safetynet.setAreaName("IT-SNG-Stale");

        safetyNetPromulgationService.checkSafetyNetArea(safetynet, Type.COASTAL_WARNING);

        assertNull(safetynet.getAreaName(),
                "a transient promulgation must drop a selected area that no longer matches the type");
        assertFalse(areaNames(safetynet).contains("IT-SNG-Stale"),
                "the no-longer-matching area must NOT be force-added for a transient promulgation; got "
                        + areaNames(safetynet));
    }

    @Test
    @Transactional
    void checkSafetyNetArea_retainsInactiveSelectedArea_forPersistedPromulgation() {
        // An INACTIVE circular area exists. The active filter excludes it from the selectable list,
        // so it is not initially matched. For a PERSISTED promulgation (id != null) whose areaName
        // points at it, checkSafetyNetArea must look it up by name and re-add it so the historical
        // selection is preserved.
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SNG-Inactive", false, 50, "0 0", "100"));
        em.flush();
        em.clear();

        SafetyNetMessagePromulgationVo safetynet =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        safetynet.setId(4242); // persisted
        safetynet.setAreaName("IT-SNG-Inactive");

        safetyNetPromulgationService.checkSafetyNetArea(safetynet, Type.COASTAL_WARNING);

        assertEquals("IT-SNG-Inactive", safetynet.getAreaName(),
                "a persisted promulgation must retain its selected area name even if inactive");
        assertTrue(areaNames(safetynet).contains("IT-SNG-Inactive"),
                "the inactive selected area must be re-added to the selectable list for a persisted "
                        + "promulgation; got " + areaNames(safetynet));
    }

    // ------------------------------------------------------------------------------------------- //
    // messagePromulgationGenerated
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void messagePromulgationGenerated_collapsesBlankLinesAndUppercasesWithoutWrapping()
            throws Exception {
        // A message that already carries a SafetyNET promulgation whose text has blank lines and
        // mixed case, plus one line well over 40 characters.
        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);

        SafetyNetMessagePromulgationVo safetynet =
                new SafetyNetMessagePromulgationVo(generationTypeVo(Requirement.OPTIONAL));
        String longLine = "this single line is intentionally far longer than forty characters wide";
        safetynet.setText("first line\n\n\nsecond line\n" + longLine);
        message.checkCreatePromulgations().add(safetynet);

        safetyNetPromulgationService.messagePromulgationGenerated(message, navtexTypeForGeneration());

        SafetyNetMessagePromulgationVo updated =
                message.promulgation(SafetyNetMessagePromulgationVo.class, NAVTEX);
        assertNotNull(updated, "the SafetyNET promulgation should still be present");
        String text = updated.getText();
        assertNotNull(text, "the processed text should not be null");

        // Blank lines collapsed: no empty lines remain.
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            assertFalse(line.isEmpty(), "blank lines must be collapsed; got: " + text);
        }
        // Exactly three content lines remain (first / second / long).
        assertEquals(3, lines.length, "blank lines must collapse to three content lines; got: " + text);

        // Uppercased.
        assertEquals(text.toUpperCase(), text, "processed text must be uppercase");
        assertTrue(text.contains("FIRST LINE"), "content must be uppercased; got: " + text);

        // The long line is NOT wrapped: it stays a single line over 40 chars.
        String lastLine = lines[lines.length - 1];
        assertTrue(lastLine.length() > 40,
                "the long line must not be wrapped to 40 chars; got length " + lastLine.length()
                        + ": " + lastLine);
        assertEquals(longLine.toUpperCase(), lastLine,
                "the long line must be preserved verbatim (only uppercased), not wrapped");
    }

    /** Builds the navtex generation type as a {@link PromulgationType} entity. */
    private PromulgationType navtexTypeForGeneration() {
        return new PromulgationType(generationTypeVo(Requirement.OPTIONAL));
    }

    // ------------------------------------------------------------------------------------------- //
    // onLoadSystemMessage
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void onLoadSystemMessage_addsNewSafetyNetPromulgationAndAreaList_whenMessageHasNone()
            throws Exception {
        // One active matching circular area exists, so loading the message should both attach a new
        // SafetyNET promulgation VO and populate (and, being sole, auto-select) the area list.
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SNG-Load", true, 50, "0 0", "100"));
        em.flush();
        em.clear();

        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        // No promulgations at all.
        assertNull(message.getPromulgations(),
                "precondition: the message must start without any promulgations");

        safetyNetPromulgationService.onLoadSystemMessage(message, navtexTypeForGeneration());

        List<BaseMessagePromulgationVo> promulgations = message.getPromulgations();
        assertNotNull(promulgations, "onLoadSystemMessage must create the promulgation list");
        SafetyNetMessagePromulgationVo added =
                message.promulgation(SafetyNetMessagePromulgationVo.class, NAVTEX);
        assertNotNull(added, "a new SafetyNET promulgation VO must be added for the navtex type");
        assertEquals(NAVTEX, added.getType().getTypeId(),
                "the added promulgation must be bound to the navtex type");

        // The selectable-area list was computed and, being the sole match, auto-selected.
        assertTrue(areaNames(added).contains("IT-SNG-Load"),
                "the matching area must be offered in the selectable list; got " + areaNames(added));
        assertEquals("IT-SNG-Load", added.getAreaName(),
                "the sole matching area must be auto-selected for the freshly loaded promulgation");
    }
}
