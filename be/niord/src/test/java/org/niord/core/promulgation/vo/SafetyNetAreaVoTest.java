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
package org.niord.core.promulgation.vo;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.SafetyNetArea;
import org.niord.core.promulgation.SafetyNetArea.SafetyNetRepetition;
import org.niord.core.promulgation.SafetyNetCircularArea;
import org.niord.core.promulgation.SafetyNetCoastalArea;
import org.niord.core.promulgation.SafetyNetNavareaArea;
import org.niord.core.promulgation.SafetyNetNavareaArea.NAVAREA;
import org.niord.core.promulgation.SafetyNetRectangularArea;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetCircularAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetCoastalAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetNavareaAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetRectangularAreaVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link SafetyNetAreaVo} and its subclasses.
 *
 * <p>These exercise the value object as a plain POJO: no Quarkus container, no CDI, no database and no
 * mocking framework. They pin three contracts that must survive a future port to Spring:
 *
 * <ul>
 *   <li>the polymorphic {@link SafetyNetAreaVo#toEntity()} dispatch, including the subtle fact that
 *       {@code SafetyNetCoastalAreaVo} <em>extends</em> {@code SafetyNetNavareaAreaVo} yet the
 *       {@code instanceof} ladder checks coastal first, so a coastal VO must map to the coastal entity;</li>
 *   <li>the human-readable description helpers {@code getRepetitionDescription()} and
 *       {@code getNavareaDescription()};</li>
 *   <li>the Jackson NAME-based polymorphic JSON contract declared by the {@code @JsonTypeInfo}/{@code @JsonSubTypes}
 *       annotations (the {@code "type"} discriminator and the round-trip back to the concrete subclass).</li>
 * </ul>
 */
public class SafetyNetAreaVoTest {

    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        mapper = new ObjectMapper();
    }

    /*******************************************/
    /** (1) toEntity() polymorphic dispatch   **/
    /*******************************************/

    @Test
    public void toEntity_circularVo_buildsCircularAreaAndCopiesCenterAndRadius() {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        vo.setCenter("56 00.0N 011 00.0E");
        vo.setRadius("25");

        SafetyNetArea entity = vo.toEntity();

        assertEquals(SafetyNetCircularArea.class, entity.getClass(),
                "circular VO must map to circular entity");
        SafetyNetCircularArea circular = (SafetyNetCircularArea) entity;
        assertEquals("56 00.0N 011 00.0E", circular.getCenter(), "center must survive onto the entity");
        assertEquals("25", circular.getRadius(), "radius must survive onto the entity");
    }

    @Test
    public void toEntity_rectangularVo_buildsRectangularArea() {
        SafetyNetRectangularAreaVo vo = new SafetyNetRectangularAreaVo();

        SafetyNetArea entity = vo.toEntity();

        assertEquals(SafetyNetRectangularArea.class, entity.getClass(),
                "rectangular VO must map to rectangular entity");
        assertTrue(entity instanceof SafetyNetRectangularArea);
    }

    @Test
    public void toEntity_coastalVo_mapsToCoastalAreaNotNavarea() {
        // SafetyNetCoastalAreaVo extends SafetyNetNavareaAreaVo, but the instanceof ladder in toEntity()
        // checks Coastal BEFORE Navarea, so it must produce a SafetyNetCoastalArea, not a SafetyNetNavareaArea.
        SafetyNetCoastalAreaVo vo = new SafetyNetCoastalAreaVo();

        SafetyNetArea entity = vo.toEntity();

        assertEquals(SafetyNetCoastalArea.class, entity.getClass(),
                "coastal VO must map to the coastal entity, not the navarea base entity");
    }

    @Test
    public void toEntity_navareaVo_mapsToNavareaAreaAndNotCoastal() {
        SafetyNetNavareaAreaVo vo = new SafetyNetNavareaAreaVo();

        SafetyNetArea entity = vo.toEntity();

        assertEquals(SafetyNetNavareaArea.class, entity.getClass(),
                "navarea VO must map to the navarea entity exactly (not the coastal subclass)");
    }

    @Test
    public void toEntity_plainBaseVo_returnsNull() {
        // A plain base SafetyNetAreaVo matches none of the instanceof branches.
        SafetyNetAreaVo vo = new SafetyNetAreaVo();

        assertNull(vo.toEntity(), "a plain base VO with no concrete subtype must yield a null entity");
    }

    /*******************************************/
    /** (2) getRepetitionDescription()        **/
    /*******************************************/

    @Test
    public void getRepetitionDescription_nullRepetition_returnsEmptyString() {
        SafetyNetAreaVo vo = new SafetyNetAreaVo();
        assertNull(vo.getRepetition(), "precondition: repetition starts unset");

        assertEquals("", vo.getRepetitionDescription(),
                "a null repetition must produce the empty string, not null");
    }

    @Test
    public void getRepetitionDescription_repetition01_returnsCodeAndDescription() {
        SafetyNetAreaVo vo = new SafetyNetAreaVo();
        vo.setRepetition(SafetyNetRepetition.REPETITION_01);

        assertEquals("01 - Transmit once", vo.getRepetitionDescription());
    }

    @Test
    public void getRepetitionDescription_repetition11_returnsCodeAndDescription() {
        SafetyNetAreaVo vo = new SafetyNetAreaVo();
        vo.setRepetition(SafetyNetRepetition.REPETITION_11);

        assertEquals("11 - Two transmissions, 6 min. interval", vo.getRepetitionDescription());
    }

    /*******************************************/
    /** (3) getNavareaDescription()           **/
    /*******************************************/

    @Test
    public void getNavareaDescription_nullNavarea_returnsEmptyString() {
        SafetyNetNavareaAreaVo vo = new SafetyNetNavareaAreaVo();
        assertNull(vo.getNavarea(), "precondition: navarea starts unset");

        assertEquals("", vo.getNavareaDescription(),
                "a null navarea must produce the empty string, not null");
    }

    @Test
    public void getNavareaDescription_navareaII_returnsNameDashCoordinator() {
        SafetyNetNavareaAreaVo vo = new SafetyNetNavareaAreaVo();
        vo.setNavarea(NAVAREA.II);

        assertEquals("II-France", vo.getNavareaDescription());
    }

    @Test
    public void getNavareaDescription_navareaXIX_returnsNameDashCoordinator() {
        SafetyNetNavareaAreaVo vo = new SafetyNetNavareaAreaVo();
        vo.setNavarea(NAVAREA.XIX);

        assertEquals("XIX-Norway", vo.getNavareaDescription());
    }

    @Test
    public void getNavareaDescription_inheritedByCoastalVo() {
        // SafetyNetCoastalAreaVo inherits getNavareaDescription() from SafetyNetNavareaAreaVo.
        SafetyNetCoastalAreaVo vo = new SafetyNetCoastalAreaVo();
        vo.setNavarea(NAVAREA.II);

        assertEquals("II-France", vo.getNavareaDescription());
    }

    /*******************************************/
    /** (4) Jackson NAME-based polymorphism   **/
    /*******************************************/

    @Test
    public void json_circularVo_emitsCircularDiscriminatorAndRoundTrips() throws Exception {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        vo.setCenter("C");
        vo.setRadius("R");
        vo.setName("X");

        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"CIRCULAR\""),
                "JSON must carry the CIRCULAR type discriminator, was: " + json);

        SafetyNetAreaVo back = mapper.readValue(json, SafetyNetAreaVo.class);
        assertTrue(back instanceof SafetyNetCircularAreaVo, "deserialized value must be a circular VO");
        assertEquals("C", ((SafetyNetCircularAreaVo) back).getCenter(), "center must survive the JSON round-trip");
        assertEquals("R", ((SafetyNetCircularAreaVo) back).getRadius(), "radius must survive the JSON round-trip");
        assertEquals("X", back.getName(), "name must survive the JSON round-trip");
    }

    @Test
    public void json_rectangularVo_emitsRectangularDiscriminatorAndRoundTrips() throws Exception {
        SafetyNetRectangularAreaVo vo = new SafetyNetRectangularAreaVo();
        vo.setName("X");
        vo.setSwCorner("SW");

        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"RECTANGULAR\""),
                "JSON must carry the RECTANGULAR type discriminator, was: " + json);

        SafetyNetAreaVo back = mapper.readValue(json, SafetyNetAreaVo.class);
        assertTrue(back instanceof SafetyNetRectangularAreaVo, "deserialized value must be a rectangular VO");
        assertEquals("SW", ((SafetyNetRectangularAreaVo) back).getSwCorner(),
                "swCorner must survive the JSON round-trip");
        assertEquals("X", back.getName(), "name must survive the JSON round-trip");
    }

    @Test
    public void json_coastalVo_emitsCoastalDiscriminatorAndRoundTrips() throws Exception {
        SafetyNetCoastalAreaVo vo = new SafetyNetCoastalAreaVo();
        vo.setName("X");
        vo.setNavarea(NAVAREA.II);

        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"COASTAL\""),
                "JSON must carry the COASTAL type discriminator, was: " + json);

        SafetyNetAreaVo back = mapper.readValue(json, SafetyNetAreaVo.class);
        // Coastal extends Navarea, so the discriminator must resolve to the exact coastal subclass.
        assertEquals(SafetyNetCoastalAreaVo.class, back.getClass(),
                "deserialized value must be the exact coastal VO subclass");
        assertEquals(NAVAREA.II, ((SafetyNetCoastalAreaVo) back).getNavarea(),
                "navarea must survive the JSON round-trip");
        assertEquals("X", back.getName(), "name must survive the JSON round-trip");
    }

    @Test
    public void json_navareaVo_emitsNavareaDiscriminatorAndRoundTrips() throws Exception {
        SafetyNetNavareaAreaVo vo = new SafetyNetNavareaAreaVo();
        vo.setName("X");
        vo.setNavarea(NAVAREA.XIX);

        String json = mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"type\":\"NAVAREA\""),
                "JSON must carry the NAVAREA type discriminator, was: " + json);

        SafetyNetAreaVo back = mapper.readValue(json, SafetyNetAreaVo.class);
        // Must resolve to the navarea VO exactly, not the coastal subclass that extends it.
        assertEquals(SafetyNetNavareaAreaVo.class, back.getClass(),
                "deserialized value must be the exact navarea VO subclass");
        assertEquals(NAVAREA.XIX, ((SafetyNetNavareaAreaVo) back).getNavarea(),
                "navarea must survive the JSON round-trip");
        assertEquals("X", back.getName(), "name must survive the JSON round-trip");
    }
}
