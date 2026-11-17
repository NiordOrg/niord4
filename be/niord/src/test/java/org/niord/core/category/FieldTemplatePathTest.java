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

package org.niord.core.category;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FieldTemplatePath}: the shape a field template's {@code field} attribute must have
 * before it is assigned as script (F-X42).
 */
class FieldTemplatePathTest {

    /** The names TemplateExecutionService and PromulgationManager bind */
    private static final Set<String> ROOTS =
            Set.of("languages", "message", "template", "part", "params", "promulgation", "promulgationType");

    @Test
    void everyFieldShapeTheTemplatesUseIsAccepted() {
        // Every distinct field attribute in the base, DK and GL templates, the test templates and the manual
        for (String field : List.of(
                "part.getDesc('en').details",
                "part.getDesc('da').subject",
                "part.getDesc('kl').details",
                "message.promulgation('navtex').text",
                "message.promulgation('audio').text",
                "message.promulgation('safetynet').text",
                "message.promulgation('twitter').tweet",
                "message.promulgation('twitter').promulgate",
                "message.part('DETAILS').getDesc('en').subject",
                "promulgation.text",
                "message.parts.get(0).hideSubject",
                "params.get('a', 'b').x")) {
            assertTrue(FieldTemplatePath.isValid(field, ROOTS), field);
        }
    }

    @Test
    void anythingButAPropertyPathIsRefused() {
        for (String field : List.of(
                "part.getDesc('en').subject = 'x'; part.getDesc('da').subject",
                "part.getDesc('en').subject; x",
                "part.getDesc(content).subject",
                "part.getDesc('en' + 'x').subject",
                "part.getDesc('e\\'n').subject",
                "part.getDesc(\"en\").subject",
                "part.getDesc('en').getSubject()",
                "part['getDesc']('en').subject",
                "part .getDesc('en').subject",
                "part.getDesc('en').",
                "part.getDesc('en'",
                "part.getDesc('en)",
                "part.getDesc(1234567890).subject",
                "part",
                "(part).subject",
                "")) {
            assertFalse(FieldTemplatePath.isValid(field, ROOTS), field);
        }
        assertFalse(FieldTemplatePath.isValid(null, ROOTS));
        assertFalse(FieldTemplatePath.isValid("part." + "x".repeat(FieldTemplatePath.MAX_LENGTH), ROOTS));
    }

    @Test
    void theRootMustBeABoundName() {
        for (String field : List.of("content.length", "this.x", "quit.x", "Java.type", "load.x", "globalThis.x")) {
            assertFalse(FieldTemplatePath.isValid(field, ROOTS), field);
        }
    }

    @Test
    void namesThatReachTheFunctionConstructorOrAJavaClassAreRefused() {
        for (String field : List.of(
                "message.id.constructor.x",
                "message.__proto__.x",
                "message.prototype.x",
                "message.getClass().x",
                "message.class.x",
                "message.status.getDeclaringClass().x",
                "message.id.call('x').x",
                "message.id.apply('x').x",
                "message.id.bind('x').x",
                "message.eval('x').x",
                "message.__noSuchProperty__")) {
            assertFalse(FieldTemplatePath.isValid(field, ROOTS), field);
        }
    }
}
