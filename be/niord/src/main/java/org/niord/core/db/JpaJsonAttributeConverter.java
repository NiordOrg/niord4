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
package org.niord.core.db;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.AttributeConverter;

/**
 * Use to convert a JPA database field to and fro JSON.
 * Usage:
 * <pre>
 *     {@literal @}Convert(converter = JpaJsonAttributeConverter.class)
 * </pre>
 */
public class JpaJsonAttributeConverter implements AttributeConverter<Object, String> {

    private final static ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults().build();
    private final static Logger log = LoggerFactory.getLogger(JpaJsonAttributeConverter.class);

    /** {@inheritDoc} */
    @Override
    public String convertToDatabaseColumn(Object value) {

        if (value == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            log.error("Error converting JSON to String: " + value);
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Object convertToEntityAttribute(String value) {

        if (value == null) {
            return null;
        }

        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JacksonException ex) {
            log.error("Error converting String to JSON: " + value);
            return null;
        }
    }
}
