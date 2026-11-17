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
package org.niord.core.category;

import org.junit.jupiter.api.Test;
import org.niord.core.model.DescEntity;
import org.niord.model.message.CategoryDescVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link CategoryDesc}, the localized-content entity for
 * {@link Category}, and the shared {@link DescEntity} truncation helper it inherits.
 *
 * <p>These are pure in-memory object transforms: no database, no CDI, no Quarkus. The tests
 * verify constructor copy semantics, entity &lt;-&gt; VO conversion, the "is this description
 * defined" predicate (including the edge case where a whitespace-only string is treated as blank/undefined),
 * field-copy semantics, the static {@code trunc} helper, and the entity back-reference.
 */
public class CategoryDescTest {

    /* ===================================================================== */
    /* CONSTRUCTOR FROM VO                                                   */
    /* ===================================================================== */

    /** 1. CategoryDesc(CategoryDescVo) copies lang (via the DescEntity super-constructor) and name. */
    @Test
    public void constructorFromVo_copiesLangAndName() {
        CategoryDescVo vo = new CategoryDescVo();
        vo.setLang("da");
        vo.setName("Fyr");

        CategoryDesc desc = new CategoryDesc(vo);

        // lang is copied through DescEntity(ILocalizedDesc)
        assertEquals("da", desc.getLang());
        // name is copied directly in the CategoryDesc constructor
        assertEquals("Fyr", desc.getName());
    }

    /* ===================================================================== */
    /* TO VO                                                                 */
    /* ===================================================================== */

    /** 2. toVo() carries the entity's lang and name onto a fresh CategoryDescVo. */
    @Test
    public void toVo_carriesLangAndName() {
        CategoryDesc desc = new CategoryDesc();
        desc.setLang("en");
        desc.setName("Light");

        CategoryDescVo vo = desc.toVo();

        assertEquals("en", vo.getLang());
        assertEquals("Light", vo.getName());
    }

    /* ===================================================================== */
    /* DESC DEFINED                                                          */
    /* ===================================================================== */

    /** 3a. descDefined() is true when the name is a non-blank string. */
    @Test
    public void descDefined_trueForNonBlankName() {
        CategoryDesc desc = new CategoryDesc();
        desc.setName("x");

        assertTrue(desc.descDefined(), "A non-blank name must make the description defined");
    }

    /** 3b. descDefined() is false when the name is null. */
    @Test
    public void descDefined_falseForNullName() {
        CategoryDesc desc = new CategoryDesc();
        desc.setName(null);

        assertFalse(desc.descDefined(), "A null name must make the description undefined");
    }

    /** 3c. descDefined() is false when the name is the empty string (length 0). */
    @Test
    public void descDefined_falseForEmptyName() {
        CategoryDesc desc = new CategoryDesc();
        desc.setName("");

        assertFalse(desc.descDefined(), "An empty name (length 0) must make the description undefined");
    }

    /**
     * 3d. descDefined() is false for a whitespace-only name. ILocalizedDesc.fieldsDefined honours the
     * "non-blank" contract via {@code !field.isBlank()}, so a string of spaces is blank and NOT defined.
     */
    @Test
    public void descDefined_falseForWhitespaceOnlyName() {
        CategoryDesc desc = new CategoryDesc();
        desc.setName(" ");

        assertFalse(desc.descDefined(), "A whitespace-only name is blank and is therefore NOT defined");
    }

    /* ===================================================================== */
    /* COPY DESC                                                             */
    /* ===================================================================== */

    /** 4. copyDesc() copies the name from another CategoryDesc into the target. */
    @Test
    public void copyDesc_copiesNameFromSource() {
        CategoryDesc source = new CategoryDesc();
        source.setName("Src");

        CategoryDesc target = new CategoryDesc();
        assertNull(target.getName(), "Target name starts unset");

        target.copyDesc(source);

        assertEquals("Src", target.getName(), "copyDesc must overwrite the target name with the source name");
    }

    /* ===================================================================== */
    /* TRUNC (inherited static helper)                                       */
    /* ===================================================================== */

    /** 5a. trunc() cuts a string longer than len down to the first len characters. */
    @Test
    public void trunc_cutsLongStringToLen() {
        assertEquals("abc", DescEntity.trunc("abcdef", 3));
    }

    /** 5b. trunc() returns null unchanged (no NPE). */
    @Test
    public void trunc_nullReturnsNull() {
        assertNull(DescEntity.trunc(null, 3));
    }

    /** 5c. trunc() leaves a string shorter than len unchanged (no trim of a short string). */
    @Test
    public void trunc_shorterThanLenIsUnchanged() {
        assertEquals("ab", DescEntity.trunc("ab", 5));
    }

    /**
     * 5d. trunc() trims trailing whitespace that falls inside the cut window: "ab   cd" cut at 4 is
     * "ab  ", which is then trimmed to "ab".
     */
    @Test
    public void trunc_trailingSpacesWithinCutAreTrimmed() {
        assertEquals("ab", DescEntity.trunc("ab   cd", 4));
    }

    /* ===================================================================== */
    /* ENTITY BACK-REFERENCE                                                 */
    /* ===================================================================== */

    /** 6. getEntity()/setEntity() round-trip the owning Category back-reference. */
    @Test
    public void getSetEntity_roundTripsOwningCategory() {
        CategoryDesc desc = new CategoryDesc();
        assertNull(desc.getEntity(), "Entity starts unset");

        Category category = new Category();
        desc.setEntity(category);

        assertSame(category, desc.getEntity(),
                "getEntity must return the exact Category instance that was set");
    }
}
