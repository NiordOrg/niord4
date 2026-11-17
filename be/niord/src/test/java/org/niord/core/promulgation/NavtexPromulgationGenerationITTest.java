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
import org.niord.core.area.Area;
import org.niord.core.message.Message;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.NavtexMessagePromulgation.NavtexPriority;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.message.AreaVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the message life-cycle / promulgation-generation hooks of
 * {@link NavtexPromulgationService}: {@code generateMessagePromulgation},
 * {@code messagePromulgationGenerated}, {@code onUpdateMessageStatus},
 * {@code resetMessagePromulgation}, {@code onCopyMessage} and the join-table maintenance done by
 * {@code deleteTransmitter}.
 *
 * <p>This is the sibling of {@link NavtexPromulgationServiceITTest}, which covers the plain
 * transmitter CRUD/query surface. This class instead exercises the NAVTEX text-formatting and
 * transmitter-selection rules. It mirrors that class's conventions exactly: {@code @QuarkusTest}
 * with the shared {@link NiordContainersResource}, extends {@link ITTest}, injects the real service
 * and an {@link EntityManager}, and wraps every test in {@link TestTransaction} so all writes roll
 * back and the seed database stays pristine.
 *
 * <p>The seed database (niord/src/test/resources/db/niord-test-seed.sql) defines a NAVTEX
 * {@link PromulgationType} with {@code typeId = "navtex"} and {@code language = "en"} but no
 * transmitter rows. Tests create their own transmitters with an {@code IT-TXG-} prefix that does not
 * occur in the seed, and attach area-matched transmitters to seed {@link Area} rows
 * ({@code id 1206}, lineage {@code /1192/1196/1206/}, used as the message area; and {@code id 1279},
 * lineage {@code /1192/1279/}, used as a non-matching message area). Because the NAVTEX type's
 * configured language is {@code en}, {@code generateMessagePromulgation} consumes the English
 * message-part descriptions.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class NavtexPromulgationGenerationITTest extends ITTest {

    private static final String NAVTEX = "navtex";

    // Postgres assigns its own per-entity ids, so the seeded areas no longer carry the legacy
    // MySQL numeric ids. We therefore resolve the two areas we need by their stable MRN business
    // key (see niord-test-seed.sql / Area.mrn) and use the resolved numeric ids below.

    /** Stable MRN of a seed area used as a message area matching a transmitter on the same area. */
    private static final String SEED_AREA_MATCH_MRN = "urn:mrn:iho:fa:dk:es-d-140";   // lineage /1/2/3/
    /** Stable MRN of a seed area used as a NON-matching message area (sibling of the match area). */
    private static final String SEED_AREA_NO_MATCH_MRN = "urn:mrn:iho:fa:dk:ek-d-395"; // lineage /1/2/6/

    @Autowired
    NavtexPromulgationService navtexPromulgationService;

    @Autowired
    PromulgationManager promulgationManager;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds the seeded 'navtex' promulgation type with the given requirement (en language). */
    private PromulgationType navtexType(Requirement requirement) {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        typeVo.setServiceId(NAVTEX);
        typeVo.setName("NAVTEX");
        typeVo.setLanguage("en");
        typeVo.setActive(Boolean.TRUE);
        typeVo.setRequirement(requirement);
        return new PromulgationType(typeVo);
    }

    /** Persists a NAVTEX transmitter attached to the seeded 'navtex' type, optionally to a seed area. */
    private NavtexTransmitter createTransmitter(String name, boolean active, Integer seedAreaId) {
        NavtexTransmitter t = new NavtexTransmitter(name);
        t.setActive(active);
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        t.setPromulgationType(new PromulgationType(typeVo));
        List<Area> areas = new ArrayList<>();
        if (seedAreaId != null) {
            Area a = new Area();
            a.setId(seedAreaId);   // createTransmitter re-resolves areas by id via persistedList
            areas.add(a);
        }
        t.setAreas(areas);
        return navtexPromulgationService.createTransmitter(t);
    }

    /** Builds a SystemMessageVo with a single English DETAILS part carrying the given HTML details. */
    private SystemMessageVo messageWithEnDetails(String html) {
        SystemMessageVo message = new SystemMessageVo();
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang("en");
        desc.setDetails(html);
        part.setDescs(new ArrayList<>(List.of(desc)));
        message.setParts(new ArrayList<>(List.of(part)));
        return message;
    }

    /** References a seed area from a message by its persisted id. */
    private AreaVo seedArea(int id) {
        AreaVo a = new AreaVo();
        a.setId(id);
        return a;
    }

    /** Resolves the persisted numeric id of a seed area by its stable MRN business key. */
    private int seedAreaIdByMrn(String mrn) {
        Area area = em.createNamedQuery("Area.findByMrn", Area.class)
                .setParameter("mrn", mrn)
                .getSingleResult();
        return area.getId();
    }

    /** Adds a navtex promulgation VO with the given text/transmitters onto the message. */
    private NavtexMessagePromulgationVo attachNavtexVo(SystemMessageVo message, Requirement requirement) {
        NavtexMessagePromulgationVo navtex =
                new NavtexMessagePromulgationVo(navtexType(requirement).toVo());
        message.checkCreatePromulgations().add(navtex);
        return navtex;
    }

    private boolean noLineExceeds(String text, int maxLength) {
        return Arrays.stream(text.split("\n")).allMatch(l -> l.length() <= maxLength);
    }

    // ------------------------------------------------------------------------------------------- //
    // generateMessagePromulgation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateMessagePromulgation_singleEnDetails_producesUppercaseWrappedText() throws Exception {
        // A long single-line description that must be wrapped to <=40 char NAVTEX lines.
        String html = "<p>Mariners advised buoy number seven removed permanently from harbour "
                + "entrance area near rocky coast headland</p>";
        SystemMessageVo message = messageWithEnDetails(html);

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                navtexPromulgationService.generateMessagePromulgation(message, navtexType(Requirement.DEFAULT));

        assertTrue(navtex.isPromulgate(),
                "a non-blank matching-language part must enable promulgation");
        String text = navtex.getText();
        assertNotNull(text, "generated NAVTEX text must not be null");

        // Uppercased.
        assertEquals(text.toUpperCase(), text, "NAVTEX text must be uppercase");
        // Trimmed (no leading/trailing whitespace).
        assertEquals(text.trim(), text, "NAVTEX text must be trimmed");
        // Wrapped to <= 40 character lines.
        assertTrue(noLineExceeds(text, NavtexPromulgationService.NAVTEX_LINE_LENGTH),
                "every NAVTEX line must be <= 40 chars; got:\n" + text);
        // Actually wrapped onto multiple lines (the source was a single long line).
        assertTrue(text.contains("\n"), "long text must be wrapped onto multiple lines");
        // Content preserved (uppercased) - a distinctive word survives wrapping.
        assertTrue(text.contains("MARINERS"), "content words must be preserved (uppercased)");
        assertTrue(text.contains("HEADLAND"), "trailing content must be preserved (uppercased)");
    }

    @Test
    @Transactional
    void generateMessagePromulgation_noMatchingLanguagePart_mandatoryStillPromulgatesWithEmptyText()
            throws Exception {
        // The only description is in Danish; the NAVTEX type language is English -> no text.
        SystemMessageVo message = new SystemMessageVo();
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo da = new MessagePartDescVo();
        da.setLang("da");
        da.setDetails("<p>Sejlere advares</p>");
        part.setDescs(new ArrayList<>(List.of(da)));
        message.setParts(new ArrayList<>(List.of(part)));

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                navtexPromulgationService.generateMessagePromulgation(message, navtexType(Requirement.MANDATORY));

        assertTrue(navtex.isPromulgate(),
                "MANDATORY type must promulgate even with no matching-language content");
        assertTrue(navtex.getText() == null || navtex.getText().isEmpty(),
                "with no matching-language part the NAVTEX text must be empty; got: " + navtex.getText());
    }

    @Test
    @Transactional
    void generateMessagePromulgation_noMatchingLanguagePart_optionalDoesNotPromulgate()
            throws Exception {
        // No parts at all -> no English content.
        SystemMessageVo message = new SystemMessageVo();
        message.setParts(new ArrayList<>());

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                navtexPromulgationService.generateMessagePromulgation(message, navtexType(Requirement.OPTIONAL));

        assertFalse(navtex.isPromulgate(),
                "OPTIONAL type must NOT promulgate when there is no matching-language content");
        assertTrue(navtex.getText() == null || navtex.getText().isEmpty(),
                "OPTIONAL with no content must produce empty text");
    }

    @Test
    @Transactional
    void generateMessagePromulgation_seedsActiveTransmittersFalse_andFlipsAreaMatchingTrue()
            throws Exception {
        // One active transmitter bound to the matching seed area, one active with no area.
        int matchAreaId = seedAreaIdByMrn(SEED_AREA_MATCH_MRN);
        createTransmitter("IT-TXG-AREA", true, matchAreaId);
        createTransmitter("IT-TXG-NOAREA", true, null);
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>Survey works underway</p>");
        message.setAreas(new ArrayList<>(List.of(seedArea(matchAreaId))));

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                navtexPromulgationService.generateMessagePromulgation(message, navtexType(Requirement.DEFAULT));

        // Both active transmitters are seeded into the map.
        assertTrue(navtex.getTransmitters().containsKey("IT-TXG-AREA"),
                "the area-bound active transmitter must be present");
        assertTrue(navtex.getTransmitters().containsKey("IT-TXG-NOAREA"),
                "the area-less active transmitter must be present");
        // The transmitter whose area matches the message area is flipped TRUE.
        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-AREA"),
                "the area-matching transmitter must be selected (TRUE)");
        // The transmitter that does not match any message area stays FALSE.
        assertEquals(Boolean.FALSE, navtex.getTransmitters().get("IT-TXG-NOAREA"),
                "a non-area-matching transmitter must stay deselected (FALSE)");
    }

    /**
     * PROM13. Generate keeps the transmitters the editor ticked by hand and the priority the editor chose. Before the
     * fix the answer was built from the message's areas alone: a hand-ticked transmitter not bound to those areas came
     * back FALSE, and the priority came back ROUTINE.
     */
    @Test
    @Transactional
    void generateMessagePromulgation_keepsTheTransmittersTheEditorTickedAndThePriority() throws Exception {
        int matchAreaId = seedAreaIdByMrn(SEED_AREA_MATCH_MRN);
        createTransmitter("IT-TXG-AREA", true, matchAreaId);
        createTransmitter("IT-TXG-HAND", true, null);
        createTransmitter("IT-TXG-OTHER", true, null);
        createTransmitter("IT-TXG-INACTIVE", false, null);
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>Survey works underway</p>");
        message.setAreas(new ArrayList<>(List.of(seedArea(matchAreaId))));
        NavtexMessagePromulgationVo incoming = attachNavtexVo(message, Requirement.DEFAULT);
        incoming.getTransmitters().put("IT-TXG-HAND", Boolean.TRUE);
        incoming.getTransmitters().put("IT-TXG-OTHER", Boolean.FALSE);
        incoming.getTransmitters().put("IT-TXG-INACTIVE", Boolean.TRUE);
        incoming.getTransmitters().put("IT-TXG-UNKNOWN", Boolean.TRUE);
        incoming.getTransmitters().put("IT-TXG-NULL", null);
        incoming.setPriority(NavtexPriority.IMPORTANT);

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                navtexPromulgationService.generateMessagePromulgation(message, navtexType(Requirement.DEFAULT));

        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-AREA"),
                "the transmitter bound to the message's area is ticked");
        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-HAND"),
                "the transmitter the editor ticked by hand stays ticked");
        assertEquals(Boolean.FALSE, navtex.getTransmitters().get("IT-TXG-OTHER"),
                "an active transmitter neither bound to the areas nor ticked stays FALSE");
        assertFalse(navtex.getTransmitters().containsKey("IT-TXG-INACTIVE"),
                "an inactive transmitter is left out, even when the editor had it ticked");
        assertFalse(navtex.getTransmitters().containsKey("IT-TXG-UNKNOWN"),
                "a name that is no transmitter is left out");
        assertFalse(navtex.getTransmitters().containsKey("IT-TXG-NULL"),
                "a name that is no transmitter is left out, whatever its value");
        assertEquals(NavtexPriority.IMPORTANT, navtex.getPriority(), "the priority the editor chose is kept");
        assertEquals("SURVEY WORKS UNDERWAY", navtex.getText(), "the text is generated as before");
        assertTrue(navtex.isPromulgate(), "promulgate is generated as before");
    }

    /**
     * R1-2 (PROM13). A message saved in area West, whose first Generate ticked West's transmitter, has its area
     * corrected to East and is generated again. The West tick was derived from the stored areas, not set by hand: it
     * goes, and East's transmitter is ticked. A hand tick of a transmitter bound to neither area stays.
     */
    @Test
    @Transactional
    void generateMessagePromulgation_dropsTicksDerivedFromTheMessagesFormerAreas() throws Exception {
        int westAreaId = seedAreaIdByMrn(SEED_AREA_MATCH_MRN);
        int eastAreaId = seedAreaIdByMrn(SEED_AREA_NO_MATCH_MRN);
        createTransmitter("IT-TXG-WEST", true, westAreaId);
        createTransmitter("IT-TXG-EAST", true, eastAreaId);
        createTransmitter("IT-TXG-HAND", true, null);

        Message stored = new Message();
        String uid = UUID.randomUUID().toString();
        stored.setUid(uid);
        stored.setRepoPath("messages/it/" + uid);
        stored.setMainType(MainType.NW);
        stored.setType(Type.LOCAL_WARNING);
        stored.setStatus(Status.DRAFT);
        stored.getAreas().add(em.find(Area.class, westAreaId));
        em.persist(stored);
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>Survey works underway</p>");
        message.setId(uid);
        message.setAreas(new ArrayList<>(List.of(seedArea(eastAreaId))));
        NavtexMessagePromulgationVo incoming = attachNavtexVo(message, Requirement.DEFAULT);
        incoming.getTransmitters().put("IT-TXG-WEST", Boolean.TRUE);    // as the first Generate left it
        incoming.getTransmitters().put("IT-TXG-EAST", Boolean.FALSE);
        incoming.getTransmitters().put("IT-TXG-HAND", Boolean.TRUE);

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                navtexPromulgationService.generateMessagePromulgation(message, navtexType(Requirement.DEFAULT));

        assertEquals(Boolean.FALSE, navtex.getTransmitters().get("IT-TXG-WEST"),
                "the tick the message's former area gave goes with that area");
        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-EAST"),
                "the transmitter of the message's new area is ticked");
        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-HAND"),
                "the transmitter the editor ticked by hand stays ticked");
    }

    /**
     * PROM13 on the path the editor's Generate takes ({@code POST /rest/promulgations/generate/navtex}):
     * {@link PromulgationManager#generateMessagePromulgation} replaces the message's own NAVTEX entry by the generated
     * one and then runs {@code messagePromulgationGenerated}; the editor's ticks and priority survive both steps.
     */
    @Test
    @Transactional
    void generateThroughTheManager_keepsTheTransmittersTheEditorTickedAndThePriority() throws Exception {
        createTransmitter("IT-TXG-HAND", true, null);
        createTransmitter("IT-TXG-OTHER", true, null);
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>Survey works underway</p>");
        message.setAreas(new ArrayList<>(List.of(seedArea(seedAreaIdByMrn(SEED_AREA_NO_MATCH_MRN)))));
        NavtexMessagePromulgationVo incoming = attachNavtexVo(message, Requirement.DEFAULT);
        incoming.getTransmitters().put("IT-TXG-HAND", Boolean.TRUE);
        incoming.getTransmitters().put("IT-TXG-OTHER", Boolean.FALSE);
        incoming.setPriority(NavtexPriority.VITAL);

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                promulgationManager.generateMessagePromulgation(NAVTEX, message);

        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-HAND"),
                "the transmitter the editor ticked by hand stays ticked");
        assertEquals(Boolean.FALSE, navtex.getTransmitters().get("IT-TXG-OTHER"),
                "an active transmitter the editor did not tick stays FALSE");
        assertEquals(NavtexPriority.VITAL, navtex.getPriority(), "the priority the editor chose is kept");
        assertEquals(navtex, message.promulgation(NavtexMessagePromulgationVo.class, NAVTEX),
                "the generated entry replaces the message's own");
    }

    // ------------------------------------------------------------------------------------------- //
    // messagePromulgationGenerated
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void messagePromulgationGenerated_collapsesBlankLinesAndRewrapsOverLongText() throws Exception {
        SystemMessageVo message = messageWithEnDetails("<p>placeholder</p>");
        NavtexMessagePromulgationVo navtex = attachNavtexVo(message, Requirement.DEFAULT);

        // Text with blank lines and an over-long line and lower-case content.
        navtex.setText("first line\n\n\nthis single line is far too long and must be rewrapped to forty chars\n\nlast line");

        navtexPromulgationService.messagePromulgationGenerated(message, navtexType(Requirement.DEFAULT));

        String text = navtex.getText();
        assertNotNull(text, "text must remain set");
        // No blank lines remain (consecutive newlines collapsed).
        assertFalse(text.contains("\n\n"), "blank lines must be collapsed; got:\n" + text);
        // All lines re-wrapped to <= 40 chars.
        assertTrue(noLineExceeds(text, NavtexPromulgationService.NAVTEX_LINE_LENGTH),
                "every line must be <= 40 chars after re-wrap; got:\n" + text);
        // Uppercased and trimmed.
        assertEquals(text.toUpperCase(), text, "text must be uppercased");
        assertEquals(text.trim(), text, "text must be trimmed");
        // Content from first and last lines preserved.
        assertTrue(text.startsWith("FIRST LINE"), "first line content must be preserved; got:\n" + text);
        assertTrue(text.contains("LAST LINE"), "last line content must be preserved; got:\n" + text);
    }

    @Test
    @Transactional
    void messagePromulgationGenerated_keepsManuallySelectedTransmitterEvenWhenAreasDoNotMatch()
            throws Exception {
        // Transmitter bound to the matching seed area, but the message references a NON-matching area.
        createTransmitter("IT-TXG-AREA", true, seedAreaIdByMrn(SEED_AREA_MATCH_MRN));
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>Works underway</p>");
        // Message area does not match the transmitter's area.
        message.setAreas(new ArrayList<>(List.of(seedArea(seedAreaIdByMrn(SEED_AREA_NO_MATCH_MRN)))));

        NavtexMessagePromulgationVo navtex = attachNavtexVo(message, Requirement.DEFAULT);
        // The transmitter was manually selected (TRUE) by the editor.
        navtex.getTransmitters().put("IT-TXG-AREA", Boolean.TRUE);

        navtexPromulgationService.messagePromulgationGenerated(message, navtexType(Requirement.DEFAULT));

        // Even though the areas do not match, the manual TRUE selection is retained.
        assertEquals(Boolean.TRUE, navtex.getTransmitters().get("IT-TXG-AREA"),
                "a manually-selected transmitter must stay TRUE even when areas do not match");
    }

    @Test
    @Transactional
    void messagePromulgationGenerated_deselectsNonMatchingTransmitterThatWasNotManuallySelected()
            throws Exception {
        createTransmitter("IT-TXG-AREA", true, seedAreaIdByMrn(SEED_AREA_MATCH_MRN));
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>Works underway</p>");
        message.setAreas(new ArrayList<>(List.of(seedArea(seedAreaIdByMrn(SEED_AREA_NO_MATCH_MRN)))));

        NavtexMessagePromulgationVo navtex = attachNavtexVo(message, Requirement.DEFAULT);
        // Present but NOT manually selected.
        navtex.getTransmitters().put("IT-TXG-AREA", Boolean.FALSE);

        navtexPromulgationService.messagePromulgationGenerated(message, navtexType(Requirement.DEFAULT));

        assertEquals(Boolean.FALSE, navtex.getTransmitters().get("IT-TXG-AREA"),
                "a non-area-matching, non-manually-selected transmitter must remain FALSE");
    }

    // ------------------------------------------------------------------------------------------- //
    // onUpdateMessageStatus
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void onUpdateMessageStatus_published_expandsPreambleTokensAndUppercasesText() throws Exception {
        Message message = new Message();
        message.setType(Type.COASTAL_WARNING);
        message.setStatus(Status.PUBLISHED);
        message.setShortId("NW-187-26");
        message.setPublishDateFrom(new Date(0L)); // 1970-01-01T00:00:00Z

        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setType(navtexType(Requirement.DEFAULT));
        navtex.setPreamble("warning ${short-id} published ${publish-date}");
        navtex.setText("low-case text body");
        message.addPromulgation(navtex);

        navtexPromulgationService.onUpdateMessageStatus(message, navtexType(Requirement.DEFAULT));

        // Tokens expanded.
        assertTrue(navtex.getPreamble().contains("NW-187-26"),
                "${short-id} must be expanded into the preamble; got: " + navtex.getPreamble());
        assertFalse(navtex.getPreamble().contains("${short-id}"),
                "${short-id} token must be consumed");
        assertFalse(navtex.getPreamble().contains("${publish-date}"),
                "${publish-date} token must be consumed");
        // ddHHmm 'UTC' MMM yy for epoch 0 in UTC -> 010000 UTC JAN 70 (uppercased).
        // The preamble is also wrapped to 40-char NAVTEX lines, which can insert a newline inside the
        // date (e.g. "...010000 UTC\nJAN 70"), so normalize whitespace before matching.
        String flatPreamble = navtex.getPreamble().replaceAll("\\s+", " ");
        assertTrue(flatPreamble.contains("010000 UTC JAN 70"),
                "${publish-date} must format to the NAVTEX UTC date; got: " + navtex.getPreamble());
        // Preamble is uppercased.
        assertEquals(navtex.getPreamble().toUpperCase(), navtex.getPreamble(),
                "preamble must be uppercased");
        // Text uppercased and trimmed.
        assertEquals("LOW-CASE TEXT BODY", navtex.getText(),
                "NAVTEX text must be uppercased and trimmed on publish");
    }

    @Test
    @Transactional
    void onUpdateMessageStatus_verified_leavesThePreambleTokens_andHoldsTheTextToTheAlphabet() throws Exception {
        Message message = new Message();
        message.setType(Type.COASTAL_WARNING);
        message.setStatus(Status.VERIFIED);
        message.setShortId("NW-187-26");
        message.setPublishDateFrom(new Date(0L));

        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setType(navtexType(Requirement.DEFAULT));
        navtex.setPreamble("warning ${short-id} published ${publish-date}");
        navtex.setText("low-case text body");
        message.addPromulgation(navtex);

        navtexPromulgationService.onUpdateMessageStatus(message, navtexType(Requirement.DEFAULT));

        // Non-public status -> the preamble's tokens are not expanded, and the preamble is not reformatted.
        assertEquals("warning ${short-id} published ${publish-date}", navtex.getPreamble(),
                "VERIFIED status must leave the preamble (and its tokens) untouched");
        // PROM16: every save and status change holds the text to the NAVTEX alphabet (upper case included), but
        // does not wrap it; the publication wraps it.
        assertEquals("LOW-CASE TEXT BODY", navtex.getText(),
                "VERIFIED status must hold the NAVTEX text to the NAVTEX alphabet");
    }

    @Test
    @Transactional
    void onUpdateMessageStatus_published_holdsTheTextAndThePreambleToTheNavtexAlphabet() throws Exception {
        // PROM16: Danish letters, guillemets, quotes and a no-break space become their NAVTEX forms at publication.
        Message message = new Message();
        message.setType(Type.COASTAL_WARNING);
        message.setStatus(Status.PUBLISHED);
        message.setShortId("NW-187-26");
        message.setPublishDateFrom(new Date(0L));

        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setType(navtexType(Requirement.DEFAULT));
        navtex.setPreamble("Søværnet «${short-id}»");
        navtex.setText("Survey off Ærø by \"POSEIDON\", keep clear!");
        message.addPromulgation(navtex);

        navtexPromulgationService.onUpdateMessageStatus(message, navtexType(Requirement.DEFAULT));

        assertEquals("SOEVAERNET 'NW-187-26'", navtex.getPreamble());
        assertEquals("SURVEY OFF AEROE BY 'POSEIDON', KEEP\nCLEAR.", navtex.getText());
    }

    // ------------------------------------------------------------------------------------------- //
    // resetMessagePromulgation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void resetMessagePromulgation_clearsTextSelectsNoTransmittersAndReDerivesPreamble()
            throws Exception {
        // One active transmitter exists, so reset must (re-)seed it as FALSE.
        createTransmitter("IT-TXG-RESET", true, null);
        em.flush();
        em.clear();

        SystemMessageVo message = messageWithEnDetails("<p>placeholder</p>");
        // The preamble starts blank, so the re-derive step in resetMessagePromulgation runs. With no
        // message series providing a navtexFormat, checkNavtexPreamble leaves it blank/null.
        NavtexMessagePromulgationVo navtex = attachNavtexVo(message, Requirement.DEFAULT);
        navtex.setText("SOME EXISTING TEXT");
        navtex.setPreamble(null);
        navtex.getTransmitters().put("IT-TXG-RESET", Boolean.TRUE);

        navtexPromulgationService.resetMessagePromulgation(message, navtexType(Requirement.DEFAULT));

        // reset() clears the text.
        assertNull(navtex.getText(), "reset must clear the NAVTEX text");
        // No message series means the re-derived preamble stays null/blank.
        assertTrue(navtex.getPreamble() == null || navtex.getPreamble().isEmpty(),
                "reset must re-derive the preamble (blank when no message series provides a format)");
        // The active transmitter is (re-)seeded as FALSE.
        assertEquals(Boolean.FALSE, navtex.getTransmitters().get("IT-TXG-RESET"),
                "reset must deselect (FALSE) the active transmitter");
    }

    @Test
    @Transactional
    void resetMessagePromulgation_doesNotClearAnAlreadySetPreamble_documentsBehavior()
            throws Exception {
        // NavtexMessagePromulgationVo.reset() clears transmitters, text and priority, but NOT the
        // preamble. resetMessagePromulgation only RE-DERIVES the preamble when it is blank (via
        // checkNavtexPreamble). So a previously-set, non-blank preamble survives a reset unchanged.
        // This documents that "reset" does not fully clear the promulgation's preamble field.
        SystemMessageVo message = messageWithEnDetails("<p>placeholder</p>");
        NavtexMessagePromulgationVo navtex = attachNavtexVo(message, Requirement.DEFAULT);
        navtex.setText("SOME EXISTING TEXT");
        navtex.setPreamble("SOME EXISTING PREAMBLE");

        navtexPromulgationService.resetMessagePromulgation(message, navtexType(Requirement.DEFAULT));

        assertNull(navtex.getText(), "reset still clears the text");
        assertEquals("SOME EXISTING PREAMBLE", navtex.getPreamble(),
                "a non-blank preamble is NOT cleared by reset (reset() leaves preamble intact, and "
                        + "checkNavtexPreamble only fills a blank one)");
    }

    // ------------------------------------------------------------------------------------------- //
    // onCopyMessage
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void onCopyMessage_nullsCopiedPreamble() throws Exception {
        SystemMessageVo message = messageWithEnDetails("<p>placeholder</p>");
        NavtexMessagePromulgationVo navtex = attachNavtexVo(message, Requirement.DEFAULT);
        navtex.setPreamble("A PREAMBLE FROM THE ORIGINAL MESSAGE");
        navtex.setText("UNTOUCHED TEXT");

        navtexPromulgationService.onCopyMessage(message, navtexType(Requirement.DEFAULT));

        assertNull(navtex.getPreamble(),
                "copying a message must reset (null) the NAVTEX preamble");
        // The copy hook only touches the preamble, not the text.
        assertEquals("UNTOUCHED TEXT", navtex.getText(),
                "onCopyMessage must not modify the NAVTEX text");
    }

    @Test
    @Transactional
    void onCopyMessage_noNavtexPromulgation_isNoOp() throws Exception {
        // Message has no navtex promulgation at all -> the hook must not fail.
        SystemMessageVo message = messageWithEnDetails("<p>placeholder</p>");

        navtexPromulgationService.onCopyMessage(message, navtexType(Requirement.DEFAULT));

        assertNull(message.promulgation(NavtexMessagePromulgationVo.class, NAVTEX),
                "no navtex promulgation should have been created by onCopyMessage");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteTransmitter - join-table maintenance
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteTransmitter_removesItFromReferencingPromulgationAndDeletesRow() {
        NavtexTransmitter created = createTransmitter("IT-TXG-DEL", true, null);
        Integer transmitterId = created.getId();
        assertNotNull(transmitterId, "transmitter must be persisted with an id");
        em.flush();

        // Persist a Message carrying a NavtexMessagePromulgation that references the transmitter
        // through the many-to-many transmitters association.
        Message message = new Message();
        message.setType(Type.COASTAL_WARNING);
        message.setStatus(Status.PUBLISHED);

        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setType(em.find(PromulgationType.class, created.getPromulgationType().getId()));
        navtex.setText("NAVTEX BODY");
        navtex.getTransmitters().add(created);
        message.addPromulgation(navtex);

        em.persist(message); // cascades to the promulgation (CascadeType.ALL)
        em.flush();
        Integer promulgationId = navtex.getId();
        assertNotNull(promulgationId, "promulgation must be persisted with an id");
        em.clear();

        // Sanity: the join row exists before deletion.
        Long beforeCount = (Long) em.createQuery(
                        "SELECT count(t) FROM NavtexMessagePromulgation m JOIN m.transmitters t "
                                + "WHERE m.id = :mid AND t.id = :tid")
                .setParameter("mid", promulgationId)
                .setParameter("tid", transmitterId)
                .getSingleResult();
        assertEquals(1L, beforeCount, "the join row must exist before deletion");

        // Delete the transmitter.
        assertTrue(navtexPromulgationService.deleteTransmitter(NAVTEX, "IT-TXG-DEL"),
                "deleteTransmitter must report success");

        em.flush();
        em.clear();

        // The transmitter row is gone.
        assertNull(em.find(NavtexTransmitter.class, transmitterId),
                "the transmitter row must be deleted");
        // The promulgation still exists but no longer references the transmitter.
        NavtexMessagePromulgation reloaded = em.find(NavtexMessagePromulgation.class, promulgationId);
        assertNotNull(reloaded, "the promulgation row must still exist");
        assertTrue(reloaded.getTransmitters().isEmpty(),
                "the deleted transmitter must be removed from the referencing promulgation");

        Long afterCount = (Long) em.createQuery(
                        "SELECT count(t) FROM NavtexMessagePromulgation m JOIN m.transmitters t "
                                + "WHERE m.id = :mid")
                .setParameter("mid", promulgationId)
                .getSingleResult();
        assertEquals(0L, afterCount, "no join rows must remain for the promulgation");
    }
}
