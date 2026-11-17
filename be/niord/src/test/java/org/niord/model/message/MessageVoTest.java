/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.model.message;

import org.junit.jupiter.api.Test;
import org.niord.model.DataFilter;
import org.niord.model.ILocalizable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MessageVo}, the REST/JSON value object at the heart of the
 * NW/NM message API contract.
 *
 * Focus areas:
 * <ul>
 *     <li>{@link MessageVo#copy(DataFilter)} field-level filtering semantics:
 *         which scalars and collections are (and are not) carried over for a minimal
 *         filter versus a {@code DETAILS} filter, and that copies are distinct instances.</li>
 *     <li>The asymmetric null-handling of {@code parts} inside {@code copy()} (documented bug).</li>
 *     <li>{@link MessageVo#localizableStream()} lineage walking of area / category parents.</li>
 *     <li>{@link MessageVo#sort(String)} description ordering.</li>
 *     <li>{@link MessageVo#rewriteRepoPath(String, String)} path rewriting in parts and attachments.</li>
 *     <li>The {@code checkCreate*} lazy-collection helpers and {@code createDesc}.</li>
 * </ul>
 *
 * Framework-neutral: only plain JUnit 4 assertions are used, so these tests verify the
 * business contract independently of Quarkus and survive a future Spring migration.
 *
 * Determinism: every {@link Date} is built from a fixed epoch (never {@code new Date()}),
 * and each test constructs its own object graph so tests are order-independent.
 */
public class MessageVoTest {

    // Fixed epoch instants - never use current time so the tests stay deterministic.
    private static final Date CREATED = new Date(1_000_000L);
    private static final Date UPDATED = new Date(2_000_000L);

    // ---------------------------------------------------------------------
    // Builders for the object graph used across tests
    // ---------------------------------------------------------------------

    /** Builds an AreaVo with a single name desc in the given language. */
    private static AreaVo area(int id, String lang, String name) {
        AreaVo area = new AreaVo();
        area.setId(id);
        area.setMrn("urn:mrn:area:" + id);
        AreaDescVo desc = new AreaDescVo();
        desc.setLang(lang);
        desc.setName(name);
        area.setDescs(new ArrayList<>(Arrays.asList(desc)));
        return area;
    }

    /** Builds a CategoryVo with a single name desc in the given language. */
    private static CategoryVo category(int id, String lang, String name) {
        CategoryVo cat = new CategoryVo();
        cat.setId(id);
        cat.setMrn("urn:mrn:cat:" + id);
        CategoryDescVo desc = new CategoryDescVo();
        desc.setLang(lang);
        desc.setName(name);
        cat.setDescs(new ArrayList<>(Arrays.asList(desc)));
        return cat;
    }

    /** Builds a MessagePartVo of DETAILS type with a single desc (subject/details). */
    private static MessagePartVo part(int indexNo, String lang, String subject, String details) {
        MessagePartVo p = new MessagePartVo();
        p.setIndexNo(indexNo);
        p.setType(MessagePartType.DETAILS);
        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang(lang);
        desc.setSubject(subject);
        desc.setDetails(details);
        p.setDescs(new ArrayList<>(Arrays.asList(desc)));
        return p;
    }

    /** Builds a MessageDescVo with lang + title. */
    private static MessageDescVo msgDesc(String lang, String title) {
        MessageDescVo desc = new MessageDescVo();
        desc.setLang(lang);
        desc.setTitle(title);
        return desc;
    }

    // =====================================================================
    // copy(DataFilter) - minimal filter
    // =====================================================================

    @Test
    public void copy_withMinimalFilter_copiesOnlyScalarsAndLeavesCollectionsNull() {
        MessageVo source = new MessageVo();
        source.setId("msg-1");
        source.setNumber(42);
        source.setShortId("NW-042-24");
        source.setStatus(Status.PUBLISHED);
        source.setMainType(MainType.NW);
        source.setType(Type.COASTAL_WARNING);
        // Set fields that should NOT be copied with a minimal filter
        source.setCreated(CREATED);
        source.setUpdated(UPDATED);
        source.setHorizontalDatum("WGS84");
        source.setAreas(new ArrayList<>(Arrays.asList(area(1, "en", "North Sea"))));
        source.setCategories(new ArrayList<>(Arrays.asList(category(1, "en", "Firing"))));
        source.setDescs(new ArrayList<>(Arrays.asList(msgDesc("en", "Title"))));
        // parts intentionally left null: a minimal filter must not touch parts.

        MessageVo copy = source.copy(DataFilter.get());

        // The scalars that copy() always carries over.
        assertNotSame(source, copy, "copy must be a distinct instance");
        assertEquals("msg-1", copy.getId());
        assertEquals(Integer.valueOf(42), copy.getNumber());
        assertEquals("NW-042-24", copy.getShortId());
        assertEquals(Status.PUBLISHED, copy.getStatus());
        assertEquals(MainType.NW, copy.getMainType());
        assertEquals(Type.COASTAL_WARNING, copy.getType());

        // Detail fields are gated behind includeDetails(): all must stay null/absent.
        assertNull(copy.getCreated(), "created must not be copied without DETAILS");
        assertNull(copy.getUpdated(), "updated must not be copied without DETAILS");
        assertNull(copy.getHorizontalDatum(), "horizontalDatum must not be copied without DETAILS");
        assertNull(copy.getAreas(), "areas must not be copied without DETAILS");
        assertNull(copy.getCategories(), "categories must not be copied without DETAILS");
        assertNull(copy.getCharts(), "charts must not be copied without DETAILS");
        assertNull(copy.getReferences(), "references must not be copied without DETAILS");
        assertNull(copy.getAttachments(), "attachments must not be copied without DETAILS");
        assertNull(copy.getDescs(), "descs must not be copied without DETAILS/title field");
        assertNull(copy.getParts(), "parts must not be copied without DETAILS/GEOMETRY");
    }

    @Test
    public void copy_withMinimalFilterAndNullParts_isSafe() {
        // A minimal filter triggers neither includeDetails() nor includeGeometry(),
        // so the unguarded parts.forEach() is never reached and null parts is safe.
        MessageVo source = new MessageVo();
        source.setId("msg-safe");
        source.setNumber(7);
        // parts == null

        MessageVo copy = source.copy(DataFilter.get());

        assertEquals("msg-safe", copy.getId());
        assertEquals(Integer.valueOf(7), copy.getNumber());
        assertNull(copy.getParts());
    }

    // =====================================================================
    // copy(DataFilter) - DETAILS filter
    // =====================================================================

    @Test
    public void copy_withDetailsFilter_copiesCollectionsAndPartsAsDistinctInstances() {
        MessageVo source = new MessageVo();
        source.setId("msg-2");
        source.setNumber(99);
        source.setShortId("NM-099-24");
        source.setStatus(Status.DRAFT);
        source.setMainType(MainType.NM);
        source.setType(Type.PERMANENT_NOTICE);
        source.setCreated(CREATED);
        source.setUpdated(UPDATED);
        source.setHorizontalDatum("WGS84");
        source.setOriginalInformation(Boolean.TRUE);

        AreaVo srcArea = area(11, "en", "Kattegat");
        source.setAreas(new ArrayList<>(Arrays.asList(srcArea)));
        source.setCategories(new ArrayList<>(Arrays.asList(category(22, "en", "Diving"))));

        ReferenceVo ref = new ReferenceVo();
        ref.setMessageId("ref-msg-1");
        ref.setType(ReferenceType.REFERENCE);
        source.setReferences(new ArrayList<>(Arrays.asList(ref)));

        AttachmentVo att = new AttachmentVo();
        att.setPath("/repo/file.pdf");
        att.setFileName("file.pdf");
        source.setAttachments(new ArrayList<>(Arrays.asList(att)));

        source.setDescs(new ArrayList<>(Arrays.asList(msgDesc("en", "The Title"))));

        MessagePartVo srcPart = part(0, "en", "Subject", "Details body");
        source.setParts(new ArrayList<>(Arrays.asList(srcPart)));

        MessageVo copy = source.copy(DataFilter.get().fields(DataFilter.DETAILS));

        // Scalars + detail scalars carried over.
        assertEquals("msg-2", copy.getId());
        assertEquals(Integer.valueOf(99), copy.getNumber());
        assertEquals(CREATED, copy.getCreated());
        assertEquals(UPDATED, copy.getUpdated());
        assertEquals("WGS84", copy.getHorizontalDatum());
        assertEquals(Boolean.TRUE, copy.getOriginalInformation());

        // Areas copied (one, same id) and are DISTINCT instances.
        assertNotNull(copy.getAreas(), "areas must be copied with DETAILS");
        assertEquals(1, copy.getAreas().size());
        AreaVo copiedArea = copy.getAreas().get(0);
        assertEquals(Integer.valueOf(11), copiedArea.getId());
        assertNotSame(srcArea, copiedArea, "copied area must be a new instance");

        // Categories copied.
        assertNotNull(copy.getCategories());
        assertEquals(1, copy.getCategories().size());
        assertEquals(Integer.valueOf(22), copy.getCategories().get(0).getId());

        // References copied.
        assertNotNull(copy.getReferences());
        assertEquals(1, copy.getReferences().size());
        assertEquals("ref-msg-1", copy.getReferences().get(0).getMessageId());
        assertEquals(ReferenceType.REFERENCE, copy.getReferences().get(0).getType());

        // Attachments copied.
        assertNotNull(copy.getAttachments());
        assertEquals(1, copy.getAttachments().size());
        assertEquals("/repo/file.pdf", copy.getAttachments().get(0).getPath());

        // Descs copied via copyDescs: title carried over into a new desc of the same lang.
        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("The Title", copy.getDescs().get(0).getTitle());

        // Parts copied (size matches) and are distinct instances; type carried over.
        assertNotNull(copy.getParts());
        assertEquals(1, copy.getParts().size());
        MessagePartVo copiedPart = copy.getParts().get(0);
        assertNotSame(srcPart, copiedPart, "copied part must be a new instance");
        assertEquals(MessagePartType.DETAILS, copiedPart.getType());
        assertEquals("Subject", copiedPart.getDescs().get(0).getSubject());
    }

    // =====================================================================
    // copy() null-guards parts like every other collection
    // =====================================================================

    @Test
    public void copy_withDetailsFilterAndNullParts_leavesPartsNull() {
        // copy() null-guards parts.forEach() like every other collection, so a message with
        // null parts and a DETAILS filter produces a copy with null parts (no exception).
        MessageVo source = new MessageVo();
        source.setId("msg-null-parts");
        // parts == null

        MessageVo copy = source.copy(DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("msg-null-parts", copy.getId());
        assertNull(copy.getParts(), "null parts must not be materialised");
    }

    @Test
    public void copy_withGeometryFilterAndNullParts_leavesPartsNull() {
        // includeGeometry() alone also reaches the (now guarded) parts.forEach().
        MessageVo source = new MessageVo();
        source.setId("msg-null-parts-geo");
        // parts == null

        MessageVo copy = source.copy(DataFilter.get().fields(DataFilter.GEOMETRY));

        assertNull(copy.getParts());
    }

    // =====================================================================
    // localizableStream()
    // =====================================================================

    @Test
    public void localizableStream_includesSelfChildrenAndWalksAreaAndCategoryLineage() {
        MessageVo message = new MessageVo();
        message.setId("msg-stream");

        AttachmentVo att = new AttachmentVo();
        att.setPath("/repo/a.pdf");
        message.setAttachments(new ArrayList<>(Arrays.asList(att)));

        ReferenceVo ref = new ReferenceVo();
        ref.setMessageId("ref-1");
        message.setReferences(new ArrayList<>(Arrays.asList(ref)));

        MessagePartVo prt = part(0, "en", "subj", "det");
        message.setParts(new ArrayList<>(Arrays.asList(prt)));

        // Area with a parent: lineage must be walked via getParent().
        AreaVo parentArea = area(1, "en", "Parent area");
        AreaVo childArea = area(2, "en", "Child area");
        childArea.setParent(parentArea);
        message.setAreas(new ArrayList<>(Arrays.asList(childArea)));

        // Category with a parent.
        CategoryVo parentCat = category(10, "en", "Parent cat");
        CategoryVo childCat = category(20, "en", "Child cat");
        childCat.setParent(parentCat);
        message.setCategories(new ArrayList<>(Arrays.asList(childCat)));

        List<ILocalizable> list = message.localizableStream().collect(Collectors.toList());

        // The message itself plus all direct children plus both area + parent and category + parent.
        assertTrue(list.contains(message), "stream must contain the message itself");
        assertTrue(list.contains(att), "stream must contain the attachment");
        assertTrue(list.contains(ref), "stream must contain the reference");
        assertTrue(list.contains(prt), "stream must contain the part");
        assertTrue(list.contains(childArea), "stream must contain the child area");
        assertTrue(list.contains(parentArea), "stream must contain the parent area (lineage walked)");
        assertTrue(list.contains(childCat), "stream must contain the child category");
        assertTrue(list.contains(parentCat), "stream must contain the parent category (lineage walked)");

        // Exactly: message, attachment, reference, part, 2 areas, 2 categories = 8 entries.
        assertEquals(8, list.size());
    }

    @Test
    public void localizableStream_withNoChildCollections_containsOnlyTheMessage() {
        MessageVo message = new MessageVo();
        message.setId("lonely");

        List<ILocalizable> list = message.localizableStream().collect(Collectors.toList());

        assertEquals(1, list.size());
        assertSame(message, list.get(0));
    }

    // =====================================================================
    // sort(language)
    // =====================================================================

    @Test
    public void sort_movesMatchingLanguageDescToFront() {
        MessageVo message = new MessageVo();
        message.setDescs(new ArrayList<>(Arrays.asList(msgDesc("da", "Titel"), msgDesc("en", "Title"))));

        // Sanity: starts with "da" first.
        assertEquals("da", message.getDescs().get(0).getLang());

        message.sort("en");

        assertEquals("en", message.getDescs().get(0).getLang(), "en must be sorted to the front");
        assertEquals("da", message.getDescs().get(1).getLang());
    }

    @Test
    public void sort_alsoReordersChildEntityDescs() {
        MessageVo message = new MessageVo();
        AttachmentVo att = new AttachmentVo();
        AttachmentDescVo daDesc = new AttachmentDescVo();
        daDesc.setLang("da");
        AttachmentDescVo enDesc = new AttachmentDescVo();
        enDesc.setLang("en");
        att.setDescs(new ArrayList<>(Arrays.asList(daDesc, enDesc)));
        message.setAttachments(new ArrayList<>(Arrays.asList(att)));

        message.sort("en");

        // sort() walks the localizableStream, so the attachment descs get reordered too.
        assertEquals("en", att.getDescs().get(0).getLang());
        assertEquals("da", att.getDescs().get(1).getLang());
    }

    @Test
    public void sort_withBlankLanguage_isNoOpAndDoesNotReorder() {
        MessageVo message = new MessageVo();
        message.setDescs(new ArrayList<>(Arrays.asList(msgDesc("da", "Titel"), msgDesc("en", "Title"))));

        message.sort("   ");

        // StringUtils.isNotBlank("   ") is false -> no reordering happens.
        assertEquals("da", message.getDescs().get(0).getLang());
        assertEquals("en", message.getDescs().get(1).getLang());
    }

    @Test
    public void sort_withNullLanguage_isNoOpAndDoesNotCrash() {
        MessageVo message = new MessageVo();
        message.setDescs(new ArrayList<>(Arrays.asList(msgDesc("da", "Titel"), msgDesc("en", "Title"))));

        message.sort(null);

        assertEquals("da", message.getDescs().get(0).getLang());
        assertEquals("en", message.getDescs().get(1).getLang());
    }

    // =====================================================================
    // rewriteRepoPath(repoPath1, repoPath2)
    // =====================================================================

    @Test
    public void rewriteRepoPath_rewritesPartDetailsAndAttachmentPath() {
        MessageVo message = new MessageVo();

        MessagePartVo prt = part(0, "en", "Subject", "see <img src=\"/old/img.png\"/>");
        message.setParts(new ArrayList<>(Arrays.asList(prt)));

        AttachmentVo att = new AttachmentVo();
        att.setPath("/old/file.pdf");
        message.setAttachments(new ArrayList<>(Arrays.asList(att)));

        message.rewriteRepoPath("/old", "/new");

        assertEquals("see <img src=\"/new/img.png\"/>", prt.getDescs().get(0).getDetails(),
                "part desc details must be rewritten");
        assertEquals("/new/file.pdf", att.getPath(), "attachment path must be rewritten");
    }

    @Test
    public void rewriteRepoPath_leavesNonMatchingContentUnchanged() {
        MessageVo message = new MessageVo();
        MessagePartVo prt = part(0, "en", "Subject", "no path here");
        message.setParts(new ArrayList<>(Arrays.asList(prt)));
        AttachmentVo att = new AttachmentVo();
        att.setPath("/unrelated/file.pdf");
        message.setAttachments(new ArrayList<>(Arrays.asList(att)));

        message.rewriteRepoPath("/old", "/new");

        assertEquals("no path here", prt.getDescs().get(0).getDetails());
        assertEquals("/unrelated/file.pdf", att.getPath());
    }

    @Test
    public void rewriteRepoPath_withBlankFromArgument_isNoOp() {
        MessageVo message = new MessageVo();
        AttachmentVo att = new AttachmentVo();
        att.setPath("/old/file.pdf");
        message.setAttachments(new ArrayList<>(Arrays.asList(att)));

        message.rewriteRepoPath("", "/new");

        assertEquals("/old/file.pdf", att.getPath(), "blank from-path must short-circuit the rewrite");
    }

    @Test
    public void rewriteRepoPath_withBlankToArgument_isNoOp() {
        MessageVo message = new MessageVo();
        AttachmentVo att = new AttachmentVo();
        att.setPath("/old/file.pdf");
        message.setAttachments(new ArrayList<>(Arrays.asList(att)));

        message.rewriteRepoPath("/old", "  ");

        assertEquals("/old/file.pdf", att.getPath(), "blank to-path must short-circuit the rewrite");
    }

    // =====================================================================
    // checkCreate* lazy collection helpers
    // =====================================================================

    @Test
    public void checkCreateParts_createsEmptyListWhenNullAndReturnsSameListWhenSet() {
        MessageVo message = new MessageVo();
        assertNull(message.getParts());

        List<MessagePartVo> created = message.checkCreateParts();
        assertNotNull(created, "checkCreateParts must create the list");
        assertTrue(created.isEmpty(), "created list must be empty");
        assertSame(created, message.getParts(), "field must now reference the created list");

        // Calling again returns the SAME existing list (no replacement).
        assertSame(created, message.checkCreateParts());
    }

    @Test
    public void checkCreateAreas_createsEmptyListWhenNullAndReturnsSameListWhenSet() {
        MessageVo message = new MessageVo();
        List<AreaVo> existing = new ArrayList<>(Arrays.asList(area(1, "en", "X")));
        message.setAreas(existing);

        assertSame(existing, message.checkCreateAreas(), "existing list must be returned unchanged");

        MessageVo empty = new MessageVo();
        List<AreaVo> created = empty.checkCreateAreas();
        assertTrue(created.isEmpty());
        assertSame(created, empty.getAreas());
    }

    @Test
    public void checkCreateCategories_createsEmptyListWhenNullAndReturnsSameListWhenSet() {
        MessageVo message = new MessageVo();
        List<CategoryVo> created = message.checkCreateCategories();
        assertTrue(created.isEmpty());
        assertSame(created, message.getCategories());
        assertSame(created, message.checkCreateCategories());
    }

    @Test
    public void checkCreateCharts_createsEmptyListWhenNull() {
        MessageVo message = new MessageVo();
        List<ChartVo> created = message.checkCreateCharts();
        assertNotNull(created);
        assertTrue(created.isEmpty());
        assertSame(created, message.getCharts());
        assertSame(created, message.checkCreateCharts());
    }

    @Test
    public void checkCreateReferences_createsEmptyListWhenNull() {
        MessageVo message = new MessageVo();
        List<ReferenceVo> created = message.checkCreateReferences();
        assertTrue(created.isEmpty());
        assertSame(created, message.getReferences());
        assertSame(created, message.checkCreateReferences());
    }

    @Test
    public void checkCreateAttachments_createsEmptyListWhenNull() {
        MessageVo message = new MessageVo();
        List<AttachmentVo> created = message.checkCreateAttachments();
        assertTrue(created.isEmpty());
        assertSame(created, message.getAttachments());
        assertSame(created, message.checkCreateAttachments());
    }

    // =====================================================================
    // createDesc(lang)
    // =====================================================================

    @Test
    public void createDesc_addsMessageDescVoWithGivenLang() {
        MessageVo message = new MessageVo();

        MessageDescVo desc = message.createDesc("en");

        assertEquals("en", desc.getLang());
        assertNotNull(message.getDescs(), "createDesc must lazily create the descs list");
        assertEquals(1, message.getDescs().size());
        assertSame(desc, message.getDescs().get(0), "created desc must be appended to the descs list");
    }

    @Test
    public void createDesc_appendsToExistingDescs() {
        MessageVo message = new MessageVo();
        message.createDesc("da");

        MessageDescVo second = message.createDesc("en");

        assertEquals(2, message.getDescs().size());
        assertEquals("da", message.getDescs().get(0).getLang());
        assertSame(second, message.getDescs().get(1));
    }
}
