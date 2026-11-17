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
package org.niord.secom;

import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.model.message.Status;
import org.niord.s124.S124Service;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.S124ExchangeSetService;
import org.niord.test.Tx;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * The dataset writers when the generator misbehaves for one message. The reconciliation sweep when a message's S-124
 * GML cannot be generated: the message gets no dataset, and every sweep counts it failed, never created; the case
 * also pins the REST body's {@code failed}, which the SECOM page shows. A sweep unit that throws, whether it creates a
 * dataset or cancels one, is counted failed too (the exchange-set build is a spy that throws for one message). And a
 * writer held in the generator while the
 * warning is cancelled, which must not make the cancelled dataset active again, whether it updates the dataset or
 * creates it. The generator is a spy that acts for
 * one message or one thread only, so every other message generates as usual; the spy gives this class an
 * application context of its own, which is why the cases are not in {@link S124DatasetPipelineITTest}. {@link DirtiesContext} closes that context after the class, so its server,
 * listeners and jobs do not stay alive on the shared database beside the SECOM tests' context.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class S124ReconcileFailureITTest extends SecomITTest {

    @MockitoSpyBean
    S124Service s124Service;

    @MockitoSpyBean
    S124ExchangeSetService exchangeSetService;

    @Test
    void aMessageWhoseGmlCannotBeGeneratedIsCountedFailedNotCreated() throws Exception {
        Message failing = Tx.requiringNew().call(() -> publishNw("secom-fail-" + uniq(), 55.5, 11.1));
        assertNotNull(awaitDatasetForMessage(failing.getUid()));
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(failing.getUid())));
        doThrow(new IllegalStateException("this message's GML cannot be generated"))
                .when(s124Service)
                .generate(argThat((Message m) -> m != null && failing.getUid().equals(m.getUid())), anyList(), any());

        S124DatasetService.ReconcileResult first = datasetService.reconcile();
        S124DatasetService.ReconcileResult second = datasetService.reconcile();

        assertEquals(1, first.failed(), "the message is counted failed, and no other fixture message: " + first);
        assertEquals(first.failed(), second.failed(), "every sweep tries it again and counts it again: " + second);
        assertEquals(0, second.created(), "a message that cannot be generated is never counted created: " + second);
        Tx.requiringNew().run(() -> assertNull(datasetService.findByMessageUid(failing.getUid()),
                "no dataset is written"));

        // The SECOM page's toast reads `failed` from this body
        given().auth().oauth2(accessToken())
                .post("/rest/secom/datasets/reconcile")
                .then()
                .statusCode(200)
                .body("failed", equalTo(1))
                .body("created", equalTo(0));
    }

    /**
     * An eligible message's unit that throws, here because its version cannot be published (a signing failure would
     * do the same), is re-run once and then skipped. SECOM5: it was left out of every count, so the SECOM page's toast
     * said "0 failed" and the scheduled run's log line stayed silent. It is counted failed now, in the sweep's result
     * and in the REST body the toast reads. The spy throws in the exchange-set build only for the message the same
     * thread generated last, so every other message publishes as usual.
     */
    @Test
    void anEligibleUnitThatThrowsTwiceIsCountedFailed() throws Exception {
        Message failing = Tx.requiringNew().call(() -> publishNw("secom-throw-" + uniq(), 55.5, 11.1));
        assertNotNull(awaitDatasetForMessage(failing.getUid()));
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(failing.getUid())));
        AtomicInteger publishAttempts = failPublishFor(failing.getUid());

        S124DatasetService.ReconcileResult result = datasetService.reconcile();

        assertEquals(2, publishAttempts.get(), "the unit is run, and re-run once: " + result);
        assertEquals(1, result.failed(), "the unit that threw twice is counted failed: " + result);
        Tx.requiringNew().run(() -> assertNull(datasetService.findByMessageUid(failing.getUid()),
                "no dataset is written"));

        // The SECOM page's toast reads `failed` from this body. The first sweep created the datasets of the other
        // fixture messages (the set-up deletes every dataset), so this one creates none: the failing unit is never
        // counted created
        given().auth().oauth2(accessToken())
                .post("/rest/secom/datasets/reconcile")
                .then()
                .statusCode(200)
                .body("failed", equalTo(1))
                .body("created", equalTo(0));
    }

    /**
     * A stale dataset's cancellation unit that throws, here because the final version that carries the cancellation
     * cannot be published, is logged and skipped, and its dataset stays ACTIVE until a later sweep. SECOM5: it was
     * counted nowhere. It is counted failed now. The warning is cancelled without a status event, so only the sweep's
     * cancellation pass reaches its dataset.
     */
    @Test
    void aCancellationUnitThatThrowsIsCountedFailed() throws Exception {
        String uid = Tx.requiringNew().call(() -> publishNw("secom-cancel-throw-" + uniq(), 55.5, 11.1)).getUid();
        assertNotNull(awaitDatasetForMessage(uid));
        Tx.requiringNew().run(() -> {
            Message message = em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                    .setParameter("uid", uid)
                    .getSingleResult();
            // Edited too, so the final version differs from the current one and has to be published
            message.getParts().getFirst().getDesc("en").setDetails("<p>edited " + uniq() + "</p>");
            message.setStatus(Status.CANCELLED);
            em.flush();
        });
        AtomicInteger publishAttempts = failPublishFor(uid);

        S124DatasetService.ReconcileResult result = datasetService.reconcile();

        assertEquals(1, publishAttempts.get(), "the cancellation pass tried to publish the final version: " + result);
        assertEquals(1, result.failed(), "the cancellation that threw is counted failed: " + result);
        assertEquals(0, result.cancelled(), "and not counted cancelled: " + result);
        Tx.requiringNew().run(() -> assertEquals(S124Dataset.Status.ACTIVE,
                datasetService.findByMessageUid(uid).getStatus(), "the failed cancellation wrote nothing"));
    }

    /**
     * Makes the exchange-set build throw whenever the thread's last generated S-124 was the given message's; every
     * other message generates and publishes as usual. {@return the number of builds refused}
     */
    private AtomicInteger failPublishFor(String uid) throws Exception {
        ThreadLocal<String> lastGenerated = new ThreadLocal<>();
        AtomicInteger refused = new AtomicInteger();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            lastGenerated.set(message == null ? null : message.getUid());
            return invocation.callRealMethod();
        }).when(s124Service).generate(any(Message.class), anyList(), any());
        doAnswer(invocation -> {
            if (uid.equals(lastGenerated.get())) {
                refused.incrementAndGet();
                throw new IllegalStateException("this message's version cannot be published");
            }
            return invocation.callRealMethod();
        }).when(exchangeSetService).publish(any(), anyList());
        return refused;
    }

    /**
     * Two writers of one dataset: one that read the dataset and the published warning just before the warning was
     * cancelled, and the cancel itself. The first is the sweep's unit for a warning edited since its last version (an
     * edit sends no status event, so the sweep is what publishes it), or the listener on a status event read late;
     * a CI run met the listener's case in SecomProtocolITTest. Before the dataset row was locked, the
     * stale writer wrote its ACTIVE row over the committed CANCELLED one, and the cancelled warning was served and
     * pushed as in force. Now the cancel waits for the stale writer and then cancels what it wrote. The spy holds the
     * stale writer in the generator, after its reads, until the cancel has committed or waits on a lock.
     */
    @Test
    void aWriterThatReadTheWarningBeforeItsCancelCannotMakeTheCancelledDatasetActive() throws Exception {
        String uid = Tx.requiringNew().call(() -> publishNw("secom-straddle-" + uniq(), 55.5, 11.1)).getUid();
        assertNotNull(awaitDatasetForMessage(uid));
        Tx.requiringNew().run(() -> em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                .setParameter("uid", uid)
                .getSingleResult()
                .getParts().getFirst().getDesc("en").setDetails("<p>edited " + uniq() + "</p>"));

        CountDownLatch staleWriterHasRead = new CountDownLatch(1);
        CountDownLatch releaseStaleWriter = new CountDownLatch(1);
        AtomicReference<Thread> staleWriterThread = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() == staleWriterThread.get()) {
                staleWriterHasRead.countDown();
                if (!releaseStaleWriter.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the stale writer was never released");
                }
            }
            return invocation.callRealMethod();
        }).when(s124Service).generate(any(Message.class), anyList(), any());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread staleWriter = new Thread(() -> run(failure, () -> datasetService.onMessageStatusChanged(uid)),
                "stale-dataset-writer");
        staleWriterThread.set(staleWriter);
        staleWriter.start();
        assertTrue(staleWriterHasRead.await(30, TimeUnit.SECONDS), "the stale writer reaches the generator");

        // The cancel and its dataset write in one transaction, as SecomProtocolITTest does
        Thread cancel = new Thread(() -> run(failure, () -> Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.CANCELLED);
            datasetService.onMessageStatusChanged(uid);
        })), "cancel");
        cancel.start();
        long deadline = System.currentTimeMillis() + 30_000;
        while (cancel.isAlive() && !aDatasetLockIsAwaited() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        releaseStaleWriter.countDown();
        staleWriter.join(30_000);
        cancel.join(30_000);
        assertFalse(staleWriter.isAlive() || cancel.isAlive(), "both writers finish");
        assertNull(failure.get(), () -> "a writer failed: " + failure.get());

        Tx.requiringNew().run(() -> {
            S124Dataset dataset = datasetService.findByMessageUid(uid);
            assertEquals(S124Dataset.Status.CANCELLED, dataset.getStatus(),
                    "the cancelled warning's dataset stays cancelled, whatever the stale writer read");
            assertNotNull(dataset.getCancelled());
            assertEquals(0, datasetService.search(null, null, null, null).stream()
                    .filter(d -> d.getUuid().equals(dataset.getUuid())).count(),
                    "a Get without a temporal extent does not serve it");
        });
    }

    /**
     * The same race on the create path, where there is no row to lock. A sweep unit finds no dataset for a published
     * warning (the listener missed its event, or an earlier generation failed) and generates one. The warning is
     * cancelled meanwhile, and the cancel's writer finds no row and does nothing. Before the message's dataset lock,
     * the unit then committed a new ACTIVE row, and the cancelled warning was served and pushed as in force until the
     * same sweep's cancellation pass reached it. Now the cancel's writer waits for the unit and cancels its row, and
     * the cancellation pass finds nothing active. The spy holds the unit in the generator, after its reads, until the
     * cancel has committed or waits on a lock; it also notes a second generation for the warning on the sweep's
     * thread, which is the cancellation pass finding the dataset ACTIVE. The cancel sets the status itself and runs
     * the writer in its own transaction, the listener's part: so no status event reaches this class's listener, and
     * the old code's outcome does not depend on when that listener runs.
     */
    @Test
    void aSweepThatCreatesTheDatasetWhileTheWarningIsCancelledCannotLeaveItActive() throws Exception {
        String uid = Tx.requiringNew().call(() -> publishNw("secom-create-straddle-" + uniq(), 55.5, 11.1)).getUid();
        assertNotNull(awaitDatasetForMessage(uid));
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(uid)));

        CountDownLatch sweepHasRead = new CountDownLatch(1);
        CountDownLatch releaseSweep = new CountDownLatch(1);
        AtomicReference<Thread> sweepThread = new AtomicReference<>();
        AtomicInteger sweepGenerations = new AtomicInteger();
        AtomicBoolean servedAfterCancel = new AtomicBoolean();
        doAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            if (Thread.currentThread() == sweepThread.get() && uid.equals(message.getUid())) {
                if (sweepGenerations.incrementAndGet() == 1) {
                    sweepHasRead.countDown();
                    if (!releaseSweep.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the sweep was never released");
                    }
                } else {
                    // The cancellation pass is cancelling the dataset: until it commits, it is served
                    servedAfterCancel.set(Tx.requiringNew().call(() -> datasetService.search(null, null, null, null)
                            .stream().anyMatch(d -> uid.equals(d.getMessage().getUid()))));
                }
            }
            return invocation.callRealMethod();
        }).when(s124Service).generate(any(Message.class), anyList(), any());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread sweep = new Thread(() -> run(failure, datasetService::reconcile), "sweep-creating-dataset");
        sweepThread.set(sweep);
        sweep.start();
        assertTrue(sweepHasRead.await(30, TimeUnit.SECONDS), "the sweep's unit reaches the generator");

        Thread cancel = new Thread(() -> run(failure, () -> Tx.requiringNew().run(() -> {
            em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                    .setParameter("uid", uid)
                    .getSingleResult()
                    .setStatus(Status.CANCELLED);
            em.flush();
            datasetService.onMessageStatusChanged(uid);
        })), "cancel");
        cancel.start();
        long deadline = System.currentTimeMillis() + 30_000;
        while (cancel.isAlive() && !aDatasetLockIsAwaited() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        releaseSweep.countDown();
        sweep.join(30_000);
        cancel.join(30_000);
        assertFalse(sweep.isAlive() || cancel.isAlive(), "the sweep and the cancel finish");
        assertNull(failure.get(), () -> "a writer failed: " + failure.get());

        assertEquals(1, sweepGenerations.get(), () -> "the sweep's cancellation pass found the cancelled warning's "
                + "dataset ACTIVE" + (servedAfterCancel.get() ? ", and a Get served it as in force until then" : ""));
        Tx.requiringNew().run(() -> {
            S124Dataset dataset = datasetService.findByMessageUid(uid);
            assertNotNull(dataset, "the sweep's unit created the dataset");
            assertEquals(S124Dataset.Status.CANCELLED, dataset.getStatus(),
                    "the cancel's writer cancelled the row the sweep created");
            assertNotNull(dataset.getCancelled());
        });
    }

    /** Runs the work, keeping the first failure for the test thread */
    private static void run(AtomicReference<Throwable> failure, Tx.ThrowingRunnable work) {
        try {
            work.run();
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        }
    }

    /** {@return whether a database session waits for a lock on an S-124 dataset row or a message's dataset lock} */
    private boolean aDatasetLockIsAwaited() {
        return Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                        "select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                                + "and (wait_event = 'advisory' or query ilike '%s124dataset%')")
                .getSingleResult()).longValue() > 0);
    }
}
