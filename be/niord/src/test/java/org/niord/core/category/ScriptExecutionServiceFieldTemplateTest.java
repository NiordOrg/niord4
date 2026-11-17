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
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterMessagePromulgationVo;
import org.niord.model.message.MessagePartVo;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for how {@link ScriptExecutionService} assigns field templates (F-X42): a field of the supported
 * property-path shape is assigned; any other field is skipped, and the rest of the templates still apply.
 * The field templates come from Freemarker output, which prints values the caller supplied.
 */
class ScriptExecutionServiceFieldTemplateTest {

    private final ScriptExecutionService service = new ScriptExecutionService();

    private static Map<String, Object> contextData(SystemMessageVo message, MessagePartVo part) {
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("message", message);
        contextData.put("part", part);
        contextData.put("params", new HashMap<>());
        return contextData;
    }

    private static MessagePartVo partWithDescs() {
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDesc("en");
        part.checkCreateDesc("da");
        return part;
    }

    @Test
    void aPropertyPathFieldIsAssignedAndAppended() {
        SystemMessageVo message = new SystemMessageVo();
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("twitter");
        TwitterMessagePromulgationVo twitter = new TwitterMessagePromulgationVo(type);
        twitter.setTweet("first");
        message.checkCreatePromulgations().add(twitter);
        MessagePartVo part = partWithDescs();

        service.applyFieldTemplates(FieldTemplateProcessor.parse(
                "<field-template field=\"part.getDesc('en').subject\" format=\"text\">Light unlit</field-template>"
                        + "<field-template field=\"message.promulgation('twitter').tweet\" update=\"append\">"
                        + "second</field-template>"),
                contextData(message, part));

        assertEquals("Light unlit", part.getDesc("en").getSubject());
        assertEquals("first\nsecond", twitter.getTweet());
    }

    @Test
    void aFieldThatIsNotAPropertyPathIsSkippedAndTheOthersStillApply() {
        MessagePartVo part = partWithDescs();

        service.applyFieldTemplates(FieldTemplateProcessor.parse(
                "<field-template field=\"part.getDesc('da').subject = 'set by the field'; part.getDesc('en').subject\">"
                        + "x</field-template>"
                        + "<field-template field=\"part.getDesc('en').details\" format=\"html\">"
                        + "Details</field-template>"),
                contextData(new SystemMessageVo(), part));

        assertNull(part.getDesc("da").getSubject(), "the statement in the field is never run");
        assertNull(part.getDesc("en").getSubject(), "the refused field is not assigned");
        assertEquals("Details", part.getDesc("en").getDetails(), "the next field template still applies");
    }
}
