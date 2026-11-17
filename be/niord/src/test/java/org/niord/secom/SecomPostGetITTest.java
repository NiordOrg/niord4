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

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.grad.secomv2.core.models.EnvelopeGetFilterObject;
import org.grad.secomv2.core.models.EnvelopeGetSummaryFilterObject;
import org.grad.secomv2.core.models.GetFilterObject;
import org.grad.secomv2.core.models.GetSummaryFilterObject;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.secom.model.S124Dataset;
import org.niord.test.Tx;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level ITs for the POST body form of the SECOM Get/GetSummary interfaces:
 * {@code POST /v2/object/search} and {@code POST /v2/object/search/summary}. SECOM's
 * capability document has one {@code get} flag covering both query forms, so advertising
 * {@code get: true} promises this form too — it is what a client must use once its geometry
 * outgrows a URL, and what AMSA's {@code s124-secom-client} uses exclusively.
 * <p>
 * Also covers, end to end through the SECOM signature filter, the signature interop the
 * live AMSA traffic pinned down (2026-09-01): a peer signing
 * ECDSA-384-SHA2 (the S-100 Part 15 mandate) over the envelope CSV <em>without</em> the
 * CD3-deprecated trailing {@code envelopeSignatureReference} field must be accepted, while
 * a tampered envelope must still be rejected.
 */
class SecomPostGetITTest extends SecomITTest {

    /** Commits a published NW + its dataset and returns the dataset */
    private S124Dataset committedDataset(double lat, double lon) {
        return Tx.requiringNew().call(() -> {
            Message published = publishNw("secom-postget-" + uniq(), lat, lon);
            datasetService.onMessageStatusChanged(published.getUid());
            return datasetService.findByMessageUid(published.getUid());
        });
    }

    /** Unzips a byte array into an entry-name -> content map */
    /** {@return a signed POST get request body querying for the given dataReference} */
    private String signedGetQuery(UUID dataReference) throws Exception {
        EnvelopeGetFilterObject envelope = new EnvelopeGetFilterObject();
        envelope.setDataReference(dataReference);
        envelope.setProductVersion("2.0.0");

        GetFilterObject request = new GetFilterObject();
        request.setEnvelope(envelope);

        ClientSigner signer = clientSigner();
        request.signEnvelope(signer, signer);
        return SECOM_MAPPER.writeValueAsString(request);
    }

    @Test
    void aSignedPostQueryAnswersLikeTheUrlForm() throws Exception {
        S124Dataset dataset = committedDataset(55.5, 11.1);

        Response response = secomGiven()
                .contentType(ContentType.JSON)
                .body(signedGetQuery(dataset.getUuid()))
                .post("/api/secom/v2/object/search")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1))
                .body("pagination.totalItems", equalTo(1))
                .body("dataResponseObject[0].exchangeMetadata.digitalSignatureValue.digitalSignature", notNullValue())
                .extract().response();

        // The default container is the same design-mandated exchange set the URL form serves
        byte[] zip = Base64.getDecoder().decode(response.<String>path("dataResponseObject[0].data"));
        Map<String, byte[]> entries = unzip(zip);
        assertTrue(entries.containsKey("S100_ROOT/CATALOG.XML"), "the exchange set carries the signed catalogue");
        String datasetFile = entries.keySet().stream()
                .filter(name -> name.startsWith("S100_ROOT/S-124/DATASET_FILES/") && name.endsWith(".GML"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the exchange set must contain an S-124 dataset file"));
        assertTrue(new String(entries.get(datasetFile), StandardCharsets.UTF_8).contains(dataset.getMrn()),
                "the dataset file carries the warning (its interoperability MRN)");
    }

    @Test
    void aSignedPostSummaryQueryReturnsTheSummaries() throws Exception {
        S124Dataset dataset = committedDataset(55.5, 11.1);

        EnvelopeGetSummaryFilterObject envelope = new EnvelopeGetSummaryFilterObject();
        envelope.setProductVersion("2.0.0");
        GetSummaryFilterObject request = new GetSummaryFilterObject();
        request.setEnvelope(envelope);
        ClientSigner signer = clientSigner();
        request.signEnvelope(signer, signer);

        secomGiven()
                .contentType(ContentType.JSON)
                .body(SECOM_MAPPER.writeValueAsString(request))
                .post("/api/secom/v2/object/search/summary")
                .then()
                .statusCode(200)
                .body("summaryObject", hasSize(1))
                .body("summaryObject[0].dataReference", equalTo(dataset.getUuid().toString()))
                .body("pagination.totalItems", equalTo(1));
    }

    @Test
    void anAmsaStyleSignatureIsAccepted() throws Exception {
        // The live interop case: ECDSA-384-SHA2 (not this node's configured SHA3-384) over the
        // envelope CSV without the CD3-deprecated trailing envelopeSignatureReference field
        // that the GRAD library still renders. Both differences at once, exactly as AMSA signs.
        S124Dataset dataset = committedDataset(55.5, 11.1);

        EnvelopeGetFilterObject envelope = new EnvelopeGetFilterObject();
        envelope.setDataReference(dataset.getUuid());
        envelope.setProductVersion("2.0.0");
        GetFilterObject request = new GetFilterObject();
        request.setEnvelope(envelope);

        // Let the library fill in the certificate, thumbprint and signature time, then replace
        // its signature with one an AMSA-style sender would produce
        ClientSigner signer = clientSigner();
        request.signEnvelope(signer, signer);
        String csv = envelope.getCsvString();
        assertTrue(csv.endsWith("."), "the GRAD rendering must still end with the deprecated empty field");
        byte[] amsaRendering = csv.substring(0, csv.length() - 1).getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("SHA384withECDSA");
        signature.initSign(signer.key());
        signature.update(amsaRendering);
        request.setEnvelopeSignature(HexFormat.of().withUpperCase().formatHex(signature.sign()));

        secomGiven()
                .contentType(ContentType.JSON)
                .body(SECOM_MAPPER.writeValueAsString(request))
                .post("/api/secom/v2/object/search")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1));
    }

    @Test
    void aTamperedPostQuerySignatureIsRejected() throws Exception {
        S124Dataset dataset = committedDataset(55.5, 11.1);

        // Corrupt the envelope after signing - the SECOM signature filter must reject it
        String tampered = signedGetQuery(dataset.getUuid())
                .replace(dataset.getUuid().toString(), UUID.randomUUID().toString());

        int status = secomGiven()
                .contentType(ContentType.JSON)
                .body(tampered)
                .post("/api/secom/v2/object/search")
                .then()
                .extract().statusCode();
        assertTrue(status >= 400, "a tampered envelope signature must not be accepted, got " + status);
    }

    @Test
    void aPostQueryWithoutAnEnvelopeIsRejected() {
        secomGiven()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/api/secom/v2/object/search")
                .then()
                .statusCode(400);

        secomGiven()
                .contentType(ContentType.JSON)
                .body("{}")
                .post("/api/secom/v2/object/search/summary")
                .then()
                .statusCode(400);
    }

    @Test
    void postQueriesRequireAClientCertificate() throws Exception {
        S124Dataset dataset = committedDataset(55.5, 11.1);

        given()
                .contentType(ContentType.JSON)
                .body(signedGetQuery(dataset.getUuid()))
                .post("/api/secom/v2/object/search")
                .then()
                .statusCode(403);
    }

    @Test
    void aPostQueryMatchingNothingIsA404LikeTheUrlForm() throws Exception {
        committedDataset(55.5, 11.1);

        int status = secomGiven()
                .contentType(ContentType.JSON)
                .body(signedGetQuery(UUID.randomUUID()))
                .post("/api/secom/v2/object/search")
                .then()
                .extract().statusCode();
        assertEquals(404, status, "an empty result set is a 404, not an empty 200 (design §6.3.5)");
    }
}
