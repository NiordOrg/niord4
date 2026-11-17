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
package org.niord.core.db;

import org.apache.commons.lang3.StringUtils;

import jakarta.persistence.AttributeConverter;
import java.util.Arrays;
import java.util.List;

/**
 * Converts a JPA field to and from a {@code List<String>} stored as one comma-separated column,
 * in list order - for short code lists whose order matters (e.g. language codes), where a
 * collection table with an order column would be more machinery than the data warrants.
 * An empty list is stored as NULL and NULL reads back as an empty list; blank entries are
 * dropped. Usage:
 * <pre>
 *     {@literal @}Convert(converter = JpaStringListAttributeConverter.class)
 * </pre>
 */
public class JpaStringListAttributeConverter implements AttributeConverter<List<String>, String> {

    /** {@inheritDoc} */
    @Override
    public String convertToDatabaseColumn(List<String> value) {
        return value == null || value.isEmpty() ? null : String.join(",", value);
    }

    /** {@inheritDoc} */
    @Override
    public List<String> convertToEntityAttribute(String value) {
        return StringUtils.isBlank(value)
                ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::isNotBlank).toList();
    }
}
