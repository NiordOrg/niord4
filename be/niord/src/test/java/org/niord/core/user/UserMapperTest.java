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
import org.niord.core.user.vo.UserVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for {@link UserMapper}. */
public class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    public void toVoCopiesAllFields() {
        User user = new User();
        user.setUsername("jdoe");
        user.setEmail("jdoe@dma.dk");
        user.setFirstName("John");
        user.setLastName("Doe");
        user.setLanguage("en");

        UserVo vo = mapper.toVo(user);

        assertEquals("jdoe", vo.getUsername());
        assertEquals("jdoe@dma.dk", vo.getEmail());
        assertEquals("John", vo.getFirstName());
        assertEquals("Doe", vo.getLastName());
        assertEquals("en", vo.getLanguage());
    }

    @Test
    public void roundTripPreservesFields() {
        UserVo vo = new UserVo();
        vo.setUsername("asmith");
        vo.setEmail("asmith@dma.dk");
        vo.setFirstName("Anna");
        vo.setLastName("Smith");
        vo.setLanguage("da");

        User user = mapper.toEntity(vo);

        assertEquals("asmith", user.getUsername());
        assertEquals("asmith@dma.dk", user.getEmail());
        assertEquals("Anna", user.getFirstName());
        assertEquals("Smith", user.getLastName());
        assertEquals("da", user.getLanguage());
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
