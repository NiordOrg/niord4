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
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainMapper;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagMapper;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.PeriodicalType;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.model.DataFilter;
import org.niord.model.publication.PublicationCategoryDescVo;
import org.niord.model.publication.PublicationCategoryVo;
import org.niord.model.publication.PublicationDescVo;
import org.niord.model.publication.PublicationType;
import org.niord.model.publication.PublicationVo;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PublicationMapper}.
 *
 * <p>These are primarily <em>differential</em> tests: the {@link Publication} entity still carries its
 * original {@code toVo(Class, DataFilter)} method, so each test asserts that the extracted mapper
 * produces output identical to the entity's own conversion, field-by-field across the nested graph
 * (category + descs, descs, and the two-tier {@code SystemPublicationVo} fields incl. the
 * self-referential template and the injected {@code Domain}/{@code MessageTag}). A {@link DataFilter}
 * on/off case and a null-entity case are also covered.
 */
public class PublicationMapperTest {

    /** Mapper under test, with its cross-package {@code @Inject} fields wired to real instances. */
    private final PublicationMapper mapper = newMapper();

    private static PublicationMapper newMapper() {
        // Same-package would suffice for visibility; DomainMapper/MessageTagMapper live in other
        // packages but expose no-arg constructors, so we build real instances and pass them into
        // PublicationMapper's constructor. The Domain we map carries no areas/categories/message-series,
        // so DomainMapper needs none of its own injects.
        return new PublicationMapper(new DomainMapper(), new MessageTagMapper());
    }

    private static final Date CREATED = new Date(1_600_000_000_000L);
    private static final Date UPDATED = new Date(1_600_000_500_000L);
    private static final Date PUB_FROM = new Date(1_600_001_000_000L);
    private static final Date PUB_TO   = new Date(1_600_002_000_000L);
    private static final Date TAG_EXPIRY = new Date(1_600_003_000_000L);

    /** Builds a representative publication-category with two localized descs. */
    private PublicationCategory newCategory() {
        PublicationCategory category = new PublicationCategory();
        category.setCategoryId("cat-1");
        category.setPriority(7);
        category.setPublish(true);

        PublicationCategoryDesc en = category.createDesc("en");
        en.setName("Category EN");
        en.setDescription("Desc EN");

        PublicationCategoryDesc da = category.createDesc("da");
        da.setName("Kategori DA");
        da.setDescription("Beskrivelse DA");

        return category;
    }

    /** Builds a leaf template publication (no further nested template). */
    private Publication newTemplate() {
        Publication template = new Publication();
        template.setPublicationId("template-id");
        template.setCreated(CREATED);
        template.setUpdated(UPDATED);
        template.setRevision(3);
        template.setMainType(PublicationMainType.TEMPLATE);
        template.setStatus(PublicationStatus.ACTIVE);
        template.setType(PublicationType.LINK);
        template.setCategory(newCategory());
        template.setEdition(2);
        template.setMessageTagFormat("fmt-${year}");
        template.setRepoPath("repo/template");

        PublicationDesc en = template.createDesc("en");
        en.setTitle("Template EN");
        en.setTitleFormat("Template ${edition}");
        en.setLink("/rest/repo/file/template/en.pdf");
        en.setFileName("en.pdf");
        en.setMessagePublicationFormat("MP ${x}");

        return template;
    }

    /** Builds a fully-populated publication referencing a template, domain, category and message tag. */
    private Publication newPublication() {
        Publication pub = new Publication();
        pub.setPublicationId("pub-id");
        pub.setCreated(CREATED);
        pub.setUpdated(UPDATED);
        pub.setRevision(11);
        pub.setMainType(PublicationMainType.PUBLICATION);
        pub.setStatus(PublicationStatus.RECORDING);
        pub.setType(PublicationType.REPOSITORY);
        pub.setCategory(newCategory());
        pub.setTemplate(newTemplate());
        pub.setDomain(newDomain());
        pub.setEdition(5);
        pub.setMessageTagFormat("firing-${year}-v${edition}");
        pub.setMessageTagFilter("msg.status == Status.PUBLISHED");
        pub.setMessageTag(newMessageTag());
        pub.setPeriodicalType(PeriodicalType.WEEKLY);
        pub.setMessagePublication(MessagePublication.EXTERNAL);
        pub.setLanguageSpecific(true);
        pub.setPublishDateFrom(PUB_FROM);
        pub.setPublishDateTo(PUB_TO);
        pub.setRepoPath("repo/pub");
        pub.getPrintSettings().put("pageSize", "A4");
        pub.getPrintSettings().put("landscape", Boolean.TRUE);
        pub.getReportParams().put("year", "2026");

        PublicationDesc en = pub.createDesc("en");
        en.setTitle("Publication EN");
        en.setTitleFormat("Pub ${edition}");
        en.setLink("/rest/repo/file/pub/en.pdf");
        en.setFileName("en.pdf");
        en.setMessagePublicationFormat("MP-EN");

        PublicationDesc da = pub.createDesc("da");
        da.setTitle("Publikation DA");
        da.setTitleFormat("Pub ${edition}");
        da.setLink("/rest/repo/file/pub/da.pdf");
        da.setFileName("da.pdf");
        da.setMessagePublicationFormat("MP-DA");

        return pub;
    }

    private Domain newDomain() {
        Domain domain = new Domain();
        domain.setDomainId("niord-domain");
        domain.setActive(true);
        domain.setName("The Domain");
        domain.setTimeZone("Europe/Copenhagen");
        return domain;
    }

    private MessageTag newMessageTag() {
        MessageTag tag = new MessageTag();
        tag.setTagId("tag-1");
        tag.setName("Tag One");
        tag.setType(MessageTagType.PUBLIC);
        tag.setExpiryDate(TAG_EXPIRY);
        tag.setLocked(true);
        return tag;
    }

    /*************************/
    /** Differential: toVo  **/
    /*************************/

    @Test
    public void publicationVoMatchesEntityToVo() {
        Publication pub = newPublication();
        DataFilter filter = DataFilter.get().fields(DataFilter.ALL);

        PublicationVo expected = pub.toVo(PublicationVo.class, filter);
        PublicationVo actual = mapper.toVo(pub, PublicationVo.class, filter);

        // A plain PublicationVo must NOT be a SystemPublicationVo
        assertFalse(expected instanceof SystemPublicationVo);
        assertFalse(actual instanceof SystemPublicationVo);

        assertPublicationBaseEquals(expected, actual);
    }

    @Test
    public void systemPublicationVoMatchesEntityToVo() {
        Publication pub = newPublication();
        DataFilter filter = DataFilter.get().fields(DataFilter.ALL);

        SystemPublicationVo expected = pub.toVo(SystemPublicationVo.class, filter);
        SystemPublicationVo actual = mapper.toVo(pub, SystemPublicationVo.class, filter);

        assertPublicationBaseEquals(expected, actual);
        assertSystemFieldsEquals(expected, actual);

        // Sanity: revision was bumped by +1 on both
        assertEquals(11 + 1, expected.getRevision());
        assertEquals(expected.getRevision(), actual.getRevision());

        // Nested template carries through as a SystemPublicationVo with its own +1 revision bump
        assertNotNull(actual.getTemplate());
        assertEquals(3 + 1, actual.getTemplate().getRevision());
        assertPublicationBaseEquals(expected.getTemplate(), actual.getTemplate());
        assertSystemFieldsEquals(expected.getTemplate(), actual.getTemplate());
    }

    @Test
    public void systemPublicationVoMatchesEntityWithLanguageFilter() {
        Publication pub = newPublication();
        DataFilter filter = DataFilter.get().lang("da");

        SystemPublicationVo expected = pub.toVo(SystemPublicationVo.class, filter);
        SystemPublicationVo actual = mapper.toVo(pub, SystemPublicationVo.class, filter);

        // The language filter keeps only the Danish desc on both sides
        assertEquals(1, expected.getDescs().size());
        assertEquals(1, actual.getDescs().size());
        assertEquals("da", actual.getDescs().get(0).getLang());

        assertPublicationBaseEquals(expected, actual);
        assertSystemFieldsEquals(expected, actual);

        // Category descs are filtered too (the entity passes the unscoped dataFilter to the category)
        assertEquals(expected.getCategory().getDescs().size(), actual.getCategory().getDescs().size());
        assertEquals(1, actual.getCategory().getDescs().size());
        assertEquals("da", actual.getCategory().getDescs().get(0).getLang());
    }

    @Test
    public void toVoLeavesDescsNullWhenEntityHasNoDescs() {
        Publication pub = new Publication();
        pub.setPublicationId("empty");
        pub.setType(PublicationType.LINK);

        PublicationVo expected = pub.toVo(PublicationVo.class, DataFilter.get().fields(DataFilter.ALL));
        PublicationVo actual = mapper.toVo(pub, PublicationVo.class, DataFilter.get().fields(DataFilter.ALL));

        assertNull(expected.getDescs());
        assertNull(actual.getDescs());
        assertEquals("empty", actual.getPublicationId());
    }

    @Test
    public void toVoReturnsNullForNullEntity() {
        assertNull(mapper.toVo(null, PublicationVo.class, DataFilter.get().fields(DataFilter.ALL)));
        assertNull(mapper.toVo(null, SystemPublicationVo.class, DataFilter.get().fields(DataFilter.ALL)));
    }

    /*************************/
    /** toEntity            **/
    /*************************/

    @Test
    public void toEntityFromSystemVoMatchesEntityConstructor() {
        Publication pub = newPublication();
        SystemPublicationVo vo = pub.toVo(SystemPublicationVo.class, DataFilter.get().fields(DataFilter.ALL));

        Publication viaCtor = new Publication(vo);
        Publication viaMapper = mapper.toEntity(vo);

        assertEntityEquals(viaCtor, viaMapper);
    }

    @Test
    public void toEntityCopiesSystemFieldsAndNestedGraph() {
        Publication pub = newPublication();
        SystemPublicationVo vo = pub.toVo(SystemPublicationVo.class, DataFilter.get().fields(DataFilter.ALL));

        Publication entity = mapper.toEntity(vo);

        assertEquals("pub-id", entity.getPublicationId());
        assertEquals(PublicationType.REPOSITORY, entity.getType());
        assertEquals(PublicationStatus.RECORDING, entity.getStatus());
        assertEquals(PublicationMainType.PUBLICATION, entity.getMainType());
        assertEquals(Integer.valueOf(5), entity.getEdition());
        assertEquals("firing-${year}-v${edition}", entity.getMessageTagFormat());
        assertEquals("msg.status == Status.PUBLISHED", entity.getMessageTagFilter());
        assertEquals(PeriodicalType.WEEKLY, entity.getPeriodicalType());
        assertEquals(MessagePublication.EXTERNAL, entity.getMessagePublication());
        assertTrue(entity.isLanguageSpecific());
        // NB: the VO carried revision 11+1, and the constructor copies it back verbatim
        assertEquals(12, entity.getRevision());

        // Nested category + descs
        assertNotNull(entity.getCategory());
        assertEquals("cat-1", entity.getCategory().getCategoryId());
        assertEquals(7, entity.getCategory().getPriority());
        assertTrue(entity.getCategory().isPublish());
        assertEquals(2, entity.getCategory().getDescs().size());
        // addDesc wires the back-reference
        assertEquals(entity.getCategory(), entity.getCategory().getDescs().get(0).getEntity());

        // Nested domain + tag round-tripped through their mappers
        assertNotNull(entity.getDomain());
        assertEquals("niord-domain", entity.getDomain().getDomainId());
        assertNotNull(entity.getMessageTag());
        assertEquals("tag-1", entity.getMessageTag().getTagId());

        // Nested template
        assertNotNull(entity.getTemplate());
        assertEquals("template-id", entity.getTemplate().getPublicationId());

        // Maps copied
        assertEquals("A4", entity.getPrintSettings().get("pageSize"));
        assertEquals("2026", entity.getReportParams().get("year"));

        // Descs
        assertEquals(2, entity.getDescs().size());
        assertEquals(entity, entity.getDescs().get(0).getEntity());
    }

    @Test
    public void toEntityFromPlainVoLeavesSystemFieldsAtDefaults() {
        PublicationVo vo = new PublicationVo();
        vo.setPublicationId("plain");
        vo.setType(PublicationType.LINK);

        Publication entity = mapper.toEntity(vo);

        assertEquals("plain", entity.getPublicationId());
        assertEquals(PublicationType.LINK, entity.getType());
        // Defaults from the entity's field initializers survive (no System branch executed)
        assertEquals(PublicationStatus.DRAFT, entity.getStatus());
        assertEquals(PublicationMainType.PUBLICATION, entity.getMainType());
        assertEquals(MessagePublication.NONE, entity.getMessagePublication());
        assertNull(entity.getDomain());
        assertNull(entity.getMessageTag());
        assertNull(entity.getTemplate());
    }

    @Test
    public void toEntitySkipsUndefinedDescs() {
        PublicationVo vo = new PublicationVo();
        vo.setPublicationId("p");
        vo.setType(PublicationType.LINK);

        PublicationDescVo defined = new PublicationDescVo();
        defined.setLang("en");
        defined.setTitle("Has a title");

        PublicationDescVo blank = new PublicationDescVo();
        blank.setLang("da");
        // no fields set -> descDefined() == false

        vo.setDescs(List.of(defined, blank));

        Publication entity = mapper.toEntity(vo);

        assertEquals(1, entity.getDescs().size());
        assertEquals("en", entity.getDescs().get(0).getLang());
        assertEquals("Has a title", entity.getDescs().get(0).getTitle());
    }

    @Test
    public void toEntityReturnsNullForNullVo() {
        assertNull(mapper.toEntity(null));
    }

    /*************************/
    /** updateEntity        **/
    /*************************/

    @Test
    public void updateEntityCopiesStateFromSourceEntity() {
        Publication target = new Publication();
        target.setPublicationId("old");
        target.getPrintSettings().put("stale", "x");
        target.getReportParams().put("stale", "y");
        PublicationDesc staleDesc = target.createDesc("fr");
        staleDesc.setTitle("Old FR");
        PublicationDesc oldEn = target.createDesc("en");
        oldEn.setTitle("Old EN");

        Publication source = newPublication();

        mapper.updateFrom(target, source);

        assertEquals("pub-id", target.getPublicationId());
        assertEquals(11, target.getRevision());
        assertEquals(PublicationStatus.RECORDING, target.getStatus());
        assertEquals(PublicationMainType.PUBLICATION, target.getMainType());
        assertEquals(PublicationType.REPOSITORY, target.getType());
        assertEquals(Integer.valueOf(5), target.getEdition());
        assertEquals(PeriodicalType.WEEKLY, target.getPeriodicalType());
        assertEquals(MessagePublication.EXTERNAL, target.getMessagePublication());
        assertTrue(target.isLanguageSpecific());

        // Nested references copied by reference (entity-to-entity copy)
        assertSame(source.getCategory(), target.getCategory());
        assertSame(source.getTemplate(), target.getTemplate());
        assertSame(source.getDomain(), target.getDomain());
        assertSame(source.getMessageTag(), target.getMessageTag());

        // Maps were cleared then replaced
        assertFalse(target.getPrintSettings().containsKey("stale"));
        assertEquals("A4", target.getPrintSettings().get("pageSize"));
        assertFalse(target.getReportParams().containsKey("stale"));
        assertEquals("2026", target.getReportParams().get("year"));

        // Descs merged in place (stale FR removed; the 2 source langs survive). The English desc the target had
        // keeps its object, so the save updates its row rather than inserting a second one (PUB2).
        assertEquals(2, target.getDescs().size());
        List<String> langs = target.getDescs().stream().map(PublicationDesc::getLang).toList();
        assertTrue(langs.contains("en"));
        assertTrue(langs.contains("da"));
        assertFalse(langs.contains("fr"));
        assertSame(oldEn, target.getDesc("en"));
        assertEquals(source.getDesc("en").getTitle(), oldEn.getTitle());
    }

    /*************************/
    /** Assertion helpers   **/
    /*************************/

    private static void assertSame(Object expected, Object actual) {
        assertTrue(expected == actual, "expected the same instance");
    }

    /** Asserts the base {@link PublicationVo} fields (incl. category + descs) match. */
    private void assertPublicationBaseEquals(PublicationVo expected, PublicationVo actual) {
        assertEquals(expected.getPublicationId(), actual.getPublicationId());
        assertEquals(expected.getCreated(), actual.getCreated());
        assertEquals(expected.getUpdated(), actual.getUpdated());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getPublishDateFrom(), actual.getPublishDateFrom());
        assertEquals(expected.getPublishDateTo(), actual.getPublishDateTo());

        assertCategoryEquals(expected.getCategory(), actual.getCategory());
        assertDescsEqual(expected.getDescs(), actual.getDescs());
    }

    private void assertCategoryEquals(PublicationCategoryVo expected, PublicationCategoryVo actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.getCategoryId(), actual.getCategoryId());
        assertEquals(expected.getPriority(), actual.getPriority());
        assertEquals(expected.getPublish(), actual.getPublish());

        if (expected.getDescs() == null) {
            assertNull(actual.getDescs());
            return;
        }
        assertEquals(expected.getDescs().size(), actual.getDescs().size());
        for (int i = 0; i < expected.getDescs().size(); i++) {
            PublicationCategoryDescVo e = expected.getDescs().get(i);
            PublicationCategoryDescVo a = actual.getDescs().get(i);
            assertEquals(e.getLang(), a.getLang());
            assertEquals(e.getName(), a.getName());
            assertEquals(e.getDescription(), a.getDescription());
        }
    }

    private void assertDescsEqual(List<PublicationDescVo> expected, List<PublicationDescVo> actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            PublicationDescVo e = expected.get(i);
            PublicationDescVo a = actual.get(i);
            assertEquals(e.getLang(), a.getLang());
            assertEquals(e.getTitle(), a.getTitle());
            assertEquals(e.getTitleFormat(), a.getTitleFormat());
            assertEquals(e.getLink(), a.getLink());
            assertEquals(e.getFileName(), a.getFileName());
            assertEquals(e.getMessagePublicationFormat(), a.getMessagePublicationFormat());
        }
    }

    /** Asserts the System-only fields match between two SystemPublicationVo projections. */
    private void assertSystemFieldsEquals(SystemPublicationVo expected, SystemPublicationVo actual) {
        assertEquals(expected.getRevision(), actual.getRevision());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getMainType(), actual.getMainType());
        assertEquals(expected.getEdition(), actual.getEdition());
        assertEquals(expected.getMessageTagFormat(), actual.getMessageTagFormat());
        assertEquals(expected.getMessageTagFilter(), actual.getMessageTagFilter());
        assertEquals(expected.getPeriodicalType(), actual.getPeriodicalType());
        assertEquals(expected.getMessagePublication(), actual.getMessagePublication());
        assertEquals(expected.isLanguageSpecific(), actual.isLanguageSpecific());
        assertEquals(expected.getRepoPath(), actual.getRepoPath());
        assertEquals(expected.getPrintSettings(), actual.getPrintSettings());
        assertEquals(expected.getReportParams(), actual.getReportParams());

        // Injected Domain
        if (expected.getDomain() == null) {
            assertNull(actual.getDomain());
        } else {
            assertNotNull(actual.getDomain());
            assertEquals(expected.getDomain().getDomainId(), actual.getDomain().getDomainId());
            assertEquals(expected.getDomain().getName(), actual.getDomain().getName());
            assertEquals(expected.getDomain().isActive(), actual.getDomain().isActive());
            assertEquals(expected.getDomain().getTimeZone(), actual.getDomain().getTimeZone());
        }

        // Injected MessageTag
        if (expected.getMessageTag() == null) {
            assertNull(actual.getMessageTag());
        } else {
            assertNotNull(actual.getMessageTag());
            assertEquals(expected.getMessageTag().getTagId(), actual.getMessageTag().getTagId());
            assertEquals(expected.getMessageTag().getName(), actual.getMessageTag().getName());
            assertEquals(expected.getMessageTag().getType(), actual.getMessageTag().getType());
            assertEquals(expected.getMessageTag().getExpiryDate(), actual.getMessageTag().getExpiryDate());
            assertEquals(expected.getMessageTag().isLocked(), actual.getMessageTag().isLocked());
        }
    }

    /** Asserts two entities produced from the same VO carry the same scalar/nested state. */
    private void assertEntityEquals(Publication expected, Publication actual) {
        assertEquals(expected.getPublicationId(), actual.getPublicationId());
        assertEquals(expected.getCreated(), actual.getCreated());
        assertEquals(expected.getUpdated(), actual.getUpdated());
        assertEquals(expected.getRevision(), actual.getRevision());
        assertEquals(expected.getMainType(), actual.getMainType());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getEdition(), actual.getEdition());
        assertEquals(expected.getMessageTagFormat(), actual.getMessageTagFormat());
        assertEquals(expected.getMessageTagFilter(), actual.getMessageTagFilter());
        assertEquals(expected.getPeriodicalType(), actual.getPeriodicalType());
        assertEquals(expected.getMessagePublication(), actual.getMessagePublication());
        assertEquals(expected.isLanguageSpecific(), actual.isLanguageSpecific());
        assertEquals(expected.getPublishDateFrom(), actual.getPublishDateFrom());
        assertEquals(expected.getPublishDateTo(), actual.getPublishDateTo());
        assertEquals(expected.getPrintSettings(), actual.getPrintSettings());
        assertEquals(expected.getReportParams(), actual.getReportParams());

        // Category
        assertEquals(expected.getCategory().getCategoryId(), actual.getCategory().getCategoryId());
        assertEquals(expected.getCategory().getPriority(), actual.getCategory().getPriority());
        assertEquals(expected.getCategory().isPublish(), actual.getCategory().isPublish());
        assertEquals(expected.getCategory().getDescs().size(), actual.getCategory().getDescs().size());

        // Domain / tag / template
        assertEquals(expected.getDomain().getDomainId(), actual.getDomain().getDomainId());
        assertEquals(expected.getMessageTag().getTagId(), actual.getMessageTag().getTagId());
        assertEquals(expected.getTemplate().getPublicationId(), actual.getTemplate().getPublicationId());

        // Descs
        assertEquals(expected.getDescs().size(), actual.getDescs().size());
        for (int i = 0; i < expected.getDescs().size(); i++) {
            PublicationDesc e = expected.getDescs().get(i);
            PublicationDesc a = actual.getDescs().get(i);
            assertEquals(e.getLang(), a.getLang());
            assertEquals(e.getTitle(), a.getTitle());
            assertEquals(e.getTitleFormat(), a.getTitleFormat());
            assertEquals(e.getLink(), a.getLink());
            assertEquals(e.getFileName(), a.getFileName());
            assertEquals(e.getMessagePublicationFormat(), a.getMessagePublicationFormat());
        }
    }
}
