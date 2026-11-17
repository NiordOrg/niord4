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
package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.aton.LightCharacterModel.LightGroup;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the small pieces of logic in:
 * <ul>
 *     <li>{@link LightCharacterModel.LightGroup} - isComposite / toString / setter round-trip</li>
 *     <li>{@link LightCharacterModel} - toString conditional fragments / setter round-trip</li>
 *     <li>{@link LightCharacterService#trimResult(String)} - whitespace + punctuation normalisation</li>
 *     <li>{@link IalaBuoyageSystem} - toString / other()</li>
 * </ul>
 *
 * No database, no CDI, no Quarkus - plain JUnit 4. The class lives in the
 * {@code org.niord.core.aton} package so it can reach the package-private
 * {@link IalaBuoyageSystem#other()} method.
 */
public class LightCharacterModelTest {

    /*****************************************************/
    /** LightGroup.isComposite()                        **/
    /*****************************************************/

    @Test
    public void lightGroupNotGroupedIsNotComposite() {
        LightGroup g = new LightGroup();
        // grouped defaults to false; even with a multi-element groupSpec it must not be composite
        g.getGroupSpec().add(2);
        g.getGroupSpec().add(3);
        assertFalse(g.isGrouped());
        assertFalse(g.isComposite(), "Not grouped => never composite");
    }

    @Test
    public void lightGroupGroupedWithSingleSpecIsNotComposite() {
        LightGroup g = new LightGroup();
        g.setGrouped(true);
        g.getGroupSpec().add(3);
        assertTrue(g.isGrouped());
        assertEquals(1, g.getGroupSpec().size());
        assertFalse(g.isComposite(), "Grouped but single spec element => not composite");
    }

    @Test
    public void lightGroupGroupedWithTwoSpecsIsComposite() {
        LightGroup g = new LightGroup();
        g.setGrouped(true);
        g.getGroupSpec().add(2);
        g.getGroupSpec().add(1);
        assertTrue(g.isGrouped());
        assertEquals(2, g.getGroupSpec().size());
        assertTrue(g.isComposite(), "Grouped with >1 spec element => composite");
    }

    @Test
    public void lightGroupGroupedWithEmptySpecIsNotComposite() {
        LightGroup g = new LightGroup();
        g.setGrouped(true);
        assertTrue(g.isGrouped());
        assertEquals(0, g.getGroupSpec().size());
        assertFalse(g.isComposite(), "Grouped but empty spec => not composite");
    }

    /*****************************************************/
    /** LightGroup.toString()                           **/
    /*****************************************************/

    @Test
    public void lightGroupToStringMinimalContainsPhaseOnly() {
        LightGroup g = new LightGroup();
        g.setPhase("Fl");
        String s = g.toString();
        assertEquals("[phase=Fl]", s);
        // No optional sections present
        assertFalse(s.contains("colors={"));
        assertFalse(s.contains("groupSpec={"));
        assertFalse(s.contains("morseCode="));
    }

    @Test
    public void lightGroupToStringIncludesColorsWhenPresent() {
        LightGroup g = new LightGroup();
        g.setPhase("Fl");
        g.getColors().add("white");
        g.getColors().add("red");
        String s = g.toString();
        assertTrue(s.startsWith("[phase=Fl"));
        assertTrue(s.contains("colors={ white, red }"));
        // groupSpec / morseCode not set => absent
        assertFalse(s.contains("groupSpec={"));
        assertFalse(s.contains("morseCode="));
    }

    @Test
    public void lightGroupToStringIncludesGroupSpecWhenPresent() {
        LightGroup g = new LightGroup();
        g.setPhase("Fl");
        g.getGroupSpec().add(2);
        g.getGroupSpec().add(3);
        String s = g.toString();
        assertTrue(s.contains("groupSpec={ 2, 3 }"));
        assertFalse(s.contains("colors={"));
        assertFalse(s.contains("morseCode="));
    }

    @Test
    public void lightGroupToStringAppendsMorseCodeWhenSet() {
        LightGroup g = new LightGroup();
        g.setPhase("Mo");
        g.setMorseCode("K");
        String s = g.toString();
        assertEquals("[phase=Mo, morseCode=K]", s);
    }

    @Test
    public void lightGroupToStringIncludesAllSectionsInOrder() {
        LightGroup g = new LightGroup();
        g.setPhase("Fl");
        g.getColors().add("white");
        g.getGroupSpec().add(2);
        g.setMorseCode("A");
        // The implementation emits phase, then colors, then groupSpec, then morseCode.
        assertEquals("[phase=Fl, colors={ white }, groupSpec={ 2 }, morseCode=A]", g.toString());
    }

    /*****************************************************/
    /** LightGroup getters/setters round-trip           **/
    /*****************************************************/

    @Test
    public void lightGroupSettersRoundTrip() {
        LightGroup g = new LightGroup();
        g.setPhase("Oc");
        g.setMorseCode("SOS");
        g.setGrouped(true);
        g.getColors().add("green");
        g.getGroupSpec().add(4);

        assertEquals("Oc", g.getPhase());
        assertEquals("SOS", g.getMorseCode());
        assertTrue(g.isGrouped());
        assertEquals(1, g.getColors().size());
        assertEquals("green", g.getColors().get(0));
        assertEquals(1, g.getGroupSpec().size());
        assertEquals(Integer.valueOf(4), g.getGroupSpec().get(0));
    }

    /*****************************************************/
    /** LightCharacterModel.toString()                  **/
    /*****************************************************/

    @Test
    public void modelToStringEmptyHasOnlyLightGroupsSection() {
        LightCharacterModel m = new LightCharacterModel();
        String s = m.toString();
        // No light groups, no elevation/period/range
        assertEquals("[lightGroups={  }]", s);
        assertFalse(s.contains("elevation="));
        assertFalse(s.contains("period="));
        assertFalse(s.contains("range="));
    }

    @Test
    public void modelToStringJoinsLightGroups() {
        LightCharacterModel m = new LightCharacterModel();
        LightGroup g1 = new LightGroup();
        g1.setPhase("Fl");
        LightGroup g2 = new LightGroup();
        g2.setPhase("Oc");
        m.getLightGroups().add(g1);
        m.getLightGroups().add(g2);
        assertEquals("[lightGroups={ [phase=Fl], [phase=Oc] }]", m.toString());
    }

    @Test
    public void modelToStringAppendsElevationPeriodRangeWhenSet() {
        LightCharacterModel m = new LightCharacterModel();
        m.setElevation(new BigDecimal("12"));
        m.setPeriod(new BigDecimal("5"));
        m.setRange(new BigDecimal("20"));
        String s = m.toString();
        assertEquals("[lightGroups={  }, elevation=12, period=5, range=20]", s);
    }

    @Test
    public void modelToStringOmitsUnsetIntegers() {
        LightCharacterModel m = new LightCharacterModel();
        // Only period is set => elevation and range must be absent
        m.setPeriod(new BigDecimal("8"));
        String s = m.toString();
        assertTrue(s.contains(", period=8"));
        assertFalse(s.contains("elevation="), "elevation not set => absent");
        assertFalse(s.contains("range="), "range not set => absent");
    }

    @Test
    public void modelGettersSettersRoundTrip() {
        LightCharacterModel m = new LightCharacterModel();
        m.setElevation(new BigDecimal("7"));
        m.setPeriod(new BigDecimal("15"));
        m.setRange(new BigDecimal("3"));
        assertEquals(new BigDecimal("7"), m.getElevation());
        assertEquals(new BigDecimal("15"), m.getPeriod());
        assertEquals(new BigDecimal("3"), m.getRange());
        assertNotNull(m.getLightGroups());
        assertTrue(m.getLightGroups().isEmpty());
    }

    /*****************************************************/
    /** LightCharacterService.trimResult(String)        **/
    /*****************************************************/

    @Test
    public void trimResultCollapsesWhitespaceRunsAndTrims() {
        // "  a   b " -> collapse -> " a b " -> trim -> "a b"
        assertEquals("a b", LightCharacterService.trimResult("  a   b "));
    }

    @Test
    public void trimResultCollapsesTabsAndNewlines() {
        // \t and \n are whitespace and collapse to a single space; surrounding ws trimmed
        assertEquals("a b", LightCharacterService.trimResult("a\t\n  b"));
    }

    @Test
    public void trimResultRemovesSpaceBeforeSemicolon() {
        // "white ; red" -> (no ws run change) -> replace " ;" -> "white; red"
        assertEquals("white; red", LightCharacterService.trimResult("white ; red"));
    }

    @Test
    public void trimResultRemovesSpaceBeforeComma() {
        // "a  ,  b" -> collapse -> "a , b" -> replace " ," -> "a, b"
        assertEquals("a, b", LightCharacterService.trimResult("a  ,  b"));
    }

    @Test
    public void trimResultCollapsesDashSpace() {
        // "Fl  -  5s" -> collapse -> "Fl - 5s" -> replace "- " -> "Fl -5s"
        assertEquals("Fl -5s", LightCharacterService.trimResult("Fl  -  5s"));
    }

    @Test
    public void trimResultAppliesSemicolonCommaAndDashTogether() {
        // "x  ;  y  ,  z" -> collapse -> "x ; y , z"
        //   replace " ;" -> "x; y , z"
        //   replace " ," -> "x; y, z"
        assertEquals("x; y, z", LightCharacterService.trimResult("x  ;  y  ,  z"));
    }

    @Test
    public void trimResultNullReturnsNull() {
        // isNotBlank(null) == false => returned unchanged (null)
        assertNull(LightCharacterService.trimResult(null));
    }

    @Test
    public void trimResultEmptyReturnsEmpty() {
        // isNotBlank("") == false => returned unchanged ("")
        assertEquals("", LightCharacterService.trimResult(""));
    }

    @Test
    public void trimResultBlankIsLeftUnchanged() {
        // A blank-only string is NOT trimmed because isNotBlank guards the whole block
        String blank = "   ";
        assertEquals("   ", LightCharacterService.trimResult(blank));
    }

    /*****************************************************/
    /** IalaBuoyageSystem                               **/
    /*****************************************************/

    @Test
    public void ialaToStringUsesDisplayName() {
        assertEquals("IALA-A", IalaBuoyageSystem.IALA_A.toString());
        assertEquals("IALA-B", IalaBuoyageSystem.IALA_B.toString());
    }

    @Test
    public void ialaOtherReturnsOpposite() {
        // other() is package-private; reachable from this same-package test
        assertEquals(IalaBuoyageSystem.IALA_B, IalaBuoyageSystem.IALA_A.other());
        assertEquals(IalaBuoyageSystem.IALA_A, IalaBuoyageSystem.IALA_B.other());
    }

    @Test
    public void ialaOtherIsInvolution() {
        // Applying other() twice returns the original value for both members
        assertEquals(IalaBuoyageSystem.IALA_A, IalaBuoyageSystem.IALA_A.other().other());
        assertEquals(IalaBuoyageSystem.IALA_B, IalaBuoyageSystem.IALA_B.other().other());
    }
}
