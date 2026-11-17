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
package org.niord.core.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.web.NiordWebException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import com.sun.management.ThreadMXBean;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The upload of a batch-job file ({@link AbstractBatchableRestService}): a form field named
 * {@value BootstrapRun#PROPERTY}, the bootstrap runner's own token (docs/code-data-config-plan.md §7.2), is refused
 * with 400 before any job starts, so no upload can put a copied token into a job's properties (the review of PR #188).
 * Every upload endpoint, the six imports the bootstrap runs among them, goes through this one method. No upload is
 * held in memory: the JSON check, the endpoint's check and the job start each open a stream of their own from the
 * uploaded part.
 */
class AbstractBatchableRestServiceTest {

    /** An upload endpoint as the REST services are, with nothing of its own */
    static class Upload extends AbstractBatchableRestService {
        String upload(MockMultipartHttpServletRequest request) throws Exception {
            return executeBatchJobFromUploadedFile(request, "chart-import");
        }
    }

    Upload upload;
    BatchService batchService;

    @BeforeEach
    void setUp() {
        upload = new Upload();
        batchService = mock(BatchService.class);
        upload.batchService = batchService;
    }

    private static MockMultipartHttpServletRequest request(Map<String, String> fields) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        MockMultipartFile file = new MockMultipartFile("file", "charts.json", "application/json",
                "[]".getBytes(StandardCharsets.UTF_8));
        request.addFile(file);
        fields.forEach((name, value) -> request.addPart(new MockPart(name, value.getBytes(StandardCharsets.UTF_8))));
        return request;
    }

    @Test
    void anUploadCarryingTheRunnersTokenIsRefusedAndStartsNoJob() {
        NiordWebException e = assertThrows(NiordWebException.class, () -> upload.upload(
                request(Map.of(BootstrapRun.PROPERTY, "a-live-run/1", "other", "x"))));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getMessage().contains("'" + BootstrapRun.PROPERTY + "'"), e.getMessage());
        verifyNoInteractions(batchService);
    }

    /** A ".json" file that is not JSON is refused with 400, naming the file, and no job starts */
    @Test
    void aJsonFileThatIsNotJsonIsRefusedAndStartsNoJob() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "charts.JSON", "application/json",
                "{not json".getBytes(StandardCharsets.UTF_8)));

        NiordWebException e = assertThrows(NiordWebException.class, () -> upload.upload(request));

        assertEquals(400, e.getStatusCode().value());
        assertTrue(e.getReason().contains("charts.JSON"), e.getReason());
        verifyNoInteractions(batchService);
    }

    /**
     * The JSON check refuses an unclosed value, an empty file and a lone closing bracket, each naming the file; a
     * file that is one string is read to the string's end, so an unclosed string or a bad escape is refused too
     */
    @Test
    void aJsonFileThatIsCutOffOrEmptyIsRefused() {
        for (String content : new String[] { "[1, 2", "{\"a\": [1, {\"b\": 2}]", "", "  \n ", "]",
                "\"text", "\"a\\qb\"" }) {
            MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
            request.addFile(new MockMultipartFile("file", "charts.json", "application/json",
                    content.getBytes(StandardCharsets.UTF_8)));

            NiordWebException e = assertThrows(NiordWebException.class, () -> upload.upload(request), content);

            assertEquals(400, e.getStatusCode().value(), content);
            assertTrue(e.getReason().startsWith("The file charts.json is not valid JSON: "), e.getReason());
        }
        assertTrue(assertThrows(NiordWebException.class, () -> upload.upload(jsonUpload(" ")))
                .getReason().endsWith("the file is empty"));
        verifyNoInteractions(batchService);
    }

    /** The JSON check accepts any whole JSON value, nested or a lone scalar, and starts the job */
    @Test
    void aJsonFileOfAnyWholeValueIsAccepted() throws Exception {
        for (String content : new String[] { "[]", "{\"a\": [1, {\"b\": [true, null, \"x\"]}]}", "42", "\"text\"" }) {
            String answer = upload.upload(jsonUpload(content));
            assertTrue(answer.contains("Started batch job 'chart-import' with file charts.json"), content);
        }
    }

    /**
     * A file the parser reads as UTF-32 (it starts with three zero bytes) and that holds bytes that are no UTF-32
     * character, or ends in the middle of one, is a fault of the file: 400 naming it, not a fault of the server
     */
    @Test
    void aJsonFileOfBytesThatAreNoCharacterIsRefusedWith400() {
        byte[][] contents = {
                { 0, 0, 0, 0x5B, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF },  // a code point above U+10FFFF
                { 0, 0, 0, 0x5B, 0, 0 },                                         // cut in the middle of a character
        };
        for (byte[] content : contents) {
            MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
            request.addFile(new MockMultipartFile("file", "data.json", "application/json", content));

            NiordWebException e = assertThrows(NiordWebException.class, () -> upload.upload(request));

            assertEquals(400, e.getStatusCode().value());
            assertTrue(e.getReason().startsWith("The file data.json is not valid JSON: "), e.getReason());
        }
        verifyNoInteractions(batchService);
    }

    /** A part whose stream fails while the JSON check reads it is a fault of the server: the IOException is the answer */
    @Test
    void aJsonPartThatCannotBeReadIsNotTakenForAFileThatIsNotJson() {
        StreamPart part = new StreamPart("charts.json", () -> new InputStream() {
            private final InputStream start = new ByteArrayInputStream("[1, ".getBytes(StandardCharsets.UTF_8));

            @Override
            public int read() throws IOException {
                int b = start.read();
                if (b < 0) {
                    throw new IOException("the part's file is gone");
                }
                return b;
            }
        });
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(part);

        IOException e = assertThrows(IOException.class, () -> upload.upload(request));

        assertEquals("the part's file is gone", e.getMessage());
        verifyNoInteractions(batchService);
    }

    /**
     * A file that is one string is checked as it streams by, not held: the upload of one of 20 million characters
     * allocates less on its thread than a quarter of the file's size (holding the string as text takes twice the
     * size), and its job starts
     */
    @Test
    void aJsonFileThatIsOneStringIsCheckedWithoutHoldingIt() throws Exception {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(),
                "this JVM does not count a thread's allocations");
        upload.upload(oneStringUpload(1_000));   // the classes and the mapper's set-up are not counted below
        long length = 20_000_000;
        MockMultipartHttpServletRequest request = oneStringUpload(length);

        long before = threads.getCurrentThreadAllocatedBytes();
        String answer = upload.upload(request);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(answer.contains("Started batch job 'chart-import' with file long.json"), answer);
        assertTrue(allocated < length / 4, "the check allocated " + allocated + " bytes for a file of " + length);
    }

    private static MockMultipartHttpServletRequest oneStringUpload(long length) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new StreamPart("long.json", () -> oneString(length)));
        return request;
    }

    /** A stream of one JSON string of the given number of characters, made as it is read */
    private static InputStream oneString(long length) {
        return new InputStream() {
            private final long end = length + 2;
            private long position;

            @Override
            public int read() {
                if (position >= end) {
                    return -1;
                }
                long at = position++;
                return at == 0 || at == end - 1 ? '"' : 'a';
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                if (position >= end) {
                    return -1;
                }
                int n = (int) Math.min(count, end - position);
                for (int i = 0; i < n; i++) {
                    buffer[offset + i] = (byte) read();
                }
                return n;
            }
        };
    }

    /** An uploaded part read only through the streams it opens, each from the supplier; reading it whole fails */
    static final class StreamPart implements MultipartFile {
        final String name;
        final Supplier<InputStream> streams;

        StreamPart(String name, Supplier<InputStream> streams) {
            this.name = name;
            this.streams = streams;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "application/json";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return -1;
        }

        @Override
        public byte[] getBytes() {
            throw new AssertionError("the upload " + name + " was read whole into memory");
        }

        @Override
        public InputStream getInputStream() {
            return streams.get();
        }

        @Override
        public Resource getResource() {
            throw new AssertionError("the upload " + name + " was read through its resource");
        }

        @Override
        public void transferTo(File dest) {
            throw new AssertionError("the upload " + name + " was copied whole");
        }

        @Override
        public void transferTo(Path dest) {
            throw new AssertionError("the upload " + name + " was copied whole");
        }
    }

    private static MockMultipartHttpServletRequest jsonUpload(String content) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(new MockMultipartFile("file", "charts.json", "application/json",
                content.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    /**
     * The endpoint's check reads the file to its end and closes it, and the job is still given the whole file (the
     * message archive import failed on every upload while the check and the job shared one stream); a refusal of the
     * check with a NiordWebException is the answer, and no job starts
     */
    @Test
    void theCheckAndTheStartEachReadTheWholeFileAndARefusalIsTheAnswer() throws Exception {
        java.util.List<String> checked = new java.util.ArrayList<>();
        Upload reading = new Upload() {
            @Override
            protected void checkBatchJob(String batchJobName, String fileName, InputStream inputStream,
                                         Map<String, Object> params) throws Exception {
                try (InputStream in = inputStream) {
                    checked.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                if (params.containsKey("refuse")) {
                    throw new NiordWebException("refused", 400);
                }
            }
        };
        reading.batchService = batchService;

        reading.upload(request(Map.of()));
        assertEquals(java.util.List.of("[]"), checked);
        org.mockito.ArgumentCaptor<InputStream> started = org.mockito.ArgumentCaptor.forClass(InputStream.class);
        verify(batchService).startBatchJobWithDataFile(eq("chart-import"), started.capture(), eq("charts.json"), any());
        assertEquals("[]", new String(started.getValue().readAllBytes(), StandardCharsets.UTF_8),
                "the job is given the whole file");

        BatchService refusedService = mock(BatchService.class);
        reading.batchService = refusedService;
        NiordWebException e = assertThrows(NiordWebException.class, () -> reading.upload(request(Map.of("refuse", "x"))));
        assertEquals(400, e.getStatusCode().value());
        verifyNoInteractions(refusedService);
    }

    /**
     * No upload is held in memory: each reading of a file opens a stream of its own from the uploaded part and closes
     * it, and the job is given the part's own stream, unread. A ".json" file is read three times (the JSON check, the
     * endpoint's check, the job start), any other file twice; a part read whole (getBytes, getResource, transferTo)
     * fails the test.
     */
    @Test
    void eachReadingOpensAStreamOfItsOwnFromThePartAndNothingHoldsTheUploadInMemory() throws Exception {
        List<InputStream> checked = new ArrayList<>();
        Upload reading = new Upload() {
            @Override
            protected void checkBatchJob(String batchJobName, String fileName, InputStream inputStream,
                                         Map<String, Object> params) throws Exception {
                checked.add(inputStream);
                try (InputStream in = inputStream) {
                    in.readAllBytes();
                }
            }
        };
        reading.batchService = batchService;
        CountingPart json = new CountingPart("charts.json", "[{\"a\": 1}, \"text\"]");
        CountingPart archive = new CountingPart("charts.zip", "not read as JSON");
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.addFile(json);
        request.addFile(archive);

        String answer = reading.upload(request);

        assertTrue(answer.contains("with file charts.json") && answer.contains("with file charts.zip"), answer);
        assertEquals(3, json.opened.size(), "the JSON check, the endpoint's check and the job start");
        assertEquals(2, archive.opened.size(), "the endpoint's check and the job start");
        for (CountingPart part : List.of(json, archive)) {
            for (CountingStream stream : part.opened) {
                assertTrue(stream.closed, "every stream opened from " + part.name + " is closed");
            }
        }
        assertTrue(holds(checked, json.opened.get(1)) && holds(checked, archive.opened.get(0)),
                "the endpoint's check reads a stream opened from the part");
        org.mockito.ArgumentCaptor<InputStream> started = org.mockito.ArgumentCaptor.forClass(InputStream.class);
        verify(batchService, org.mockito.Mockito.times(2))
                .startBatchJobWithDataFile(eq("chart-import"), started.capture(), anyString(), any());
        assertTrue(holds(started.getAllValues(), json.opened.get(2)) && holds(started.getAllValues(), archive.opened.get(1)),
                "the job is given the part's own stream");
        assertEquals(0, json.opened.get(2).position(), "nothing of the job's stream is read before the job has it");
        assertEquals(0, archive.opened.get(1).position(), "nothing of the job's stream is read before the job has it");
    }

    /** Whether the list holds this very stream (not an equal copy) */
    private static boolean holds(List<InputStream> streams, InputStream stream) {
        return streams.stream().anyMatch(s -> s == stream);
    }

    /** A stream that says whether it was closed and how far it was read */
    static final class CountingStream extends ByteArrayInputStream {
        boolean closed;

        CountingStream(byte[] content) {
            super(content);
        }

        int position() {
            return pos;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** An uploaded part that keeps every stream opened from it and refuses each way of reading it whole */
    static final class CountingPart implements MultipartFile {
        final String name;
        final byte[] content;
        final List<CountingStream> opened = new ArrayList<>();

        CountingPart(String name, String content) {
            this.name = name;
            this.content = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            throw new AssertionError("the upload " + name + " was read whole into memory");
        }

        @Override
        public InputStream getInputStream() {
            CountingStream stream = new CountingStream(content);
            opened.add(stream);
            return stream;
        }

        @Override
        public Resource getResource() {
            throw new AssertionError("the upload " + name + " was read through its resource");
        }

        @Override
        public void transferTo(File dest) {
            throw new AssertionError("the upload " + name + " was copied whole");
        }

        @Override
        public void transferTo(Path dest) {
            throw new AssertionError("the upload " + name + " was copied whole");
        }
    }

    @Test
    void anUploadWithoutTheTokenStartsTheJobWithItsFields() throws Exception {
        String answer = upload.upload(request(Map.of("other", "x")));

        assertTrue(answer.contains("Started batch job 'chart-import' with file charts.json"), answer);
        verify(batchService).startBatchJobWithDataFile(eq("chart-import"), any(InputStream.class), anyString(),
                argThat(properties -> "x".equals(properties.get("other"))
                        && !properties.containsKey(BootstrapRun.PROPERTY)));
    }
}
