/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series.criteria;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists a criteria document as canonical JSON.
 *
 * Deliberately NOT JpaJsonAttributeConverter, which deserialises to a bare
 * Object and would lose the polymorphic node typing, nor
 * JpaPropertiesAttributeConverter, which is fixed to Map<String,Object>. Both
 * also catch parse failures and return null, and a silently-nulled criteria
 * document is the difference between "this series has no query" and "this series
 * matches everything".
 *
 * A null column stays null: that is the no-membership case, and it is not the
 * same as an empty document.
 *
 * DELIBERATELY JACKSON 2, unlike the rest of this application: the canonical
 * criteria bytes (fixture hashes, the legacy importer's byte-identical dry run)
 * depend on the Jackson 2 mapper in CriteriaSerialization, which is what this
 * converter writes to and reads from the column.
 * com.fasterxml.jackson.databind is on the compile classpath through
 * resteasy-jackson2-provider.
 */
@Converter
public class JpaCriteriaAttributeConverter implements AttributeConverter<IssueCriteriaVo, String> {

    @Override
    public String convertToDatabaseColumn(IssueCriteriaVo value) {
        if (value == null) {
            return null;
        }
        try {
            return CriteriaSerialization.mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CriteriaParseException("could not serialize the criteria document", e);
        }
    }

    @Override
    public IssueCriteriaVo convertToEntityAttribute(String value) {
        if (value == null) {
            return null;
        }
        try {
            return CriteriaSerialization.mapper().readValue(value, IssueCriteriaVo.class);
        } catch (JsonProcessingException e) {
            // Loudly. A stored document that cannot be read is a data problem,
            // and continuing with null would resolve as an empty query.
            throw new CriteriaParseException("could not parse the stored criteria document: " + value, e);
        }
    }
}
