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
package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code SafetyNetPromulgationService.generateMessagePromulgation}, the text that SafetyNET's
 * <em>Generate</em> and the message save make from a message's parts.
 *
 * <p>No container: the service is built with a mocked {@link NiordApp} (the type's language), and the text is made
 * from a plain {@link SystemMessageVo}; generating reads nothing stored. The rest of the service is covered by
 * {@code SafetyNetPromulgationServiceITTest} and {@code SafetyNetPromulgationGenerationITTest}.
 */
public class SafetyNetPromulgationServiceTest {

    private static SafetyNetPromulgationService service() {
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguage("en")).thenReturn("en");
        SafetyNetPromulgationService service = new SafetyNetPromulgationService(null);
        service.app = app;
        return service;
    }

    private static PromulgationType safetyNetType(Requirement requirement) {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId("safetynet");
        typeVo.setServiceId(SafetyNetMessagePromulgation.SERVICE_ID);
        typeVo.setName("SafetyNET");
        typeVo.setLanguage("en");
        typeVo.setActive(Boolean.TRUE);
        typeVo.setRequirement(requirement);
        return new PromulgationType(typeVo);
    }

    private static MessagePartVo detailsPart(String html) {
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        if (html != null) {
            MessagePartDescVo desc = new MessagePartDescVo();
            desc.setLang("en");
            desc.setDetails(html);
            part.setDescs(new ArrayList<>(List.of(desc)));
        }
        return part;
    }

    /**
     * The mappers give a part without any text no list of texts, and a message without parts no list of parts (a
     * part whose texts are all blanked is kept so). Generate reads either as giving no text: the other parts' texts
     * make the SafetyNET text, and a message without parts has none, as one with an empty list.
     */
    @Test
    public void aPartWithoutTexts_orNoParts_giveNoText() throws Exception {
        SystemMessageVo message = new SystemMessageVo();
        message.setParts(new ArrayList<>(List.of(detailsPart(null), detailsPart("<p>Buoy missing</p>"))));

        SafetyNetMessagePromulgationVo safetynet = (SafetyNetMessagePromulgationVo)
                service().generateMessagePromulgation(message, safetyNetType(Requirement.DEFAULT));
        assertTrue(safetynet.isPromulgate());
        assertEquals("BUOY MISSING", safetynet.getText());

        SafetyNetMessagePromulgationVo none = (SafetyNetMessagePromulgationVo)
                service().generateMessagePromulgation(new SystemMessageVo(), safetyNetType(Requirement.MANDATORY));
        assertTrue(none.isPromulgate(), "a MANDATORY type promulgates without content");
        assertNull(none.getText());
    }
}
