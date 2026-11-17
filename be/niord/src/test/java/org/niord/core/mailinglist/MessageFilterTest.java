/*
 * Copyright 2024 Danish Maritime Authority.
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
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.promulgation.NavtexMessagePromulgation;
import org.niord.core.promulgation.NavtexTransmitter;
import org.niord.core.promulgation.PromulgationType;
import org.niord.model.message.Type;

import javax.script.ScriptException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the observable behavior of {@link MessageFilter}, the Nashorn-backed message
 * filter evaluator used by mailing-list triggers.
 *
 * <p>{@code MessageFilter.getInstance(expr)} compiles the JavaScript function
 * {@code function matchesMessage(msg) { return <expr>; }} via the standalone Nashorn
 * engine. {@code matches(message)} invokes that function with the supplied
 * {@link Message} bound to {@code msg} and coerces the result to a boolean.
 *
 * <p>Key oracles pinned here:
 * <ul>
 *   <li>A <em>compile-time</em> error (syntactically invalid expression) surfaces as a
 *       checked {@link ScriptException} thrown from {@code getInstance}.</li>
 *   <li>A <em>runtime</em> error inside {@code matches} (e.g. calling a non-existent
 *       method) throws {@link IllegalArgumentException} naming the filter (MAILLIST2);
 *       reading a property of, or calling a function on, a value that is null (a null
 *       {@code msg}, a missing promulgation) yields {@code false}.</li>
 *   <li>The expression is free to ignore {@code msg} entirely, so a constant-{@code true}
 *       filter matches even a {@code null} message.</li>
 * </ul>
 *
 * <p>Note on boolean coercion: comparisons such as {@code .equals(...)} return a real
 * Java/JS boolean, so they are used (rather than truthy values) to keep the assertions
 * unambiguous about the cast to {@code Boolean} performed in {@code matches}.
 */
public class MessageFilterTest {

    @Test
    public void constantTrueMatchesAnyMessage() throws ScriptException {
        assertTrue(MessageFilter.getInstance("true").matches(new Message()), "Constant 'true' filter must match");
    }

    @Test
    public void constantFalseMatchesNoMessage() throws ScriptException {
        assertFalse(MessageFilter.getInstance("false").matches(new Message()), "Constant 'false' filter must not match");
    }

    @Test
    public void compoundBooleanExpressionEvaluatesToTrue() throws ScriptException {
        assertTrue(MessageFilter.getInstance("1 == 1 && 2 == 2").matches(new Message()),
                "'1 == 1 && 2 == 2' must evaluate true");
    }

    @Test
    public void expressionReadingMessageGetterMatchesWhenValueEquals() throws ScriptException {
        Message m = new Message();
        m.setUid("abc");
        assertTrue(MessageFilter.getInstance("msg.getUid().equals('abc')").matches(m),
                "Filter on msg.getUid().equals('abc') must match a message with uid 'abc'");
    }

    @Test
    public void expressionReadingMessageGetterDoesNotMatchWhenValueDiffers() throws ScriptException {
        Message m = new Message();
        m.setUid("xyz");
        assertFalse(MessageFilter.getInstance("msg.getUid().equals('abc')").matches(m),
                "Filter on msg.getUid().equals('abc') must not match a message with uid 'xyz'");
    }

    @Test
    public void invalidJavaScriptFunctionKeywordThrowsScriptException() {
        try {
            MessageFilter.getInstance("function (((");
            fail("A syntactically invalid expression must throw ScriptException at compile time");
        } catch (ScriptException e) {
            // expected: the wrapped function fails to compile in the Nashorn engine
        }
    }

    @Test
    public void invalidJavaScriptTokensThrowScriptException() {
        try {
            MessageFilter.getInstance("a b c )(");
            fail("Garbage tokens must throw ScriptException at compile time");
        } catch (ScriptException e) {
            // expected
        }
    }

    @Test
    public void runtimeErrorInsideMatchesThrowsNamingTheFilter() throws ScriptException {
        // Compiles fine, but invoking msg.noSuchMethod() throws at runtime: an error, not "no match" (MAILLIST2)
        MessageFilter filter = MessageFilter.getInstance("msg.noSuchMethod()");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> filter.matches(new Message()),
                "A runtime error inside the filter must surface, not read as no match");
        assertTrue(e.getMessage().contains("msg.noSuchMethod()"), "the error names the filter: " + e.getMessage());
    }

    @Test
    public void nullMessageMatchesWhenExpressionIgnoresMsg() throws ScriptException {
        assertTrue(MessageFilter.getInstance("true").matches(null),
                "A constant filter that ignores msg must match even a null message");
    }

    @Test
    public void nullMessageDoesNotMatchWhenExpressionDereferencesMsg() throws ScriptException {
        // msg is null -> msg.getUid() calls a function on null inside the JS function: no match, as a null
        // promulgation is
        assertFalse(MessageFilter.getInstance("msg.getUid().equals('x')").matches(null),
                "Dereferencing a null msg must yield false");
    }

    @Test
    public void triggerFilterShapesInUseStillEvaluateOnTheSandboxedEngine() throws ScriptException {
        // The shapes the stored mailing-list triggers use: an enum compared with its name, a bean path
        // through a related entity, and a call on a promulgation the message looks up by type.
        Message m = new Message();
        m.setType(Type.COASTAL_WARNING);
        MessageSeries series = new MessageSeries();
        series.setSeriesId("dma-nw");
        m.setMessageSeries(series);
        PromulgationType navtexType = new PromulgationType();
        navtexType.setTypeId("navtex");
        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setType(navtexType);
        navtex.setPromulgate(true);
        navtex.getTransmitters().add(new NavtexTransmitter("Baltico"));
        m.getPromulgations().add(navtex);

        assertTrue(MessageFilter.getInstance("msg.type == 'COASTAL_WARNING'").matches(m));
        assertFalse(MessageFilter.getInstance("msg.type == 'LOCAL_WARNING'").matches(m));

        assertTrue(MessageFilter.getInstance("msg.messageSeries.seriesId == 'dma-nw'").matches(m));
        assertFalse(MessageFilter.getInstance("msg.messageSeries.seriesId == 'dma-nm'").matches(m));

        assertTrue(MessageFilter.getInstance(
                "msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Baltico')")
                .matches(m));
        assertFalse(MessageFilter.getInstance(
                "msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Rogaland')")
                .matches(m));
    }

    @Test
    public void filterRunsWithoutJavaAndWithoutProcessOrLoadFunctions() throws ScriptException {
        // The filter is text an administrator enters on a trigger, so it runs on the sandboxed engine.
        // Checked with typeof: calling quit() itself would end the test JVM if the sandbox regressed.
        assertTrue(MessageFilter.getInstance(
                        "typeof Java === 'undefined' && typeof Packages === 'undefined'"
                                + " && typeof quit === 'undefined' && typeof exit === 'undefined'"
                                + " && typeof load === 'undefined' && typeof loadWithNewGlobal === 'undefined'")
                        .matches(new Message()),
                "none of these globals exists in the filter's engine");
    }
}
