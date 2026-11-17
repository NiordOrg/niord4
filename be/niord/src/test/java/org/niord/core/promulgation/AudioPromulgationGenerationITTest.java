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
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.AudioMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.DataFilter;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AudioPromulgationService#generateMessagePromulgation} and
 * {@link AudioPromulgationService#messagePromulgationGenerated} — the audio
 * (radio-broadcast) text generation that was previously recorded in docs/untestable-code.md as the
 * remaining promulgation-generation gap (its Navtex and SafetyNet siblings are covered by
 * {@code NavtexPromulgationGenerationITTest} / {@code SafetyNetPromulgationGenerationITTest}, but Audio
 * had no generation test).
 *
 * <p>The audio path ({@code html2audio}) differs from NAVTEX: it strips HTML to plain text, verbalises
 * positions via the {@code template} dictionary + a {@link org.niord.core.util.PositionAssembler}, and
 * expands the abbreviation "{@code pos.}" to "{@code position}" — but it does <em>not</em> uppercase the
 * text. The tests build an audio {@link PromulgationType} in-memory (mirroring the Navtex generation IT,
 * which does the same) and drive the real service, so the dictionary/app collaborators are wired.
 *
 * <p>Framework-coupled — see docs/framework-coupled-tests.md.
 */
class AudioPromulgationGenerationITTest extends ITTest {

    private static final String AUDIO = "audio";

    @Autowired
    AudioPromulgationService audioPromulgationService;

    /** Builds an 'audio' promulgation type (English language) with the given requirement. */
    private PromulgationType audioType(Requirement requirement) {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(AUDIO);
        typeVo.setServiceId(AUDIO);
        typeVo.setName("Audio");
        typeVo.setLanguage("en");
        typeVo.setActive(Boolean.TRUE);
        typeVo.setRequirement(requirement);
        return new PromulgationType(typeVo);
    }

    /** Builds a SystemMessageVo with a single English DETAILS part carrying the given HTML details. */
    private SystemMessageVo messageWithEnDetails(String html) {
        SystemMessageVo message = new SystemMessageVo();
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang("en");
        desc.setDetails(html);
        part.setDescs(new ArrayList<>(List.of(desc)));
        message.setParts(new ArrayList<>(List.of(part)));
        return message;
    }

    // ------------------------------------------------------------------------------------------- //
    // generateMessagePromulgation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateMessagePromulgation_stripsHtmlExpandsPosAbbreviationAndKeepsCase() throws Exception {
        // Whitespace around "pos." is required for the abbreviation-expansion regex to fire.
        String html = "<p>Buoy near pos. seven has been removed from the area</p>";
        SystemMessageVo message = messageWithEnDetails(html);

        AudioMessagePromulgationVo audio = (AudioMessagePromulgationVo)
                audioPromulgationService.generateMessagePromulgation(message, audioType(Requirement.DEFAULT));

        assertTrue(audio.isPromulgate(),
                "a non-blank matching-language part must enable promulgation");
        String text = audio.getText();
        assertNotNull(text, "generated audio text must not be null");

        // "pos." (surrounded by whitespace) is expanded to "position".
        assertTrue(text.contains("position seven"),
                "the 'pos.' abbreviation must be expanded to 'position'; got: " + text);
        assertFalse(text.contains("pos."),
                "no 'pos.' abbreviation must remain; got: " + text);
        // HTML is stripped to plain text.
        assertFalse(text.contains("<p>") || text.contains("</p>"),
                "HTML markup must be stripped from the audio text; got: " + text);
        // Case is preserved (audio, unlike NAVTEX, does not uppercase) - the leading "Buoy" survives.
        assertTrue(text.contains("Buoy near position seven"),
                "audio text must preserve case and content (not uppercase like NAVTEX); got: " + text);
    }

    @Test
    @Transactional
    void generateMessagePromulgation_noMatchingLanguagePart_mandatoryPromulgatesWithEmptyText()
            throws Exception {
        // The only description is Danish; the audio type language is English -> no matching content.
        SystemMessageVo message = new SystemMessageVo();
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo da = new MessagePartDescVo();
        da.setLang("da");
        da.setDetails("<p>Sejlere advares</p>");
        part.setDescs(new ArrayList<>(List.of(da)));
        message.setParts(new ArrayList<>(List.of(part)));

        AudioMessagePromulgationVo audio = (AudioMessagePromulgationVo)
                audioPromulgationService.generateMessagePromulgation(message, audioType(Requirement.MANDATORY));

        assertTrue(audio.isPromulgate(),
                "MANDATORY type must promulgate even with no matching-language content");
        assertTrue(audio.getText() == null || audio.getText().isEmpty(),
                "with no matching-language part the audio text must be empty; got: " + audio.getText());
    }

    @Test
    @Transactional
    void generateMessagePromulgation_noContent_optionalDoesNotPromulgate() throws Exception {
        SystemMessageVo message = new SystemMessageVo();
        message.setParts(new ArrayList<>());

        AudioMessagePromulgationVo audio = (AudioMessagePromulgationVo)
                audioPromulgationService.generateMessagePromulgation(message, audioType(Requirement.OPTIONAL));

        assertFalse(audio.isPromulgate(),
                "OPTIONAL type must NOT promulgate when there is no matching-language content");
        assertTrue(audio.getText() == null || audio.getText().isEmpty(),
                "OPTIONAL with no content must produce empty text");
    }

    // ------------------------------------------------------------------------------------------- //
    // messagePromulgationGenerated: after every template run and every Generate (L-241, PROM20)
    // ------------------------------------------------------------------------------------------- //

    /** Adds an Audio promulgation of the given type with the given tick and text to the message. */
    private AudioMessagePromulgationVo addAudio(SystemMessageVo message, PromulgationType type, boolean promulgate,
                                                String text) {
        AudioMessagePromulgationVo audio = new AudioMessagePromulgationVo(type.toVo(DataFilter.get()));
        audio.setPromulgate(promulgate);
        audio.setText(text);
        message.checkCreatePromulgations().add(audio);
        return audio;
    }

    /**
     * PROM20: a template run resets the Audio text and keeps the tick, and no base template writes an audio text, so
     * the editor got Audio ticked with no text, and the save, which keeps only a promulgation with a text, dropped it
     * without a word. A ticked Audio with a blank text now gets the text Generate makes from the details in the type's
     * language, tidied, so the save keeps the tick.
     */
    @Test
    @Transactional
    void messagePromulgationGenerated_tickedAudioWithoutText_getsTheTextOfTheDetails() throws Exception {
        PromulgationType type = audioType(Requirement.DEFAULT);
        SystemMessageVo message = messageWithEnDetails("<p>Buoy near pos. seven has been removed from the area</p>");
        AudioMessagePromulgationVo audio = addAudio(message, type, true, null);

        audioPromulgationService.messagePromulgationGenerated(message, type);

        assertTrue(audio.isPromulgate(), "the tick stays");
        assertEquals("Buoy near position seven has been removed from the area", audio.getText(),
                "the text Generate makes from the English details, tidied");
        assertTrue(audio.promulgationDataDefined(), "the save keeps a promulgation that holds data");
    }

    /** PROM20: a blank text (white space only, as an emptied text box sends it) counts as no text. */
    @Test
    @Transactional
    void messagePromulgationGenerated_tickedAudioWithABlankText_getsTheTextOfTheDetails() throws Exception {
        PromulgationType type = audioType(Requirement.DEFAULT);
        SystemMessageVo message = messageWithEnDetails("<p>First light.</p><p>Second light.</p>");
        AudioMessagePromulgationVo audio = addAudio(message, type, true, "  \n ");

        audioPromulgationService.messagePromulgationGenerated(message, type);

        assertEquals("First light.\nSecond light.", audio.getText(),
                "the details' text, with no blank line and no white space at the ends");
    }

    /** PROM20's right path: a text a template or the editor wrote is kept, and only tidied as before. */
    @Test
    @Transactional
    void messagePromulgationGenerated_tickedAudioWithAText_keepsItsText() throws Exception {
        PromulgationType type = audioType(Requirement.DEFAULT);
        SystemMessageVo message = messageWithEnDetails("<p>Text of the details</p>");
        AudioMessagePromulgationVo audio = addAudio(message, type, true, "\n Written by the template\n\n\nSecond line \n");

        audioPromulgationService.messagePromulgationGenerated(message, type);

        assertEquals("Written by the template\nSecond line", audio.getText());
    }

    /** PROM20's right path: an Audio that is not ticked gets no text; the editor did not ask for it. */
    @Test
    @Transactional
    void messagePromulgationGenerated_untickedAudio_getsNoText() throws Exception {
        PromulgationType type = audioType(Requirement.DEFAULT);
        SystemMessageVo message = messageWithEnDetails("<p>Text of the details</p>");
        AudioMessagePromulgationVo audio = addAudio(message, type, false, null);

        audioPromulgationService.messagePromulgationGenerated(message, type);

        assertFalse(audio.isPromulgate());
        assertNull(audio.getText());
    }

    /**
     * PROM20's limit (the old rule): with no details in the type's language there is nothing to make the text from, so
     * the ticked Audio keeps no text, and the save still drops it.
     */
    @Test
    @Transactional
    void messagePromulgationGenerated_tickedAudioWithoutDetailsInItsLanguage_staysWithoutText() throws Exception {
        PromulgationType type = audioType(Requirement.DEFAULT);
        SystemMessageVo message = new SystemMessageVo();
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo da = new MessagePartDescVo();
        da.setLang("da");
        da.setDetails("<p>Sejlere advares</p>");
        part.setDescs(new ArrayList<>(List.of(da)));
        message.setParts(new ArrayList<>(List.of(part)));
        AudioMessagePromulgationVo audio = addAudio(message, type, true, null);

        audioPromulgationService.messagePromulgationGenerated(message, type);

        assertTrue(audio.isPromulgate());
        assertTrue(audio.getText() == null || audio.getText().isBlank(), "got: " + audio.getText());
        assertFalse(audio.promulgationDataDefined());
    }

    /**
     * PROM20's limit of the API: a description without a language (the editor never sends one, but PUT
     * /rest/templates/execute takes it, and a template writes into it as into every language) is skipped as one in
     * another language. Before, every template run with a ticked Audio threw a NullPointerException on it, and the
     * endpoint answered 500.
     */
    @Test
    @Transactional
    void messagePromulgationGenerated_tickedAudio_skipsADescriptionWithoutALanguage() throws Exception {
        PromulgationType type = audioType(Requirement.DEFAULT);
        SystemMessageVo message = messageWithEnDetails("<p>The light is unlit.</p>");
        MessagePartDescVo noLanguage = new MessagePartDescVo();
        noLanguage.setDetails("<p>A description without a language</p>");
        message.getParts().getFirst().getDescs().addFirst(noLanguage);
        AudioMessagePromulgationVo audio = addAudio(message, type, true, null);

        audioPromulgationService.messagePromulgationGenerated(message, type);

        assertTrue(audio.isPromulgate());
        assertEquals("The light is unlit.", audio.getText(), "only the English description makes the text");
    }
}
