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
package org.niord.s124;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.niord.model.message.ReferenceType;

import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTypeGeneralLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ReferenceCategoryLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ReferenceCategoryType;

/**
 * Unit tests for the S-124 v2.0.0 codelist mappings.
 */
public class S124StandardMappingsTest {

    @Test
    public void referenceCategoryMappingFollowsTheSpecForEveryNiordReferenceType() {
        assertEquals(ReferenceCategoryLabel.WARNING_CANCELLATION,
                S124StandardMappings.toReferenceCategory(ReferenceType.CANCELLATION));
        assertEquals(ReferenceCategoryLabel.WARNING_CANCELLATION,
                S124StandardMappings.toReferenceCategory(ReferenceType.UPDATE));
        assertEquals(ReferenceCategoryLabel.WARNING_REFERENCE,
                S124StandardMappings.toReferenceCategory(ReferenceType.REPETITION));
        assertEquals(ReferenceCategoryLabel.WARNING_CANCELLATION,
                S124StandardMappings.toReferenceCategory(ReferenceType.REPETITION_NEW_TIME));
        assertEquals(ReferenceCategoryLabel.WARNING_REFERENCE,
                S124StandardMappings.toReferenceCategory(ReferenceType.REFERENCE));
        assertEquals(ReferenceCategoryLabel.WARNING_REFERENCE,
                S124StandardMappings.toReferenceCategory(null));
    }

    /**
     * Regression: {@code noMessageOnHand} previously used Niord's {@code cancelsReferencedMessage()}
     * set, which includes cancellation references. S-124 reserves {@code noMessageOnHand=true} for
     * empty In-Force Bulletins, not ordinary warning references or cancellations.
     */
    @Test
    public void noMessageOnHandIsFalseForAllWarningReferences() {
        for (ReferenceType type : ReferenceType.values()) {
            assertFalse(S124StandardMappings.noMessageOnHand(type),
                    "noMessageOnHand must be false for " + type);
        }
    }

    /**
     * Pins the complete navwarnTypeGeneral codelist. The XSD lists the label and code
     * enumerations in codelist order (label N pairs with code N), which the official IHO
     * S-164 test datasets confirm (e.g. {@code code="17">Special Operations},
     * {@code code="10">Newly Discovered Dangers}, {@code code="8">In-Force Bulletin}).
     */
    @Test
    public void navwarnTypeGeneralCodesFollowTheXsdCodelistOrder() {
        NavwarnTypeGeneralLabel[] labels = NavwarnTypeGeneralLabel.values();
        assertEquals(labels.length, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.size(),
                "every codelist label must have a code");
        // The generated JAXB enum preserves the XSD enumeration order, so ordinal + 1 == code
        for (NavwarnTypeGeneralLabel label : labels) {
            assertEquals(label.ordinal() + 1, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(label),
                    "code for " + label);
        }
        // Spot-check the pairs seen in the official IHO S-164 sample datasets
        assertEquals(1, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(NavwarnTypeGeneralLabel.AIDS_TO_NAVIGATION_CHANGES));
        assertEquals(3, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(NavwarnTypeGeneralLabel.DRIFTING_HAZARDS));
        assertEquals(8, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(NavwarnTypeGeneralLabel.IN_FORCE_BULLETIN));
        assertEquals(10, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(NavwarnTypeGeneralLabel.NEWLY_DISCOVERED_DANGERS));
        assertEquals(17, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(NavwarnTypeGeneralLabel.SPECIAL_OPERATIONS));
        // The mapper's fallback label is Other Hazards; the default code must be its code
        assertEquals(5, S124StandardMappings.NAVWARN_TYPE_GENERAL_CODES.get(NavwarnTypeGeneralLabel.OTHER_HAZARDS));
        assertEquals(5, S124StandardMappings.NAVWARN_TYPE_GENERAL_DEFAULT_CODE);
    }

    /** The XSD pairs category labels with non-positional codes: 1=Cancellation, 2=Reference, 3=In-Force */
    @Test
    public void referenceCategoryElementCarriesTheSpecCodeValuePair() {
        ReferenceCategoryType cancellation =
                S124StandardMappings.buildReferenceCategory(ReferenceCategoryLabel.WARNING_CANCELLATION);
        assertEquals(ReferenceCategoryLabel.WARNING_CANCELLATION, cancellation.getValue());
        assertEquals(BigInteger.valueOf(1), cancellation.getCode());

        ReferenceCategoryType reference =
                S124StandardMappings.buildReferenceCategory(ReferenceCategoryLabel.WARNING_REFERENCE);
        assertEquals(BigInteger.valueOf(2), reference.getCode());

        ReferenceCategoryType inForce =
                S124StandardMappings.buildReferenceCategory(ReferenceCategoryLabel.IN_FORCE);
        assertEquals(BigInteger.valueOf(3), inForce.getCode());
    }
}
