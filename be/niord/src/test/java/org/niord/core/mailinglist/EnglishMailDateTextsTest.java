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

package org.niord.core.mailinglist;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The base product's English mail texts write a date the English way. A mailing-list subject's {@code ${date-long}}
 * is formatted by {@code mail.date_format.long} (MailingListExecutionService), and its English value was the Danish
 * form "d. MMMM yyyy", which wrote "20. November 2026" into an English subject. FAILS on that value.
 */
class EnglishMailDateTextsTest {

    private static final String MAIL_TEXTS = "defaults/base/texts/en/mail.properties";

    /** 20 November 2026, 12:00 UTC */
    private static final Date DAY = new Date(1_795_176_000_000L);

    private static Properties englishMailTexts() throws IOException {
        Properties texts = new Properties();
        try (InputStream in = EnglishMailDateTextsTest.class.getClassLoader().getResourceAsStream(MAIL_TEXTS)) {
            assertNotNull(in, MAIL_TEXTS + " is on the class path");
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return texts;
    }

    private static String format(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ENGLISH);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(DAY);
    }

    @Test
    void theLongMailDateIsWrittenTheEnglishWay() throws IOException {
        Properties texts = englishMailTexts();
        assertEquals("d MMMM yyyy", texts.getProperty("mail.date_format.long"));
        assertEquals("20 November 2026", format(texts.getProperty("mail.date_format.long")));
        assertEquals("20-11-2026", format(texts.getProperty("mail.date_format.short")));
    }
}
