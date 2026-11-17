/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.promulgation.vo;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the polymorphic JSON round-trip contract for {@link BaseMessagePromulgationVo} subtypes.
 * <p>
 * Promulgation value objects are added through a plug-in mechanism, so the abstract super-class uses
 * {@code @JsonTypeInfo(use=Id.CLASS, property="@class")} rather than {@code @JsonSubTypes}. This means the
 * fully-qualified class name is stamped into the serialized JSON under the {@code "@class"} property, and
 * deserialization into the abstract base type relies on that marker to reconstruct the correct concrete
 * subtype. A future Spring port must preserve this exact serialization behaviour, so these tests assert
 * both the on-the-wire shape ({@code @class} + FQCN) and that the concrete type and its key field survive
 * a full serialize/deserialize cycle.
 */
public class MessagePromulgationVoJsonTest {

    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        // Mirror PromulgationTest: the polymorphic type validator must allow the promulgation base type
        // for @class-based deserialization to succeed.
        mapper = JsonMapper.builderWithJackson2Defaults()
                .polymorphicTypeValidator(BasicPolymorphicTypeValidator
                        .builder()
                        .allowIfBaseType(BaseMessagePromulgationVo.class)
                        .build())
                .build();
    }

    @Test
    public void navtexVoSerializesWithClassMarkerAndFullyQualifiedName() throws IOException {
        NavtexMessagePromulgationVo data = new NavtexMessagePromulgationVo();
        data.setText("NAV BODY");

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("@class"),
                "JSON must contain the @class type marker, got: " + json);
        assertTrue(json.contains(NavtexMessagePromulgationVo.class.getName()),
                "JSON must contain the fully-qualified NavtexMessagePromulgationVo class name, got: " + json);
    }

    @Test
    public void navtexVoDeserializesBackToConcreteSubtypeWithTextPreserved() throws IOException {
        NavtexMessagePromulgationVo data = new NavtexMessagePromulgationVo();
        data.setText("NAV BODY");

        String json = mapper.writeValueAsString(data);
        BaseMessagePromulgationVo result = mapper.readValue(json, BaseMessagePromulgationVo.class);

        assertEquals(NavtexMessagePromulgationVo.class, result.getClass(),
                "Deserializing into the abstract base must yield the Navtex concrete subtype");
        assertEquals("NAV BODY", ((NavtexMessagePromulgationVo) result).getText(),
                "The text field must survive the round-trip");
    }

    @Test
    public void safetyNetVoSerializesWithClassMarkerAndFullyQualifiedName() throws IOException {
        SafetyNetMessagePromulgationVo data = new SafetyNetMessagePromulgationVo();
        data.setText("SN BODY");
        data.setAreaName("A");

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("@class"),
                "JSON must contain the @class type marker, got: " + json);
        assertTrue(json.contains(SafetyNetMessagePromulgationVo.class.getName()),
                "JSON must contain the fully-qualified SafetyNetMessagePromulgationVo class name, got: " + json);
    }

    @Test
    public void safetyNetVoDeserializesBackToConcreteSubtypeWithFieldsPreserved() throws IOException {
        SafetyNetMessagePromulgationVo data = new SafetyNetMessagePromulgationVo();
        data.setText("SN BODY");
        data.setAreaName("A");

        String json = mapper.writeValueAsString(data);
        BaseMessagePromulgationVo result = mapper.readValue(json, BaseMessagePromulgationVo.class);

        assertEquals(SafetyNetMessagePromulgationVo.class, result.getClass(),
                "Deserializing into the abstract base must yield the SafetyNet concrete subtype");
        SafetyNetMessagePromulgationVo sn = (SafetyNetMessagePromulgationVo) result;
        assertEquals("SN BODY", sn.getText(), "The text field must survive the round-trip");
        assertEquals("A", sn.getAreaName(), "The areaName field must survive the round-trip");
    }

    @Test
    public void twitterVoSerializesWithClassMarkerAndFullyQualifiedName() throws IOException {
        TwitterMessagePromulgationVo data = new TwitterMessagePromulgationVo();
        data.setTweet("TWEET");

        String json = mapper.writeValueAsString(data);

        assertTrue(json.contains("@class"),
                "JSON must contain the @class type marker, got: " + json);
        assertTrue(json.contains(TwitterMessagePromulgationVo.class.getName()),
                "JSON must contain the fully-qualified TwitterMessagePromulgationVo class name, got: " + json);
    }

    @Test
    public void twitterVoDeserializesBackToConcreteSubtypeWithTweetPreserved() throws IOException {
        TwitterMessagePromulgationVo data = new TwitterMessagePromulgationVo();
        data.setTweet("TWEET");

        String json = mapper.writeValueAsString(data);
        BaseMessagePromulgationVo result = mapper.readValue(json, BaseMessagePromulgationVo.class);

        assertEquals(TwitterMessagePromulgationVo.class, result.getClass(),
                "Deserializing into the abstract base must yield the Twitter concrete subtype");
        assertEquals("TWEET", ((TwitterMessagePromulgationVo) result).getTweet(),
                "The tweet field must survive the round-trip");
    }
}
