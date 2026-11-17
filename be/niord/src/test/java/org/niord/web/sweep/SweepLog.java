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
package org.niord.web.sweep;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import org.niord.core.util.MdcContext;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The server log of the sweep: one appender on the root logger for the whole run, which keeps every event a rule
 * may concern (WARN and above, and any event with a throwable) with its request id and thread.
 *
 * <p>An event belongs to a request when it carries the request's id ({@code X-Request-Id: sweep-<group>-<n>}, which
 * {@code RequestIdFilter} puts into the MDC), or when it carries no id, was written on a servlet thread, and falls
 * between the request's send and its answer (the error dispatch runs after the filter removed the id), or in the
 * 100 ms after the answer when no other request was under way or answered in between ({@code SweepEngine.belongsTo}).
 * An upload's or a settled write's events also include those of background work ({@code job-} ids and no id) in its
 * window. What belongs to no request is background: an ERROR there is listed by the sweep's last test.
 *
 * <p>The rule for a request's events: no ERROR, no event whose throwable chain or message holds a
 * {@code LazyInitializationException} or a {@code StackOverflowError}, and no other event with a throwable unless the
 * entry lists it in {@code expectedLog} or it is a refusal the exception advice logged at WARN.
 */
public final class SweepLog extends AppenderBase<ILoggingEvent> {

    /** The loggers that log a refused request with its exception at WARN, as a refusal */
    static final Set<String> REFUSAL_LOGGERS = Set.of(
            "org.niord.core.conf.RestExceptionStatusAdvice",
            "org.niord.web.publication.PublicationExceptionAdvice");

    /** The throwables no request may meet, whatever its entry expects */
    static final List<String> FATAL = List.of("LazyInitializationException", "StackOverflowError");

    /** One kept event */
    public record Event(long millis, String level, String logger, String thread, String requestId, String message,
                        List<String> throwables) {

        public boolean isError() {
            return "ERROR".equals(level);
        }

        public boolean onServletThread() {
            return thread != null && thread.startsWith("http-");
        }

        /**
         * The throwable chain holds a lazy-loading failure or a stack overflow, or the message names one (a handler
         * that logs {@code "... " + e} without the throwable, or Spring's own "Resolved [...]" line): never allowed
         */
        public boolean isFatal() {
            return names(FATAL, message) || throwables.stream().anyMatch(t -> names(FATAL, t));
        }

        private static boolean names(List<String> names, String text) {
            return text != null && names.stream().anyMatch(text::contains);
        }

        /** The text expectedLog entries and known faults are matched against */
        public String describe() {
            String line = level + " " + logger + " [" + thread + "] req=" + requestId + ": " + message;
            return throwables.isEmpty() ? line : line + " | " + String.join(" <- ", throwables);
        }
    }

    private final List<Event> events = new ArrayList<>();
    private volatile long lastBackgroundMillis;
    private Logger root;

    /** Attaches the appender to the root logger */
    public static SweepLog attach() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        SweepLog log = new SweepLog();
        log.setContext(context);
        log.setName("endpoint-sweep");
        log.start();
        log.root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        log.root.addAppender(log);
        return log;
    }

    /** Detaches the appender */
    public void detach() {
        if (root != null) {
            root.detachAppender(this);
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String thread = event.getThreadName();
        if (thread == null || !thread.startsWith("http-")) {
            lastBackgroundMillis = event.getTimeStamp();
        }
        if (event.getLevel().isGreaterOrEqual(Level.WARN) || event.getThrowableProxy() != null) {
            List<String> throwables = new ArrayList<>();
            for (IThrowableProxy t = event.getThrowableProxy(); t != null && throwables.size() < 10; t = t.getCause()) {
                throwables.add(t.getClassName() + ": " + t.getMessage());
            }
            String requestId = event.getMDCPropertyMap() == null ? null
                    : event.getMDCPropertyMap().get(MdcContext.REQUEST_ID);
            Event kept = new Event(event.getTimeStamp(), event.getLevel().toString(), event.getLoggerName(), thread,
                    requestId, event.getFormattedMessage(), throwables);
            synchronized (events) {
                events.add(kept);
            }
        }
    }

    /** Keeps an event as if it had been logged (the engine's own unit tests) */
    void add(Event event) {
        synchronized (events) {
            events.add(event);
        }
    }

    /** Every kept event so far */
    public List<Event> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /** The time of the last event of any level written outside a servlet thread */
    public long lastBackgroundMillis() {
        return lastBackgroundMillis;
    }

    /** Whether the event breaks the rule of a request whose entry lists the expected log lines */
    static boolean breaksTheRule(Event event, Collection<String> expectedLog) {
        if (event.isFatal()) {
            return true;
        }
        if (expectedLog.stream().anyMatch(expected -> event.describe().contains(expected))) {
            return false;
        }
        if (event.isError()) {
            return true;
        }
        return !event.throwables().isEmpty() && !REFUSAL_LOGGERS.contains(event.logger());
    }
}
