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
import org.niord.model.message.AttachmentVo;
import org.niord.model.message.AttachmentVo.AttachmentDisplayType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Attachment} entity's pure (non-persistent) logic: the value-object
 * constructor (scalar copy, null-fileSize coercion to {@code 0L}, and localized-desc creation
 * filtered by {@code descDefined()}), {@link Attachment#toVo(DataFilter)} (scalar copy and
 * language-filtered descs, plus the empty-desc -> {@code null} contract), {@code updateAttachment}
 * (scalar copy plus blank-desc pruning via {@code copyDescsAndRemoveBlanks}), {@code createDesc},
 * and scalar getter/setter round-trips including {@link AttachmentDisplayType}.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. Domain objects
 * are built directly with {@code new} + setters and assertions check concrete field/collection
 * state, so the tests survive a future Quarkus -> Spring port. The class lives in
 * {@code org.niord.core.message} so it can reach the package-private fields of the entity directly,
 * though here all behavior is exercised through the public API.
 */
public class AttachmentEntityTest {

    /** Builds an {@link AttachmentDescVo} with the given language and caption. */
    private static AttachmentDescVo descVo(String lang, String caption) {
        AttachmentDescVo desc = new AttachmentDescVo();
        desc.setLang(lang);
        desc.setCaption(caption);
        return desc;
    }

    // --------------------------------------------------------------------------------------------- //
    // Constructor Attachment(AttachmentVo) — scalar copy
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorCopiesScalarFields() {
        AttachmentVo vo = new AttachmentVo();
        vo.setType("image/png");
        vo.setPath("messages/uid/map.png");
        vo.setFileName("map.png");
        vo.setFileSize(1234L);
        vo.setDisplay(AttachmentDisplayType.ABOVE);
        vo.setWidth("640");
        vo.setHeight("480");

        Attachment att = new Attachment(vo);

        assertEquals("image/png", att.getType());
        assertEquals("messages/uid/map.png", att.getPath());
        assertEquals("map.png", att.getFileName());
        assertEquals(1234L, att.getFileSize());
        assertEquals(AttachmentDisplayType.ABOVE, att.getDisplay());
        assertEquals("640", att.getWidth());
        assertEquals("480", att.getHeight());
    }

    @Test
    public void constructorCoercesNullFileSizeToZero() {
        AttachmentVo vo = new AttachmentVo();
        vo.setFileName("nosize.dat");
        vo.setFileSize(null);

        Attachment att = new Attachment(vo);

        assertEquals(0L, att.getFileSize());
    }

    @Test
    public void constructorCopiesNonNullFileSize() {
        AttachmentVo vo = new AttachmentVo();
        vo.setFileSize(1234L);

        Attachment att = new Attachment(vo);

        assertEquals(1234L, att.getFileSize());
    }

    @Test
    public void constructorWithNullDescsLeavesDescsEmpty() {
        AttachmentVo vo = new AttachmentVo();
        vo.setFileName("x.dat");
        // descs left null on the VO

        Attachment att = new Attachment(vo);

        assertTrue(att.getDescs().isEmpty(), "descs list should be empty when VO has no descs");
    }

    @Test
    public void constructorCreatesOnlyDefinedDescs() {
        AttachmentVo vo = new AttachmentVo();
        List<AttachmentDescVo> descs = new ArrayList<>();
        descs.add(descVo("en", "Map"));   // defined -> kept
        descs.add(descVo("da", ""));      // blank caption -> descDefined() == false -> dropped
        vo.setDescs(descs);

        Attachment att = new Attachment(vo);

        assertEquals(1, att.getDescs().size(), "only the non-blank desc should be created");
        AttachmentDesc desc = att.getDescs().get(0);
        assertEquals("en", desc.getLang());
        assertEquals("Map", desc.getCaption());
    }

    @Test
    public void constructorDropsAllBlankDescs() {
        AttachmentVo vo = new AttachmentVo();
        List<AttachmentDescVo> descs = new ArrayList<>();
        descs.add(descVo("en", ""));    // blank
        descs.add(descVo("da", null));  // null caption -> not defined
        vo.setDescs(descs);

        Attachment att = new Attachment(vo);

        assertTrue(att.getDescs().isEmpty(), "no defined desc should be created");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(DataFilter)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoCopiesScalarFields() {
        Attachment att = new Attachment();
        att.setType("application/pdf");
        att.setPath("messages/uid/doc.pdf");
        att.setFileName("doc.pdf");
        att.setFileSize(9000L);
        att.setDisplay(AttachmentDisplayType.SEPARATE_PAGE);
        att.setWidth("100");
        att.setHeight("200");

        AttachmentVo vo = att.toVo(DataFilter.get());

        assertEquals("application/pdf", vo.getType());
        assertEquals("messages/uid/doc.pdf", vo.getPath());
        assertEquals("doc.pdf", vo.getFileName());
        assertEquals(Long.valueOf(9000L), vo.getFileSize());
        assertEquals(AttachmentDisplayType.SEPARATE_PAGE, vo.getDisplay());
        assertEquals("100", vo.getWidth());
        assertEquals("200", vo.getHeight());
    }

    @Test
    public void toVoWithNoDescsLeavesDescsNull() {
        Attachment att = new Attachment();
        att.setFileName("x.dat");

        AttachmentVo vo = att.toVo(DataFilter.get());

        assertNull(vo.getDescs(), "no descs on entity -> VO descs should remain null");
    }

    @Test
    public void toVoFiltersDescsByLanguage() {
        Attachment att = new Attachment();
        att.createDesc("en").setCaption("Map");
        att.createDesc("da").setCaption("Kort");

        AttachmentVo vo = att.toVo(DataFilter.get().lang("en"));

        assertEquals(1, vo.getDescs().size(), "only the en desc should pass the language filter");
        assertEquals("en", vo.getDescs().get(0).getLang());
        assertEquals("Map", vo.getDescs().get(0).getCaption());
    }

    @Test
    public void toVoWithoutLanguageFilterIncludesAllDescs() {
        Attachment att = new Attachment();
        att.createDesc("en").setCaption("Map");
        att.createDesc("da").setCaption("Kort");

        AttachmentVo vo = att.toVo(DataFilter.get());

        assertEquals(2, vo.getDescs().size(), "no language restriction -> all descs included");
    }

    // --------------------------------------------------------------------------------------------- //
    // updateAttachment(other)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateAttachmentCopiesScalarFields() {
        Attachment target = new Attachment();
        target.setIndexNo(0);
        target.setType("old/type");
        target.setFileName("old.dat");
        target.setPath("old/path");
        target.setFileSize(1L);
        target.setDisplay(AttachmentDisplayType.ABOVE);
        target.setWidth("1");
        target.setHeight("1");

        Attachment source = new Attachment();
        source.setIndexNo(7);
        source.setType("image/jpeg");
        source.setFileName("new.jpg");
        source.setPath("new/path");
        source.setFileSize(5000L);
        source.setDisplay(AttachmentDisplayType.BELOW);
        source.setWidth("800");
        source.setHeight("600");

        target.updateAttachment(source);

        assertEquals(0, target.getIndexNo(), "the list position is the order; indexNo is read-only");
        assertEquals("image/jpeg", target.getType());
        assertEquals("new.jpg", target.getFileName());
        assertEquals("new/path", target.getPath());
        assertEquals(5000L, target.getFileSize());
        assertEquals(AttachmentDisplayType.BELOW, target.getDisplay());
        assertEquals("800", target.getWidth());
        assertEquals("600", target.getHeight());
    }

    @Test
    public void updateAttachmentCopiesDescsAndRemovesBlanks() {
        Attachment target = new Attachment();

        Attachment source = new Attachment();
        source.createDesc("en").setCaption("Caption");  // defined -> copied
        source.createDesc("da").setCaption("");         // blank -> pruned

        target.updateAttachment(source);

        assertEquals(1, target.getDescs().size(), "blank source desc should be dropped after update");
        AttachmentDesc copied = target.getDescs().get(0);
        assertEquals("en", copied.getLang());
        assertEquals("Caption", copied.getCaption());
    }

    @Test
    public void updateAttachmentMergesExistingDescByLanguage() {
        Attachment target = new Attachment();
        target.createDesc("en").setCaption("Stale caption");

        Attachment source = new Attachment();
        source.createDesc("en").setCaption("Fresh caption");

        target.updateAttachment(source);

        assertEquals(1, target.getDescs().size(), "existing en desc should be merged, not duplicated");
        assertEquals("en", target.getDescs().get(0).getLang());
        assertEquals("Fresh caption", target.getDescs().get(0).getCaption());
    }

    // --------------------------------------------------------------------------------------------- //
    // createDesc(lang)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void createDescAddsBackReferencedDescToList() {
        Attachment att = new Attachment();

        AttachmentDesc desc = att.createDesc("en");

        assertEquals("en", desc.getLang());
        assertSame(att, desc.getEntity(), "desc should back-reference the owning attachment");
        assertEquals(1, att.getDescs().size());
        assertSame(desc, att.getDescs().get(0), "the created desc should be the one stored in the list");
    }

    @Test
    public void createDescAppendsMultipleDescs() {
        Attachment att = new Attachment();

        att.createDesc("en");
        att.createDesc("da");

        assertEquals(2, att.getDescs().size());
        assertEquals("en", att.getDescs().get(0).getLang());
        assertEquals("da", att.getDescs().get(1).getLang());
    }

    // --------------------------------------------------------------------------------------------- //
    // Scalar getter/setter round-trips
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void scalarSettersAndGettersRoundTrip() {
        Attachment att = new Attachment();

        att.setIndexNo(3);
        att.setType("text/plain");
        att.setPath("a/b/c");
        att.setFileName("notes.txt");
        att.setFileSize(42L);
        att.setWidth("320");
        att.setHeight("240");

        assertEquals(3, att.getIndexNo());
        assertEquals("text/plain", att.getType());
        assertEquals("a/b/c", att.getPath());
        assertEquals("notes.txt", att.getFileName());
        assertEquals(42L, att.getFileSize());
        assertEquals("320", att.getWidth());
        assertEquals("240", att.getHeight());
    }

    @Test
    public void displayTypeRoundTripsForEachEnumValue() {
        Attachment att = new Attachment();

        for (AttachmentDisplayType type : AttachmentDisplayType.values()) {
            att.setDisplay(type);
            assertEquals(type, att.getDisplay());
        }
    }

    @Test
    public void setDescsReplacesUnderlyingList() {
        Attachment att = new Attachment();
        att.createDesc("en");

        List<AttachmentDesc> replacement = new ArrayList<>();
        att.setDescs(replacement);

        assertSame(replacement, att.getDescs());
        assertTrue(att.getDescs().isEmpty());
    }
}
