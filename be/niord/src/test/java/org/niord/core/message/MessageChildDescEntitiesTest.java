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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.model.DataFilter;
import org.niord.model.message.AttachmentDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.ReferenceDescVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the three simpler localized-description entities that hang off message
 * sub-structures: {@link MessagePartDesc} (subject/details), {@link ReferenceDesc} (description)
 * and {@link AttachmentDesc} (caption).
 *
 * <p>Each entity is exercised purely through its observable, non-persistent behaviour:
 * <ul>
 *   <li>{@code descDefined()} — true iff at least one of its translatable fields is non-empty
 *       (delegates to {@code ILocalizedDesc.fieldsDefined}, which checks {@code length() > 0}).</li>
 *   <li>the value-object constructor — copies {@code lang} and the entity's field(s); for
 *       {@link MessagePartDesc} the {@code subject} is truncated to the 500-char column limit via
 *       {@link org.niord.core.model.DescEntity#trunc} while {@code details} is copied verbatim
 *       (no truncation), and the other two entities copy their single field with no truncation.</li>
 *   <li>{@code toVo(...)} — round-trips {@code lang} and the field(s) into the matching VO.</li>
 *   <li>{@code copyDesc(other)} — copies {@code lang} and the entity-specific field(s) from another
 *       instance of the same concrete type.</li>
 * </ul>
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. Entities and VOs
 * are built directly with {@code new} + setters and asserted on concrete field/string-length values,
 * so the tests survive a future Quarkus-to-Spring port.
 */
public class MessageChildDescEntitiesTest {

    /** Builds a string of {@code n} repeated 'a' characters (non-whitespace, so trunc().trim() is a no-op). */
    private static String repeat(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    // =============================================================================================== //
    // MessagePartDesc
    // =============================================================================================== //

    @Test
    public void messagePart_descDefined_falseWhenBothFieldsNullOrEmpty() {
        MessagePartDesc desc = new MessagePartDesc();
        assertFalse(desc.descDefined(), "null subject + null details is undefined");

        desc.setSubject("");
        desc.setDetails("");
        assertFalse(desc.descDefined(), "empty subject + empty details is undefined");
    }

    @Test
    public void messagePart_descDefined_trueWhenOnlySubjectSet() {
        MessagePartDesc desc = new MessagePartDesc();
        desc.setSubject("Notice to mariners");
        assertTrue(desc.descDefined(), "subject alone makes the desc defined");
    }

    @Test
    public void messagePart_descDefined_trueWhenOnlyDetailsSet() {
        MessagePartDesc desc = new MessagePartDesc();
        desc.setDetails("Detailed body text");
        assertTrue(desc.descDefined(), "details alone makes the desc defined");
    }

    @Test
    public void messagePart_voConstructor_truncatesSubjectTo500_copiesLangAndDetails() {
        MessagePartDescVo vo = new MessagePartDescVo();
        vo.setLang("en");
        vo.setSubject(repeat(600));
        String longDetails = repeat(5000);
        vo.setDetails(longDetails);

        MessagePartDesc desc = new MessagePartDesc(vo);

        assertEquals("en", desc.getLang(), "lang is copied");
        assertEquals(500, desc.getSubject().length(), "subject is truncated to the 500-char column limit");
        assertEquals(longDetails, desc.getDetails(), "details are copied verbatim with no truncation");
        assertEquals(5000, desc.getDetails().length(), "details length is preserved");
    }

    @Test
    public void messagePart_voConstructor_keepsSubjectShorterThanLimitUntouched() {
        MessagePartDescVo vo = new MessagePartDescVo();
        vo.setLang("da");
        vo.setSubject("Kort emne");
        vo.setDetails(null);

        MessagePartDesc desc = new MessagePartDesc(vo);

        assertEquals("da", desc.getLang());
        assertEquals("Kort emne", desc.getSubject(), "short subject is kept verbatim");
        assertNull(desc.getDetails(), "null details stays null");
    }

    @Test
    public void messagePart_voConstructor_subjectExactlyAtLimitNotTruncated() {
        MessagePartDescVo vo = new MessagePartDescVo();
        vo.setLang("en");
        String exactly500 = repeat(500);
        vo.setSubject(exactly500);

        MessagePartDesc desc = new MessagePartDesc(vo);

        assertEquals(exactly500, desc.getSubject(), "subject of exactly 500 chars is left untouched");
        assertEquals(500, desc.getSubject().length());
    }

    @Test
    public void messagePart_voConstructorWithFilter_behavesLikeDefaultConstructor() {
        MessagePartDescVo vo = new MessagePartDescVo();
        vo.setLang("en");
        vo.setSubject(repeat(600));
        vo.setDetails("body");

        MessagePartDesc desc = new MessagePartDesc(vo, DataFilter.get());

        assertEquals("en", desc.getLang());
        assertEquals(500, desc.getSubject().length());
        assertEquals("body", desc.getDetails());
    }

    @Test
    public void messagePart_toVo_copiesLangSubjectAndDetails() {
        MessagePartDesc desc = new MessagePartDesc();
        desc.setLang("en");
        desc.setSubject("The subject");
        desc.setDetails("The details");

        MessagePartDescVo vo = desc.toVo(DataFilter.get());

        assertEquals("en", vo.getLang());
        assertEquals("The subject", vo.getSubject());
        assertEquals("The details", vo.getDetails());
    }

    @Test
    public void messagePart_toVo_doesNotTruncateAndPreservesNulls() {
        MessagePartDesc desc = new MessagePartDesc();
        desc.setLang("en");
        // toVo performs no truncation; even a directly-set over-limit subject round-trips unchanged.
        String overLimit = repeat(700);
        desc.setSubject(overLimit);
        desc.setDetails(null);

        MessagePartDescVo vo = desc.toVo(DataFilter.get());

        assertEquals(overLimit, vo.getSubject(), "toVo copies subject verbatim (no truncation)");
        assertEquals(700, vo.getSubject().length());
        assertNull(vo.getDetails(), "null details round-trips as null");
    }

    @Test
    public void messagePart_copyDesc_copiesLangSubjectAndDetailsFromOther() {
        MessagePartDesc source = new MessagePartDesc();
        source.setLang("fr");
        source.setSubject("Sujet");
        source.setDetails("Détails");

        MessagePartDesc target = new MessagePartDesc();
        target.setLang("en");
        target.setSubject("old subject");
        target.setDetails("old details");

        target.copyDesc(source);

        assertEquals("fr", target.getLang());
        assertEquals("Sujet", target.getSubject());
        assertEquals("Détails", target.getDetails());
    }

    @Test
    public void messagePart_copyDesc_copiesNullFieldsFromOther() {
        MessagePartDesc source = new MessagePartDesc();
        source.setLang("en");
        // subject + details left null on the source

        MessagePartDesc target = new MessagePartDesc();
        target.setLang("da");
        target.setSubject("non-null");
        target.setDetails("non-null");

        target.copyDesc(source);

        assertEquals("en", target.getLang());
        assertNull(target.getSubject(), "null subject is copied over the previous value");
        assertNull(target.getDetails(), "null details is copied over the previous value");
    }

    // =============================================================================================== //
    // ReferenceDesc
    // =============================================================================================== //

    @Test
    public void reference_descDefined_falseWhenDescriptionNullOrEmpty() {
        ReferenceDesc desc = new ReferenceDesc();
        assertFalse(desc.descDefined(), "null description is undefined");

        desc.setDescription("");
        assertFalse(desc.descDefined(), "empty description is undefined");
    }

    @Test
    public void reference_descDefined_trueWhenDescriptionSet() {
        ReferenceDesc desc = new ReferenceDesc();
        desc.setDescription("See also message 042");
        assertTrue(desc.descDefined(), "non-empty description makes the desc defined");
    }

    @Test
    public void reference_voConstructor_copiesLangAndDescriptionWithoutTruncation() {
        ReferenceDescVo vo = new ReferenceDescVo();
        vo.setLang("en");
        String longDescription = repeat(2000);
        vo.setDescription(longDescription);

        ReferenceDesc desc = new ReferenceDesc(vo);

        assertEquals("en", desc.getLang());
        assertEquals(longDescription, desc.getDescription(), "description is copied verbatim (no truncation)");
        assertEquals(2000, desc.getDescription().length());
    }

    @Test
    public void reference_voConstructor_copiesNullDescription() {
        ReferenceDescVo vo = new ReferenceDescVo();
        vo.setLang("da");
        vo.setDescription(null);

        ReferenceDesc desc = new ReferenceDesc(vo);

        assertEquals("da", desc.getLang());
        assertNull(desc.getDescription());
    }

    @Test
    public void reference_toVo_copiesLangAndDescription() {
        ReferenceDesc desc = new ReferenceDesc();
        desc.setLang("en");
        desc.setDescription("Cancels message 099");

        ReferenceDescVo vo = desc.toVo();

        assertEquals("en", vo.getLang());
        assertEquals("Cancels message 099", vo.getDescription());
    }

    @Test
    public void reference_copyDesc_copiesLangAndDescriptionFromOther() {
        ReferenceDesc source = new ReferenceDesc();
        source.setLang("fr");
        source.setDescription("Référence");

        ReferenceDesc target = new ReferenceDesc();
        target.setLang("en");
        target.setDescription("old description");

        target.copyDesc(source);

        assertEquals("fr", target.getLang());
        assertEquals("Référence", target.getDescription());
    }

    // =============================================================================================== //
    // AttachmentDesc
    // =============================================================================================== //

    @Test
    public void attachment_descDefined_falseWhenCaptionNullOrEmpty() {
        AttachmentDesc desc = new AttachmentDesc();
        assertFalse(desc.descDefined(), "null caption is undefined");

        desc.setCaption("");
        assertFalse(desc.descDefined(), "empty caption is undefined");
    }

    @Test
    public void attachment_descDefined_trueWhenCaptionSet() {
        AttachmentDesc desc = new AttachmentDesc();
        desc.setCaption("Chart excerpt");
        assertTrue(desc.descDefined(), "non-empty caption makes the desc defined");
    }

    @Test
    public void attachment_voConstructor_copiesLangAndCaptionWithoutTruncation() {
        AttachmentDescVo vo = new AttachmentDescVo();
        vo.setLang("en");
        String longCaption = repeat(1500);
        vo.setCaption(longCaption);

        AttachmentDesc desc = new AttachmentDesc(vo);

        assertEquals("en", desc.getLang());
        assertEquals(longCaption, desc.getCaption(), "caption is copied verbatim (no truncation)");
        assertEquals(1500, desc.getCaption().length());
    }

    @Test
    public void attachment_voConstructor_copiesNullCaption() {
        AttachmentDescVo vo = new AttachmentDescVo();
        vo.setLang("da");
        vo.setCaption(null);

        AttachmentDesc desc = new AttachmentDesc(vo);

        assertEquals("da", desc.getLang());
        assertNull(desc.getCaption());
    }

    @Test
    public void attachment_toVo_copiesLangAndCaption() {
        AttachmentDesc desc = new AttachmentDesc();
        desc.setLang("en");
        desc.setCaption("Figure 1");

        AttachmentDescVo vo = desc.toVo();

        assertEquals("en", vo.getLang());
        assertEquals("Figure 1", vo.getCaption());
    }

    @Test
    public void attachment_copyDesc_copiesLangAndCaptionFromOther() {
        AttachmentDesc source = new AttachmentDesc();
        source.setLang("fr");
        source.setCaption("Légende");

        AttachmentDesc target = new AttachmentDesc();
        target.setLang("en");
        target.setCaption("old caption");

        target.copyDesc(source);

        assertEquals("fr", target.getLang());
        assertEquals("Légende", target.getCaption());
    }
}
