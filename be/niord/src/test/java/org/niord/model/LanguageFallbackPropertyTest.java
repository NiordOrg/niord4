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
package org.niord.model;

import org.junit.jupiter.api.Test;
import org.niord.model.message.AreaDescVo;
import org.niord.model.message.AreaVo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the language fallback ({@link ILocalizable#getDescs(DataFilter)}, {@link ILocalizable#getDesc},
 * {@link ILocalizable#sortDescs}): which language of a message, an area or a category a reader is shown. The public
 * API ({@code /rest/public/v1/...?lang=}), the public site, the mails and the reports all pick the language this
 * way. Entities with zero to four languages, generated from a fixed seed.
 */
public class LanguageFallbackPropertyTest {

    private static final long SEED = 20261115L;
    private static final String[] LANGS = { "da", "en", "kl", "fo" };

    private static AreaVo area(Random r) {
        AreaVo a = new AreaVo();
        List<String> langs = new ArrayList<>(List.of(LANGS));
        int n = r.nextInt(5);
        for (int i = 0; i < n; i++) {
            String lang = langs.remove(r.nextInt(langs.size()));
            a.checkCreateDesc(lang).setName("name-" + lang);
        }
        return a;
    }

    private static String langs(AreaVo a) {
        return a.getDescs() == null ? "none" : a.getDescs().stream().map(AreaDescVo::getLang).toList().toString();
    }

    @Test
    public void aLanguageTheEntityHas_isTheOneShown_andAnEntityWithTextsIsNeverShownWithout() {
        Random random = new Random(SEED);
        for (int i = 0; i < 5000; i++) {
            AreaVo a = area(random);
            String lang = random.nextInt(5) == 0 ? null : LANGS[random.nextInt(LANGS.length)];
            String defaultLang = random.nextBoolean() ? null : LANGS[random.nextInt(LANGS.length)];
            DataFilter filter = DataFilter.get().defaultLanguage(defaultLang).lang(lang);
            List<AreaDescVo> shown = a.getDescs(filter);
            String context = langs(a) + " lang=" + lang + " default=" + defaultLang + " -> "
                    + (shown == null ? "null" : shown.stream().map(AreaDescVo::getLang).toList());
            if (a.getDescs() == null || a.getDescs().isEmpty()) {
                assertTrue(shown == null || shown.isEmpty(), context);
                continue;
            }
            assertTrue(shown != null && !shown.isEmpty(), context);
            if (lang == null) {
                assertEquals(a.getDescs().size(), shown.size(), context);
            } else if (a.getDesc(lang) != null) {
                assertEquals(1, shown.size(), context);
                assertEquals(lang, shown.getFirst().getLang(), context);
            } else {
                // Not there: one text, the default language's when the entity has it, else the first
                assertEquals(1, shown.size(), context);
                if (defaultLang != null && a.getDesc(defaultLang) != null) {
                    assertEquals(defaultLang, shown.getFirst().getLang(), context);
                }
            }
        }
    }

    @Test
    public void theLanguageAsked_isMatchedIgnoringCase_likeGetDesc_documentsBug() {
        // PB-7. getDesc("EN") finds the English text (it compares ignoring case), but the filter the public API and
        // the REST services build from the request's lang parameter compares exactly (DataFilter.includeLang,
        // Objects.equals): "?lang=EN" finds no text and falls back to the default language or the first text. A
        // client that writes the code in capitals is shown the Danish text of a warning that has an English one.
        // Likely as the old system (the same DataFilter); pinned so a change shows.
        AreaVo a = new AreaVo();
        a.checkCreateDesc("da").setName("Skagerrak (da)");
        a.checkCreateDesc("en").setName("Skagerrak (en)");
        assertEquals("en", a.getDesc("EN").getLang());
        List<AreaDescVo> shown = a.getDescs(DataFilter.get().lang("EN"));
        // Expected: [en]; seen: [da], the first text
        assertEquals(List.of("da"), shown.stream().map(AreaDescVo::getLang).toList());

        Random random = new Random(SEED + 1);
        int differ = 0;
        for (int i = 0; i < 1000; i++) {
            AreaVo x = area(random);
            String lang = LANGS[random.nextInt(LANGS.length)];
            if (x.getDesc(lang) == null) {
                continue;
            }
            List<String> lower = x.getDescs(DataFilter.get().lang(lang)).stream().map(AreaDescVo::getLang).toList();
            List<String> upper = x.getDescs(DataFilter.get().lang(lang.toUpperCase(Locale.ROOT))).stream()
                    .map(AreaDescVo::getLang).toList();
            if (!lower.equals(upper)) {
                differ++;
            }
        }
        assertTrue(differ > 100, "upper and lower case give different texts in " + differ + " cases");
    }

    @Test
    public void sortingTheTexts_putsTheLanguageFirst_andKeepsTheOthersInOrder() {
        Random random = new Random(SEED + 2);
        for (int i = 0; i < 5000; i++) {
            AreaVo a = area(random);
            if (a.getDescs() == null) {
                continue;
            }
            List<String> before = a.getDescs().stream().map(AreaDescVo::getLang).toList();
            String lang = LANGS[random.nextInt(LANGS.length)];
            a.sortDescs(lang);
            List<String> after = a.getDescs().stream().map(AreaDescVo::getLang).toList();
            List<String> expected = new ArrayList<>();
            if (before.contains(lang)) {
                expected.add(lang);
            }
            before.stream().filter(l -> !l.equals(lang)).forEach(expected::add);
            assertEquals(expected, after, before + " sorted for " + lang);
        }
    }
}
