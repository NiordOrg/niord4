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
import jakarta.persistence.EntityManagerFactory;
import org.niord.core.aton.AtonNode;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.promulgation.TwitterPromulgationService;
import org.niord.core.promulgation.TwitterSettings;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.TwitterSettingsVo;
import org.niord.test.Tx;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fixture of {@link DeliverySweepGroup}: its rows, made by name (looked up first, so a second run in the same
 * context makes nothing twice) and removed by the tear-down, which reads each one gone.
 *
 * <p>Through the API, as the identity whose view the reads show: the three promulgation types with their transmitter
 * and areas, the mailing list with its report trigger, the contact, the parameter type, the Niord integration and the
 * firing period. Through the services and the entity manager, where no endpoint can make or remove the row: the
 * Twitter settings (their POST stores the row and answers 500, PROM7; no endpoint removes them, PROM8), the AtoN (AtoNs
 * are imported, never made one by one) and the scheduled mail (mails are queued by the product's own work).
 */
final class DeliverySweepFixture {

    /** The rows of the group */
    static final String NAVTEX = "sweep-delivery-navtex";
    static final String TRANSMITTER = "sweep-delivery-transmitter";
    static final String SAFETYNET = "sweep-delivery-safetynet";
    static final String AREA_CIRCULAR = "sweep-delivery-area-circular";
    static final String AREA_RECTANGULAR = "sweep-delivery-area-rectangular";
    static final String AREA_COASTAL = "sweep-delivery-area-coastal";
    static final String AREA_NAVAREA = "sweep-delivery-area-navarea";
    static final String TWITTER = "sweep-delivery-twitter";
    static final String LIST = "sweep-delivery-list";
    static final String REPORT = "sweep-delivery-report";
    static final String CONTACT = "sweep-delivery-contact";
    static final String PARAM_TYPE = "sweep-delivery-param-type";
    static final String INTEGRATION = "sweep-delivery-integration";
    static final String MAIL = "sweep-delivery-mail";
    static final String ATON = "sweep-delivery-aton";
    static final String FIRING_AREA = "sweep-delivery-firing-area";
    static final String FIRING_PERIOD = "sweep-delivery-firing-period";
    /** Two messages of the seed, under names of the group: the group makes no message (see the record) */
    static final String SEED_NW_MESSAGE = "sweep-delivery-seed-nw-message";
    static final String SEED_NM_MESSAGE = "sweep-delivery-seed-nm-message";
    /**
     * An inline template category that carries the id of no stored category: a non-sysadmin's execution looks the id
     * up (404) instead of running the inline definition. A name, not a row: no table holds it, nothing to remove.
     */
    static final String INLINE_CATEGORY_WITH_ID = "sweep-delivery-inline-category-with-id";

    static final Set<String> ROWS = Set.of(NAVTEX, TRANSMITTER, SAFETYNET, AREA_CIRCULAR, AREA_RECTANGULAR,
            AREA_COASTAL, AREA_NAVAREA, TWITTER, LIST, REPORT, CONTACT, PARAM_TYPE, INTEGRATION, MAIL, ATON,
            FIRING_AREA, FIRING_PERIOD, SEED_NW_MESSAGE, SEED_NM_MESSAGE, INLINE_CATEGORY_WITH_ID);

    static final List<String> AREAS = List.of(AREA_CIRCULAR, AREA_RECTANGULAR, AREA_COASTAL, AREA_NAVAREA);

    /** The tables the group's rows (and the rows its write cases make and remove) live in */
    static final Set<String> TABLES = Set.of("AtonNode", "AtonTag", "Contact", "FiringPeriod", "FiringSchedule",
            "MailingList", "MailingListDesc", "MailingListTrigger", "MailingListTriggerDesc",
            "MailingListTrigger_scriptResourcePaths", "MailingListTrigger_statusChanges", "MailingList_Contact",
            "MailingList_User", "MessageSeriesMapping", "NavtexTransmitter", "NavtexTransmitter_Area",
            "NiordIntegration", "ParamType", "PromulgationType", "PromulgationType_Domain", "SafetyNetArea",
            "ScheduledMail", "ScheduledMailRecipient", "TwitterSettings");

    /** An id no row of the group's kinds has */
    static final int UNKNOWN = 987654321;

    static final String DOMAIN = SweepIdentity.DOMAIN;
    static final String FIRING_DOMAIN = "niord-fa";
    static final String INTEGRATION_URL = "http://127.0.0.1:9/" + INTEGRATION;
    static final String CONTACT_EMAIL = CONTACT + "@example.org";
    static final String MAIL_RECIPIENT = MAIL + "@example.org";

    /** The AtoN's position: open sea, away from the boxes and tiles the AtoN ITs read */
    static final double ATON_LAT = 54.1234;
    static final double ATON_LON = 3.4567;
    static final int ATON_UID = 2_000_000_101;
    static final int ATON_TILE_ZOOM = 14;

    /** The fixture's firing period: two hours either side of noon (UTC) of the group's firing day */
    static final long FIRING_PERIOD_FROM = DeliverySweepGroup.FIRING_DAY - 2 * 3_600_000L;
    static final long FIRING_PERIOD_TO = DeliverySweepGroup.FIRING_DAY + 2 * 3_600_000L;

    // ------------------------------------------------------------------------------------------- //
    // Set-up
    // ------------------------------------------------------------------------------------------- //

    void setUp(SweepContext ctx) throws Exception {
        promulgationTypes(ctx);
        mailingList(ctx);
        contact(ctx);
        paramType(ctx);
        integration(ctx);
        firingPeriod(ctx);
        aton(ctx);
        scheduledMail(ctx);
        ctx.putRow(SEED_NW_MESSAGE, "NW-001-26");
        ctx.putRow(SEED_NM_MESSAGE, "NM-001-26");
        ctx.putRow(INLINE_CATEGORY_WITH_ID, UNKNOWN);
    }

    private void promulgationTypes(SweepContext ctx) throws Exception {
        for (String[] type : List.of(new String[]{NAVTEX, "navtex"}, new String[]{SAFETYNET, "safetynet"},
                new String[]{TWITTER, "twitter"})) {
            SweepResponse found = ctx.send("sysadmin", promulgationType(type[0]));
            if (found.status() == 404) {
                ctx.ok("sysadmin", SweepRequest.post("/rest/promulgations/promulgation-type")
                        .json(typeBody(type[0], type[1], type[0], 901, List.of(DOMAIN))));
            } else {
                ctx.expectOk(found);
            }
            ctx.putRow(type[0], type[0]);
        }

        SweepResponse transmitters = ctx.ok("sysadmin", transmitters(NAVTEX));
        if (!transmitters.json().getList("name").contains(TRANSMITTER)) {
            ctx.ok("sysadmin", SweepRequest.post("/rest/promulgation/navtex/transmitters/{typeId}/transmitter")
                    .var("typeId", NAVTEX).json(transmitterBody(NAVTEX, TRANSMITTER, true)));
        }
        ctx.putRow(TRANSMITTER, TRANSMITTER);

        List<Object> areas = ctx.ok("sysadmin", safetyNetAreas(SAFETYNET)).json().getList("name");
        for (String area : AREAS) {
            if (!areas.contains(area)) {
                ctx.ok("sysadmin", SweepRequest.post("/rest/promulgation/safetynet/areas/{typeId}/area")
                        .var("typeId", SAFETYNET).json(areaBody(SAFETYNET, area, AREAS.indexOf(area) + 1, "SWEEP")));
            }
            ctx.putRow(area, area);
        }

        // The Twitter settings through the service: the POST stores them and answers 500 (PROM7)
        TwitterPromulgationService twitter = ctx.bean(TwitterPromulgationService.class);
        if (twitter.getSettings(TWITTER) == null) {
            TwitterSettingsVo settings = twitterSettings(TWITTER, "sweep-delivery ${title}", "fixture");
            twitter.createSettings(new TwitterSettings(settings));
        }
    }

    private void mailingList(SweepContext ctx) {
        SweepResponse found = ctx.send("admin", mailingList(LIST));
        if (found.status() == 404) {
            ctx.ok("sysadmin", SweepRequest.post("/rest/mailing-lists/mailing-list").json(listBody(LIST,
                    List.of(reportTrigger()))));
        } else {
            ctx.expectOk(found);
        }
        ctx.putRow(LIST, LIST);
        // The trigger's id is not in the list's details: the report list names it
        SweepResponse reports = ctx.ok("user", SweepRequest.get("/rest/mailing-lists/reports").query("lang", "en"));
        Object trigger = reports.json().get("find { it.name == '" + LIST + "' }.id");
        if (trigger == null) {
            throw new IllegalStateException("The report trigger of " + LIST + " is not listed: " + reports.preview());
        }
        ctx.putRow(REPORT, trigger);
    }

    private void contact(SweepContext ctx) {
        Integer id = contactId(ctx, CONTACT_EMAIL);
        if (id == null) {
            id = ctx.ok("sysadmin", SweepRequest.post("/rest/contacts/contact").json(contactBody(CONTACT_EMAIL,
                    CONTACT))).json().getInt("id");
        }
        ctx.putRow(CONTACT, id);
    }

    private void paramType(SweepContext ctx) {
        Integer id = ctx.ok("anonymous", SweepRequest.get("/rest/templates/parameter-types")).json()
                .get("find { it.name == '" + PARAM_TYPE + "' }.id");
        if (id == null) {
            id = ctx.ok("sysadmin", SweepRequest.post("/rest/templates/parameter-type")
                    .json(DeliverySweepGroup.paramTypeBody(null, PARAM_TYPE))).json().getInt("id");
        }
        ctx.putRow(PARAM_TYPE, id);
    }

    private void integration(SweepContext ctx) {
        Integer id = ctx.ok("sysadmin", SweepRequest.get("/rest/niord-integrations/all")).json()
                .get("find { it.url == '" + INTEGRATION_URL + "' }.id");
        if (id == null) {
            id = ctx.ok("sysadmin", SweepRequest.post("/rest/niord-integrations/niord-integration")
                    .json(DeliverySweepGroup.integration(null, INTEGRATION_URL, List.of(Map.of(
                            "sourceSeriesId", "sweep-delivery-source", "targetSeriesId", "dma-nw")))))
                    .json().getInt("id");
        }
        ctx.putRow(INTEGRATION, id);
    }

    private void firingPeriod(SweepContext ctx) {
        SweepResponse day = ctx.ok("anonymous", DeliverySweepGroup.firingPeriods(DeliverySweepGroup.FIRING_DAY));
        Integer area = day.json().get("[0].area.id");
        if (area == null) {
            throw new IllegalStateException("The domain " + FIRING_DOMAIN + " has no firing area: " + day.preview());
        }
        ctx.putRow(FIRING_AREA, area);
        Integer period = day.json().get("[0].firingPeriods[0].id");
        if (period == null) {
            SweepResponse stored = ctx.ok("editor", DeliverySweepGroup.putFiringPeriods(DeliverySweepGroup.FIRING_DAY,
                    area, List.of(Map.of("fromDate", FIRING_PERIOD_FROM, "toDate", FIRING_PERIOD_TO))));
            period = stored.json().get("firingPeriods[0].id");
        }
        ctx.putRow(FIRING_PERIOD, period);
    }

    private void aton(SweepContext ctx) {
        Integer id = ctx.ok("anonymous", SweepRequest.get("/rest/atons/aton/{atonUids}").var("atonUids", ATON))
                .json().get("[0].id");
        if (id == null) {
            AtonNodeVo vo = new AtonNodeVo();
            vo.setLat(ATON_LAT);
            vo.setLon(ATON_LON);
            vo.setVisible(true);
            vo.setUid(ATON_UID);
            vo.setVersion(1);
            vo.setUser("sweep-delivery");
            vo.setTags(atonTags("buoy_cardinal"));
            EntityManager em = em(ctx);
            id = Tx.requiringNew().call(() -> {
                AtonNode node = new AtonNode(vo);
                em.persist(node);
                em.flush();
                return node.getId();
            });
        }
        ctx.putRow(ATON, id);
    }

    private void scheduledMail(SweepContext ctx) throws Exception {
        Integer id = ctx.ok("sysadmin", SweepRequest.get("/rest/scheduled-mails/search").query("subject", MAIL))
                .json().get("data.find { it.subject == '" + MAIL + "' }.id");
        if (id == null) {
            EntityManager em = em(ctx);
            id = Tx.requiringNew().call(() -> {
                ScheduledMail mail = new ScheduledMail();
                mail.setCreated(new Date());
                // Far ahead: the minute sender takes a pending mail only once its send date has come
                mail.setSendDate(new Date(System.currentTimeMillis() + 3_650L * 24 * 3_600_000L));
                mail.setSender("sweep-delivery-sender@example.org");
                mail.setSubject(MAIL);
                mail.setStatus(ScheduledMail.Status.PENDING);
                mail.setHtmlContents("<p>" + MAIL + "</p>");
                mail.addRecipient(new ScheduledMailRecipient(ScheduledMailRecipient.RecipientType.TO, MAIL_RECIPIENT));
                em.persist(mail);
                em.flush();
                return mail.getId();
            });
        }
        ctx.putRow(MAIL, id);
    }

    // ------------------------------------------------------------------------------------------- //
    // Tear-down
    // ------------------------------------------------------------------------------------------- //

    List<String> tearDown(SweepContext ctx) throws Exception {
        List<String> left = new ArrayList<>();

        if (ctx.hasRow(TRANSMITTER)) {
            ctx.send("sysadmin", SweepRequest.delete("/rest/promulgation/navtex/transmitters/{typeId}/transmitter/{name}")
                    .var("typeId", NAVTEX).var("name", TRANSMITTER));
            if (ctx.send("sysadmin", transmitters(NAVTEX)).text().contains("\"" + TRANSMITTER + "\"")) {
                left.add(TRANSMITTER);
            }
        }
        for (String area : AREAS) {
            if (ctx.hasRow(area)) {
                ctx.send("sysadmin", SweepRequest.delete("/rest/promulgation/safetynet/areas/{typeId}/area/{name}")
                        .var("typeId", SAFETYNET).var("name", area));
            }
        }
        if (ctx.hasRow(SAFETYNET)) {
            String remaining = ctx.send("sysadmin", safetyNetAreas(SAFETYNET)).text();
            AREAS.stream().filter(a -> remaining.contains("\"" + a + "\"")).forEach(left::add);
        }
        if (ctx.hasRow(TWITTER)) {
            // No endpoint removes Twitter settings (PROM8): through the entity manager, so the type can go
            EntityManager em = em(ctx);
            Tx.requiringNew().run(() -> em.createQuery("delete from TwitterSettings s where s.promulgationType.id in "
                    + "(select t.id from PromulgationType t where t.typeId = :typeId)")
                    .setParameter("typeId", TWITTER).executeUpdate());
        }
        for (String type : List.of(NAVTEX, SAFETYNET, TWITTER)) {
            if (ctx.hasRow(type)) {
                ctx.send("sysadmin", SweepRequest.delete("/rest/promulgations/promulgation-type/{typeId}")
                        .var("typeId", type));
                SweepResponse read = ctx.send("sysadmin", promulgationType(type).note("read after the tear-down"));
                if (!SweepEngine.matches(Cell.status(404, "No such promulgation type: " + type), read)) {
                    left.add(type + " (read after the delete: " + read.status() + ")");
                }
            }
        }

        if (ctx.hasRow(LIST)) {
            ctx.send("sysadmin", SweepRequest.delete("/rest/mailing-lists/mailing-list/{mailingListId}")
                    .var("mailingListId", LIST));
            SweepResponse read = ctx.send("admin", mailingList(LIST).note("read after the tear-down"));
            if (!SweepEngine.matches(Cell.status(404, "Mailing list " + LIST + " not found"), read)) {
                left.add(LIST + " (read after the delete: " + read.status() + ")");
            }
        }
        if (ctx.hasRow(CONTACT)) {
            ctx.send("sysadmin", SweepRequest.delete("/rest/contacts/contact/{id}").var("id", ctx.row(CONTACT)));
            if (contactId(ctx, CONTACT_EMAIL) != null) {
                left.add(CONTACT);
            }
        }
        if (ctx.hasRow(PARAM_TYPE)) {
            Object id = ctx.row(PARAM_TYPE);
            ctx.send("sysadmin", SweepRequest.delete("/rest/templates/parameter-type/{id}").var("id", id));
            SweepResponse read = ctx.send("anonymous", DeliverySweepGroup.paramType(id).note("read after the tear-down"));
            if (!SweepEngine.matches(Cell.status(404, "No such parameter type: " + id), read)) {
                left.add(PARAM_TYPE + " (id " + id + ", read after the delete: " + read.status() + ")");
            }
        }
        if (ctx.hasRow(INTEGRATION)) {
            Object id = ctx.row(INTEGRATION);
            ctx.send("sysadmin", SweepRequest.delete("/rest/niord-integrations/niord-integration/{id}").var("id", id));
            if (ctx.send("sysadmin", SweepRequest.get("/rest/niord-integrations/all")).text().contains(INTEGRATION_URL)) {
                left.add(INTEGRATION + " (id " + id + ")");
            }
        }
        if (ctx.hasRow(FIRING_PERIOD)) {
            Object area = ctx.row(FIRING_AREA);
            ctx.send("editor", DeliverySweepGroup.putFiringPeriods(DeliverySweepGroup.FIRING_DAY, area, List.of()));
            SweepResponse day = ctx.send("anonymous", DeliverySweepGroup.firingPeriods(DeliverySweepGroup.FIRING_DAY)
                    .note("read after the tear-down"));
            List<Object> periods = day.json().getList("find { it.area.id == " + area + " }.firingPeriods");
            if (periods == null || !periods.isEmpty()) {
                left.add(FIRING_PERIOD + " (id " + ctx.row(FIRING_PERIOD) + ")");
            }
        }
        if (ctx.hasRow(ATON)) {
            Object id = ctx.row(ATON);
            EntityManager em = em(ctx);
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from AtonTag t where t.atonNode.id = :id").setParameter("id", id)
                        .executeUpdate();
                AtonNode node = em.find(AtonNode.class, id);
                if (node != null) {
                    em.remove(node);
                }
            });
            SweepResponse read = ctx.send("anonymous", SweepRequest.get("/rest/atons/aton/{atonUids}")
                    .var("atonUids", ATON).note("read after the tear-down"));
            if (!read.json().getList("$").isEmpty()) {
                left.add(ATON + " (id " + id + ")");
            }
        }
        if (ctx.hasRow(MAIL)) {
            Object id = ctx.row(MAIL);
            EntityManager em = em(ctx);
            Tx.requiringNew().run(() -> {
                ScheduledMail mail = em.find(ScheduledMail.class, id);
                if (mail != null) {
                    em.remove(mail);
                }
            });
            SweepResponse read = ctx.send("sysadmin", SweepRequest.get("/rest/scheduled-mails/search")
                    .query("subject", MAIL).note("read after the tear-down"));
            if (read.json().getInt("total") != 0) {
                left.add(MAIL + " (id " + id + ")");
            }
        }
        return left;
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests and bodies
    // ------------------------------------------------------------------------------------------- //

    static EntityManager em(SweepContext ctx) {
        return SharedEntityManagerCreator.createSharedEntityManager(ctx.bean(EntityManagerFactory.class));
    }

    /** A map that keeps its order and takes null values */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    static SweepRequest promulgationType(Object typeId) {
        return SweepRequest.get("/rest/promulgations/promulgation-type/{typeId}").var("typeId", typeId);
    }

    static SweepRequest transmitters(Object typeId) {
        return SweepRequest.get("/rest/promulgation/navtex/transmitters/{typeId}/all").var("typeId", typeId);
    }

    static SweepRequest safetyNetAreas(Object typeId) {
        return SweepRequest.get("/rest/promulgation/safetynet/areas/{typeId}/all").var("typeId", typeId);
    }

    static SweepRequest mailingList(Object id) {
        return SweepRequest.get("/rest/mailing-lists/mailing-list/{mailingListId}").var("mailingListId", id);
    }

    /** An inactive promulgation type of the service, in the domains */
    static Map<String, Object> typeBody(String typeId, String serviceId, String name, int priority,
                                        List<String> domains) {
        return map("typeId", typeId, "serviceId", serviceId, "name", name, "active", false, "requirement", "OPTIONAL",
                "priority", priority, "language", "en",
                "domains", domains.stream().map(d -> Map.of("domainId", d)).toList());
    }

    static Map<String, Object> transmitterBody(String typeId, String name, boolean active) {
        return map("promulgationType", Map.of("typeId", typeId), "name", name, "active", active, "areas", List.of());
    }

    /** A SafetyNET area of the shape its name ends with (circular, rectangular, coastal or navarea) */
    static Map<String, Object> areaBody(String typeId, String name, int sortOrder, String prefix) {
        Map<String, Object> area = map("promulgationType", Map.of("typeId", typeId), "name", name, "active", true,
                "sortOrder", sortOrder, "repetition", "REPETITION_01", "messageAreaPrefix", prefix);
        if (name.endsWith("rectangular")) {
            area.put("type", "RECTANGULAR");
            area.put("swCorner", "55 10");
            area.put("northings", "2");
            area.put("eastings", "3");
        } else if (name.endsWith("coastal")) {
            area.put("type", "COASTAL");
            area.put("coastalArea", "A");
        } else if (name.endsWith("navarea")) {
            area.put("type", "NAVAREA");
            area.put("navarea", "I");
        } else {
            area.put("type", "CIRCULAR");
            area.put("center", "57 10");
            area.put("radius", "25");
        }
        return area;
    }

    static TwitterSettingsVo twitterSettings(String typeId, String format, String secret) {
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId(typeId);
        TwitterSettingsVo settings = new TwitterSettingsVo();
        settings.setPromulgationType(type);
        settings.setFormat(format);
        settings.setIncludeThumbnail(false);
        settings.setApiKey("sweep-key-" + secret);
        settings.setApiSecret("sweep-secret-" + secret);
        settings.setAccessToken("sweep-token-" + secret);
        settings.setAccessTokenSecret("sweep-token-secret-" + secret);
        return settings;
    }

    /** An inactive mailing list with an English and a Danish description and the triggers */
    static Map<String, Object> listBody(String id, List<Map<String, Object>> triggers) {
        return map("mailingListId", id, "active", false,
                "descs", List.of(map("lang", "en", "name", id, "description", "The sweep's list " + id),
                        map("lang", "da", "name", id + "-da", "description", "Fejesporets liste")),
                "triggers", triggers);
    }

    /** A scheduled trigger usable as a public report, with a script; in an inactive list it never runs on its own */
    static Map<String, Object> reportTrigger() {
        return map("type", "SCHEDULED", "scheduleType", "DAILY", "scheduledExecutionTime", "08:00",
                "scheduledExecutionTimeZone", "Europe/Copenhagen",
                "messageQuery", "messageSeries=dma-nw&status=PUBLISHED&type=COASTAL_WARNING&sortBy=ID",
                "scriptResourcePaths", List.of("templates/mailinglist/active-navwarns.ftl"), "publicReport", true,
                "descs", List.of(Map.of("lang", "en", "subject", REPORT)));
    }

    static Map<String, Object> contactBody(String email, String name) {
        return map("email", email, "name", name, "language", "en");
    }

    /** The id of the contact with the address, or null */
    static Integer contactId(SweepContext ctx, String email) {
        SweepResponse found = ctx.ok("sysadmin", SweepRequest.get("/rest/contacts/search").query("name", email)
                .query("maxSize", 100));
        return found.json().get("data.find { it.email == '" + email + "' }.id");
    }

    static AtonTagVo[] atonTags(String type) {
        return new AtonTagVo[]{
                new AtonTagVo("seamark:ref", ATON),
                new AtonTagVo("seamark:name", ATON),
                new AtonTagVo("seamark:type", type)};
    }

    /** An AtoN as a body: the fixture's name and the type, as tags (an object of key and value, as the API reads them) */
    static Map<String, Object> atonBody(String type) {
        Map<String, Object> tags = new LinkedHashMap<>();
        for (AtonTagVo tag : atonTags(type)) {
            tags.put(tag.getK(), tag.getV());
        }
        return map("lat", ATON_LAT, "lon", ATON_LON, "tags", tags);
    }

    /** The same AtoN with its tags written as a list of key-value objects, a form the API does not read */
    static Map<String, Object> atonBodyWithTagList(String type) {
        List<Map<String, String>> tags = new ArrayList<>();
        for (AtonTagVo tag : atonTags(type)) {
            tags.add(Map.of("k", tag.getK(), "v", tag.getV()));
        }
        return map("lat", ATON_LAT, "lon", ATON_LON, "tags", tags);
    }

    /** A firing period of the fixture's day, with the id when it is stored */
    static Map<String, Object> period(Object id) {
        return map("id", id, "fromDate", FIRING_PERIOD_FROM, "toDate", FIRING_PERIOD_TO);
    }
}
