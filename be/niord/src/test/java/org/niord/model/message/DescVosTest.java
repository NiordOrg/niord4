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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the six localized "*DescVo" value objects:
 * {@link MessageDescVo}, {@link AreaDescVo}, {@link CategoryDescVo},
 * {@link MessagePartDescVo}, {@link AttachmentDescVo} and {@link ReferenceDescVo}.
 *
 * <p>These tests verify the {@code ILocalizedDesc} contract implemented by each VO:
 * <ul>
 *     <li>{@code descDefined()} — true iff at least one content field is non-blank.</li>
 *     <li>{@code copyDesc(other)} — copies content fields but NOT lang, and casts the
 *         argument (so a foreign concrete type throws {@link ClassCastException}).</li>
 *     <li>{@code getLang()/setLang()} round-trips.</li>
 * </ul>
 *
 * <p>The tests live in the same package as the classes under test so that the
 * package-private content fields are reachable for white-box verification where useful;
 * the assertions themselves use only the public getters/setters.
 */
public class DescVosTest {

    /* ===================================================================== */
    /* MessageDescVo                                                         */
    /* ===================================================================== */

    @Test
    public void messageDesc_descDefined_falseWhenFreshlyConstructed() {
        assertFalse(new MessageDescVo().descDefined());
    }

    @Test
    public void messageDesc_descDefined_trueWhenTitleSet() {
        MessageDescVo d = new MessageDescVo();
        d.setTitle("Notice to Mariners");
        assertTrue(d.descDefined());
    }

    @Test
    public void messageDesc_descDefined_trueWhenOnlyVicinitySet() {
        MessageDescVo d = new MessageDescVo();
        d.setVicinity("North of the lighthouse");
        assertTrue(d.descDefined());
    }

    @Test
    public void messageDesc_descDefined_trueWhenOnlyPublicationSet() {
        MessageDescVo d = new MessageDescVo();
        d.setPublication("pub");
        assertTrue(d.descDefined());
    }

    @Test
    public void messageDesc_descDefined_trueWhenOnlyInternalPublicationSet() {
        MessageDescVo d = new MessageDescVo();
        d.setInternalPublication("internal");
        assertTrue(d.descDefined());
    }

    @Test
    public void messageDesc_descDefined_trueWhenOnlySourceSet() {
        MessageDescVo d = new MessageDescVo();
        d.setSource("DMA");
        assertTrue(d.descDefined());
    }

    @Test
    public void messageDesc_descDefined_falseForEmptyStringFields() {
        // fieldsDefined() uses length() > 0, so the empty string is NOT "defined".
        MessageDescVo d = new MessageDescVo();
        d.setTitle("");
        d.setVicinity("");
        d.setPublication("");
        d.setInternalPublication("");
        d.setSource("");
        assertFalse(d.descDefined());
    }

    @Test
    public void messageDesc_descDefined_falseForWhitespaceOnly() {
        // fieldsDefined() honours the "non-blank" contract via String.isBlank(), so a
        // whitespace-only value (e.g. a single space) is NOT a defined description.
        MessageDescVo d = new MessageDescVo();
        d.setTitle(" ");
        assertFalse(d.descDefined(), "whitespace-only title is blank and NOT defined");
    }

    @Test
    public void messageDesc_copyDesc_copiesAllContentFields() {
        MessageDescVo src = new MessageDescVo();
        src.setTitle("T");
        src.setVicinity("V");
        src.setPublication("P");
        src.setInternalPublication("IP");
        src.setSource("S");

        MessageDescVo target = new MessageDescVo();
        target.copyDesc(src);

        assertEquals("T", target.getTitle());
        assertEquals("V", target.getVicinity());
        assertEquals("P", target.getPublication());
        assertEquals("IP", target.getInternalPublication());
        assertEquals("S", target.getSource());
    }

    @Test
    public void messageDesc_copyDesc_doesNotCopyLang() {
        MessageDescVo src = new MessageDescVo();
        src.setLang("en");
        src.setTitle("T");

        MessageDescVo target = new MessageDescVo();
        target.setLang("da");
        target.copyDesc(src);

        assertEquals("da", target.getLang());
        assertEquals("T", target.getTitle());
    }

    @Test
    public void messageDesc_copyDesc_foreignTypeThrowsClassCastException() {
        assertThrows(ClassCastException.class, () -> new MessageDescVo().copyDesc(new AreaDescVo()));
    }

    @Test
    public void messageDesc_lang_roundTrips() {
        MessageDescVo d = new MessageDescVo();
        assertNull(d.getLang());
        d.setLang("fr");
        assertEquals("fr", d.getLang());
    }

    /* ===================================================================== */
    /* AreaDescVo                                                            */
    /* ===================================================================== */

    @Test
    public void areaDesc_descDefined_falseWhenFreshlyConstructed() {
        assertFalse(new AreaDescVo().descDefined());
    }

    @Test
    public void areaDesc_descDefined_falseForEmptyName() {
        AreaDescVo d = new AreaDescVo();
        d.setName("");
        assertFalse(d.descDefined());
    }

    @Test
    public void areaDesc_descDefined_trueWhenNameSet() {
        AreaDescVo d = new AreaDescVo();
        d.setName("Kattegat");
        assertTrue(d.descDefined());
    }

    @Test
    public void areaDesc_copyDesc_copiesNameButNotLang() {
        AreaDescVo src = new AreaDescVo();
        src.setLang("en");
        src.setName("Kattegat");

        AreaDescVo target = new AreaDescVo();
        target.setLang("da");
        target.copyDesc(src);

        assertEquals("Kattegat", target.getName());
        assertEquals("da", target.getLang());
    }

    @Test
    public void areaDesc_copyDesc_foreignTypeThrowsClassCastException() {
        // Pinned in the task: AreaDescVo.copyDesc(MessageDescVo) must throw CCE.
        assertThrows(ClassCastException.class, () -> new AreaDescVo().copyDesc(new MessageDescVo()));
    }

    @Test
    public void areaDesc_lang_roundTrips() {
        AreaDescVo d = new AreaDescVo();
        assertNull(d.getLang());
        d.setLang("da");
        assertEquals("da", d.getLang());
    }

    /* ===================================================================== */
    /* CategoryDescVo                                                        */
    /* ===================================================================== */

    @Test
    public void categoryDesc_descDefined_falseWhenFreshlyConstructed() {
        assertFalse(new CategoryDescVo().descDefined());
    }

    @Test
    public void categoryDesc_descDefined_falseForEmptyName() {
        CategoryDescVo d = new CategoryDescVo();
        d.setName("");
        assertFalse(d.descDefined());
    }

    @Test
    public void categoryDesc_descDefined_trueWhenNameSet() {
        CategoryDescVo d = new CategoryDescVo();
        d.setName("Firing exercises");
        assertTrue(d.descDefined());
    }

    @Test
    public void categoryDesc_copyDesc_copiesNameButNotLang() {
        CategoryDescVo src = new CategoryDescVo();
        src.setLang("en");
        src.setName("Firing exercises");

        CategoryDescVo target = new CategoryDescVo();
        target.setLang("da");
        target.copyDesc(src);

        assertEquals("Firing exercises", target.getName());
        assertEquals("da", target.getLang());
    }

    @Test
    public void categoryDesc_copyDesc_foreignTypeThrowsClassCastException() {
        assertThrows(ClassCastException.class, () -> new CategoryDescVo().copyDesc(new AreaDescVo()));
    }

    @Test
    public void categoryDesc_lang_roundTrips() {
        CategoryDescVo d = new CategoryDescVo();
        assertNull(d.getLang());
        d.setLang("en");
        assertEquals("en", d.getLang());
    }

    /* ===================================================================== */
    /* MessagePartDescVo                                                     */
    /* ===================================================================== */

    @Test
    public void messagePartDesc_descDefined_falseWhenFreshlyConstructed() {
        assertFalse(new MessagePartDescVo().descDefined());
    }

    @Test
    public void messagePartDesc_descDefined_trueWhenOnlySubjectSet() {
        MessagePartDescVo d = new MessagePartDescVo();
        d.setSubject("Subject");
        assertTrue(d.descDefined());
    }

    @Test
    public void messagePartDesc_descDefined_trueWhenOnlyDetailsSet() {
        MessagePartDescVo d = new MessagePartDescVo();
        d.setDetails("Details");
        assertTrue(d.descDefined());
    }

    @Test
    public void messagePartDesc_descDefined_falseForEmptyFields() {
        MessagePartDescVo d = new MessagePartDescVo();
        d.setSubject("");
        d.setDetails("");
        assertFalse(d.descDefined());
    }

    @Test
    public void messagePartDesc_copyDesc_copiesAllContentFields() {
        MessagePartDescVo src = new MessagePartDescVo();
        src.setSubject("Subject");
        src.setDetails("Details");

        MessagePartDescVo target = new MessagePartDescVo();
        target.copyDesc(src);

        assertEquals("Subject", target.getSubject());
        assertEquals("Details", target.getDetails());
    }

    @Test
    public void messagePartDesc_copyDesc_doesNotCopyLang() {
        MessagePartDescVo src = new MessagePartDescVo();
        src.setLang("en");
        src.setSubject("Subject");

        MessagePartDescVo target = new MessagePartDescVo();
        target.setLang("da");
        target.copyDesc(src);

        assertEquals("Subject", target.getSubject());
        assertEquals("da", target.getLang());
    }

    @Test
    public void messagePartDesc_copyDesc_foreignTypeThrowsClassCastException() {
        assertThrows(ClassCastException.class, () -> new MessagePartDescVo().copyDesc(new CategoryDescVo()));
    }

    @Test
    public void messagePartDesc_lang_roundTrips() {
        MessagePartDescVo d = new MessagePartDescVo();
        assertNull(d.getLang());
        d.setLang("da");
        assertEquals("da", d.getLang());
    }

    /* ===================================================================== */
    /* AttachmentDescVo                                                      */
    /* ===================================================================== */

    @Test
    public void attachmentDesc_descDefined_falseWhenFreshlyConstructed() {
        assertFalse(new AttachmentDescVo().descDefined());
    }

    @Test
    public void attachmentDesc_descDefined_falseForEmptyCaption() {
        AttachmentDescVo d = new AttachmentDescVo();
        d.setCaption("");
        assertFalse(d.descDefined());
    }

    @Test
    public void attachmentDesc_descDefined_trueWhenCaptionSet() {
        AttachmentDescVo d = new AttachmentDescVo();
        d.setCaption("Chart excerpt");
        assertTrue(d.descDefined());
    }

    @Test
    public void attachmentDesc_copyDesc_copiesCaptionButNotLang() {
        AttachmentDescVo src = new AttachmentDescVo();
        src.setLang("en");
        src.setCaption("Chart excerpt");

        AttachmentDescVo target = new AttachmentDescVo();
        target.setLang("da");
        target.copyDesc(src);

        assertEquals("Chart excerpt", target.getCaption());
        assertEquals("da", target.getLang());
    }

    @Test
    public void attachmentDesc_copyDesc_foreignTypeThrowsClassCastException() {
        assertThrows(ClassCastException.class, () -> new AttachmentDescVo().copyDesc(new ReferenceDescVo()));
    }

    @Test
    public void attachmentDesc_lang_roundTrips() {
        AttachmentDescVo d = new AttachmentDescVo();
        assertNull(d.getLang());
        d.setLang("en");
        assertEquals("en", d.getLang());
    }

    /* ===================================================================== */
    /* ReferenceDescVo                                                       */
    /* ===================================================================== */

    @Test
    public void referenceDesc_descDefined_falseWhenFreshlyConstructed() {
        assertFalse(new ReferenceDescVo().descDefined());
    }

    @Test
    public void referenceDesc_descDefined_falseForEmptyDescription() {
        ReferenceDescVo d = new ReferenceDescVo();
        d.setDescription("");
        assertFalse(d.descDefined());
    }

    @Test
    public void referenceDesc_descDefined_trueWhenDescriptionSet() {
        ReferenceDescVo d = new ReferenceDescVo();
        d.setDescription("Cancels NM 1234");
        assertTrue(d.descDefined());
    }

    @Test
    public void referenceDesc_copyDesc_copiesDescriptionButNotLang() {
        ReferenceDescVo src = new ReferenceDescVo();
        src.setLang("en");
        src.setDescription("Cancels NM 1234");

        ReferenceDescVo target = new ReferenceDescVo();
        target.setLang("da");
        target.copyDesc(src);

        assertEquals("Cancels NM 1234", target.getDescription());
        assertEquals("da", target.getLang());
    }

    @Test
    public void referenceDesc_copyDesc_foreignTypeThrowsClassCastException() {
        assertThrows(ClassCastException.class, () -> new ReferenceDescVo().copyDesc(new AttachmentDescVo()));
    }

    @Test
    public void referenceDesc_lang_roundTrips() {
        ReferenceDescVo d = new ReferenceDescVo();
        assertNull(d.getLang());
        d.setLang("fr");
        assertEquals("fr", d.getLang());
    }

    /* ===================================================================== */
    /* Cross-cutting: fieldsDefined static utility                           */
    /* ===================================================================== */

    @Test
    public void fieldsDefined_falseForNoArgs() {
        assertFalse(org.niord.model.ILocalizedDesc.fieldsDefined());
    }

    @Test
    public void fieldsDefined_falseForAllNull() {
        assertFalse(org.niord.model.ILocalizedDesc.fieldsDefined(null, null));
    }

    @Test
    public void fieldsDefined_falseForAllEmpty() {
        assertFalse(org.niord.model.ILocalizedDesc.fieldsDefined("", ""));
    }

    @Test
    public void fieldsDefined_trueWhenAnyNonEmpty() {
        assertTrue(org.niord.model.ILocalizedDesc.fieldsDefined(null, "", "x"));
    }
}
