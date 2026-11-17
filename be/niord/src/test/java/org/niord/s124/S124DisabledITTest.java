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
package org.niord.s124;

import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageKind;
import org.niord.core.message.MessageService;
import org.niord.model.message.Status;
import org.niord.test.ITTest;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * With {@code niord.s124.enabled=false} the S-124 generator and its public API are absent: no
 * {@link S124Service} and no {@link S124RestService} bean, nothing mapped under {@code /rest/S-124}
 * (the XSDs included), and the producer code is neither read nor validated, which the malformed
 * {@code dk00} below proves by booting. {@code /rest/settings/web} tells the frontends so.
 * <p>
 * The automatic cancellation warning stays: decision 2 of {@code be/docs/s124-cancellation-plan.md}
 * is unchanged by the switch (the owner, 2026-11-01), so cancelling a published NW of an
 * auto-numbered series still mints one, in a context without the S-124 generator. The minted
 * warning takes the next current-year number of {@code dma-nw} inside the test's transaction,
 * so the number rolls back with the test (SERIES6); the test's transaction holds its row until
 * then, so nothing here sets that number in a transaction of its own.
 * <p>
 * Runs in its own context (the property source differs from the shared one).
 */
@TestPropertySource(properties = {"niord.s124.enabled=false", "niord.s124.producer-code=dk00"})
class S124DisabledITTest extends ITTest {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    MessageService messageService;


    @PersistenceContext
    EntityManager em;

    private final List<Integer> committedIds = new ArrayList<>();

    @AfterEach
    void removeCommittedMessages() {
        for (Integer id : committedIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory h where h.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        committedIds.clear();
    }

    @Test
    void noS124BeansExistWhenDisabled() {
        assertEquals(0, applicationContext.getBeanNamesForType(S124Service.class).length,
                "the S-124 generator must not exist with niord.s124.enabled=false");
        assertEquals(0, applicationContext.getBeanNamesForType(S124RestService.class).length,
                "the public S-124 API must not exist with niord.s124.enabled=false");
    }

    @Test
    void theS124EndpointsAreNotMappedWhenDisabled() {
        // A published, numbered NW the endpoint would serve as GML if it were mapped
        String uid = Tx.requiringNew().call(() -> {
            try {
                Message created = messageService.createMessage(MessageTestFactory.draftNw("S-124 off IT message"));
                created.setNumber(915);
                created.setShortId("NW-915-01");
                created.checkUpdateYear();
                created.setStatus(Status.PUBLISHED);
                em.flush();
                committedIds.add(created.getId());
                return created.getUid();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Response message = given().when().get("/rest/S-124/messages/" + uid).then().extract().response();
        assertEquals(404, message.statusCode(), "the GML endpoint must not be mapped");
        assertFalse(message.getBody().asString().contains("No message found"),
                "the 404 is the unmapped path's, not the endpoint's own not-found answer");

        Response xsd = given().when().get("/rest/S-124/xsds/124_2.0.0").then().extract().response();
        assertEquals(404, xsd.statusCode(), "the XSD endpoint must not be mapped");
        assertFalse(xsd.getBody().asString().contains("schema"), "no XSD is served");
    }

    @Test
    void theWebSettingsReportS124AndSecomAsOff() {
        given()
                .when().get("/rest/settings/web")
                .then().statusCode(200)
                .body("s124Enabled", is(false))
                .body("secomEnabled", is(false));
    }

    @Test
    @Transactional
    void cancellingAPublishedNwStillMintsTheCancellationWarning() throws Exception {
        Message created = messageService.createMessage(MessageTestFactory.draftNw("S-124 off cancel " + System.nanoTime()));
        messageService.updateStatus(created.getUid(), Status.VERIFIED);
        Message published = messageService.updateStatus(created.getUid(), Status.PUBLISHED);

        messageService.cancelMessage(published.getUid());

        List<Message> minted = em.createQuery("select m from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId = :uid", Message.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", published.getUid())
                .getResultList();
        assertEquals(1, minted.size(), "cancelling an NW of an auto-numbered series mints one cancellation warning");
        assertEquals(Status.PUBLISHED, minted.get(0).getStatus());
        assertNotNull(minted.get(0).getNumber(), "the cancellation warning is numbered from the series");
    }
}
