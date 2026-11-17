/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.publication;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.PeriodicalType;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.model.DataFilter;
import org.niord.model.publication.PublicationCategoryVo;
import org.niord.model.publication.PublicationDescVo;
import org.niord.model.publication.PublicationType;
import org.niord.model.publication.PublicationVo;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the mutation / copy logic of the {@link Publication} entity that complements
 * {@link PublicationEntityTest}: {@code updatePublication} (entity-to-entity copy + map and desc
 * rebuild), the plain {@link PublicationVo} constructor (which must <em>not</em> populate the
 * system-only fields), the plain {@code toVo(PublicationVo.class)} projection (which must strip the
 * system-only fields), and the {@code ${...}}-placeholder expansion performed by
 * {@code updateFromTemplate}.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. Every template
 * scenario deliberately keeps {@code messageTagFormat} blank so the {@code findOrCreatePublicMessageTag}
 * branch (which needs a {@code MessageTagService} / a transaction) is never reached, letting us pass a
 * {@code null} service. Assertions check concrete field values, map contents and exact expanded strings
 * so they survive a future Spring port.
 */
public class PublicationUpdateEntityTest {

    /** Builds a fully-populated source publication used by the updatePublication tests. */
    private Publication fullSource() {
        Publication src = new Publication();
        src.setPublicationId("source-id");
        src.setStatus(PublicationStatus.ACTIVE);
        src.setMainType(PublicationMainType.TEMPLATE);
        src.setType(PublicationType.REPOSITORY);
        src.setEdition(7);
        src.setPeriodicalType(PeriodicalType.MONTHLY);
        src.setMessagePublication(MessagePublication.EXTERNAL);
        src.setLanguageSpecific(true);
        src.setMessageTagFormat("tag-${year}");
        src.setMessageTagFilter("msg.status == Status.PUBLISHED");
        src.setPublishDateFrom(fixedDate(2021, Calendar.FEBRUARY, 1));
        src.setPublishDateTo(fixedDate(2021, Calendar.FEBRUARY, 28));
        return src;
    }

    /** Returns a deterministic date with a zeroed time-of-day. */
    private static Date fixedDate(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        return c.getTime();
    }

    // --------------------------------------------------------------------------------------------- //
    // updatePublication — scalar field copy
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updatePublicationCopiesAllScalarFields() {
        Publication src = fullSource();

        // Target starts on the entity defaults, all different from the source.
        Publication target = new Publication();
        assertEquals(PublicationStatus.DRAFT, target.getStatus());
        assertEquals(PublicationMainType.PUBLICATION, target.getMainType());
        assertEquals(PublicationType.LINK, target.getType());

        target.updatePublication(src);

        assertEquals("source-id", target.getPublicationId());
        assertEquals(PublicationStatus.ACTIVE, target.getStatus());
        assertEquals(PublicationMainType.TEMPLATE, target.getMainType());
        assertEquals(PublicationType.REPOSITORY, target.getType());
        assertEquals(Integer.valueOf(7), target.getEdition());
        assertEquals(PeriodicalType.MONTHLY, target.getPeriodicalType());
        assertEquals(MessagePublication.EXTERNAL, target.getMessagePublication());
        assertTrue(target.isLanguageSpecific());
        assertEquals("tag-${year}", target.getMessageTagFormat());
        assertEquals("msg.status == Status.PUBLISHED", target.getMessageTagFilter());
        assertEquals(fixedDate(2021, Calendar.FEBRUARY, 1), target.getPublishDateFrom());
        assertEquals(fixedDate(2021, Calendar.FEBRUARY, 28), target.getPublishDateTo());
    }

    // --------------------------------------------------------------------------------------------- //
    // updatePublication — printSettings / reportParams replacement
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updatePublicationReplacesPrintSettingsAndReportParams() {
        Publication src = fullSource();
        src.getPrintSettings().put("pageSize", "A4");
        src.getReportParams().put("area", "north");

        Publication target = new Publication();
        // Pre-existing keys that must be dropped (the maps are cleared, not merged).
        target.getPrintSettings().put("pageSize", "A3");
        target.getPrintSettings().put("legacyKey", "stale");
        target.getReportParams().put("legacyParam", "stale");

        target.updatePublication(src);

        // Old keys gone, new keys present and updated.
        assertEquals(1, target.getPrintSettings().size());
        assertEquals("A4", target.getPrintSettings().get("pageSize"));
        assertFalse(target.getPrintSettings().containsKey("legacyKey"));

        assertEquals(1, target.getReportParams().size());
        assertEquals("north", target.getReportParams().get("area"));
        assertFalse(target.getReportParams().containsKey("legacyParam"));
    }

    // --------------------------------------------------------------------------------------------- //
    // updatePublication — descs merged in place, blanks dropped
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updatePublicationRebuildsDescsAndDropsBlankSourceDescs() {
        Publication src = fullSource();
        // A populated source desc -> must survive the copy.
        PublicationDesc en = src.createDesc("en");
        en.setTitle("English title");
        // A completely blank source desc -> must be removed by copyDescsAndRemoveBlanks.
        src.createDesc("da");

        Publication target = new Publication();
        // A stale target desc, in a language the source lacks, that the merge must remove.
        PublicationDesc stale = target.createDesc("fr");
        stale.setTitle("Stale French");

        target.updatePublication(src);

        // Only the one populated source desc remains.
        assertEquals(1, target.getDescs().size());
        assertNull(target.getDesc("fr"), "the stale 'fr' desc must be gone");
        assertNull(target.getDesc("da"), "the blank 'da' source desc must be dropped");
        assertEquals("English title", target.getDesc("en").getTitle());
    }

    /**
     * A language both publications have keeps its desc object and takes the source's fields (a field the source
     * leaves empty is emptied). A new object would be a second row for the same (lang, entity_id), which Hibernate
     * inserts before it deletes the orphan, so the save broke the unique key (PUB2).
     */
    @Test
    public void updatePublicationKeepsTheDescOfALanguageBothHave() {
        Publication src = fullSource();
        PublicationDesc srcEn = src.createDesc("en");
        srcEn.setTitle("New English title");
        PublicationDesc srcDe = src.createDesc("de");
        srcDe.setTitle("Deutscher Titel");

        Publication target = new Publication();
        PublicationDesc en = target.createDesc("en");
        en.setTitle("Old English title");
        en.setLink("https://example.org/old.pdf");
        PublicationDesc da = target.createDesc("da");
        da.setTitle("Dansk titel");

        target.updatePublication(src);

        assertEquals(2, target.getDescs().size());
        assertTrue(target.getDesc("en") == en, "the English desc is the same object, updated in place");
        assertEquals("New English title", en.getTitle());
        assertNull(en.getLink(), "a field the source leaves empty is emptied");
        assertNull(target.getDesc("da"), "a language the source lacks is removed");
        assertEquals("Deutscher Titel", target.getDesc("de").getTitle());
        assertTrue(target.getDesc("de") != srcDe, "a new language gets a desc of its own");
        assertTrue(target.getDesc("de").getEntity() == target, "owned by the target");
    }

    // --------------------------------------------------------------------------------------------- //
    // Publication(PublicationVo) — plain VO constructor keeps system defaults
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void plainVoConstructorCopiesBaseFieldsAndKeepsSystemDefaults() {
        PublicationVo vo = new PublicationVo();
        vo.setPublicationId("plain-id");
        vo.setType(PublicationType.REPOSITORY);
        vo.setPublishDateFrom(fixedDate(2022, Calendar.MARCH, 1));
        vo.setPublishDateTo(fixedDate(2022, Calendar.MARCH, 31));

        PublicationCategoryVo cat = new PublicationCategoryVo();
        cat.setCategoryId("cat-1");
        cat.setPriority(3);
        cat.setPublish(true);
        vo.setCategory(cat);

        PublicationDescVo desc = new PublicationDescVo();
        desc.setLang("en");
        desc.setTitle("Plain VO title");
        vo.checkCreateDescs().add(desc);

        Publication p = new Publication(vo);

        // Base fields copied from the plain VO.
        assertEquals("plain-id", p.getPublicationId());
        assertEquals(PublicationType.REPOSITORY, p.getType());
        assertEquals(fixedDate(2022, Calendar.MARCH, 1), p.getPublishDateFrom());
        assertEquals(fixedDate(2022, Calendar.MARCH, 31), p.getPublishDateTo());
        assertEquals("cat-1", p.getCategory().getCategoryId());
        assertEquals(3, p.getCategory().getPriority());
        assertEquals(1, p.getDescs().size());
        assertEquals("Plain VO title", p.getDesc("en").getTitle());

        // The system-only fields are NOT touched by the plain-VO branch -> entity defaults stay.
        assertEquals(PublicationStatus.DRAFT, p.getStatus());
        assertEquals(PublicationMainType.PUBLICATION, p.getMainType());
        assertEquals(0, p.getRevision());
        assertNull(p.getEdition());
        assertNull(p.getPeriodicalType());
        assertNull(p.getMessageTagFormat());
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(PublicationVo.class) — plain projection strips system-only fields
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoPlainReturnsValueObjectWithoutSystemFields() {
        Publication p = new Publication();
        p.setPublicationId("vo-id");
        p.setRepoPath("publications/v/oi/vo-id");
        p.setRevision(9);
        p.setStatus(PublicationStatus.ACTIVE);
        p.setMainType(PublicationMainType.TEMPLATE);
        p.setType(PublicationType.LINK);
        p.setEdition(4);
        p.setMessageTagFormat("tag");
        p.setPublishDateFrom(fixedDate(2020, Calendar.JANUARY, 1));
        PublicationDesc en = p.createDesc("en");
        en.setTitle("VO title");

        PublicationVo vo = p.toVo(PublicationVo.class, DataFilter.get());

        // The plain VO is exactly a PublicationVo, never a SystemPublicationVo.
        assertFalse(vo instanceof org.niord.core.publication.vo.SystemPublicationVo,
                "must not be a SystemPublicationVo");
        assertEquals(PublicationVo.class, vo.getClass());

        // Base fields are present...
        assertEquals("vo-id", vo.getPublicationId());
        assertEquals(PublicationType.LINK, vo.getType());
        assertEquals(fixedDate(2020, Calendar.JANUARY, 1), vo.getPublishDateFrom());
        assertEquals(1, vo.getDescs().size());
        assertEquals("VO title", vo.getDesc("en").getTitle());

        // ...but the system-only fields (revision/status/mainType/repoPath/...) are simply not on a
        // plain PublicationVo. We verify the projection did not, for instance, leak them via a subtype.
        assertFalse(vo instanceof org.niord.core.repo.IRepoBackedVo);
    }

    // --------------------------------------------------------------------------------------------- //
    // updateFromTemplate — ${year}/${edition} expansion of the desc title
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateFromTemplateExpandsTitleFormatPlaceholders() {
        // Template carries a title FORMAT with placeholders; messageTagFormat stays blank so the
        // find-or-create-tag branch (needing a MessageTagService) is never entered.
        Publication template = new Publication();
        template.setType(PublicationType.REPOSITORY);
        PublicationDesc tDesc = template.createDesc("en");
        tDesc.setTitleFormat("firing-${year}-v${edition}");

        Publication p = new Publication();
        p.setStatus(PublicationStatus.DRAFT);
        p.setEdition(5);                                       // -> ${edition} = 5
        p.setPublishDateFrom(fixedDate(2023, Calendar.MARCH, 15)); // -> ${year} = 2023
        p.setTemplate(template);

        p.updateFromTemplate(new PublicationTemplateUpdateCtx(p, null));

        // The template's titleFormat is expanded into the concrete publication's desc title.
        assertEquals("firing-2023-v5", p.getDesc("en").getTitle());
        // messageTagFormat was never set, so no tag was looked up / created.
        assertNull(p.getMessageTag());
        assertNull(p.getMessageTagFormat());
    }
}
