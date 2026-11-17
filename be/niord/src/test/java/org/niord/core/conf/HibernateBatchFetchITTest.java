/*
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.niord.core.message.MessageSearchParams;
import org.niord.model.message.MessageVo;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.niord.web.MessageSearchRestService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every deployment loads lazy collections and proxies for up to 256 owners per query. Without batching a
 * message list loads each message's collections one query at a time; with it, a list walks every collection
 * once per batch, so the batch size sets the number of round trips to the database. The value comes from
 * niord-base's core defaults, so a deployable that sets nothing has it.
 */
class HibernateBatchFetchITTest extends ITTest {

    /**
     * The most statements one list of the published messages may take. The value object of a message walks
     * fifteen collections (the message's areas, categories, charts, texts, parts, references and attachments,
     * the parts' texts, dates and features, the texts of the attachments, references, areas and categories,
     * and the categories' editor fields); each takes one statement while the list holds at most 256
     * messages. Besides them come the query for the ids, the query for the messages and the batch loads of
     * the areas, series and users the messages name, by id. A batch size of 16 takes more than this for
     * the seed's own messages, and a query per message takes one more statement for every message listed.
     */
    private static final int MESSAGE_LIST_STATEMENT_CEILING = 25;

    /** Fewer messages than this, and the ceiling above would hold even with a query per message. */
    private static final int MESSAGE_LIST_MINIMUM = 15;

    @PersistenceContext
    EntityManager em;

    @Autowired
    MessageSearchRestService messageSearch;

    @Test
    void lazyLoadsAreBatchedTwoHundredFiftySixOwnersAtATime() {
        int batchSize = em.getEntityManagerFactory()
                .unwrap(SessionFactoryImplementor.class)
                .getSessionFactoryOptions()
                .getDefaultBatchFetchSize();

        assertEquals(256, batchSize, "the core defaults must give Hibernate a default batch fetch size of 256");
    }

    /**
     * The effect rather than the value: the administration app's list of the published messages (a page
     * of up to 200, as the app asks for it) takes a statement per collection, not one per message. The
     * statements are counted on the list's own session, so the indexers and other background work that
     * share the database do not reach the count.
     */
    @Test
    void theMessageListLoadsEachCollectionInOneStatement() {
        StatementCounter counter = new StatementCounter();
        int listed = Tx.requiringNew().call(() -> {
            em.unwrap(Session.class).addEventListeners(counter);
            PagedSearchResultVo<MessageVo> page = messageSearch.searchMessages(MessageSearchParams.instantiate(
                    null, Map.of(
                            "lang", new String[] { "en" },
                            "mainType", new String[] { "NW", "NM" },
                            "maxSize", new String[] { "200" })));
            return page.getData().size();
        });

        assertTrue(listed >= MESSAGE_LIST_MINIMUM, "the list held only " + listed + " published messages, "
                + "too few for the statement ceiling to tell batched loads from a query per message");
        assertTrue(counter.statements <= MESSAGE_LIST_STATEMENT_CEILING, "listing " + listed
                + " published messages took " + counter.statements + " statements, more than the "
                + MESSAGE_LIST_STATEMENT_CEILING + " of one per collection the list walks: an association the "
                + "message value object reads is loaded per message, or the batch fetch size has dropped");
    }

    /** Counts the statements a session prepares. */
    private static final class StatementCounter implements SessionEventListener {

        int statements;

        @Override
        public void jdbcPrepareStatementStart() {
            statements++;
        }
    }
}
