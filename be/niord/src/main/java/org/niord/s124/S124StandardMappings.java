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

import java.math.BigInteger;
import java.util.Map;

import org.niord.model.message.ReferenceType;

import dk.dma.niord.s100.xmlbindings.s100.gml.profiles._5_0.impl.ReferenceTypeImpl;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.NavwarnTypeGeneralLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ObjectFactory;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ReferenceCategoryLabel;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.ReferenceCategoryType;
import dk.dma.niord.s100.xmlbindings.s124.v2_0_0.RestrictionLabel;

/**
 * Single source of truth for S-124 v2.0.0 codelist mappings. The XSD ({@code 124_2.0.0.xsd})
 * declares {@code restrictionLabel} / {@code restrictionCode} and
 * {@code referenceCategoryLabel} / {@code referenceCategoryCode} as separate enumerations
 * paired by IHO Feature Catalogue convention — the codes are NOT positional, so {@code ordinal()}
 * can't be used.
 */
public final class S124StandardMappings {

    private static final ObjectFactory S124 = new ObjectFactory();

    /** {@code restrictionLabel} → {@code restrictionCode} per S-124 v2.0.0 §restrictionCode. */
    public static final Map<RestrictionLabel, Integer> RESTRICTION_CODES = Map.of(
            RestrictionLabel.ENTRY_PROHIBITED, 7,
            RestrictionLabel.ENTRY_RESTRICTED, 8,
            RestrictionLabel.AREA_TO_BE_AVOIDED, 14,
            RestrictionLabel.STOPPING_PROHIBITED, 25,
            RestrictionLabel.SPEED_RESTRICTED, 27);

    /** {@code referenceCategoryLabel} → {@code referenceCategoryCode} per S-124 v2.0.0. */
    public static final Map<ReferenceCategoryLabel, Integer> REFERENCE_CATEGORY_CODES = Map.of(
            ReferenceCategoryLabel.WARNING_CANCELLATION, 1,
            ReferenceCategoryLabel.WARNING_REFERENCE, 2,
            ReferenceCategoryLabel.IN_FORCE, 3);

    /**
     * The fallback {@code navwarnTypeGeneralCode} used when no explicit mapping exists for a label.
     * Mirrors {@code S124Mapper.toNavwarnTypeGeneral}, which defaults to Other Hazards (code 5).
     */
    public static final int NAVWARN_TYPE_GENERAL_DEFAULT_CODE = 5;

    /**
     * {@code navwarnTypeGeneralLabel} → {@code navwarnTypeGeneralCode}: the complete S-124 v2.0.0
     * codelist. The XSD declares labels and codes as separate enumerations, both listed in codelist
     * order (verified against the IHO S-164 test datasets, e.g. {@code code="17">Special Operations}
     * and {@code code="10">Newly Discovered Dangers}), so label N pairs with code N — but the JAXB
     * enum can't express that, hence this table.
     */
    public static final Map<NavwarnTypeGeneralLabel, Integer> NAVWARN_TYPE_GENERAL_CODES = Map.ofEntries(
            Map.entry(NavwarnTypeGeneralLabel.AIDS_TO_NAVIGATION_CHANGES, 1),
            Map.entry(NavwarnTypeGeneralLabel.AQUACULTURE_AND_FISHING_INSTALLATIONS, 2),
            Map.entry(NavwarnTypeGeneralLabel.DRIFTING_HAZARDS, 3),
            Map.entry(NavwarnTypeGeneralLabel.ECDIS_OPERATING_ANOMALIES_INCLUDING_OFFICIAL_DATA_ISSUES, 4),
            Map.entry(NavwarnTypeGeneralLabel.OTHER_HAZARDS, 5),
            Map.entry(NavwarnTypeGeneralLabel.HEALTH_ADVISORIES, 6),
            Map.entry(NavwarnTypeGeneralLabel.ICE_INFORMATION, 7),
            Map.entry(NavwarnTypeGeneralLabel.IN_FORCE_BULLETIN, 8),
            Map.entry(NavwarnTypeGeneralLabel.DANGEROUS_NATURAL_PHENOMENA, 9),
            Map.entry(NavwarnTypeGeneralLabel.NEWLY_DISCOVERED_DANGERS, 10),
            Map.entry(NavwarnTypeGeneralLabel.OFFSHORE_INFRASTRUCTURE, 11),
            Map.entry(NavwarnTypeGeneralLabel.PIRACY_OR_ROBBERY, 12),
            Map.entry(NavwarnTypeGeneralLabel.COMMUNICATION_OR_BROADCAST_SERVICE_CHANGE, 13),
            Map.entry(NavwarnTypeGeneralLabel.ROUTEING_CHANGE, 14),
            Map.entry(NavwarnTypeGeneralLabel.SCIENTIFIC_INSTRUMENTS_CHANGE, 15),
            Map.entry(NavwarnTypeGeneralLabel.SECURITY_REQUIREMENT_CHANGE, 16),
            Map.entry(NavwarnTypeGeneralLabel.SPECIAL_OPERATIONS, 17),
            Map.entry(NavwarnTypeGeneralLabel.TOWING_OPERATIONS, 18),
            Map.entry(NavwarnTypeGeneralLabel.WORKS, 19),
            Map.entry(NavwarnTypeGeneralLabel.RIG_LIST, 20));

    private S124StandardMappings() {}

    /**
     * Maps a Niord {@link ReferenceType} to the corresponding S-124
     * {@link ReferenceCategoryLabel}. UPDATE and REPETITION_NEW_TIME supersede the previous
     * warning, plain REPETITION is a routine re-broadcast, and REFERENCE is a generic
     * cross-reference.
     */
    public static ReferenceCategoryLabel toReferenceCategory(ReferenceType type) {
        if (type == null) {
            return ReferenceCategoryLabel.WARNING_REFERENCE;
        }
        return switch (type) {
            case CANCELLATION, UPDATE, REPETITION_NEW_TIME -> ReferenceCategoryLabel.WARNING_CANCELLATION;
            case REPETITION, REFERENCE -> ReferenceCategoryLabel.WARNING_REFERENCE;
        };
    }

    /**
     * Returns the {@code noMessageOnHand} flag for a Niord reference type. This flag is reserved
     * for empty In-Force Bulletins; warning cancellation and reference entries always carry false.
     */
    public static boolean noMessageOnHand(ReferenceType type) {
        return false;
    }

    /** Builds a {@code referenceCategory} JAXB element with the spec-mandated code/value pair. */
    public static ReferenceCategoryType buildReferenceCategory(ReferenceCategoryLabel label) {
        ReferenceCategoryType rct = S124.createReferenceCategoryType();
        rct.setValue(label);
        rct.setCode(BigInteger.valueOf(REFERENCE_CATEGORY_CODES.get(label)));
        return rct;
    }

    /** Builds a {@code gml:ReferenceType} (used for {@code theWarning}, {@code affects}, etc.). */
    public static ReferenceTypeImpl buildGmlReference(String href, String role) {
        ReferenceTypeImpl ref = new ReferenceTypeImpl();
        ref.setHref(href);
        if (role != null) {
            ref.setRole(role);
        }
        return ref;
    }
}
