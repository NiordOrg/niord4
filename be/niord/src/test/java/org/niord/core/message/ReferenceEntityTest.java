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
import org.niord.model.message.ReferenceDescVo;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.ReferenceVo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link Reference} entity's pure (non-persistent) logic: the value-object
 * constructor (which copies {@code messageId}/{@code type} and materialises only the
 * {@link ReferenceDescVo#descDefined() defined} descriptions), {@link Reference#toVo(DataFilter)}
 * (always copies {@code messageId}/{@code type}, language-filters descs and leaves {@code descs}
 * null when the entity has none), {@link Reference#updateReference(Reference)} (copies
 * {@code indexNo}/{@code messageId}/{@code type} and copies descs while dropping blank ones),
 * {@link Reference#createDesc(String)} wiring, and the simple field round-trips.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. {@link Reference}
 * and {@link ReferenceVo} instances are built directly and asserted on concrete field/collection
 * values, so the tests survive a future Quarkus-to-Spring port.
 */
public class ReferenceEntityTest {

    /** Builds a {@link ReferenceDescVo} with the given language and description. */
    private static ReferenceDescVo descVo(String lang, String description) {
        ReferenceDescVo desc = new ReferenceDescVo();
        desc.setLang(lang);
        desc.setDescription(description);
        return desc;
    }

    // --------------------------------------------------------------------------------------------- //
    // Reference(ReferenceVo) constructor
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorCopiesMessageIdAndTypeAndKeepsOnlyDefinedDescs() {
        ReferenceVo vo = new ReferenceVo();
        vo.setMessageId("NW-001-23");
        vo.setType(ReferenceType.REFERENCE);
        vo.setDescs(List.of(
                descVo("en", "see also"),
                descVo("da", "")));   // blank -> filtered out

        Reference ref = new Reference(vo);

        assertEquals("NW-001-23", ref.getMessageId());
        assertEquals(ReferenceType.REFERENCE, ref.getType());

        // The blank "da" desc must be dropped: exactly one desc survives.
        assertEquals(1, ref.getDescs().size(), "only the non-blank desc should be materialised");
        ReferenceDesc desc = ref.getDescs().get(0);
        assertEquals("en", desc.getLang());
        assertEquals("see also", desc.getDescription());
        assertSame(ref, desc.getEntity(), "created desc must back-reference its owning entity");
    }

    @Test
    public void constructorWithNullDescsLeavesNoDescs() {
        ReferenceVo vo = new ReferenceVo();
        vo.setMessageId("NW-002-23");
        vo.setType(ReferenceType.CANCELLATION);
        vo.setDescs(null);

        Reference ref = new Reference(vo);

        assertEquals("NW-002-23", ref.getMessageId());
        assertEquals(ReferenceType.CANCELLATION, ref.getType());
        assertTrue(ref.getDescs().isEmpty(), "null VO descs must leave the entity desc list empty");
    }

    @Test
    public void constructorDropsAllDescsWhenEveryDescIsBlank() {
        ReferenceVo vo = new ReferenceVo();
        vo.setMessageId("NW-003-23");
        vo.setType(ReferenceType.REPETITION);
        vo.setDescs(List.of(
                descVo("en", ""),
                descVo("da", null)));

        Reference ref = new Reference(vo);

        assertTrue(ref.getDescs().isEmpty(), "all-blank VO descs must leave the entity desc list empty");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo(DataFilter)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoCopiesMessageIdAndTypeAndFiltersDescsByLanguage() {
        Reference ref = new Reference();
        ref.setMessageId("NW-010-23");
        ref.setType(ReferenceType.UPDATE);
        ref.createDesc("en").setDescription("english");
        ref.createDesc("da").setDescription("dansk");

        ReferenceVo vo = ref.toVo(DataFilter.get().lang("en"));

        assertEquals("NW-010-23", vo.getMessageId());
        assertEquals(ReferenceType.UPDATE, vo.getType());

        assertEquals(1, vo.getDescs().size(), "only the en desc should pass the language filter");
        ReferenceDescVo desc = vo.getDescs().get(0);
        assertEquals("en", desc.getLang());
        assertEquals("english", desc.getDescription());
    }

    @Test
    public void toVoWithNoDescsLeavesDescsNull() {
        Reference ref = new Reference();
        ref.setMessageId("NW-011-23");
        ref.setType(ReferenceType.REFERENCE);
        // no descs added

        ReferenceVo vo = ref.toVo(DataFilter.get().lang("en"));

        assertEquals("NW-011-23", vo.getMessageId());
        assertEquals(ReferenceType.REFERENCE, vo.getType());
        assertNull(vo.getDescs(), "a Reference with no descs must produce a VO with null descs");
    }

    @Test
    public void toVoWithNonMatchingLanguageFallsBackToFirstDesc() {
        Reference ref = new Reference();
        ref.setMessageId("NW-012-23");
        ref.setType(ReferenceType.REPETITION_NEW_TIME);
        ref.createDesc("en").setDescription("english");
        ref.createDesc("da").setDescription("dansk");

        // No "fr" desc exists -> ILocalizable.getDescs(filter) falls back to the first available.
        ReferenceVo vo = ref.toVo(DataFilter.get().lang("fr"));

        assertEquals(1, vo.getDescs().size());
        assertEquals("en", vo.getDescs().get(0).getLang());
        assertEquals("english", vo.getDescs().get(0).getDescription());
    }

    // --------------------------------------------------------------------------------------------- //
    // updateReference(Reference)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void updateReferenceCopiesScalarFields() {
        Reference target = new Reference();
        target.setIndexNo(0);
        target.setMessageId("OLD-001");
        target.setType(ReferenceType.REFERENCE);

        Reference source = new Reference();
        source.setIndexNo(7);
        source.setMessageId("NEW-001");
        source.setType(ReferenceType.CANCELLATION);

        target.updateReference(source);

        assertEquals(0, target.getIndexNo(), "the list position is the order; indexNo is read-only");
        assertEquals("NEW-001", target.getMessageId());
        assertEquals(ReferenceType.CANCELLATION, target.getType());
    }

    @Test
    public void updateReferenceCopiesDescsAndRemovesBlanks() {
        Reference target = new Reference();
        target.setMessageId("OLD-002");
        target.setType(ReferenceType.REFERENCE);

        Reference source = new Reference();
        source.setMessageId("NEW-002");
        source.setType(ReferenceType.UPDATE);
        source.createDesc("en").setDescription("kept");
        source.createDesc("da").setDescription("");   // blank -> removed after copy

        target.updateReference(source);

        assertEquals(1, target.getDescs().size(), "only the non-blank desc should remain after copy");
        ReferenceDesc desc = target.getDescs().get(0);
        assertEquals("en", desc.getLang());
        assertEquals("kept", desc.getDescription());
    }

    // --------------------------------------------------------------------------------------------- //
    // createDesc(String)
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void createDescAddsWiredDescToList() {
        Reference ref = new Reference();

        ReferenceDesc desc = ref.createDesc("en");

        assertEquals("en", desc.getLang());
        assertSame(ref, desc.getEntity(), "created desc must back-reference its owning entity");
        assertEquals(1, ref.getDescs().size(), "created desc must be appended to the entity desc list");
        assertSame(desc, ref.getDescs().get(0));
    }

    // --------------------------------------------------------------------------------------------- //
    // Getter / setter round-trips
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void messageIdRoundTrips() {
        Reference ref = new Reference();
        ref.setMessageId("NW-099-23");
        assertEquals("NW-099-23", ref.getMessageId());
    }

    @Test
    public void typeRoundTrips() {
        Reference ref = new Reference();
        ref.setType(ReferenceType.REPETITION);
        assertEquals(ReferenceType.REPETITION, ref.getType());
    }

    @Test
    public void indexNoRoundTrips() {
        Reference ref = new Reference();
        ref.setIndexNo(42);
        assertEquals(42, ref.getIndexNo());
    }
}
