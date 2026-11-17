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
package org.niord.core.user;

import org.junit.jupiter.api.Test;
import org.niord.core.user.vo.ContactVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for {@link ContactMapper}. */
public class ContactMapperTest {

    private final ContactMapper mapper = new ContactMapper();

    @Test
    public void toVoCopiesAllFields() {
        Contact contact = new Contact();
        contact.setId(5);
        contact.setEmail("c@dma.dk");
        contact.setName("Coastguard");
        contact.setLanguage("en");

        ContactVo vo = mapper.toVo(contact);

        assertEquals(Integer.valueOf(5), vo.getId());
        assertEquals("c@dma.dk", vo.getEmail());
        assertEquals("Coastguard", vo.getName());
        assertEquals("en", vo.getLanguage());
    }

    @Test
    public void roundTripPreservesFields() {
        ContactVo vo = new ContactVo();
        vo.setId(9);
        vo.setEmail("x@dma.dk");
        vo.setName("Harbour");
        vo.setLanguage("da");

        Contact contact = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(9), contact.getId());
        assertEquals("x@dma.dk", contact.getEmail());
        assertEquals("Harbour", contact.getName());
        assertEquals("da", contact.getLanguage());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }
}
