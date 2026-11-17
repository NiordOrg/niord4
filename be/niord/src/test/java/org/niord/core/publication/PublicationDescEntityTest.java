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
import org.niord.model.publication.PublicationDescVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link PublicationDesc} entity's pure logic: the {@code descDefined()}
 * "has at least one content field" check (which, unlike the {@link PublicationDescVo}, also counts
 * {@code messagePublicationFormat}), the {@code update(PublicationDescVo)} field copy, the
 * {@code toVo()} value-object round-trip, and the {@code copyDesc} entity-to-entity copy contract.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. They assert
 * concrete field values and exact boolean outcomes so they survive a future Spring port.
 *
 * <p>Note the documented asymmetry between the entity and the VO: the entity's {@code descDefined()}
 * treats {@code messagePublicationFormat} as a defining field, whereas
 * {@link PublicationDescVo#descDefined()} does not. See {@link #descDefinedIsTrueWhenOnlyMessagePublicationFormatSet_documentsBug()}.
 */
public class PublicationDescEntityTest {

    // --------------------------------------------------------------------------------------------- //
    // descDefined — "has at least one non-blank content field"
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void descDefinedIsFalseForFreshDesc() {
        PublicationDesc desc = new PublicationDesc();

        // No content fields set at all (lang is irrelevant to descDefined).
        assertFalse(desc.descDefined(), "a fresh desc with no content fields is not defined");

        // Setting only the language must NOT make it "defined" — lang is not a content field.
        desc.setLang("en");
        assertFalse(desc.descDefined(), "setting only lang must not make the desc defined");
    }

    @Test
    public void descDefinedIsTrueWhenAnySingleContentFieldSet() {
        // title alone counts
        PublicationDesc byTitle = new PublicationDesc();
        byTitle.setTitle("Some title");
        assertTrue(byTitle.descDefined(), "title alone must make the desc defined");

        // titleFormat alone counts
        PublicationDesc byTitleFormat = new PublicationDesc();
        byTitleFormat.setTitleFormat("{{title}}");
        assertTrue(byTitleFormat.descDefined(), "titleFormat alone must make the desc defined");

        // link alone counts
        PublicationDesc byLink = new PublicationDesc();
        byLink.setLink("http://example.dk/doc.pdf");
        assertTrue(byLink.descDefined(), "link alone must make the desc defined");

        // fileName alone counts
        PublicationDesc byFileName = new PublicationDesc();
        byFileName.setFileName("report.pdf");
        assertTrue(byFileName.descDefined(), "fileName alone must make the desc defined");
    }

    @Test
    public void descDefinedTreatsBlankStringsAsUndefined() {
        // fieldsDefined() requires length > 0; the empty string does not count.
        PublicationDesc empties = new PublicationDesc();
        empties.setTitle("");
        empties.setTitleFormat("");
        empties.setLink("");
        empties.setFileName("");
        empties.setMessagePublicationFormat("");
        assertFalse(empties.descDefined(), "empty-string content fields must not make the desc defined");

        // A single non-empty field among the empties flips it to defined.
        empties.setTitle("x");
        assertTrue(empties.descDefined(), "one non-empty field among blanks makes the desc defined");
    }

    /**
     * FIXED (P1): the entity and VO now agree. {@link PublicationDesc#descDefined()} and
     * {@link PublicationDescVo#descDefined()} both count {@code messagePublicationFormat} as a
     * defining field (via {@code fieldsDefined}), so a desc carrying ONLY a
     * messagePublicationFormat is "defined" on both sides of the entity/VO boundary and is no
     * longer silently dropped when descs are filtered before persistence.
     */
    @Test
    public void descDefinedIsTrueWhenOnlyMessagePublicationFormatSet() {
        PublicationDesc desc = new PublicationDesc();
        desc.setMessagePublicationFormat("NAVTEX");

        // Entity: messagePublicationFormat IS a defining field.
        assertTrue(desc.descDefined(), "entity descDefined must count messagePublicationFormat");

        // VO: the very same single field is now counted as defining too -> entity/VO agree.
        PublicationDescVo vo = new PublicationDescVo();
        vo.setMessagePublicationFormat("NAVTEX");
        assertTrue(vo.descDefined(), "VO descDefined now counts messagePublicationFormat, matching the entity");
    }

    // --------------------------------------------------------------------------------------------- //
    // update(PublicationDescVo)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateCopiesAllFiveContentFieldsFromVo() {
        PublicationDescVo vo = new PublicationDescVo();
        vo.setLang("da");
        vo.setTitle("Efterretninger");
        vo.setTitleFormat("{{year}} - {{title}}");
        vo.setLink("http://example.dk/efs.pdf");
        vo.setFileName("efs.pdf");
        vo.setMessagePublicationFormat("EfS {{number}}");

        PublicationDesc desc = new PublicationDesc();
        desc.update(vo);

        assertEquals("Efterretninger", desc.getTitle());
        assertEquals("{{year}} - {{title}}", desc.getTitleFormat());
        assertEquals("http://example.dk/efs.pdf", desc.getLink());
        assertEquals("efs.pdf", desc.getFileName());
        assertEquals("EfS {{number}}", desc.getMessagePublicationFormat());

        // update() copies only the five content fields; it must NOT touch lang.
        assertNull(desc.getLang(), "update(vo) must not copy lang");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo round-trip
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoRoundTripsAllFiveContentFieldsPlusLang() {
        PublicationDesc desc = new PublicationDesc();
        desc.setLang("en");
        desc.setTitle("Notices");
        desc.setTitleFormat("{{title}} ({{week}})");
        desc.setLink("http://example.dk/notices.pdf");
        desc.setFileName("notices.pdf");
        desc.setMessagePublicationFormat("NtM {{number}}");

        PublicationDescVo vo = desc.toVo();

        // toVo copies lang in addition to the five content fields.
        assertEquals("en", vo.getLang());
        assertEquals("Notices", vo.getTitle());
        assertEquals("{{title}} ({{week}})", vo.getTitleFormat());
        assertEquals("http://example.dk/notices.pdf", vo.getLink());
        assertEquals("notices.pdf", vo.getFileName());
        assertEquals("NtM {{number}}", vo.getMessagePublicationFormat());
    }

    // --------------------------------------------------------------------------------------------- //
    // copyDesc — entity-to-entity
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void copyDescCopiesContentFieldsButNotLang() {
        PublicationDesc source = new PublicationDesc();
        source.setLang("da");
        source.setTitle("Kilde");
        source.setTitleFormat("{{kilde}}");
        source.setLink("http://example.dk/kilde.pdf");
        source.setFileName("kilde.pdf");
        source.setMessagePublicationFormat("Kilde-format");

        PublicationDesc target = new PublicationDesc();
        target.setLang("en"); // the target keeps its own language

        target.copyDesc(source);

        // All five content fields are copied from the source.
        assertEquals("Kilde", target.getTitle());
        assertEquals("{{kilde}}", target.getTitleFormat());
        assertEquals("http://example.dk/kilde.pdf", target.getLink());
        assertEquals("kilde.pdf", target.getFileName());
        assertEquals("Kilde-format", target.getMessagePublicationFormat());

        // copyDesc must NOT overwrite the target's language.
        assertEquals("en", target.getLang(), "copyDesc must not copy lang");
    }

    @Test
    public void copyDescThrowsClassCastExceptionForNonPublicationDesc() {
        PublicationDesc target = new PublicationDesc();

        // copyDesc unconditionally casts its argument to PublicationDesc.
        // Passing a different ILocalizedDesc implementation must blow up with a ClassCastException.
        assertThrows(ClassCastException.class, () -> target.copyDesc(new PublicationDescVo()));
    }
}
