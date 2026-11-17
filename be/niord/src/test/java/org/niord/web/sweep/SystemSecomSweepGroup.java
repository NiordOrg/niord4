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

import jakarta.persistence.EntityManager;
import org.niord.core.message.Message;
import org.niord.model.message.Status;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.SecomOutboxService;
import org.niord.secom.service.SecomSubscriptionService;
import org.niord.test.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.niord.web.sweep.SystemSweepFixture.map;

/**
 * The SECOM administration interface of the system group ({@code endpoints/system.json}, the entries of context
 * {@code secom}), run by {@code org.niord.secom.SecomEndpointSweepITTest} in the SECOM context (the module is off in
 * the plain one). That context shares the database, and {@code SecomITTest} wipes the SECOM tables before each test, so
 * the fixture is made inside the class: the test class publishes the message ({@code publishNw}, removed with its
 * dataset and outbox entries by {@code SecomITTest} after the class), and this group finds its dataset (made by the
 * status listener, or by the service when the listener is late), subscribes a node of its own and queues an upload of
 * the dataset to it. The tear-down removes the subscription, the node and their outbox entries and reads them gone.
 *
 * <p>Every handler is {@code @RolesAllowed(SYSADMIN)} with no check in code: one anonymous cell each, the sweep as
 * sysadmin. The writes on the series configuration work on {@code dma-nm} (the wipe exposes only {@code dma-nw}, the
 * series the published message is in). Reconcile backfills a dataset for every published message of the shared
 * database: global, refused cells only. The key-store reload reloads what every SECOM test uses, to the same: run once,
 * last, and its answer must name the certificate it had. Renew reaches a partner (the MCP Identity Registry): exempt,
 * its refused cells. Republish and retry only enqueue or reset an outbox row, which the dispatcher (off in this
 * context) would send: swept, each read back through the outbox and its rows removed.
 */
class SystemSecomSweepGroup implements SweepGroup {

    /** The published message's title, which the test class publishes it under */
    static final String MESSAGE = "sweep-system-secom-message";
    static final String DATASET = "sweep-system-secom-dataset";
    static final String NODE = "sweep-system-secom-node";
    static final String SUBSCRIPTION = "sweep-system-secom-subscription";
    static final String OUTBOX = "sweep-system-secom-outbox";

    static final String NODE_MRN = "urn:mrn:mcp:service:mcc:test:instance:" + NODE;
    static final int UNKNOWN = SystemSweepFixture.UNKNOWN;
    static final UUID UNKNOWN_UUID = UUID.fromString("00000000-0000-4000-8000-000987654321");

    @Override
    public String name() {
        return "system";
    }

    @Override
    public String context() {
        return "secom";
    }

    @Override
    public Set<String> rows() {
        return Set.of(MESSAGE, DATASET, NODE, SUBSCRIPTION, OUTBOX);
    }

    @Override
    public Set<String> tables() {
        return Set.of("S124Dataset", "S124DatasetVersion", "S124SeriesConfig", "SecomNode", "SecomOutboxEntry",
                "SecomSubscription");
    }

    @Override
    public Map<String, String> leftByDesign() {
        return Map.of("the published message " + MESSAGE + ", its dataset and outbox entries, until the class ends",
                "SecomITTest.removePublishedMessages removes them after the sweep's tests, with its other "
                        + "publishNw messages");
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void setUp(SweepContext ctx) throws Exception {
        EntityManager em = SystemSweepFixture.em(ctx);
        S124DatasetService datasets = ctx.bean(S124DatasetService.class);
        String uid = Tx.requiringNew().call(() -> em.createQuery("select m from Message m join m.descs d "
                        + "where d.title = :title and m.status = :status", Message.class)
                .setParameter("title", MESSAGE).setParameter("status", Status.PUBLISHED)
                .getResultList().stream().map(Message::getUid).findFirst().orElse(null));
        if (uid == null) {
            throw new IllegalStateException("No published message " + MESSAGE + ": SecomEndpointSweepITTest "
                    + "publishes it before the sweep");
        }
        ctx.putRow(MESSAGE, uid);

        // The status listener makes the dataset; when it is late, the service makes it
        S124Dataset dataset = null;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (dataset == null && System.currentTimeMillis() < deadline) {
            dataset = Tx.requiringNew().call(() -> datasets.findByMessageUid(uid));
            if (dataset == null) {
                Thread.sleep(50);
            }
        }
        if (dataset == null) {
            Tx.requiringNew().run(() -> datasets.onMessageStatusChanged(uid));
            dataset = Tx.requiringNew().call(() -> datasets.findByMessageUid(uid));
        }
        UUID datasetUuid = dataset.getUuid();
        ctx.putRow(DATASET, datasetUuid);

        SecomSubscriptionService subscriptions = ctx.bean(SecomSubscriptionService.class);
        SecomOutboxService outbox = ctx.bean(SecomOutboxService.class);
        UUID subscription = Tx.requiringNew().call(() -> {
            SecomSubscription found = subscriptions.getAll(SecomSubscription.class).stream()
                    .filter(s -> NODE_MRN.equals(s.getNode().getMrn())).findFirst().orElse(null);
            if (found != null) {
                return found.getUuid();
            }
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            return subscriptions.subscribe(NODE_MRN, template).getUuid();
        });
        ctx.putRow(SUBSCRIPTION, subscription);
        ctx.putRow(NODE, NODE_MRN);

        Integer entry = Tx.requiringNew().call(() -> {
            SecomOutboxEntry upload = outbox.search(null, 1000).stream()
                    .filter(o -> NODE_MRN.equals(o.getNode().getMrn())
                            && o.getOperation() == SecomOutboxEntry.Operation.UPLOAD)
                    .findFirst().orElse(null);
            if (upload != null) {
                return upload.getId();
            }
            return outbox.enqueueUpload(subscriptions.findByUuid(subscription),
                    datasets.findByUuid(datasetUuid)).getId();
        });
        ctx.putRow(OUTBOX, entry);
    }

    @Override
    public List<String> tearDown(SweepContext ctx) {
        List<String> left = new ArrayList<>();
        EntityManager em = SystemSweepFixture.em(ctx);
        Tx.requiringNew().run(() -> {
            em.createQuery("delete from SecomOutboxEntry o where o.node.mrn = :mrn").setParameter("mrn", NODE_MRN)
                    .executeUpdate();
            em.createQuery("delete from SecomSubscription s where s.node.mrn = :mrn").setParameter("mrn", NODE_MRN)
                    .executeUpdate();
            em.createQuery("delete from SecomNode n where n.mrn = :mrn").setParameter("mrn", NODE_MRN)
                    .executeUpdate();
        });
        SweepResponse nodes = ctx.send("sysadmin", SweepRequest.get("/rest/secom/nodes").note("read after the tear-down"));
        if (nodes.text().contains(NODE_MRN)) {
            left.add(NODE + " (" + NODE_MRN + ", still listed)");
        }
        SweepResponse outbox = ctx.send("sysadmin", SweepRequest.get("/rest/secom/outbox").note("read after the tear-down"));
        if (outbox.text().contains(NODE_MRN)) {
            left.add(OUTBOX + " (entries of " + NODE_MRN + ", still listed)");
        }
        return left;
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    @Override
    public void cases(SweepCases cases) {
        cases.add("status", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/secom/status"))
                .sweep((ctx, as) -> {
                    SweepResponse status = ctx.ok(as, SweepRequest.get("/rest/secom/status"));
                    if (status.is2xx()) {
                        ctx.checkEquals(true, status.json().get("enabled"), "enabled", status);
                        ctx.check(status.json().getList("scopedSeries").contains("dma-nw"),
                                "the scoped series hold dma-nw", status);
                        ctx.check(status.json().getLong("datasets") >= 1 && status.json().getLong("subscriptions") >= 1,
                                "the counts hold the fixture's dataset and subscription", status);
                    }
                }));

        cases.add("seriesConfigs", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/secom/series-config"))
                .sweep((ctx, as) -> {
                    SweepResponse configs = ctx.ok(as, SweepRequest.get("/rest/secom/series-config"));
                    if (configs.is2xx()) {
                        ctx.checkEquals(true, configs.json().get("find { it.seriesId == 'dma-nw' }.exposeOverSecom"),
                                "dma-nw is exposed", configs);
                    }
                }));

        cases.add("upsertSeriesConfig", SweepCase
                .of((ctx, row) -> seriesConfig("dma-nm", false, List.of()))
                .sweep((ctx, as) -> {
                    try {
                        SweepResponse answer = ctx.ok(as, seriesConfig("dma-nm", false, List.of("EN")));
                        if (answer.is2xx()) {
                            readSeriesConfigBack(ctx, as, false, List.of("en"));
                        }
                        ctx.ok(as, seriesConfig("dma-nm", true, List.of()));
                        readSeriesConfigBack(ctx, as, true, List.of());
                    } finally {
                        removeSeriesConfigAndReadGone(ctx, as);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), seriesConfig("dma-nm", true, List.of("xx"))
                        .note("a language outside the model languages"), Cell.status(400, "xx"))));

        cases.add("deleteSeriesConfig", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/secom/series-config/{seriesId}").var("seriesId", "dma-nm"))
                .sweep((ctx, as) -> {
                    ctx.ok(as, seriesConfig("dma-nm", true, List.of("da")));
                    SweepResponse removed = ctx.ok(as, SweepRequest.delete("/rest/secom/series-config/{seriesId}")
                            .var("seriesId", "dma-nm"));
                    if (removed.is2xx()) {
                        ctx.checkEquals("dma-nm", removed.json().getString("seriesId"), "the removed series", removed);
                        ctx.checkEquals(List.of("da"), removed.json().getList("languages"),
                                "the removed configuration's languages", removed);
                    }
                    removeSeriesConfigAndReadGone(ctx, as);
                }));

        cases.add("nodes", SweepCase.of((ctx, row) -> SweepRequest.get("/rest/secom/nodes")));

        cases.add("subscriptions", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/secom/subscriptions"))
                .sweep((ctx, as) -> {
                    SweepResponse subscriptions = ctx.ok(as, SweepRequest.get("/rest/secom/subscriptions"));
                    if (subscriptions.is2xx()) {
                        ctx.checkEquals(NODE_MRN, subscriptions.json().getString("find { it.uuid == '"
                                + ctx.row(SUBSCRIPTION) + "' }.nodeMrn"), "the fixture subscription's node", subscriptions);
                    }
                }));

        cases.add("deleteSubscription", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/secom/subscriptions/{uuid}").var("uuid", UNKNOWN_UUID))
                .sweep((ctx, as) -> {
                    SecomSubscriptionService subscriptions = ctx.bean(SecomSubscriptionService.class);
                    UUID uuid = Tx.requiringNew().call(() -> {
                        SecomSubscription template = new SecomSubscription();
                        template.setDataProductType("S124");
                        return subscriptions.subscribe(NODE_MRN, template).getUuid();
                    });
                    try {
                        SweepResponse removed = ctx.ok(as, SweepRequest.delete("/rest/secom/subscriptions/{uuid}")
                                .var("uuid", uuid));
                        if (removed.is2xx()) {
                            ctx.checkEquals(uuid.toString(), removed.json().getString("uuid"), "the removed subscription",
                                    removed);
                        }
                    } finally {
                        if (Tx.requiringNew().call(() -> subscriptions.findByUuid(uuid)) != null) {
                            Tx.requiringNew().run(() -> subscriptions.adminRemove(uuid));
                        }
                        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/subscriptions")
                                .note("read after the delete"));
                        ctx.check(!read.text().contains(uuid.toString()), "the subscription " + uuid + " is gone", read);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/secom/subscriptions/{uuid}")
                        .var("uuid", UNKNOWN_UUID).note("unknown-id probe"),
                        Cell.status(400, "Unknown subscription " + UNKNOWN_UUID))));

        cases.add("datasets", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/secom/datasets"))
                .sweep((ctx, as) -> {
                    SweepResponse datasets = ctx.ok(as, SweepRequest.get("/rest/secom/datasets"));
                    if (datasets.is2xx()) {
                        ctx.checkEquals(ctx.row(MESSAGE), datasets.json().getString("find { it.uuid == '"
                                + ctx.row(DATASET) + "' }.messageUid"), "the fixture dataset's message", datasets);
                    }
                }));

        cases.add("dataset", SweepCase
                .of((ctx, row) -> dataset(ctx.row(DATASET)))
                .sweep((ctx, as) -> {
                    SweepResponse dataset = ctx.ok(as, dataset(ctx.row(DATASET)));
                    if (dataset.is2xx()) {
                        ctx.checkEquals(ctx.row(MESSAGE), dataset.json().getString("messageUid"), "the dataset's message",
                                dataset);
                        ctx.check(dataset.json().getInt("gmlSize") > 0, "the dataset has a published GML", dataset);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), dataset(UNKNOWN_UUID).note("unknown-id probe"),
                            Cell.status(400, "Unknown dataset " + UNKNOWN_UUID));
                    ctx.call(as.name(), dataset("abc").note("malformed-id probe"),
                            Cell.status(400, "Failed to convert 'uuid' with value: 'abc'"));
                }));

        cases.add("datasetGml", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/secom/datasets/{uuid}/gml").var("uuid", ctx.row(DATASET)))
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/secom/datasets/{uuid}/gml")
                        .var("uuid", UNKNOWN_UUID).note("unknown-id probe"),
                        Cell.status(400, "Unknown dataset " + UNKNOWN_UUID))));

        // The outbox dispatcher is off in the SECOM context (SecomITTest): a republish and a retry only enqueue or
        // reset a row, which nothing sends; each is read back and its rows removed
        cases.add("republishDataset", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/secom/datasets/{uuid}/republish").var("uuid", UNKNOWN_UUID))
                .sweep((ctx, as) -> {
                    List<Integer> before = uploadsToTheNode(ctx, as);
                    List<Integer> added = new ArrayList<>();
                    try {
                        SweepResponse answer = ctx.ok(as, SweepRequest.post("/rest/secom/datasets/{uuid}/republish")
                                .var("uuid", ctx.row(DATASET)));
                        if (answer.is2xx()) {
                            ctx.checkEquals("1", answer.text().trim(),
                                    "the subscriptions the dataset was enqueued to (the fixture's)", answer);
                            uploadsToTheNode(ctx, as).stream().filter(i -> !before.contains(i)).forEach(added::add);
                            ctx.checkEquals(1, added.size(), "the new upload entries for " + NODE_MRN, answer);
                        }
                    } finally {
                        removeOutboxEntriesAndReadGone(ctx, as, added);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/secom/datasets/{uuid}/republish")
                        .var("uuid", UNKNOWN_UUID).note("unknown-id probe"),
                        Cell.status(400, "Unknown dataset " + UNKNOWN_UUID))));

        cases.add("reconcile", SweepCase.of((ctx, row) -> SweepRequest.post("/rest/secom/datasets/reconcile")));

        cases.add("outbox", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/secom/outbox"))
                .sweep((ctx, as) -> {
                    SweepResponse outbox = ctx.ok(as, SweepRequest.get("/rest/secom/outbox"));
                    if (outbox.is2xx()) {
                        ctx.checkEquals("UPLOAD", outbox.json().getString("find { it.id == " + ctx.row(OUTBOX)
                                + " }.operation"), "the fixture entry's operation", outbox);
                    }
                })
                .variants((ctx, as) -> {
                    SweepResponse pending = ctx.ok(as, SweepRequest.get("/rest/secom/outbox").query("status", "PENDING")
                            .query("limit", 1000));
                    ctx.check(pending.json().getList("status").stream().allMatch("PENDING"::equals),
                            "status=PENDING lists only pending entries", pending);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/secom/outbox").query("status", "XX")
                        .note("unknown status"), Cell.status(400, "Failed to convert 'status'"))));

        cases.add("cancelOutboxEntry", SweepCase
                .of((ctx, row) -> SweepRequest.delete("/rest/secom/outbox/{id}").var("id", UNKNOWN))
                .sweep((ctx, as) -> {
                    SecomOutboxService outbox = ctx.bean(SecomOutboxService.class);
                    SecomSubscriptionService subscriptions = ctx.bean(SecomSubscriptionService.class);
                    S124DatasetService datasets = ctx.bean(S124DatasetService.class);
                    Integer id = Tx.requiringNew().call(() -> outbox.enqueueUpload(
                            subscriptions.findByUuid((UUID) ctx.row(SUBSCRIPTION)),
                            datasets.findByUuid((UUID) ctx.row(DATASET))).getId());
                    try {
                        SweepResponse answer = ctx.ok(as, SweepRequest.delete("/rest/secom/outbox/{id}").var("id", id));
                        if (answer.is2xx()) {
                            ctx.checkEquals(String.valueOf(id), answer.text().trim(), "the cancelled entry's id", answer);
                            SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/outbox").query("limit", 1000));
                            ctx.checkEquals("CANCELLED", read.json().getString("find { it.id == " + id + " }.status"),
                                    "the cancelled entry's status", read);
                        }
                    } finally {
                        EntityManager em = SystemSweepFixture.em(ctx);
                        Tx.requiringNew().run(() -> em.createQuery("delete from SecomOutboxEntry o where o.id = :id")
                                .setParameter("id", id).executeUpdate());
                        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/outbox").query("limit", 1000)
                                .note("read after the removal"));
                        ctx.check(read.json().get("find { it.id == " + id + " }") == null,
                                "the outbox entry " + id + " is gone", read);
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.delete("/rest/secom/outbox/{id}")
                        .var("id", UNKNOWN).note("unknown-id probe"), Cell.status(400, "Unknown outbox entry " + UNKNOWN))));

        cases.add("retryOutboxEntry", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/secom/outbox/{id}/retry").var("id", UNKNOWN))
                .sweep((ctx, as) -> {
                    // An entry of its own, FAILED after its attempts: the retry makes it PENDING again
                    // with a fresh schedule (a cancelled or delivered entry is refused, 409)
                    SecomOutboxService outbox = ctx.bean(SecomOutboxService.class);
                    SecomSubscriptionService subscriptions = ctx.bean(SecomSubscriptionService.class);
                    S124DatasetService datasets = ctx.bean(S124DatasetService.class);
                    Integer id = Tx.requiringNew().call(() -> outbox.enqueueUpload(
                            subscriptions.findByUuid((UUID) ctx.row(SUBSCRIPTION)),
                            datasets.findByUuid((UUID) ctx.row(DATASET))).getId());
                    try {
                        EntityManager em = SystemSweepFixture.em(ctx);
                        Tx.requiringNew().run(() -> em.createQuery("update SecomOutboxEntry o set o.status = :status, "
                                        + "o.attempts = 8, o.lastError = 'peer answered 500' where o.id = :id")
                                .setParameter("status", SecomOutboxEntry.Status.FAILED).setParameter("id", id)
                                .executeUpdate());
                        SweepResponse answer = ctx.ok(as, SweepRequest.post("/rest/secom/outbox/{id}/retry").var("id", id));
                        if (answer.is2xx()) {
                            ctx.checkEquals(String.valueOf(id), answer.text().trim(), "the retried entry's id", answer);
                            SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/outbox").query("limit", 1000));
                            ctx.checkEquals("PENDING", read.json().getString("find { it.id == " + id + " }.status"),
                                    "the retried entry's status (it was FAILED)", read);
                            ctx.checkEquals("0", read.json().getString("find { it.id == " + id + " }.attempts"),
                                    "the retried entry's attempts (a fresh schedule)", read);
                        }
                    } finally {
                        removeOutboxEntriesAndReadGone(ctx, as, List.of(id));
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.post("/rest/secom/outbox/{id}/retry")
                        .var("id", UNKNOWN).note("unknown-id probe"), Cell.status(400, "Unknown outbox entry " + UNKNOWN))));

        cases.add("renewCertificates", SweepCase.of((ctx, row) -> SweepRequest.post("/rest/secom/security/renew")));

        cases.add("reloadSecurity", SweepCase
                .of((ctx, row) -> SweepRequest.post("/rest/secom/security/reload"))
                .sweep((ctx, as) -> {
                    SweepResponse reload = ctx.ok(as, SweepRequest.post("/rest/secom/security/reload"));
                    if (reload.is2xx()) {
                        // The same key stores: the service certificate is the one it had
                        ctx.checkEquals(reload.json().getString("previousFingerprint"),
                                reload.json().getString("serviceCertificateFingerprint"),
                                "the service certificate after the reload (its fingerprint)", reload);
                    }
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** The ids of the outbox's upload entries for the fixture's node */
    private static List<Integer> uploadsToTheNode(SweepContext ctx, SweepIdentity as) {
        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/outbox").query("limit", 1000));
        List<Integer> ids = read.json().getList("findAll { it.nodeMrn == '" + NODE_MRN
                + "' && it.operation == 'UPLOAD' }.id", Integer.class);
        return ids == null ? List.of() : ids;
    }

    /** Removes the outbox entries (no endpoint removes one) and reads them gone */
    private static void removeOutboxEntriesAndReadGone(SweepContext ctx, SweepIdentity as, List<Integer> ids) {
        if (ids.isEmpty()) {
            return;
        }
        EntityManager em = SystemSweepFixture.em(ctx);
        Tx.requiringNew().run(() -> em.createQuery("delete from SecomOutboxEntry o where o.id in :ids")
                .setParameter("ids", ids).executeUpdate());
        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/outbox").query("limit", 1000)
                .note("read after the removal"));
        for (Integer id : ids) {
            ctx.check(read.json().get("find { it.id == " + id + " }") == null, "the outbox entry " + id + " is gone",
                    read);
        }
    }

    private static SweepRequest dataset(Object uuid) {
        return SweepRequest.get("/rest/secom/datasets/{uuid}").var("uuid", uuid);
    }

    private static SweepRequest seriesConfig(String seriesId, boolean expose, List<String> languages) {
        return SweepRequest.put("/rest/secom/series-config/{seriesId}").var("seriesId", seriesId)
                .json(map("seriesId", seriesId, "exposeOverSecom", expose, "languages", languages));
    }

    private static void readSeriesConfigBack(SweepContext ctx, SweepIdentity as, boolean expose,
                                             List<String> languages) {
        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/series-config"));
        if (!read.is2xx()) {
            return;
        }
        ctx.checkEquals(expose, read.json().get("find { it.seriesId == 'dma-nm' }.exposeOverSecom"),
                "dma-nm's exposeOverSecom", read);
        ctx.checkEquals(languages, read.json().getList("find { it.seriesId == 'dma-nm' }.languages"),
                "dma-nm's languages", read);
    }

    private static void removeSeriesConfigAndReadGone(SweepContext ctx, SweepIdentity as) {
        SweepResponse read = ctx.ok(as, SweepRequest.get("/rest/secom/series-config").note("before the clean-up"));
        if (read.json().get("find { it.seriesId == 'dma-nm' }") != null) {
            ctx.ok(as, SweepRequest.delete("/rest/secom/series-config/{seriesId}").var("seriesId", "dma-nm"));
        }
        SweepResponse gone = ctx.ok(as, SweepRequest.get("/rest/secom/series-config").note("read after the delete"));
        ctx.check(gone.json().get("find { it.seriesId == 'dma-nm' }") == null, "dma-nm's configuration is gone", gone);
    }
}
