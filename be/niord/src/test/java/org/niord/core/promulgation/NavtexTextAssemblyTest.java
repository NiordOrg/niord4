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
import org.niord.core.message.Message;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * The NAVTEX text Niord generates from a message's English details ({@link NavtexPromulgationService
 * #generateMessagePromulgation}): positions rewritten to the NAVTEX form, 40-character lines, upper case. The
 * transmitter lookup (a database query) and the language lookup are stubbed; the text assembly is the
 * service's own.
 * <p>
 * The property: every character of a generated text is in the NAVTEX alphabet (ITA2: letters, digits, space,
 * line break and {@code - ? : ( ) . , ' = / +}), and every line is at most 40 characters.
 */
public class NavtexTextAssemblyTest {

    private static final Pattern NAVTEX_ALPHABET = Pattern.compile("[A-Z0-9 \\n\\-?:().,'=/+]*");

    private static String generate(String html) throws Exception {
        NavtexPromulgationService service = spy(new NavtexPromulgationService(null, null));
        doReturn(new ArrayList<NavtexTransmitter>()).when(service).findTransmittersByAreas(anyString(), any(), anyBoolean());
        doReturn("en").when(service).getLanguage(any());

        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId("navtex");
        typeVo.setServiceId("navtex");
        typeVo.setName("NAVTEX");
        typeVo.setLanguage("en");
        typeVo.setActive(Boolean.TRUE);
        typeVo.setRequirement(Requirement.DEFAULT);

        SystemMessageVo message = new SystemMessageVo();
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang("en");
        desc.setDetails(html);
        part.setDescs(new ArrayList<>(List.of(desc)));
        message.setParts(new ArrayList<>(List.of(part)));

        NavtexMessagePromulgationVo navtex = (NavtexMessagePromulgationVo)
                service.generateMessagePromulgation(message, new PromulgationType(typeVo));
        return navtex.getText();
    }

    private static List<String> outsideTheAlphabet(String text) {
        List<String> chars = new ArrayList<>();
        text.codePoints()
                .mapToObj(Character::toString)
                .filter(c -> !NAVTEX_ALPHABET.matcher(c).matches())
                .distinct()
                .forEach(c -> chars.add(String.format("U+%04X", c.codePointAt(0))));
        return chars;
    }

    @Test
    public void positionsInTheTemplateForm_becomeNavtexPositions_inLinesOfForty() throws Exception {
        // The form the templates write ("57° 01.7'N - 003° 30.6'E", dec-1) is rewritten to "57-01.7N 003-30.6E".
        String text = generate("<p>Denmark. North Sea. Wreck of fishing vessel in pos. 57° 01.7'N - 003° 30.6'E "
                + "and 57° 02.0'N - 003° 31.0'E. Mariners are advised to keep clear.</p>");
        assertTrue(text.contains("57-01.7N 003-30.6E"), text);
        assertTrue(text.contains("57-02.0N 003-31.0E"), text);
        assertTrue(Arrays.stream(text.split("\n")).allMatch(l -> l.length() <= 40), text);
        assertEquals(List.of(), outsideTheAlphabet(text), text);
    }

    @Test
    public void everyLine_isAtMostFortyCharacters_whereverTheTextEnds() throws Exception {
        // NAVTEX lines are 40 characters (NavtexPromulgationService.NAVTEX_LINE_LENGTH). TextUtils.maxLineLength
        // breaks at the last space inside the window, so a line broken at a space is at most 39 long (U6, kept on
        // purpose); only a paragraph's last line, or a word with no space in it, reaches the limit. The text of the
        // first test ends nowhere near it, so a fold at 41 passed this class.
        // Here paragraphs of 30 to 50 characters, in words of 1 to 12 letters, and single words of 38 to 45
        // letters put the end of the text at every column around 40, generated from the details and typed into
        // the NAVTEX field alike.
        List<String> failures = new ArrayList<>();
        int texts = 0;
        List<String> paragraphs = new ArrayList<>();
        for (int length = 30; length <= 50; length++) {
            for (int word = 1; word <= 12; word++) {
                String p = ("Z".repeat(word) + " ").repeat(60).substring(0, length);
                paragraphs.add(p.endsWith(" ") ? p.substring(0, length - 1) + "Z" : p);
            }
        }
        for (int length = 38; length <= 45; length++) {
            paragraphs.add("Z".repeat(length));
        }
        for (String p : paragraphs) {
            for (String text : List.of(generate("<p>" + p + "</p>"), typed(p))) {
                texts++;
                for (String line : text.split("\n")) {
                    if (line.length() > 40) {
                        failures.add(p.length() + " characters in words of " + p.indexOf(' ') + ": a line of "
                                + line.length());
                    }
                }
            }
        }
        assertEquals(2 * (21 * 12 + 8), texts);
        assertEquals(List.of(), failures);
    }

    /** The text a duty officer typed into the NAVTEX field, as messagePromulgationGenerated leaves it */
    private static String typed(String text) throws Exception {
        NavtexPromulgationService service = new NavtexPromulgationService(null, null);
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId("navtex");
        SystemMessageVo message = new SystemMessageVo();
        NavtexMessagePromulgationVo navtex = new NavtexMessagePromulgationVo(typeVo);
        navtex.setText(text);
        message.checkCreatePromulgations().add(navtex);
        service.messagePromulgationGenerated(message, new PromulgationType(typeVo));
        return navtex.getText();
    }

    @Test
    public void charactersOutsideTheNavtexAlphabet_areMappedIntoIt() throws Exception {
        // PROM16. Every generated text is held to the NAVTEX alphabet (NavtexText.normalise): Danish letters are
        // transliterated (Ø to OE, Æ to AE, Å to AA), quotes and guillemets become ', and a position in a form the
        // NAVTEX rewrite does not know keeps its figures but loses its degree sign. In niord_test (read 2026-11-12)
        // 337 of the 1231 NAVTEX texts sent held characters outside the alphabet ('"' 119, 'Ø' 115, U+00A0 79,
        // 'Æ' 62, '»' and '«' 49, 'Å' 38, '*' 15, '°' 3).
        String spaced = generate("<p>Buoy in pos. 54° 45,7' N - 010° 29,1' E removed.</p>");
        assertTrue(spaced.contains("54 45.7' N") || spaced.contains("54 45,7' N"), spaced);
        assertEquals(List.of(), outsideTheAlphabet(spaced), spaced);

        String seconds = generate("<p>Buoy in pos. 55° 30' 15\"N - 012° 15' 30\"E removed.</p>");
        assertEquals(List.of(), outsideTheAlphabet(seconds), seconds);

        String danish = generate("<p>Work carried out by »HENRY P LADING« at Løgstør.</p>");
        assertEquals(List.of(), outsideTheAlphabet(danish), danish);
        assertTrue(danish.contains("'HENRY P LADING'"), danish);
        assertTrue(danish.contains("LOEGSTOER"), danish);

        String quoted = generate("<p>Survey off Skagen Ø by &quot;POSEIDON&quot; &amp; “NEPTUN”, Ærø and Århus.</p>");
        assertEquals(List.of(), outsideTheAlphabet(quoted), quoted);
        String flat = quoted.replace('\n', ' ');
        assertTrue(flat.contains("SKAGEN OE"), quoted);
        assertTrue(flat.contains("'POSEIDON' AND 'NEPTUN'"), quoted);
        assertTrue(flat.contains("AEROE AND AARHUS"), quoted);
    }

    @Test
    public void aMappedLetterThatLengthensALine_isWrappedAfterTheMapping() throws Exception {
        // Ø becomes OE, so the mapping runs before the text is split into lines of 40 characters.
        String text = generate("<p>" + "ØØØØ ".repeat(12) + "</p>");
        assertEquals(List.of(), outsideTheAlphabet(text), text);
        assertTrue(Arrays.stream(text.split("\n")).allMatch(l -> l.length() <= 40), text);
        assertTrue(text.contains("OEOEOEOE"), text);
    }

    @Test
    public void aLinkInsideNiord_leavesItsWordsAndNothingAfterThem() throws Exception {
        // A link to another warning has no address the plain text can show; it left " <>" after its words,
        // which the alphabet turned into a space before the full stop.
        String text = generate("<p>Replaces <a href=\"#/message/NW-001-26\">NW-001-26</a>.</p>");
        assertEquals("REPLACES NW-001-26.", text);
    }

    @Test
    public void aNoBreakSpaceInTheDetails_becomesASpace() throws Exception {
        // The ",&nbsp;" the geometry templates write between positions reaches the generated text as a space.
        String nbsp = generate("<p>Firing in pos. 57° 01.7'N - 003° 30.6'E,&nbsp;57° 02.0'N - 003° 31.0'E.</p>");
        assertEquals(List.of(), outsideTheAlphabet(nbsp), nbsp);
    }

    @Test
    public void aTextTypedIntoTheNavtexField_isNormalisedOnSave() throws Exception {
        // PROM16. A duty officer may write or paste the NAVTEX text itself. Generate (messagePromulgationGenerated),
        // every save (onCreateMessage, onUpdateMessage) and the publication (onUpdateMessageStatus) hold it to the
        // NAVTEX alphabet, as for a generated text: a pasted position loses its degree sign, a no-break space
        // becomes a space and a Danish letter is transliterated.
        String pasted = "Wreck in pos. 57° 01.7'N - 003° 30.6'E,\u00A0near Løgstør, by \"POSEIDON\".";

        // Generate
        NavtexPromulgationService service = new NavtexPromulgationService(null, null);
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId("navtex");
        SystemMessageVo vo = new SystemMessageVo();
        NavtexMessagePromulgationVo navtexVo = new NavtexMessagePromulgationVo(typeVo);
        navtexVo.setText(pasted);
        vo.checkCreatePromulgations().add(navtexVo);
        service.messagePromulgationGenerated(vo, new PromulgationType(typeVo));
        assertNormalised(navtexVo.getText(), true);

        // Save, for a draft and on every later save: mapped, not yet split into lines (the publication splits it)
        Message message = new Message();
        message.setStatus(Status.DRAFT);
        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setType(new PromulgationType(typeVo));
        navtex.setText(pasted);
        message.addPromulgation(navtex);
        service.onUpdateMessage(message, new PromulgationType(typeVo));
        assertNormalised(navtex.getText(), false);

        navtex.setText(pasted);
        service.onCreateMessage(message, new PromulgationType(typeVo));
        assertNormalised(navtex.getText(), false);

        // Publication: the text and the preamble once its tokens are expanded
        message.setStatus(Status.PUBLISHED);
        message.setShortId("NW-001-26");
        message.setPublishDateFrom(new Date(0L));
        navtex.setText(pasted);
        navtex.setPreamble("Søværnet «${short-id}» ${publish-date}");
        service.onUpdateMessageStatus(message, new PromulgationType(typeVo));
        assertNormalised(navtex.getText(), true);
        assertEquals(List.of(), outsideTheAlphabet(navtex.getPreamble()), navtex.getPreamble());
        assertTrue(navtex.getPreamble().startsWith("SOEVAERNET 'NW-001-26'"), navtex.getPreamble());
    }

    private static void assertNormalised(String text, boolean inLinesOfForty) {
        assertEquals(List.of(), outsideTheAlphabet(text), text);
        if (inLinesOfForty) {
            assertTrue(Arrays.stream(text.split("\n")).allMatch(l -> l.length() <= 40), text);
        }
        String flat = text.replace('\n', ' ');
        assertTrue(flat.contains("57 01.7'N - 003 30.6'E, NEAR LOEGSTOER, BY 'POSEIDON'."), text);
    }
}
