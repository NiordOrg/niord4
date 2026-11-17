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

package org.niord.core;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.core.category.FieldTemplateProcessor;
import org.niord.core.category.FieldTemplateProcessor.FieldTemplate;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterMessagePromulgationVo;
import org.niord.core.script.NashornEngines;
import org.openjdk.nashorn.api.scripting.JSObject;

import javax.script.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Various message template tests
 */
public class MessageTemplateTest {

    /**
     * Test that we can use Javascript to update message fields via nested property names,
     * such as "msg.promulgation('twitter').tweet"
     */
    @Test
    public void testJavaScriptFieldUpdate() throws Exception {

        // The field templates run on the sandboxed engine
        ScriptEngine jsEngine = NashornEngines.sandboxed();

        SystemMessageVo msg = new SystemMessageVo();

        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("twitter");

        TwitterMessagePromulgationVo twitter = new TwitterMessagePromulgationVo(type);
        twitter.setTweet("tweet start ");
        msg.checkCreatePromulgations().add(twitter);

        jsEngine.put("msg", msg);
        jsEngine.put("result", "tweet end");

        String script = "msg.promulgation('twitter').tweet += result;";
        jsEngine.eval(script);

        Assertions.assertEquals("tweet start tweet end", twitter.getTweet());
    }

    /**
     * Test that we can override the Nashorn load function.
     *
     * See https://bugs.openjdk.java.net/secure/attachment/54881/LoaderTest.java
     */
    @Test
    public void testJavaScriptLoadFunction() throws Exception {

        // Script resources, which load other resources through the niord: scheme, run on the trusted engine
        ScriptEngine jsEngine = NashornEngines.trusted();

        // Get original load function
        final JSObject origLoadFn = (JSObject)jsEngine.get("load");

        // Get global. Not really necessary as we could use null too, just for completeness.
        final JSObject thisRef = (JSObject)jsEngine.eval("(function() { return this; })()");

        // Define a new "load" function
        final Function<Object, Object> newLoadFn = (source) -> {
            if (source instanceof String) {
                final String strSource = (String)source;
                if (strSource.startsWith("myurlscheme:")) {
                    // handle "myurlscheme:"
                    return origLoadFn.call(thisRef, createCustomSource(strSource));
                }
            }
            // Fall back to original load for everything else
            return origLoadFn.call(thisRef, source);
        };

        // Replace built-in load with our load
        jsEngine.put("load", newLoadFn);
        // Load a dynamically generated script
        jsEngine.eval("load('myurlscheme:boo'); print(doubleUp('again'));");
    }

    /**
     * A custom source must define a "name" and a "script" property
     */
    public static Object createCustomSource(final String source) {
        final Map<String, String> sourceMap = new HashMap<>();
        sourceMap.put("name", source);
        sourceMap.put("script", "function doubleUp(txt) { return txt + ' and ' + txt; }");
        return sourceMap;
    }

    /**
     * Test parsing a text in the FieldTemplate format
     */
    @Test
    public void testFieldTemplates() throws Exception {

        String text = IOUtils.toString(this.getClass().getResourceAsStream("/field-templates.txt"),"UTF-8");
        List<FieldTemplate> fieldTemplates = FieldTemplateProcessor.parse(text);
        fieldTemplates.forEach(ft -> System.out.println("Field template: " + ft));

        Assertions.assertEquals(1, fieldTemplates.size());

        FieldTemplate ft = fieldTemplates.get(0);
        Assertions.assertEquals(ft.getContent(), "Fyr slukket");
        Assertions.assertEquals(ft.getField(), "part.getDesc('da').subject");
        Assertions.assertEquals(ft.getFormat(), "text");
    }

}
