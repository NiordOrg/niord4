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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The failure text of the sweep: what was sent, as whom, what was expected, what came back, what the request logged.
 *
 * <pre>
 * GET /rest/sources/source/{id}   [SourceRestService.getSource, endpoints/sources.json]
 *   as sysadmin (NiordDomain: niord-nw), id=987654321 (unknown-id probe), request id sweep-sources-0412
 *   expected: 404 "No such source"   real: 500
 *   log of the request: ERROR ... NullPointerException: ...
 *   body: {"title":"Internal Server Error", ...}   (first 300 characters)
 *   If the new answer is intended, change the entry; if not, it is a fault (record it, do not fix it here).
 * </pre>
 */
public final class SweepFailure {

    static final String ADVICE =
            "If the new answer is intended, change the entry; if not, it is a fault (record it, do not fix it here).";

    private SweepFailure() {
    }

    /** The text of a mismatch of the answer */
    static String of(EndpointTable table, SweepResponse response, String expected, String extra, SweepLog log) {
        SweepRequest request = response.request();
        EndpointEntry entry = table.entryAt(request.verb(), request.pattern());
        StringBuilder text = new StringBuilder();
        text.append(request.keyLike());
        if (entry != null) {
            text.append("   [").append(entry.handler).append(", endpoints/").append(table.groupOf(entry))
                    .append(".json]");
        }
        text.append("\n  as ").append(response.identity());
        if (request.domainSet) {
            text.append(", NiordDomain of this request: ").append(request.domain == null ? "none" : request.domain);
        }
        List<String> values = new ArrayList<>();
        request.vars.forEach((name, value) -> values.add(name + "=" + value));
        request.query.forEach(q -> values.add("?" + q.getKey() + "=" + q.getValue()));
        if (!values.isEmpty()) {
            text.append(", ").append(String.join(", ", values));
        }
        if (request.note() != null) {
            text.append(" (").append(request.note()).append(')');
        }
        text.append(", request id ").append(response.requestId());
        String detail = response.detail();
        text.append("\n  expected: ").append(expected).append("   real: ").append(response.status())
                .append(detail == null ? "" : " \"" + detail + "\"");
        if (extra != null) {
            text.append("\n  ").append(extra);
        }
        List<SweepLog.Event> events = log == null ? List.of() : log.events().stream()
                .filter(e -> response.requestId().equals(e.requestId())
                        || (e.requestId() == null && e.onServletThread() && e.millis() >= response.sentMillis()
                        && e.millis() <= response.receivedMillis() + SweepEngine.TAIL_MILLIS))
                .toList();
        text.append("\n  log of the request: ").append(events.isEmpty() ? "nothing at WARN or above"
                : events.stream().map(SweepLog.Event::describe).collect(Collectors.joining("\n    ")));
        if (request.bodyPreview() != null) {
            text.append("\n  sent: ").append(request.bodyPreview());
        }
        text.append("\n  body: ").append(response.preview()).append("   (first 300 characters)");
        text.append("\n  ").append(ADVICE);
        return text.toString();
    }

    /** The text of a log event that breaks the rule of a request */
    static String ofLog(SweepResponse response, SweepLog.Event event) {
        return response.request().keyLike() + "\n  as " + response.identity() + ", request id "
                + response.requestId() + "\n  answered " + response.status()
                + ", but its log breaks the rule (no ERROR, no lazy-loading failure or stack overflow, no other "
                + "throwable the entry does not list in expectedLog):\n    " + event.describe() + "\n  " + ADVICE;
    }
}
