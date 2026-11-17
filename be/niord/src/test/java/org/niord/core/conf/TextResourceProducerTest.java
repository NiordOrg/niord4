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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link TextResourceProducer#loadResourceText(Class, String)} - the classpath text
 * loader behind the {@code @TextResource} CDI producer.
 *
 * <p>Covers the three observable behaviours of the static method: it reads a classpath resource line
 * by line (re-appending the platform line separator after every line), it caches the result so a
 * second load returns the very same {@link String} instance, and it raises
 * {@link IllegalArgumentException} for a missing resource. The {@code @Produces} method itself is the
 * thin CDI wrapper and is exercised indirectly elsewhere; the pure static method tested here carries
 * the logic and is framework-neutral (survives a Quarkus -&gt; Spring migration).</p>
 *
 * <p>The fixture {@code /textresource/sample.txt} (under {@code src/test/resources}) contains exactly
 * two lines, "Line one" and "Line two".</p>
 */
public class TextResourceProducerTest {

    private static final String RESOURCE = "/textresource/sample.txt";

    @Test
    public void loadsResourceTextAppendingLineSeparatorAfterEachLine() {
        String text = TextResourceProducer.loadResourceText(TextResourceProducerTest.class, RESOURCE);

        String sep = System.lineSeparator();
        // BufferedReader.readLine() strips the terminator; the method re-appends a separator per line.
        assertEquals("Line one" + sep + "Line two" + sep, text);
    }

    @Test
    public void cachesResultSoRepeatedCacheHitsReturnTheSameInstance() {
        // Warm the cache, then two cache HITS must return the identical cached String reference.
        // NB: since the TEXTRES1 fix the cache-miss call also returns the cached instance directly
        // (the load path stores result.toString() in a local, caches that and returns it); here we
        // pin the genuine caching property: hit == hit.
        TextResourceProducer.loadResourceText(TextResourceProducerTest.class, RESOURCE); // warm
        String hit1 = TextResourceProducer.loadResourceText(TextResourceProducerTest.class, RESOURCE);
        String hit2 = TextResourceProducer.loadResourceText(TextResourceProducerTest.class, RESOURCE);

        assertSame(hit1, hit2, "repeated cache hits must return the same cached instance");
    }

    @Test
    public void cacheMissReturnsTheSameInstanceThatIsCached_TEXTRES1() {
        // Use a distinct resource so this test owns the cache-miss for that key regardless of order.
        String miss = TextResourceProducer.loadResourceText(TextResourceProducerTest.class, "/textresource/sample2.txt");
        String hit = TextResourceProducer.loadResourceText(TextResourceProducerTest.class, "/textresource/sample2.txt");

        // Same content...
        assertEquals(miss, hit);
        // ...and the cache-miss call returns the very instance that was cached (the load path
        // stores result.toString() in a local, caches it and returns that same reference).
        assertSame(miss, hit, "cache-miss call must return the same instance that is cached");
    }

    @Test
    public void missingResourceRaisesIllegalArgumentException() {
        try {
            TextResourceProducer.loadResourceText(TextResourceProducerTest.class, "/textresource/does-not-exist.txt");
            fail("a missing resource must raise IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("Undefined resource"),
                    "message should name the undefined resource; got: " + e.getMessage());
        }
    }
}
