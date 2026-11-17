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

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.rest.MrnCertificateFilter;
import org.niord.test.Tx;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level ITs for the SECOM v2 read plane served by the RESTEasy servlet at
 * {@code /api/secom}: certificate authentication (positive + negative), capability, ping,
 * and Get/GetSummary semantics — stable dataReferences, geometry/validity filters,
 * pagination, and the signed exchange metadata applied by the SECOM writer interceptor.
 * <p>
 * Data is committed via {@link Tx} (HTTP requests run outside the test transaction);
 * the SECOM tables are wiped before each test by the base class.
 */
class SecomProtocolITTest extends SecomITTest {

    /** Commits a published NW + its dataset and returns the dataset */
    private S124Dataset committedDataset(double lat, double lon) {
        return Tx.requiringNew().call(() -> {
            Message published = publishNw("secom-proto-" + uniq(), lat, lon);
            datasetService.onMessageStatusChanged(published.getUid());
            return datasetService.findByMessageUid(published.getUid());
        });
    }

    /** Unzips a byte array into an entry-name -> content map */
    // ------------------------------------------------------------------------------------------- //
    // Certificate authentication
    // ------------------------------------------------------------------------------------------- //

    @Test
    void requestsWithoutAClientCertificateAreRejected() {
        given()
                .get("/api/secom/v2/capability")
                .then()
                .statusCode(403);
    }

    @Test
    void certificatesFromAnUntrustedRootAreRejected() {
        given()
                .header(MrnCertificateFilter.CERT_HEADER, untrustedCertHeader())
                .get("/api/secom/v2/capability")
                .then()
                .statusCode(403);
    }

    @Test
    void garbageCertificateHeadersAreRejected() {
        given()
                .header(MrnCertificateFilter.CERT_HEADER, "bm90LWEtY2VydA==")
                .get("/api/secom/v2/capability")
                .then()
                .statusCode(403);
    }

    @Test
    void base64DerCertificateHeadersAreAccepted() {
        // The Caddy-style header encoding: base64 of the DER bytes (= the PEM body)
        String derHeader = clientCertHeader()
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");

        given()
                .header(MrnCertificateFilter.CERT_HEADER, derHeader)
                .get("/api/secom/v2/capability")
                .then()
                .statusCode(200);
    }

    // ------------------------------------------------------------------------------------------- //
    // Capability + Ping
    // ------------------------------------------------------------------------------------------- //

    @Test
    void capabilityAdvertisesTheS124ReadInterfaces() {
        secomGiven()
                .get("/api/secom/v2/capability")
                .then()
                .statusCode(200)
                .body("capability", hasSize(1))
                .body("capability[0].dataProductType", equalTo("S-124"))
                // The design-mandated payload packaging: 1 = S100_ExchangeSet
                .body("capability[0].containerType", equalTo(1))
                .body("capability[0].implementedInterfaces.get", equalTo(true))
                .body("capability[0].implementedInterfaces.getSummary", equalTo(true))
                .body("capability[0].implementedInterfaces.subscription", equalTo(true))
                .body("capability[0].implementedInterfaces.upload", equalTo(false));
    }

    @Test
    void pingReportsTheServiceTimestamp() {
        // The ping timestamp is the service-side lastUpdatedAt: when the dataset store last
        // changed, falling back to "now" for an empty store - either way it must be present
        secomGiven()
                .get("/api/secom/v2/ping")
                .then()
                .statusCode(200)
                .body("timestamp", notNullValue());

        committedDataset(55.5, 11.1);
        secomGiven()
                .get("/api/secom/v2/ping")
                .then()
                .statusCode(200)
                .body("timestamp", notNullValue());
    }

    @Test
    void pingIsServedWithoutAClientCertificate() {
        // Spec §4.1.7 wants a status check "without sending a valid message"; registries
        // typically ping unauthenticated. Every other endpoint keeps requiring a certificate
        // (see requestsWithoutAClientCertificateAreRejected).
        given()
                .get("/api/secom/v2/ping")
                .then()
                .statusCode(200)
                .body("timestamp", notNullValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // Get
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getServesAnExchangeSetByDefault() throws Exception {
        // The design mandates S-100 exchange sets (containerType 1) as the payload packaging;
        // an unspecified containerType defaults to it
        S124Dataset dataset = committedDataset(55.5, 11.1);

        Response response = secomGiven()
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1))
                .body("pagination.totalItems", equalTo(1))
                .body("dataResponseObject[0].exchangeMetadata.digitalSignatureValue.digitalSignature", notNullValue())
                .extract().response();

        byte[] zip = Base64.getDecoder().decode(response.<String>path("dataResponseObject[0].data"));
        Map<String, byte[]> entries = unzip(zip);
        assertTrue(entries.containsKey("S100_ROOT/CATALOG.XML"), "the exchange set carries the signed catalogue");
        assertTrue(entries.containsKey("S100_ROOT/CATALOG.SIGN"), "the catalogue signature is included");
        String datasetFile = entries.keySet().stream()
                .filter(name -> name.startsWith("S100_ROOT/S-124/DATASET_FILES/") && name.endsWith(".GML"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the exchange set must contain an S-124 dataset file"));
        assertTrue(new String(entries.get(datasetFile), StandardCharsets.UTF_8).contains(dataset.getMrn()),
                "the dataset file carries the warning (its interoperability MRN)");
    }

    @Test
    void getServesTheBareDatasetWhenExplicitlyRequested() {
        // Legacy peers may request containerType 0 = S100_DataSet - the payload is then the
        // exact persisted GML bytes
        S124Dataset dataset = committedDataset(55.5, 11.1);

        Response response = secomGiven()
                .queryParam("containerType", "0")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1))
                .body("dataResponseObject[0].exchangeMetadata.digitalSignatureValue.digitalSignature", notNullValue())
                .extract().response();

        String data = response.path("dataResponseObject[0].data");
        String gml = new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
        assertEquals(dataset.getCurrentVersion().getGml(), gml, "Get must serve the exact published GML bytes");
    }

    @Test
    void getByDataReferenceReturnsExactlyThatDataset() {
        S124Dataset first = committedDataset(55.5, 11.1);
        committedDataset(57.5, 10.1);

        secomGiven()
                .queryParam("dataReference", first.getUuid().toString())
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1))
                .body("pagination.totalItems", equalTo(1));

        // Unknown dataReference -> 404 ("if no data is returned, the response is one of the
        // HTTP codes 400, 403 or 404", design §6.3.5)
        secomGiven()
                .queryParam("dataReference", "00000000-0000-8000-8000-000000000000")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(404);
    }

    @Test
    void dataReferenceDoesNotBypassTheOtherFilters() {
        S124Dataset dataset = committedDataset(55.5, 11.1);

        // dataReference + geometry that does not cover the dataset -> excluded (404, no data)
        secomGiven()
                .queryParam("dataReference", dataset.getUuid().toString())
                .queryParam("geometry", "POLYGON((-40 -40, -39 -40, -39 -39, -40 -39, -40 -40))")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(404);

        // dataReference + validity window that ends before the dataset becomes valid -> excluded
        secomGiven()
                .queryParam("dataReference", dataset.getUuid().toString())
                .queryParam("validTo", "1970-01-02T00:00:00")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(404);

        // dataReference + filters that do match -> returned
        secomGiven()
                .queryParam("dataReference", dataset.getUuid().toString())
                .queryParam("geometry", "POLYGON((5 50, 15 50, 15 60, 5 60, 5 50))")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1));
    }

    @Test
    void getFiltersByWktGeometry() {
        committedDataset(55.5, 11.1);

        secomGiven()
                .queryParam("geometry", "POLYGON((5 50, 15 50, 15 60, 5 60, 5 50))")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1));

        secomGiven()
                .queryParam("geometry", "POLYGON((-40 -40, -39 -40, -39 -39, -40 -39, -40 -40))")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(404);

        secomGiven()
                .queryParam("geometry", "NOT-WKT")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(400);
    }

    @Test
    void getPaginatesOneBased() {
        committedDataset(55.5, 11.1);
        committedDataset(56.5, 12.1);
        committedDataset(57.5, 13.1);

        secomGiven()
                .queryParam("page", 1)
                .queryParam("pageSize", 2)
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(2))
                .body("pagination.totalItems", equalTo(3))
                .body("pagination.maxItemsPerPage", equalTo(2));

        secomGiven()
                .queryParam("page", 2)
                .queryParam("pageSize", 2)
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1));

        // Without an explicit pageSize the metadata must advertise the applied default (100),
        // so clients can detect truncation when totalItems exceeds it
        secomGiven()
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("pagination.totalItems", equalTo(3))
                .body("pagination.maxItemsPerPage", equalTo(100));

        secomGiven()
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .body("pagination.maxItemsPerPage", equalTo(100));

        // Every item on a page is a signed exchange-set build, so a larger pageSize is capped at
        // the default - and the metadata advertises the cap, not the size asked for
        secomGiven()
                .queryParam("pageSize", 1000)
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(3))
                .body("pagination.totalItems", equalTo(3))
                .body("pagination.maxItemsPerPage", equalTo(100));

        secomGiven()
                .queryParam("pageSize", 1000)
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .body("summaryObject", hasSize(3))
                .body("pagination.maxItemsPerPage", equalTo(100));
    }

    @Test
    void unsupportedGetVariantsAreRejected() {
        // containerType NONE (wire value 2) is invalid
        secomGiven()
                .queryParam("containerType", "2")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(400);

        // UNLOCODE filtering was deliberately dropped (22 MB lookup table for an unused feature)
        secomGiven()
                .queryParam("unlocode", "DKCPH")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(400);

        // Unknown product versions match nothing -> 404 like any other empty Get result
        secomGiven()
                .queryParam("productVersion", "9.9.9")
                .get("/api/secom/v2/object")
                .then()
                .statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // GetSummary
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getSummaryDescribesTheDataset() {
        S124Dataset dataset = committedDataset(55.5, 11.1);
        String shortId = Tx.requiringNew().call(() ->
                datasetService.findByUuid(dataset.getUuid()).getMessage().getShortId());

        // The info_* fields are defined against the S-124 dataset content (design §6.4.5):
        // info_identifier = gml:id (the message short ID), info_name = datasetFileIdentifier,
        // info_description = datasetTitle. Default container = exchange set (wire value 1),
        // and info_size is the size of the payload a Get would return.
        secomGiven()
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .body("summaryObject", hasSize(1))
                .body("summaryObject[0].dataReference", equalTo(dataset.getUuid().toString()))
                .body("summaryObject[0].dataProductType", equalTo("S-124"))
                .body("summaryObject[0].dataProtection", equalTo(false))
                .body("summaryObject[0].dataCompression", equalTo(false))
                .body("summaryObject[0].containerType", equalTo(1))
                .body("summaryObject[0].info_identifier", equalTo(shortId))
                // the S-100 Part 17 clause 17-4.3 file identifier: 124 + producer code + alphanumeric short id + .GML
                .body("summaryObject[0].info_name", equalTo("124DK00" + shortId.replaceAll("[^A-Za-z0-9]", "") + ".GML"))
                .body("summaryObject[0].info_description", equalTo("Niord S-124 Dataset"))
                .body("summaryObject[0].info_status", equalTo("ACTIVE"))
                .body("summaryObject[0].info_productVersion", equalTo("2.0.0"))
                .body("summaryObject[0].info_size", greaterThan(0))
                .body("pagination.totalItems", equalTo(1));

        // For the bare-dataset container the size is the exact stored GML length
        secomGiven()
                .queryParam("containerType", "0")
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .body("summaryObject[0].containerType", equalTo(0))
                .body("summaryObject[0].info_size", equalTo(dataset.getCurrentVersion().gmlBytes().length));
    }

    @Test
    void getSummaryAndGetAgreeOnTheDataReference() {
        S124Dataset dataset = committedDataset(55.5, 11.1);

        // The summary's dataReference must be usable for a follow-up Get (a random
        // per-request UUID would break exactly this round-trip)
        String dataReference = secomGiven()
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .extract().path("summaryObject[0].dataReference");

        assertEquals(dataset.getUuid().toString(), dataReference);

        secomGiven()
                .queryParam("dataReference", dataReference)
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1));
    }

    @Test
    void cancelledDatasetsRequireATemporalExtentOrDataReference() {
        S124Dataset dataset = committedDataset(55.5, 11.1);
        Tx.requiringNew().run(() -> {
            try {
                messageService.updateStatus(dataset.getMessage().getUid(), org.niord.model.message.Status.CANCELLED);
                datasetService.onMessageStatusChanged(dataset.getMessage().getUid());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Without a temporal extent, only active warnings are served (spec 4.1.3/4.1.4);
        // an empty Get result is a 404 (design §6.3.5), an empty summary list is fine
        secomGiven()
                .get("/api/secom/v2/object")
                .then()
                .statusCode(404);
        secomGiven()
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .body("summaryObject", hasSize(0));

        // With a temporal extent covering the dataset, the cancelled dataset is included
        secomGiven()
                .queryParam("validFrom", "1970-01-01T00:00:00")
                .get("/api/secom/v2/object/summary")
                .then()
                .statusCode(200)
                .body("summaryObject", hasSize(1))
                .body("summaryObject[0].info_status", equalTo("CANCELLED"));

        // A known dataReference stays individually retrievable (use case 2)
        secomGiven()
                .queryParam("dataReference", dataset.getUuid().toString())
                .get("/api/secom/v2/object")
                .then()
                .statusCode(200)
                .body("dataResponseObject", hasSize(1));
    }

    // ------------------------------------------------------------------------------------------- //
    // Disabled-by-default admin visibility of the store
    // ------------------------------------------------------------------------------------------- //

    @Test
    void datasetGmlUnmarshalsAsXml() {
        S124Dataset dataset = committedDataset(55.5, 11.1);
        assertNotNull(dataset);
        String gml = dataset.getCurrentVersion().getGml();
        assertTrue(gml.startsWith("<?xml"), "published GML is a full XML document");
        assertTrue(gml.contains("http://www.iho.int/S124/gml/2.0"),
                "stored GML is an S-124 v2.0.0 dataset");
    }
}
