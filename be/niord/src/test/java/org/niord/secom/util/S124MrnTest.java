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
package org.niord.secom.util;

import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.model.message.MainType;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link S124Mrn}: the MRN must mirror the expression used by the S-124
 * generator ({@code org.niord.s124.mapper.S124Mapper#toMrn}), so the dataset row key can
 * never diverge from the {@code interoperabilityIdentifier} inside the GML.
 */
public class S124MrnTest {

    @Test
    public void mrnMirrorsTheTemplateExpression() {
        Message message = new Message();
        message.setMainType(MainType.NW);
        message.setShortId("NW-004-25");

        assertEquals("urn:mrn:iho:nw:se:nw-004-25", S124Mrn.of(message, "SE"));
    }

    @Test
    public void theCountryTokenIsTheCountrySettingLowerCased() {
        Message message = new Message();
        message.setMainType(MainType.NW);
        message.setShortId("NW-004-25");

        assertEquals("urn:mrn:iho:nw:gl:nw-004-25", S124Mrn.of(message, "GL"));
        assertEquals("urn:mrn:iho:nw:gl:nw-004-25", S124Mrn.of(message, "gl"),
                "a lower-case country gives the same token");
    }

    @Test
    public void fallsBackToTheUidWhenShortIdIsMissing() {
        Message message = new Message();
        message.setMainType(MainType.NW);
        message.setUid("11ecb8e0-a234-4de8-9c88-1dd0e4f4c11d");

        assertEquals("urn:mrn:iho:nw:se:11ecb8e0-a234-4de8-9c88-1dd0e4f4c11d", S124Mrn.of(message, "SE"));
    }
}
