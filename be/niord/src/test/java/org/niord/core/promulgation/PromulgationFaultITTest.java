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
package org.niord.core.promulgation;

import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.ScriptResourceService;
import org.niord.model.DataFilter;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault injection on the promulgation steps of a warning: a NAVTEX or SafetyNET selection that cannot be resolved on
 * save, and a promulgation type whose script fails when its text is generated. Each case asserts the corrected
 * behaviour, beside the right path.
 */
class PromulgationFaultITTest extends ITTest {

    private static final String NAVTEX = "navtex";

    @Autowired
    NavtexPromulgationService navtexPromulgationService;

    @Autowired
    SafetyNetPromulgationService safetyNetPromulgationService;

    @Autowired
    PromulgationManager promulgationManager;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @Autowired
    ScriptResourceService resourceService;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    private final List<String> typeIds = new ArrayList<>();
    private final List<String> scriptPaths = new ArrayList<>();
    private final List<String> messageUids = new ArrayList<>();
    private final Set<String> transmitterNames = new LinkedHashSet<>();

    @AfterEach
    void cleanup() {
        if (TestTransaction.isActive()) {
            TestTransaction.flagForRollback();
            TestTransaction.end();
        }
        for (String uid : messageUids) {
            try {
                Tx.requiringNew().run(() -> {
                    Message msg = messageQueryService.findByUid(uid);
                    if (msg != null) {
                        em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                                .setParameter("messageId", msg.getId())
                                .getResultList()
                                .forEach(em::remove);
                        em.remove(msg);
                    }
                });
            } catch (Exception ignored) {
                // best effort
            }
        }
        for (String name : transmitterNames) {
            try {
                Tx.requiringNew().run(() -> navtexPromulgationService.deleteTransmitter(NAVTEX, name));
            } catch (Exception ignored) {
                // best effort
            }
        }
        messageUids.clear();
        transmitterNames.clear();
        for (String typeId : typeIds) {
            try {
                Tx.requiringNew().run(() -> promulgationTypeService.deletePromulgationType(typeId));
            } catch (Exception ignored) {
                // best effort
            }
        }
        for (String path : scriptPaths) {
            try {
                Tx.requiringNew().run(() -> em.createQuery("delete from ScriptResource r where r.path = :p")
                        .setParameter("p", path).executeUpdate());
            } catch (Exception ignored) {
                // best effort
            }
        }
        typeIds.clear();
        scriptPaths.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // A NAVTEX transmitter or SafetyNET area that cannot be resolved on save
    // ------------------------------------------------------------------------------------------- //

    private NavtexTransmitter newTransmitter(String name) {
        NavtexTransmitter t = new NavtexTransmitter(name);
        t.setActive(true);
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        t.setPromulgationType(new PromulgationType(typeVo));
        t.setAreas(new ArrayList<>());
        return t;
    }

    /** Creates a committed transmitter through the service, removed again in cleanup */
    private void createTransmitter(String name) {
        transmitterNames.add(name);
        Tx.requiringNew().run(() -> navtexPromulgationService.createTransmitter(newTransmitter(name)));
    }

    /**
     * Stores a committed transmitter directly, as a deployment's existing row: the service now refuses a name that
     * differs from an existing one only in case, but a deployment may already hold such twins.
     */
    private void storeExistingTwin(String name) {
        transmitterNames.add(name);
        Tx.requiringNew().run(() -> {
            NavtexTransmitter t = newTransmitter(name);
            t.setPromulgationType(promulgationTypeService.getPromulgationType(NAVTEX));
            em.persist(t);
        });
    }

    /**
     * A dma-nw coastal warning whose NAVTEX promulgation selects the given transmitter names: a new one when uid is
     * null, else the template of an update of the stored message with that uid and status, carrying the id of its
     * stored NAVTEX promulgation as the editor sends it
     */
    private Message navtexMessage(String uid, Status status, String n, List<String> transmitters) {
        Message msg = new Message();
        Integer promulgationId = null;
        if (uid != null) {
            msg.setUid(uid);
            msg.setStatus(status);
            promulgationId = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid)
                    .promulgation(NavtexMessagePromulgation.class, NAVTEX).getId());
        }
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle("IT prom14 " + n);
        NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
        navtex.setId(promulgationId);
        PromulgationType type = new PromulgationType();
        type.setTypeId(NAVTEX);
        navtex.setType(type);
        navtex.setPromulgate(true);
        navtex.setText("IT PROM14");
        for (String name : transmitters) {
            navtex.getTransmitters().add(new NavtexTransmitter(name));
        }
        msg.addPromulgation(navtex);
        return msg;
    }

    /** The names of the transmitters the stored message's NAVTEX promulgation selects, sorted */
    private List<String> storedTransmitters(String uid) {
        return Tx.requiringNew().call(() -> messageQueryService.findByUid(uid)
                .promulgation(NavtexMessagePromulgation.class, NAVTEX).getTransmitters().stream()
                .map(NavtexTransmitter::getName)
                .sorted()
                .collect(Collectors.toList()));
    }

    /**
     * Three transmitters exist: one with a unique name, and two of one type whose names differ only in case
     * ("IT-TX-Rog-n" and "it-tx-rog-n", a deployment's existing rows). A message is created selecting the unique one
     * and one that does not exist: the create (the path every import, the firing run and the cancellation warning
     * take) drops the vanished name with a WARN. The editor's update then selects the unique one, the exact-case
     * twin and the vanished one: the update is refused with an {@code IllegalArgumentException} (400) naming the
     * vanished transmitter, and the stored message is unchanged. Unticking it, the update stores the unique one and
     * the exact-case twin, not the other twin.
     */
    @Test
    void aSelectedTransmitterWithACaseTwinIsKeptAndAVanishedOneRefusesTheSave() {
        String n = Long.toString(System.nanoTime(), 36);
        String plain = "IT-TX-Plain-" + n;
        String twin = "IT-TX-Rog-" + n;
        String gone = "IT-TX-Gone-" + n;
        createTransmitter(plain);
        createTransmitter(twin);
        storeExistingTwin(twin.toLowerCase());

        String uid = Tx.requiringNew().call(() ->
                messageService.createMessage(navtexMessage(null, null, n, List.of(plain, gone))).getUid());
        messageUids.add(uid);
        assertEquals(List.of(plain), storedTransmitters(uid),
                "a created message (an import, the firing run) drops a transmitter this deployment lacks");
        Status status = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Tx.requiringNew().run(
                () -> messageService.updateMessage(navtexMessage(uid, status, n, List.of(plain, twin, gone)))),
                "an editor's update that selects a transmitter that does not exist is refused");
        assertTrue(refused.getMessage().contains("'" + gone + "'"), "the refusal names it: " + refused.getMessage());
        assertEquals(List.of(plain), storedTransmitters(uid), "the refused update changed nothing");

        Tx.requiringNew().run(() -> messageService.updateMessage(navtexMessage(uid, status, n, List.of(plain, twin))));
        assertEquals(List.of(plain, twin), storedTransmitters(uid),
                "the transmitter with a case twin is kept, resolved by its exact name");
    }

    /**
     * A transmitter whose name differs from an existing one of the type only in case is refused with an
     * {@code IllegalStateException} (409), so no new twins are made. Where twins already exist, a name that matches
     * neither exactly is ambiguous and refused the same way, instead of being read as "no such transmitter".
     */
    @Test
    void aTransmitterNamedLikeAnExistingOneIsRefused() {
        String n = Long.toString(System.nanoTime(), 36);
        String name = "IT-TX-Twin-" + n;
        createTransmitter(name);

        transmitterNames.add(name.toLowerCase()); // removed in cleanup, should the refusal fail
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(
                () -> navtexPromulgationService.createTransmitter(newTransmitter(name.toLowerCase()))));
        assertTrue(refused.getMessage().contains(name.toLowerCase()), refused.getMessage());
        assertEquals(1L, Tx.requiringNew().call(() -> em.createQuery(
                        "select count(t) from NavtexTransmitter t where lower(t.name) = :name", Long.class)
                .setParameter("name", name.toLowerCase())
                .getSingleResult()), "the twin was not stored");

        storeExistingTwin(name.toLowerCase());
        assertEquals(name, Tx.requiringNew().call(
                () -> navtexPromulgationService.findTransmitterByName(NAVTEX, name).getName()),
                "control: an exact name resolves to its own transmitter");
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().call(
                () -> navtexPromulgationService.findTransmitterByName(NAVTEX, name.toUpperCase())),
                "a name that matches two twins and neither exactly is ambiguous");
    }

    /** A message with a SafetyNET promulgation of the seeded navtex type that selects the area with the name */
    private Message safetyNetMessage(String areaName) {
        Message msg = new Message();
        SafetyNetMessagePromulgation safetynet = new SafetyNetMessagePromulgation();
        PromulgationType type = new PromulgationType();
        type.setTypeId(NAVTEX);
        safetynet.setType(type);
        SafetyNetCircularArea area = new SafetyNetCircularArea();
        area.setName(areaName);
        safetynet.setArea(area);
        msg.addPromulgation(safetynet);
        return msg;
    }

    private SafetyNetArea selectedArea(Message msg) {
        return msg.promulgation(SafetyNetMessagePromulgation.class, NAVTEX).getArea();
    }

    /**
     * The SafetyNET sites. A SafetyNET area that no longer exists: the editor's update of a message that
     * selects it is refused with an {@code IllegalArgumentException} (400) naming it, a created message drops it,
     * and loading or generating a promulgation that names it clears the name instead of failing with a
     * {@code NullPointerException} (500). An area that exists resolves to the stored row (the control), and a new
     * area named like an existing one is refused with an {@code IllegalStateException} (409).
     */
    @Test
    @Transactional
    void aSafetyNetAreaThatNoLongerExistsAnswers400NotAServerError() throws Exception {
        String n = Long.toString(System.nanoTime(), 36);
        String kept = "IT-SN-Keep-" + n;
        String gone = "IT-SN-Gone-" + n;
        SafetyNetCircularArea area = new SafetyNetCircularArea();
        area.setName(kept);
        area.setActive(true);
        area.setCenter("0 0");
        area.setRadius("100");
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        area.setPromulgationType(new PromulgationType(typeVo));
        safetyNetPromulgationService.createArea(area);
        em.flush();
        PromulgationType type = promulgationTypeService.getPromulgationType(NAVTEX);

        Message existing = safetyNetMessage(kept);
        safetyNetPromulgationService.onUpdateMessage(existing, type);
        assertNotNull(selectedArea(existing).getId(), "control: an existing area resolves to the stored row");

        Message update = safetyNetMessage(gone);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> safetyNetPromulgationService.onUpdateMessage(update, type));
        assertTrue(refused.getMessage().contains("'" + gone + "'"), "the refusal names it: " + refused.getMessage());

        Message created = safetyNetMessage(gone);
        safetyNetPromulgationService.onCreateMessage(created, type);
        assertNull(selectedArea(created), "a created message (an import) drops an area this deployment lacks");

        SafetyNetMessagePromulgationVo loaded = new SafetyNetMessagePromulgationVo(type.toVo(DataFilter.get()));
        loaded.setAreaName(gone);
        safetyNetPromulgationService.checkSafetyNetArea(loaded, Type.COASTAL_WARNING);
        assertNotEquals(gone, loaded.getAreaName(), "the vanished area's name is cleared, not dereferenced");

        SafetyNetCircularArea twin = new SafetyNetCircularArea();
        twin.setName(kept.toLowerCase());
        twin.setActive(true);
        twin.setCenter("0 0");
        twin.setRadius("100");
        twin.setPromulgationType(new PromulgationType(typeVo));
        assertThrows(IllegalStateException.class, () -> safetyNetPromulgationService.createArea(twin),
                "a new area named like an existing one is refused");
    }

    // ------------------------------------------------------------------------------------------- //
    // A promulgation type's script fails when its text is generated
    // ------------------------------------------------------------------------------------------- //

    private String scriptResource(String path, String content) {
        scriptPaths.add(path);
        Tx.requiringNew().run(() -> {
            ScriptResource resource = new ScriptResource();
            resource.setPath(path);
            resource.setContent(content);
            resourceService.createScriptResource(resource);
        });
        return path;
    }

    private String navtexBackedType(String typeId, String scriptPath) {
        typeIds.add(typeId);
        Tx.requiringNew().run(() -> {
            PromulgationTypeVo vo = new PromulgationTypeVo();
            vo.setTypeId(typeId);
            vo.setServiceId(NAVTEX);
            vo.setName(typeId);
            vo.setPriority(301);
            vo.setActive(true);
            vo.setLanguage("en");
            vo.setScriptResourcePaths(scriptPath == null ? new ArrayList<>() : new ArrayList<>(List.of(scriptPath)));
            promulgationTypeService.createPromulgationType(new PromulgationType(vo));
        });
        return typeId;
    }

    private String generatedText(String typeId) {
        return ((NavtexMessagePromulgationVo) generated(typeId)).getText();
    }

    /** The failure of Generate for the type, as the manager throws it (the transaction helper wraps it) */
    private PromulgationException generateFailure(String typeId) {
        RuntimeException e = assertThrows(RuntimeException.class, () -> generated(typeId));
        return assertInstanceOf(PromulgationException.class, e.getCause(),
                "Generate fails with a PromulgationException: " + e);
    }

    /** Generate over HTTP, as the admin application's editor calls it */
    private ExtractableResponse<Response> generateOverHttp(String typeId) {
        return given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "type", "COASTAL_WARNING",
                        "parts", List.of(Map.of(
                                "type", "DETAILS",
                                "descs", List.of(Map.of("lang", "en", "details", "<p>Buoy adrift</p>"))))))
                .when().post("/rest/promulgations/generate/" + typeId)
                .then().extract();
    }

    /**
     * Generate over HTTP fails for the type with 500 and a problem detail an editor can read: it names the type and
     * quotes neither the script nor a stack trace
     */
    private void assertGenerateFailsReadably(String typeId, String scriptText) {
        ExtractableResponse<Response> answer = generateOverHttp(typeId);
        assertEquals(500, answer.statusCode(), "Generate over HTTP fails for " + typeId);
        String detail = answer.jsonPath().getString("detail");
        assertNotNull(detail, "the answer has a readable reason: " + answer.asString());
        assertTrue(detail.contains("The " + typeId + " promulgation could not be generated"), detail);
        assertFalse(answer.asString().contains(scriptText), "the script is not quoted: " + answer.asString());
        assertFalse(answer.asString().contains("\tat "), "no stack trace: " + answer.asString());
    }

    private BaseMessagePromulgationVo generated(String typeId) {
        SystemMessageVo message = new SystemMessageVo();
        message.setType(Type.COASTAL_WARNING);
        message.setPromulgations(new ArrayList<>());
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo desc = new MessagePartDescVo();
        desc.setLang("en");
        desc.setDetails("<p>Buoy adrift</p>");
        part.setDescs(new ArrayList<>(List.of(desc)));
        message.setParts(new ArrayList<>(List.of(part)));
        BaseMessagePromulgationVo p = Tx.requiringNew().call(() ->
                promulgationManager.generateMessagePromulgation(typeId, message));
        return p;
    }

    /**
     * Four NAVTEX-backed types generate the text of one message: one whose script rewrites the text (the
     * control: its rewrite shows in the result), one whose script throws while it renders (an undefined value),
     * one whose script path has no resource, and one whose script writes a field that cannot be assigned (a
     * promulgation the message does not carry), which used to be logged and skipped. The script adjusts the broadcast text (a header, a cancellation footer,
     * the wording), so a failing script fails Generate for its type with a {@code PromulgationException} that names
     * the type and carries the script's error as its cause, instead of answering the unscripted text. Over HTTP,
     * Generate answers 500 for the failing type with a problem detail that names the type and quotes neither the
     * script nor a stack trace, and the editor's per-type error message says it could not be generated; the
     * working type still answers 200.
     */
    @Test
    void aFailingPromulgationScriptFailsGenerateWithTheScriptAndTheCause() {
        String n = Long.toString(System.nanoTime(), 36);
        String rewrite = scriptResource("templates/it/prom12-rewrite-" + n + ".ftl",
                "<field-template field=\"promulgation.text\">SCRIPTED ${promulgation.text}</field-template>");
        String boom = scriptResource("templates/it/prom12-boom-" + n + ".ftl",
                "<field-template field=\"promulgation.text\">SCRIPTED ${promulgation.noSuchValue.boom}</field-template>");

        String okType = navtexBackedType("it-p12ok-" + n, rewrite);
        assertEquals("SCRIPTED BUOY ADRIFT", generatedText(okType), "control: a working script rewrites the text");

        String unassignable = scriptResource("templates/it/prom12-unassignable-" + n + ".ftl",
                "<field-template field=\"message.promulgation('it-none-" + n + "').text\">X</field-template>");

        String boomType = navtexBackedType("it-p12boom-" + n, boom);
        String missingType = navtexBackedType("it-p12miss-" + n, "templates/it/prom12-missing-" + n + ".ftl");
        String unassignableType = navtexBackedType("it-p12unas-" + n, unassignable);
        for (String typeId : List.of(boomType, missingType, unassignableType)) {
            PromulgationException failure = generateFailure(typeId);
            assertTrue(failure.getMessage().contains("promulgation type " + typeId + " failed"),
                    "the failure names the type: " + failure.getMessage());
            assertNotNull(failure.getCause(), "the script's own error is the cause");
        }

        assertEquals(200, generateOverHttp(okType).statusCode(), "control: Generate over HTTP answers the working type");
        assertGenerateFailsReadably(boomType, "noSuchValue");
        assertGenerateFailsReadably(missingType, "prom12-missing-" + n);
        assertGenerateFailsReadably(unassignableType, "it-none-" + n);
    }
}
