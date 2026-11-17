/*
 * Copyright 2016 Danish Maritime Authority.
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

import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.repo.RepositoryService;
import org.niord.core.util.WebUtils;
import org.niord.core.web.NiordWebException;
import org.slf4j.Logger;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import jakarta.inject.Inject;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * May be used as a super class for REST services that handles the uploading of a batch-job file
 * and subsequent execution of the associated batch job.
 * <p>
 * Each plain form field of the upload becomes a job property. A field named {@value BootstrapRun#PROPERTY}, the
 * bootstrap runner's own token (docs/code-data-config-plan.md §7.2), is refused with 400 before any job starts, as
 * an archive item carrying it is ({@code BootstrapArchive}): this covers every upload endpoint, the six imports the
 * bootstrap runs among them.
 * <p>
 * No uploaded file is held in memory: each reading of a file (the JSON check, the endpoint's check, the job start)
 * opens a stream of its own from the uploaded part, from its start, and closes it; a large part stays where the
 * multipart resolver put it, on disk. A file whose name ends in ".json" and that is not JSON is refused with 400
 * before any job starts, as is a file the endpoint's own {@link #checkBatchJob} refuses with a
 * {@link NiordWebException}.
 */
public abstract class AbstractBatchableRestService {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(AbstractBatchableRestService.class);

    @Inject
    BatchService batchService;

    @Inject
    RepositoryService repositoryService;

    /**
     * Starts the execution of a batch job from an uploaded file
     *
     * @param request the multi-part form data input request
     * @param batchJobName the name of the batch job
     * @return a status
     * @throws NiordWebException 400 when a form field is named {@value BootstrapRun#PROPERTY}, when a file named
     *                           ".json" is not JSON, or when the endpoint's own check refuses a file
     */
    protected String executeBatchJobFromUploadedFile(MultipartHttpServletRequest request, String batchJobName) throws Exception {

        // Initialise the form parsing parameters
        final Map<String, Object> formParams = WebUtils.getMultipartFormParams(request);
        if (formParams.containsKey(BootstrapRun.PROPERTY)) {
            throw new NiordWebException("The field '" + BootstrapRun.PROPERTY
                    + "' is the bootstrap runner's own; an upload may not carry it", 400);
        }

        // The uploaded parts themselves: each reading below opens a stream of its own from the part. A JSON file
        // that does not parse is refused before any job starts
        final Map<String, MultipartFile> formFiles = new LinkedHashMap<>(WebUtils.getMultipartFileMap(request));
        for (Map.Entry<String, MultipartFile> file : formFiles.entrySet()) {
            checkJson(file.getKey(), file.getValue());
        }

        return executeBatchJob(formParams, formFiles, batchJobName);
    }

    /**
     * Refuses a file whose name ends in ".json" when it is not JSON: its job would only fail in its reader. The
     * file's first value is read token by token to its end from a stream of its own, so neither a tree nor a copy
     * of the file is built, and no string of it is kept as text.
     *
     * @throws NiordWebException 400 naming the file and the parse error, also when its bytes are no character in
     *                           the encoding the parser detected
     * @throws IOException       when the uploaded part cannot be read
     */
    private static void checkJson(String fileName, MultipartFile file) throws IOException {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return;
        }
        String reason;
        try (InputStream in = file.getInputStream();
             JsonParser parser = JsonMapper.builderWithJackson2Defaults().build().createParser(in)) {
            JsonToken first = parser.nextToken();
            if (first != null) {
                parser.skipChildren();
                if (first == JsonToken.VALUE_STRING) {
                    // The parser reads a string only when asked for it, and skipChildren does not read a scalar.
                    // The next token skips a file that is one string to the string's end, checking it without
                    // keeping its text (finishing the token would keep it whole); so it also reads what follows
                    // the string, and a stray closing bracket after it is refused
                    parser.nextToken();
                }
                return;
            }
            reason = "the file is empty";
        } catch (JacksonIOException e) {
            if (!(e.getCause() instanceof CharConversionException)) {
                // The part could not be read: a fault of the server, not of the file
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
            // Bytes that are no character in the encoding the parser detected (a UTF-32 reader throws this): a
            // fault of the file
            reason = e.getOriginalMessage();
        } catch (JacksonException e) {
            reason = e.getOriginalMessage();
        }
        throw new NiordWebException("The file " + fileName + " is not valid JSON: " + reason, 400);
    }

    /**
     * Shared batch-job execution for the uploaded files. Every file is checked before any job starts, and a
     * {@link NiordWebException} from a check is the answer, with its own status. Another failure of a file is
     * reported in the answer's text and leaves the file out. Each file has its own copy of the form parameters:
     * its check may change them, and its job is started with the copy its check left. The check and the start
     * each read the file through a stream of their own, opened from the part and closed after: a check may read
     * its stream to the end and close it.
     */
    private String executeBatchJob(Map<String, Object> formParams, Map<String, MultipartFile> formFiles, String batchJobName) {
        final StringBuilder txt = new StringBuilder();

        // Check every file, each from a stream of its own and with its own copy of the parameters
        final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        formFiles.forEach((fileName, file) -> {
            Map<String, Object> params = new HashMap<>(formParams);
            try (InputStream in = file.getInputStream()) {
                checkBatchJob(batchJobName, fileName, in, params);
                accepted.put(fileName, params);
            } catch (NiordWebException e) {
                throw e;
            } catch (Exception e) {
                appendError(txt, batchJobName, fileName, e);
            }
        });

        // Start the batch job for each accepted file, from a stream of its own
        accepted.forEach((fileName, params) -> {
            try (InputStream in = formFiles.get(fileName).getInputStream()) {
                startBatchJob(batchJobName, fileName, in, params, txt);
            } catch (Exception e) {
                appendError(txt, batchJobName, fileName, e);
            }
        });

        return txt.toString();
    }

    private static void appendError(StringBuilder txt, String batchJobName, String fileName, Exception e) {
        String errorMsg = "Error executing batch job " + batchJobName + " with file " + fileName + ": " + e.getMessage();
        log.error(errorMsg, e);
        txt.append(errorMsg);
    }

    /**
     * Allows sub-classes to validate the uploaded file and parameters.
     * The method should throw an exception to prevent the batch job from running: a {@link NiordWebException} is
     * the answer to the upload, with its status, and nothing starts; another exception is reported in the answer's
     * text and leaves only this file out. The stream is the file's own, from its start, and may be read to its end
     * and closed; the job is started with another.
     *
     * @param batchJobName the batch job name
     * @param fileName the file name
     * @param inputStream the file contents
     * @param params the non-file form parameters, this file's own copy, which its job is started with
     */
    @SuppressWarnings("unused")
    protected void checkBatchJob(String batchJobName, String fileName, InputStream inputStream, Map<String, Object> params) throws Exception {
        // By default, we accept the batch job
    }

    /**
     * Starts the batch job on the given file
     *
     * @param batchJobName the batch job name
     * @param fileName the file name
     * @param inputStream the file contents
     * @param params the non-file form parameters
     * @param txt a log of the import
     */
    private void startBatchJob(String batchJobName, String fileName, InputStream inputStream, Map<String, Object> params, StringBuilder txt) throws Exception {
        batchService.startBatchJobWithDataFile(
                batchJobName,
                inputStream,
                fileName,
                params);

        String message = "Started batch job '" + batchJobName + "' with file " + fileName;
        log.info(message);
        txt.append(message);
    }

}
