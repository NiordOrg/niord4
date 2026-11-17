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
package org.niord.core.conf;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;

/**
 * Customize the Jackson Mapping Operations.
 *
 * This is required for the promulgation VO polymorphism.
 *
 * Jackson 3 mappers are immutable, so the polymorphic type validator is configured on the
 * {@link tools.jackson.databind.json.JsonMapper.Builder} (Spring Boot 4's {@link JsonMapperBuilderCustomizer})
 * rather than mutated on a built mapper as in Jackson 2.
 *
 * @author Nikolaos Vastardis (email: Nikolaos.Vastardis@gla-rad.org)
 */
@Configuration
public class JacksonObjectMapperCustomizer {

    @Bean
    public JsonMapperBuilderCustomizer customize() {
        return builder -> builder.polymorphicTypeValidator(
                BasicPolymorphicTypeValidator
                        .builder()
                        .allowIfBaseType(BaseMessagePromulgationVo.class)
                        .build());
    }

}
