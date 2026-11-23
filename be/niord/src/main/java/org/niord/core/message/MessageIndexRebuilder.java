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
package org.niord.core.message;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.niord.core.settings.SettingChangedEvent;
import org.niord.core.util.MdcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Builds the message search indexes again when {@value #INCLUDE_DELETED} changes, so that the saved value holds for
 * every message, not only for the messages indexed after the save:
 * <ul>
 *     <li>the Lucene index on every node, since each node has its own ({@link MessageLuceneIndex#rebuildIndex}): on the
 *     node that saved the setting at once, on another node when its poll has found the save;</li>
 *     <li>the PostgreSQL table once, on the node that saved the setting, since the nodes share it
 *     ({@link MessagePostgresIndex#recreateIndex}). It is rebuilt in one transaction, so a search reads the table as it
 *     was until the rebuild commits.</li>
 * </ul>
 * While the Lucene index fills again, a search from it is marked as answered from part of the messages (the warning
 * the editors' list shows after a start), and {@code GET /rest/monitor/message-index} says which index is not yet
 * complete. The rebuilds run on a thread of this bean, one after another, each pass by the value it reads when it
 * starts; the request or the poll that changed the setting does not wait for them.
 */
@Component
public class MessageIndexRebuilder {

    /** The setting whose change builds the indexes again */
    static final String INCLUDE_DELETED = "messageIndexIncludeDeletedMessages";

    private static final Logger log = LoggerFactory.getLogger(MessageIndexRebuilder.class);

    @Inject
    MessageLuceneIndex luceneIndex;

    @Inject
    MessagePostgresIndex postgresIndex;

    /** One thread, which ends after a minute without work: a rebuild is rare */
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.MINUTES,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().name("message-index-rebuild").daemon(true).factory());

    /** Where a rebuild runs: the pool; a test runs it on its own thread */
    Executor executor = pool;

    MessageIndexRebuilder() {
        pool.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * Starts the rebuild when {@value #INCLUDE_DELETED} has changed. The fields of both indexes already hold the new
     * value.
     *
     * @param event the setting whose field a save, here or on another node, changed
     */
    @EventListener
    void settingChanged(SettingChangedEvent event) {
        if (!INCLUDE_DELETED.equals(event.key())) {
            return;
        }
        boolean sharedTable = event.savedHere();
        log.info("{} changed: building the message search indexes again ({})", INCLUDE_DELETED,
                sharedTable ? "Lucene and PostgreSQL" : "Lucene; the node that saved it builds the PostgreSQL table");
        try {
            executor.execute(MdcContext.wrapRunnable(() -> rebuild(sharedTable)));
        } catch (RejectedExecutionException e) {
            // The application is stopping; the next start reads the saved value
            log.warn("The message search indexes are not built again: " + e);
        }
    }

    /**
     * Builds the Lucene index again and, when asked, the PostgreSQL table. A failure of one is logged and does not
     * keep the other from being built.
     *
     * @param sharedTable whether to build the PostgreSQL table again too
     */
    void rebuild(boolean sharedTable) {
        try {
            luceneIndex.rebuildIndex();
        } catch (RuntimeException e) {
            log.error("Failed building the Lucene message index again: " + e.getMessage(), e);
        }
        if (sharedTable) {
            try {
                long t0 = System.currentTimeMillis();
                int indexed = postgresIndex.recreateIndex();
                log.info("Built the PostgreSQL message search table again: {} messages in {} ms", indexed,
                        System.currentTimeMillis() - t0);
            } catch (RuntimeException e) {
                log.error("Failed building the PostgreSQL message search table again: " + e.getMessage(), e);
            }
        }
    }
}
