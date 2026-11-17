/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.DataFilter;
import org.niord.model.message.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link PromulgationType}.
 * <p>
 * These tests verify two pieces of business behavior that survive any future
 * framework migration:
 * <ul>
 *     <li>The constructor-from-VO field copy, in particular the "active" defaulting
 *         logic ({@code active = isActive() != null && isActive()}) and null-safe
 *         handling of collection fields.</li>
 *     <li>The {@code toVo(DataFilter)} DETAILS gating: which fields are copied
 *         unconditionally vs. only when the filter includes details.</li>
 * </ul>
 */
public class PromulgationTypeTest {

    /*****************************************************************/
    /** (1) Constructor-from-VO field copy + active defaulting     **/
    /*****************************************************************/

    @Test
    public void constructorDefaultsNullActiveToFalse() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setActive(null);

        PromulgationType entity = new PromulgationType(vo);

        assertFalse(entity.isActive(), "null VO active must default to false");
    }

    @Test
    public void constructorCopiesTrueActive() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setActive(Boolean.TRUE);

        PromulgationType entity = new PromulgationType(vo);

        assertTrue(entity.isActive(), "Boolean.TRUE active must yield true");
    }

    @Test
    public void constructorCopiesFalseActive() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setActive(Boolean.FALSE);

        PromulgationType entity = new PromulgationType(vo);

        assertFalse(entity.isActive(), "Boolean.FALSE active must yield false");
    }

    @Test
    public void constructorCopiesScalarFields() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId("navtex-dk");
        vo.setServiceId("navtex");
        vo.setName("NAVTEX Denmark");
        vo.setPriority(42);
        vo.setRequirement(Requirement.MANDATORY);
        vo.setLanguage("da");

        PromulgationType entity = new PromulgationType(vo);

        assertEquals("navtex-dk", entity.getTypeId(), "typeId copied");
        assertEquals("navtex", entity.getServiceId(), "serviceId copied");
        assertEquals("NAVTEX Denmark", entity.getName(), "name copied");
        assertEquals(42, entity.getPriority(), "priority copied");
        assertEquals(Requirement.MANDATORY, entity.getRequirement(), "requirement copied");
        assertEquals("da", entity.getLanguage(), "language copied");
    }

    @Test
    public void constructorCopiesMessageTypes() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        Set<Type> types = new HashSet<>();
        types.add(Type.COASTAL_WARNING);
        vo.setMessageTypes(types);

        PromulgationType entity = new PromulgationType(vo);

        assertEquals(1, entity.getMessageTypes().size(), "exactly one message type copied");
        assertTrue(entity.getMessageTypes().contains(Type.COASTAL_WARNING),
                "COASTAL_WARNING present in entity message types");
    }

    @Test
    public void constructorCopiesScriptResourcePathsInOrder() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setScriptResourcePaths(new ArrayList<>(Arrays.asList("a", "b")));

        PromulgationType entity = new PromulgationType(vo);

        assertEquals(Arrays.asList("a", "b"), entity.getScriptResourcePaths(),
                "script resource paths copied in order");
    }

    @Test
    public void constructorWithNullCollectionsYieldsEmptyNonNullCollections() {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setDomains(null);
        vo.setMessageTypes(null);
        vo.setScriptResourcePaths(null);

        PromulgationType entity = new PromulgationType(vo);

        assertNotNull(entity.getDomains(), "domains must be non-null");
        assertTrue(entity.getDomains().isEmpty(), "domains must be empty");
        assertNotNull(entity.getMessageTypes(), "messageTypes must be non-null");
        assertTrue(entity.getMessageTypes().isEmpty(), "messageTypes must be empty");
        assertNotNull(entity.getScriptResourcePaths(), "scriptResourcePaths must be non-null");
        assertTrue(entity.getScriptResourcePaths().isEmpty(), "scriptResourcePaths must be empty");
    }

    /*****************************************************************/
    /** (2) toVo() (no-arg) -> DETAILS ON: copies all fields       **/
    /*****************************************************************/

    @Test
    public void toVoWithDetailsCopiesAllFields() {
        PromulgationType entity = buildFullEntity();

        PromulgationTypeVo vo = entity.toVo();

        // Unconditionally-copied scalar fields
        assertEquals("nx", vo.getTypeId(), "typeId set");
        assertEquals("navtex", vo.getServiceId(), "serviceId set");
        assertEquals("NAVTEX", vo.getName(), "name set");
        assertEquals(5, vo.getPriority(), "priority set");
        assertEquals(Requirement.DEFAULT, vo.getRequirement(), "requirement set");

        // Detail-gated fields (present because details ON)
        assertNotNull(vo.isActive(), "active set when details ON");
        assertTrue(vo.isActive(), "active is true");
        assertEquals("en", vo.getLanguage(), "language set when details ON");

        assertNotNull(vo.getMessageTypes(), "messageTypes set when details ON");
        assertEquals(1, vo.getMessageTypes().size(), "exactly one message type in vo");
        assertTrue(vo.getMessageTypes().contains(Type.LOCAL_WARNING),
                "LOCAL_WARNING present in vo message types");

        assertEquals(Arrays.asList("s1"), vo.getScriptResourcePaths(),
                "scriptResourcePaths copied when details ON");
    }

    /*****************************************************************/
    /** (3) toVo(DataFilter.get()) -> DETAILS OFF: gating absent   **/
    /*****************************************************************/

    @Test
    public void toVoWithoutDetailsOmitsDetailGatedFields() {
        PromulgationType entity = buildFullEntity();

        PromulgationTypeVo vo = entity.toVo(DataFilter.get());

        // Unconditionally-copied scalar fields are still present
        assertEquals("nx", vo.getTypeId(), "typeId still set without details");
        assertEquals("navtex", vo.getServiceId(), "serviceId still set without details");
        assertEquals("NAVTEX", vo.getName(), "name still set without details");
        assertEquals(5, vo.getPriority(), "priority still set without details");
        assertEquals(Requirement.DEFAULT, vo.getRequirement(),
                "requirement still set without details");

        // Detail-gated fields are absent
        assertNull(vo.isActive(), "active omitted without details");
        assertNull(vo.getLanguage(), "language omitted without details");
        assertNull(vo.getDomains(), "domains omitted without details");
        assertNull(vo.getMessageTypes(), "messageTypes omitted without details");
        assertNull(vo.getScriptResourcePaths(), "scriptResourcePaths omitted without details");
    }

    /*****************************************************************/
    /** (4) toVo() with empty collections -> fields stay null      **/
    /*****************************************************************/

    @Test
    public void toVoWithDetailsLeavesEmptyCollectionsNull() {
        PromulgationType entity = new PromulgationType();
        entity.setTypeId("nx");
        entity.setServiceId("navtex");
        entity.setName("NAVTEX");
        entity.setPriority(5);
        entity.setRequirement(Requirement.DEFAULT);
        entity.setActive(true);
        entity.setLanguage("en");
        entity.setMessageTypes(new HashSet<Type>());
        entity.setScriptResourcePaths(new ArrayList<String>());

        PromulgationTypeVo vo = entity.toVo();

        assertNull(vo.getMessageTypes(), "empty messageTypes are not copied to vo");
        assertNull(vo.getScriptResourcePaths(), "empty scriptResourcePaths are not copied to vo");
    }

    /*****************************************************************/
    /** Helpers                                                    **/
    /*****************************************************************/

    /** Builds an entity with all fields populated for toVo() gating tests. */
    private PromulgationType buildFullEntity() {
        PromulgationType entity = new PromulgationType();
        entity.setTypeId("nx");
        entity.setServiceId("navtex");
        entity.setName("NAVTEX");
        entity.setPriority(5);
        entity.setRequirement(Requirement.DEFAULT);
        entity.setActive(true);
        entity.setLanguage("en");

        Set<Type> messageTypes = new HashSet<>();
        messageTypes.add(Type.LOCAL_WARNING);
        entity.setMessageTypes(messageTypes);

        List<String> paths = new ArrayList<>();
        paths.add("s1");
        entity.setScriptResourcePaths(paths);

        return entity;
    }
}
