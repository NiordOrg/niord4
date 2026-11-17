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
import org.niord.core.message.vo.MessageParamFilterVo;
import org.niord.core.user.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for the {@link MessageParamFilter} entity's pure logic: the value-object constructor
 * and the {@code toVo} round-trip.
 *
 * <p>The behaviourally important detail pinned here is the data-isolation property of the VO
 * boundary: the {@link MessageParamFilterVo} has no {@code user} field, the VO constructor never
 * sets the owning {@link User}, and {@code toVo} never exposes it. A named filter is therefore
 * user-scoped only on the entity side; the owning user is never carried across the VO boundary in
 * either direction. The remaining tests assert plain id/name/parameters copying and the user
 * getter/setter.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks — so they survive
 * a future Spring port.
 */
public class MessageParamFilterEntityTest {

    // --------------------------------------------------------------------------------------------- //
    // MessageParamFilter(MessageParamFilterVo) constructor
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorFromVoCopiesIdNameParametersButNotUser() {
        MessageParamFilterVo vo = new MessageParamFilterVo();
        vo.setId(7);
        vo.setName("my filter");
        vo.setParameters("status=PUBLISHED&type=LOCAL_WARNING");

        MessageParamFilter f = new MessageParamFilter(vo);

        assertEquals(Integer.valueOf(7), f.getId());
        assertEquals("my filter", f.getName());
        assertEquals("status=PUBLISHED&type=LOCAL_WARNING", f.getParameters());
        assertNull(f.getUser(), "VO ctor must not set the owning user");
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoMapsIdNameParametersAndDropsUser() {
        MessageParamFilter f = new MessageParamFilter();
        f.setId(9);
        f.setName("n");
        f.setParameters("p=1");
        User u = new User();
        u.setId(3);
        f.setUser(u);

        MessageParamFilterVo vo = f.toVo();

        // The VO exposes id/name/parameters only — it has no getUser(), so the owning user is
        // structurally dropped at the VO boundary.
        assertEquals(Integer.valueOf(9), vo.getId());
        assertEquals("n", vo.getName());
        assertEquals("p=1", vo.getParameters());
    }

    // --------------------------------------------------------------------------------------------- //
    // VO round-trip
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void voRoundTripPreservesData() {
        MessageParamFilterVo source = new MessageParamFilterVo();
        source.setId(42);
        source.setName("round-trip");
        source.setParameters("a=1&b=2");

        MessageParamFilterVo result = new MessageParamFilter(source).toVo();

        assertEquals(Integer.valueOf(42), result.getId());
        assertEquals("round-trip", result.getName());
        assertEquals("a=1&b=2", result.getParameters());
    }

    // --------------------------------------------------------------------------------------------- //
    // Getters and Setters
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void settersAndGettersForUser() {
        MessageParamFilter f = new MessageParamFilter();

        User u = new User();
        f.setUser(u);
        assertSame(u, f.getUser());

        f.setName("x");
        assertEquals("x", f.getName());

        f.setParameters("y");
        assertEquals("y", f.getParameters());
    }
}
