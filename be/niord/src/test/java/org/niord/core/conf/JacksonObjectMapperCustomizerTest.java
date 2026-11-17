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

import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JacksonObjectMapperCustomizer#customize()} - the Jackson customizer that installs a
 * restrictive {@link BasicPolymorphicTypeValidator} so the promulgation VO polymorphism
 * ({@code @JsonTypeInfo(use=Id.CLASS)} on {@code BaseMessagePromulgationVo}) only accepts subtypes of that
 * base, rather than the default laissez-faire validator.
 *
 * <p>This pins the configuration decision that governs every promulgation-VO (de)serialization in the
 * application: that polymorphic types are constrained to the {@code BaseMessagePromulgationVo} hierarchy.
 *
 * <p><b>Port note.</b> Jackson 3 mappers are immutable, so the customizer now returns a Spring Boot 4
 * {@link JsonMapperBuilderCustomizer} that sets the validator directly on the
 * {@link tools.jackson.databind.json.JsonMapper.Builder} (no more {@code postConfigurer} on a mutable mapper).
 * The test drives the customizer the same way Spring does: apply it to a fresh builder, build the mapper, and
 * read the installed validator off {@code mapper.deserializationConfig().getPolymorphicTypeValidator()}. The
 * behaviour under test (restrictive validator scoped to {@code BaseMessagePromulgationVo}) is framework-neutral
 * and would have to be re-established identically under any future framework.
 *
 * <p>We assert the installed validator's type rather than driving full polymorphic deserialization: the
 * customizer does not enable default typing, so the validator is only consulted for the
 * {@code @JsonTypeInfo}-annotated promulgation hierarchy, whose round-trip is already covered by the
 * promulgation VO tests.
 */
public class JacksonObjectMapperCustomizerTest {

    /** Builds the {@link ObjectMapper} exactly as Spring would: applying the customizer to a fresh builder. */
    private static ObjectMapper customizedMapper(JsonMapperBuilderCustomizer customizer) {
        JsonMapper.Builder builder = JsonMapper.builder();
        customizer.customize(builder);
        return builder.build();
    }

    @Test
    public void freshMapperDoesNotUseTheRestrictiveValidator() {
        // A builder-built mapper with no customizer applied must default to the laissez-faire validator -
        // establishing that the restrictive validator below is the customizer's doing.
        ObjectMapper built = JsonMapper.builder().build();
        PolymorphicTypeValidator beforeBuilt = built.deserializationConfig().getPolymorphicTypeValidator();
        assertFalse(beforeBuilt instanceof BasicPolymorphicTypeValidator,
                "a builder-built ObjectMapper without the customizer must not carry the restrictive validator");
    }

    @Test
    public void customizeInstallsTheRestrictiveBasicPolymorphicTypeValidator() {
        JsonMapperBuilderCustomizer customizer = new JacksonObjectMapperCustomizer().customize();

        // Baseline: a builder with no customizer applied carries the default (non-restrictive) validator.
        ObjectMapper baseline = JsonMapper.builder().build();
        PolymorphicTypeValidator before = baseline.deserializationConfig().getPolymorphicTypeValidator();

        ObjectMapper mapper = customizedMapper(customizer);
        PolymorphicTypeValidator after = mapper.deserializationConfig().getPolymorphicTypeValidator();

        // The customizer has a real, observable effect: it swaps in the restrictive validator...
        assertNotSame(before, after, "customize() must replace the default validator");
        // ...of the expected restrictive type (scoped, in production, to BaseMessagePromulgationVo).
        assertTrue(after instanceof BasicPolymorphicTypeValidator,
                "customize() must install a BasicPolymorphicTypeValidator; got: "
                        + (after == null ? "null" : after.getClass().getName()));
    }

    @Test
    public void customizeAppliesConsistentlyAcrossMappers() {
        JacksonObjectMapperCustomizer customizer = new JacksonObjectMapperCustomizer();

        // A fresh customizer per builder, mirroring how Spring applies the bean to each ObjectMapper it builds.
        ObjectMapper m1 = customizedMapper(customizer.customize());
        ObjectMapper m2 = customizedMapper(customizer.customize());

        assertTrue(m1.deserializationConfig().getPolymorphicTypeValidator() instanceof BasicPolymorphicTypeValidator);
        assertTrue(m2.deserializationConfig().getPolymorphicTypeValidator() instanceof BasicPolymorphicTypeValidator);
    }
}
