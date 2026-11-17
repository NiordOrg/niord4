/*
 * Copyright 2026 Danish Maritime Authority.
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LightCharacterParser}, which turns the light character of an AtoN into the model that
 * {@code light-character.ftl} prints as words for mariners ("Fl(2)G. 5s" becomes "groups of 2 flashes in green,
 * repeated every 5 seconds"), over the forms of a light list: every phase with and without a group and
 * colours, and the period, elevation and range written the ways a light list writes them.
 * <p>
 * The property: a number in the light character is the number in the model, or the character is refused (and
 * the directive then prints it as written). A number may not change on the way: the period, elevation and range,
 * and the group's numbers ("Fl(2)" is two flashes), as well as the phase and the colours written (a parser that
 * kept "grouped" and lost the numbers passed the first version of this test).
 */
public class LightCharacterParserGridTest {

    private static LightCharacterModel parse(String lc) throws Exception {
        return LightCharacterParser.getInstance().parse(lc);
    }

    @Test
    public void wholePeriodsElevationsAndRanges_areReadAsWritten_overTheGrid() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        String[] phases = { "F", "Fl", "LFl", "Q", "VQ", "IQ", "Iso", "Oc", "Al" };
        String[] groups = { "", "(2)", "(3)", "(2+1)" };
        String[] colours = { "", "W", "R", "G", "Y", "WR", "WRG" };
        for (String phase : phases) {
            for (String group : groups) {
                if (!group.isEmpty() && (phase.equals("F") || phase.equals("Iso") || phase.equals("Al"))) {
                    continue;
                }
                for (String colour : colours) {
                    for (int period : new int[] { 1, 3, 5, 10, 15, 60 }) {
                        String lc = phase + group + (colour.isEmpty() ? "" : "." + colour) + "." + period + "s 12m 8M";
                        // The group's numbers ("(2+1)" is 2 then 1) and the colours ("WRG" is W, R, G), as written
                        List<Integer> groupSpec = group.isEmpty() ? List.of()
                                : Arrays.stream(group.substring(1, group.length() - 1).split("\\+"))
                                        .map(Integer::valueOf).toList();
                        List<String> colourSpec = colour.chars().mapToObj(c -> String.valueOf((char) c)).toList();
                        try {
                            LightCharacterModel m = parse(lc);
                            if (m.getPeriod() == null || m.getPeriod().intValueExact() != period
                                    || m.getElevation() == null || m.getElevation().intValueExact() != 12
                                    || m.getRange() == null || m.getRange().intValueExact() != 8) {
                                failures.add(lc + " -> period " + m.getPeriod() + ", elevation " + m.getElevation()
                                        + ", range " + m.getRange());
                            } else if (m.getLightGroups().size() != 1
                                    || !phase.equals(m.getLightGroups().get(0).getPhase())
                                    || !groupSpec.equals(m.getLightGroups().get(0).getGroupSpec())
                                    || !colourSpec.equals(m.getLightGroups().get(0).getColors())) {
                                failures.add(lc + " -> groups " + m.getLightGroups().stream()
                                        .map(g -> g.getPhase() + g.getGroupSpec() + g.getColors()).toList());
                            }
                        } catch (Exception e) {
                            failures.add(lc + " -> refused: " + e.getMessage());
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 1000, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " of " + checked + " wrong, the first: "
                + failures.subList(0, Math.min(10, failures.size())));
    }

    @Test
    public void aPeriodOrRangeWithADecimalPoint_isReadAsWritten() throws Exception {
        // A dot between two digits is a decimal point, every other dot a separator (ATON14): light lists write
        // fractional periods (Fl.2.5s, Iso.1.5s), and "Fl.G.2.5s" was read as 25 seconds when normalize()
        // removed every dot. The parse is also the editor's validation of a light character, so the model reads
        // the decimal rather than refusing it.
        assertEquals(new BigDecimal("2.5"), parse("Fl.G.2.5s").getPeriod());
        assertEquals(new BigDecimal("1.5"), parse("Iso.1.5s").getPeriod());

        LightCharacterModel withRange = parse("Fl.R.3s 4.5M");
        assertEquals(new BigDecimal("3"), withRange.getPeriod());
        assertEquals(new BigDecimal("4.5"), withRange.getRange());

        LightCharacterModel all = parse("Fl(2)W.7.5s 12m 8M");
        assertEquals(new BigDecimal("7.5"), all.getPeriod());
        assertEquals(new BigDecimal("12"), all.getElevation());
        assertEquals(new BigDecimal("8"), all.getRange());
        assertEquals(1, all.getLightGroups().size());
        assertEquals("Fl", all.getLightGroups().get(0).getPhase());
        assertEquals(List.of(2), all.getLightGroups().get(0).getGroupSpec());
        assertEquals(List.of("W"), all.getLightGroups().get(0).getColors());

        LightCharacterModel twoGroups = parse("Q(6)+LFl.W.12.5s");
        assertEquals(new BigDecimal("12.5"), twoGroups.getPeriod());
        assertEquals(List.of("Q", "LFl"), twoGroups.getLightGroups().stream().map(LightCharacterModel.LightGroup::getPhase).toList());
    }

    @Test
    public void aPeriodWithADecimalComma_isRefused_andSoPrintedAsWritten() {
        // With a comma the leftover "2," is unknown and the parse fails; LightCharacterDirective then writes the
        // character unchanged, which is safe.
        assertThrows(Exception.class, () -> parse("Fl.G.2,5s"));
    }

    @Test
    public void alternatingFlashes_loseTheFlashes_documentsBehavior() throws Exception {
        // normalize() turns "AlFl" into "Al", so "Al.Fl.WR.5s" is modelled as an alternating light with no
        // flashes, the same as "Al.WR.5s". A verbose text can then not say that the light flashes.
        LightCharacterModel withFlashes = parse("Al.Fl.WR.5s");
        LightCharacterModel without = parse("Al.WR.5s");
        assertEquals(1, withFlashes.getLightGroups().size());
        assertEquals("Al", withFlashes.getLightGroups().get(0).getPhase());
        assertEquals(without.getLightGroups().get(0).getPhase(), withFlashes.getLightGroups().get(0).getPhase());
    }
}
