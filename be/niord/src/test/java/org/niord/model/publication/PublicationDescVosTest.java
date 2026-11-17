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

package org.niord.model.publication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the publication *DescVo value objects ({@link PublicationDescVo} and
 * {@link PublicationCategoryDescVo}), which implement {@code ILocalizedDesc}.
 *
 * These are pure object-logic tests: they exercise {@code descDefined()}, {@code copyDesc(...)}
 * and the lang accessor round-trip. No framework, no current-time/locale/timezone reliance.
 */
public class PublicationDescVosTest {

    /* ------------------------------------------------------------------ */
    /* PublicationDescVo                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    public void publicationDesc_freshInstance_isNotDefined() {
        PublicationDescVo desc = new PublicationDescVo();
        assertFalse(desc.descDefined(), "A fresh desc with no content fields must not be defined");
    }

    @Test
    public void publicationDesc_definedWhenTitleSet() {
        PublicationDescVo desc = new PublicationDescVo();
        desc.setTitle("Notices to Mariners");
        assertTrue(desc.descDefined(), "Setting title alone must make the desc defined");
    }

    @Test
    public void publicationDesc_definedWhenTitleFormatSet() {
        PublicationDescVo desc = new PublicationDescVo();
        desc.setTitleFormat("${title} (${year})");
        assertTrue(desc.descDefined(), "Setting titleFormat alone must make the desc defined");
    }

    @Test
    public void publicationDesc_definedWhenLinkSet() {
        PublicationDescVo desc = new PublicationDescVo();
        desc.setLink("http://example.org/pub.pdf");
        assertTrue(desc.descDefined(), "Setting link alone must make the desc defined");
    }

    @Test
    public void publicationDesc_definedWhenFileNameSet() {
        PublicationDescVo desc = new PublicationDescVo();
        desc.setFileName("pub.pdf");
        assertTrue(desc.descDefined(), "Setting fileName alone must make the desc defined");
    }

    @Test
    public void publicationDesc_emptyStringFieldsAreNotDefined() {
        // fieldsDefined uses length()>0, so empty strings count as blank -> not defined.
        PublicationDescVo desc = new PublicationDescVo();
        desc.setTitle("");
        desc.setTitleFormat("");
        desc.setLink("");
        desc.setFileName("");
        assertFalse(desc.descDefined(), "Empty-string content fields must not count as defined");
    }

    @Test
    public void publicationDesc_whitespaceTitleIsNotDefined() {
        // fieldsDefined() honours the "non-blank" contract via String.isBlank(), so a single-space
        // title is blank and does NOT make the description defined.
        PublicationDescVo desc = new PublicationDescVo();
        desc.setTitle(" ");
        assertFalse(desc.descDefined(),
                "A single-space title is blank and is NOT treated as defined");
    }

    @Test
    public void publicationDesc_onlyMessagePublicationFormat_isDefined() {
        // FIXED (P1): descDefined() now inspects messagePublicationFormat too (matching copyDesc() and
        // the canonical entity PublicationDesc.descDefined()), so a desc whose only populated content
        // field is messagePublicationFormat reports defined and is no longer silently dropped.
        PublicationDescVo desc = new PublicationDescVo();
        desc.setMessagePublicationFormat("HTML");
        assertTrue(desc.descDefined(), "messagePublicationFormat now counts towards descDefined()");

        // ...and it is genuinely copied by copyDesc:
        PublicationDescVo target = new PublicationDescVo();
        target.copyDesc(desc);
        assertEquals("HTML", target.getMessagePublicationFormat(),
                "copyDesc must copy messagePublicationFormat");
    }

    @Test
    public void publicationDesc_copyDesc_copiesAllFiveContentFields() {
        PublicationDescVo source = new PublicationDescVo();
        source.setTitle("Title");
        source.setTitleFormat("Format");
        source.setLink("Link");
        source.setFileName("File");
        source.setMessagePublicationFormat("MsgFmt");

        PublicationDescVo target = new PublicationDescVo();
        target.copyDesc(source);

        assertEquals("Title", target.getTitle());
        assertEquals("Format", target.getTitleFormat());
        assertEquals("Link", target.getLink());
        assertEquals("File", target.getFileName());
        assertEquals("MsgFmt", target.getMessagePublicationFormat());
    }

    @Test
    public void publicationDesc_copyDesc_doesNotCopyLang() {
        PublicationDescVo source = new PublicationDescVo();
        source.setLang("en");
        source.setTitle("English title");

        PublicationDescVo target = new PublicationDescVo();
        target.setLang("da");
        target.copyDesc(source);

        assertEquals("da", target.getLang(), "copyDesc must NOT overwrite the target lang");
        assertEquals("English title", target.getTitle(), "copyDesc must copy the content title");
    }

    @Test
    public void publicationDesc_copyDesc_overwritesExistingContentWithNulls() {
        // Copying from an empty source must clear previously-set content fields (assignment, not merge).
        PublicationDescVo source = new PublicationDescVo();

        PublicationDescVo target = new PublicationDescVo();
        target.setTitle("Old title");
        target.setMessagePublicationFormat("Old fmt");
        target.copyDesc(source);

        assertNull(target.getTitle(), "copyDesc must overwrite title with the source's null");
        assertNull(target.getMessagePublicationFormat(),
                "copyDesc must overwrite messagePublicationFormat with the source's null");
    }

    @Test
    public void publicationDesc_copyDesc_wrongType_throwsClassCastException() {
        PublicationDescVo target = new PublicationDescVo();
        assertThrows(ClassCastException.class, () -> target.copyDesc(new PublicationCategoryDescVo()));
    }

    @Test
    public void publicationDesc_langRoundTrips() {
        PublicationDescVo desc = new PublicationDescVo();
        assertNull(desc.getLang(), "lang defaults to null");
        desc.setLang("da");
        assertEquals("da", desc.getLang());
        desc.setLang("en");
        assertEquals("en", desc.getLang());
    }

    /* ------------------------------------------------------------------ */
    /* PublicationCategoryDescVo                                          */
    /* ------------------------------------------------------------------ */

    @Test
    public void categoryDesc_freshInstance_isNotDefined() {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        assertFalse(desc.descDefined(),
                "A fresh category desc with no content fields must not be defined");
    }

    @Test
    public void categoryDesc_definedWhenNameSet() {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        desc.setName("Charts");
        assertTrue(desc.descDefined(), "Setting name alone must make the category desc defined");
    }

    @Test
    public void categoryDesc_definedWhenDescriptionSet() {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        desc.setDescription("Nautical charts");
        assertTrue(desc.descDefined(), "Setting description alone must make the category desc defined");
    }

    @Test
    public void categoryDesc_emptyStringFieldsAreNotDefined() {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        desc.setName("");
        desc.setDescription("");
        assertFalse(desc.descDefined(), "Empty-string content fields must not count as defined");
    }

    @Test
    public void categoryDesc_copyDesc_copiesContentFields() {
        PublicationCategoryDescVo source = new PublicationCategoryDescVo();
        source.setName("Charts");
        source.setDescription("Nautical charts");

        PublicationCategoryDescVo target = new PublicationCategoryDescVo();
        target.copyDesc(source);

        assertEquals("Charts", target.getName());
        assertEquals("Nautical charts", target.getDescription());
    }

    @Test
    public void categoryDesc_copyDesc_doesNotCopyLang() {
        PublicationCategoryDescVo source = new PublicationCategoryDescVo();
        source.setLang("en");
        source.setName("English name");

        PublicationCategoryDescVo target = new PublicationCategoryDescVo();
        target.setLang("da");
        target.copyDesc(source);

        assertEquals("da", target.getLang(), "copyDesc must NOT overwrite the target lang");
        assertEquals("English name", target.getName(), "copyDesc must copy the content name");
    }

    @Test
    public void categoryDesc_copyDesc_overwritesExistingContentWithNulls() {
        PublicationCategoryDescVo source = new PublicationCategoryDescVo();

        PublicationCategoryDescVo target = new PublicationCategoryDescVo();
        target.setName("Old name");
        target.setDescription("Old description");
        target.copyDesc(source);

        assertNull(target.getName(), "copyDesc must overwrite name with the source's null");
        assertNull(target.getDescription(), "copyDesc must overwrite description with the source's null");
    }

    @Test
    public void categoryDesc_copyDesc_wrongType_throwsClassCastException() {
        PublicationCategoryDescVo target = new PublicationCategoryDescVo();
        assertThrows(ClassCastException.class, () -> target.copyDesc(new PublicationDescVo()));
    }

    @Test
    public void categoryDesc_langRoundTrips() {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        assertNull(desc.getLang(), "lang defaults to null");
        desc.setLang("da");
        assertEquals("da", desc.getLang());
        desc.setLang("en");
        assertEquals("en", desc.getLang());
    }
}
