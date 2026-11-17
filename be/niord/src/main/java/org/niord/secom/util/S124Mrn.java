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

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.message.Message;

/**
 * Computes the MRN of the S-124 dataset generated for a message.
 * <p>
 * This MUST mirror the expression used by the S-124 generator
 * ({@code org.niord.s124.mapper.S124Mapper#toMrn}):
 * <pre>{@code "urn:mrn:iho:" + mainType.toLowerCase() + ":" + country.toLowerCase() + ":" + (isNotBlank(shortId) ? shortId : uid).toLowerCase()}</pre>
 * so that the MRN stored with the dataset row always matches the
 * {@code interoperabilityIdentifier} embedded in the generated GML. The caller passes the
 * country the generator is given: the country setting, as {@code S124Service.country()} reads it.
 */
public final class S124Mrn {

    private S124Mrn() {
    }

    /**
     * Returns the S-124 dataset MRN for the given message
     * @param message the message
     * @param country the deployment's country (the country setting); its token is lower-cased
     */
    public static String of(Message message, String country) {
        String id = StringUtils.isNotBlank(message.getShortId()) ? message.getShortId() : message.getUid();
        return "urn:mrn:iho:" + message.getMainType().name().toLowerCase() + ":" + country.toLowerCase(Locale.ROOT)
                + ":" + id.toLowerCase();
    }
}
