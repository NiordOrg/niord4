/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.script;

import org.springframework.stereotype.Service;

import freemarker.cache.TemplateLoader;
import freemarker.core.Environment;
import freemarker.template.AttemptExceptionReporter;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateNotFoundException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.script.directive.MultiResourceBundleModel;
import org.niord.core.script.pdf.HtmlToPdfRenderer;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.web.map.MapImageCredit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.niord.core.settings.Setting.Type.Boolean;
import static org.niord.core.settings.Setting.Type.Password;

/**
 * Main interface for accessing and processing Freemarker report templates
 */
@Service
public class FmTemplateService extends BaseService {

    public static final String LANGUAGE_PROPERTY    = "language";
    public static final String LANGUAGES_PROPERTY   = "languages";
    public static final String BUNDLE_PROPERTY      = "text";
    public static final String TIME_ZONE_PROPERTY   = "timeZone";

    public enum ProcessFormat { TEXT, PDF }

        @Setting(value = "pdfEncryptionEnabled", description = "Whether PDF reports should be encrypted or not. Read at start-up.",
            defaultValue = "false", type = Boolean)
    Boolean pdfEncryptionEnabled;

        // No password in the code (the owner's ruling of 2026-11-04): a fresh database holds none until a
        // sysadmin sets one, and encryption is off until pdfEncryptionEnabled is set.
        @Setting(value = "pdfEncryptionPassword", description = "The PDF reports encryption password. Read at start-up.",
            defaultValue = "", type = Password)
    String pdfEncryptionPassword;

    private final DictionaryService dictionaryService;

    private final DomainService domainService;

    private final ScriptResourceService resourceService;

    private final NiordApp app;

    private final MapImageCredit mapImageCredit;

    FmTemplateService(DictionaryService dictionaryService, DomainService domainService, ScriptResourceService resourceService, NiordApp app, MapImageCredit mapImageCredit) {
        this.dictionaryService = dictionaryService;
        this.domainService = domainService;
        this.resourceService = resourceService;
        this.app = app;
        this.mapImageCredit = mapImageCredit;
    }

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FmTemplateService.class);

    /************************************/
    /** Freemarker Template execution  **/
    /************************************/

    /**
     * If enabled, returns the PDF encryption password to use whilst generating PDF reports.
     * Otherwise, returns null.
     * @return a PDF encryption
     */
    private String getPDFEncryptionPassword() {
        if (pdfEncryptionEnabled != null && pdfEncryptionEnabled && StringUtils.isNotBlank(pdfEncryptionPassword)) {
            return pdfEncryptionPassword;
        }
        return null;
    }

    /**
     * Create a new Freemarker template builder.
     * The builder must be populated and processed within the current transaction.
     * @return a new Freemarker template builder
     */
    public FmTemplateBuilder newFmTemplateBuilder() {
        return new FmTemplateBuilder(this);
    }

    /**
     * Constructs a Freemarker Template based on the given template builder
     * @param templateBuilder the template builder to construct a template from
     * @return the Freemarker Template
     */
    private Template constructFmTemplate(FmTemplateBuilder templateBuilder) throws IOException {

        // Standard data properties
        templateBuilder.getData().put("baseUri", app.getBaseUri());
        templateBuilder.getData().put("adminAppUrl", app.getMessageLinkBaseUri());
        templateBuilder.getData().put("country", app.getCountry());
        templateBuilder.getData().put("defaultLanguage", app.getDefaultLanguage());
        if (!templateBuilder.getData().containsKey("draft")) {
            templateBuilder.getData().put("draft", false);
        }

        // Load the resource bundle(s) with the given language and name, and save it in the "text" data property
        // If a language is specified, only add the resource bundle for that language.
        // If no language is specified, add the resource bundles for all supported languages.
        if (templateBuilder.getDictionaryNames() != null && templateBuilder.getDictionaryNames().length > 0) {

            List<String> languages = new ArrayList<>();
            if (StringUtils.isNotBlank(templateBuilder.getLanguage())) {
                // Add resource bundle for selected language
                languages.add(app.getLanguage(templateBuilder.getLanguage()));

                String  language = app.getLanguage(templateBuilder.getLanguage());
                templateBuilder.getData().put(LANGUAGE_PROPERTY, language);
                templateBuilder.getData().put(LANGUAGES_PROPERTY, app.getLanguages(language));
            } else {
                // Add resource bundles for all supported languages
                languages.addAll(Arrays.asList(app.getLanguages()));

                templateBuilder.getData().put(LANGUAGES_PROPERTY, app.getLanguages());
            }

            List<ResourceBundle> bundles = languages.stream()
                    .map(lang -> dictionaryService.getDictionariesAsResourceBundle(templateBuilder.getDictionaryNames(), lang))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (!bundles.isEmpty()) {
                MultiResourceBundleModel resourceBundleModel =  new MultiResourceBundleModel(
                        bundles,
                        new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_31).build(), app.getDefaultLanguage());
                templateBuilder.getData().put(BUNDLE_PROPERTY, resourceBundleModel);
            }
        }

        // The zone dates are printed in. A caller that already knows which zone
        // the document belongs to keeps it: a publication's dates belong to the
        // domain that OWNS it, and the request's current domain is not always
        // that one -- an unattended release has no request domain at all and
        // would otherwise print in whatever zone the container runs in.
        // Otherwise the request's domain answers, as it always has.
        if (templateBuilder.getData().get(TIME_ZONE_PROPERTY) == null) {
            Domain domain = domainService.currentDomain();
            String timeZone = (domain != null && StringUtils.isNotBlank(domain.getTimeZone()))
                    ? domain.getTimeZone()
                    : TimeZone.getDefault().getID();
            templateBuilder.getData().put(TIME_ZONE_PROPERTY, timeZone);
        }

        Locale locale = app.getLocale(templateBuilder.getLanguage());

        // Create the Freemarker template loader and configuration
        FmTemplateLoader templateLoader = new FmTemplateLoader(resourceService, app.getDefaultLanguage());
        Configuration cfg = newConfiguration(templateLoader);

        return cfg.getTemplate(templateBuilder.getTemplatePath(), locale, "UTF-8");
    }

    /**
     * Returns the Freemarker configuration every template is rendered with.
     * <p>
     * A template is data an operator can edit, so it reaches no Java class beyond what the data model hands it:
     * {@code ?new} admits only the directives of {@code org.niord.core.script.directive}
     * ({@link DirectiveClassResolver}), and {@code ?api} is off. An error a template recovers from in an
     * {@code <#attempt>} block is logged as a warning, not as an error: the template has handled it (the draft print
     * names a language without a {@code lang.<code>} text by its code, TMPL20).
     *
     * @param templateLoader the template loader
     * @return the Freemarker configuration
     */
    static Configuration newConfiguration(TemplateLoader templateLoader) {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setLocalizedLookup(true);
        cfg.setTemplateLoader(templateLoader);
        cfg.setObjectWrapper(new NiordAppObjectWrapper(cfg.getIncompatibleImprovements()));
        cfg.setNewBuiltinClassResolver(DirectiveClassResolver.INSTANCE);
        cfg.setAPIBuiltinEnabled(false);
        cfg.setAttemptExceptionReporter(AttemptExceptionReporter.LOG_WARN_REPORTER);
        return cfg;
    }

    /** The opening tag of an HTML document's body */
    private static final Pattern BODY_START = Pattern.compile("<body\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * Returns the HTML with a line of text at the top of the document: right after the opening {@code <body>} tag, or
     * before everything when there is none. The line is made here and not by a template, because the template a
     * deployment renders is its database row, which a change in the repository never reaches: so every document gets
     * it, whichever template it was made from (the bootstrap's, an imported or edited row, a country's). The text is
     * escaped.
     *
     * @param html   the document
     * @param notice the text of the line; blank leaves the document as it is
     * @return the document with the line
     */
    public static String withTopNotice(String html, String notice) {
        if (StringUtils.isBlank(notice) || html == null) {
            return html;
        }
        String line = "<p class=\"top-notice\" style=\"border: 1px solid #a00; color: #a00; padding: 4px 8px;"
                + " margin: 0 0 8px 0\">" + StringEscapeUtils.escapeHtml4(notice) + "</p>";
        Matcher body = BODY_START.matcher(html);
        return body.find()
                ? html.substring(0, body.end()) + line + html.substring(body.end())
                : line + html;
    }

    /** Returns the base URI used to access this application */
    private String getBaseUri() {
        return app.getBaseUri();
    }

    /************************************/
    /** Freemarker Template Builder    **/
    /************************************/

    /**
     * Used by the client for building a new Freemarker Template.
     *
     * Initialize the builder by calling FmService.newFmTemplateBuilder()
     */
    @SuppressWarnings("unused")
    public static class FmTemplateBuilder {

        private static Logger log = LoggerFactory.getLogger(FmTemplateBuilder.class);

        String templatePath;
        Map<String, Object> data;
        String language;
        String[] dictionaryNames;
        String timeZone;
        String topNotice;
        FmTemplateService templateService;

        /**
         * Should only be initialized from the FmTemplateService.newFmTemplateBuilder() call
         */
        private FmTemplateBuilder(FmTemplateService templateService) {
            this.templateService = Objects.requireNonNull(templateService);
        }

        /**
         * Process the Freemarker template defined by the builder
         * @return the generated contents
         */
        public String process() throws IOException, TemplateException {

            // Check parameters
            if (StringUtils.isBlank(templatePath)) {
                throw new IllegalArgumentException("Template path must be specified");
            }
            if (data == null) {
                data = new HashMap<>();
            }

            // Process the Freemarker template
            try {
                Template fmTemplate = templateService.constructFmTemplate(this);

                StringWriter result = new StringWriter();
                Environment environment = fmTemplate.createProcessingEnvironment(data, result);
                if (timeZone != null) {
                    environment.setTimeZone(TimeZone.getTimeZone(timeZone));
                }
                environment.process();

                return result.toString();
            } catch (TemplateNotFoundException e) {
                // A name that names no template: the caller gets the exception and decides what it is (a request's
                // unknown template name answers 404, a report or mail that needs it fails and logs it there)
                log.debug("No freemarker template " + templatePath + ": " + e.getMessage());
                throw e;
            } catch (IOException e) {
                log.error("Failed loading freemarker template " + templatePath, e);
                throw e;
            } catch (TemplateException e) {
                log.error("Failed executing freemarker template " + templatePath, e);
                throw e;
            }
        }

        /**
         * Generates text or PDF based on the Freemarker HTML template and streams it to the output stream
         *
         * @param format the output format
         * @param out the output stream
         */
        public void process(ProcessFormat format, OutputStream out) throws Exception {

            long t0 = System.currentTimeMillis();
            try {
                // Process the template, and add the line the caller asks for at the top of the document
                String result = withTopNotice(process(), topNotice);

                if (format == ProcessFormat.TEXT) {
                    // The template's output as it is, which is also the debug HTML of a report: what the PDF
                    // renderer adds on its own, such as the map credit in the page footer, is not in it
                    IOUtils.write(result, out, "UTF-8");

                    log.debug("Completed Freemarker text generation for " + getTemplatePath()
                            + " in " + (System.currentTimeMillis() - t0) + " ms");

                } else if (format == ProcessFormat.PDF) {

                    // Every PDF passes here, whichever template it was made from (the bootstrap's, an
                    // imported or edited row, a country's), so a PDF that shows a map image credits it here
                    MapImageCredit mapImageCredit = templateService.mapImageCredit;
                    HtmlToPdfRenderer.newBuilder()
                            .baseUri(templateService.getBaseUri())
                            .html(result)
                            .encrypt(templateService.getPDFEncryptionPassword())
                            .mapCredit(mapImageCredit.imageAddress(), mapImageCredit.text())
                            .pdf(out)
                            .build()
                            .render();

                    log.info("Completed Freemarker PDF generation for " + getTemplatePath()
                            + " in " + (System.currentTimeMillis() - t0) + " ms");
                }

            } catch (Exception e) {
                log.error("Error generating " + format + " from template " + getTemplatePath() , e);
                throw e;
            }
        }

        /*****************************************/
        /** Method-chaining Getters and Setters **/
        /***/

        public String getTemplatePath() {
            return templatePath;
        }

        public FmTemplateBuilder templatePath(String templatePath) {
            this.templatePath = templatePath;
            return this;
        }

        public Map<String, Object> getData() {
            return data;
        }

        public Map<String, Object> checkCreateData() {
            if (data == null) {
                data = new HashMap<>();
            }
            return data;
        }

        public FmTemplateBuilder data(Map<String, Object> data) {
            checkCreateData().putAll(data);
            return this;
        }

        public FmTemplateBuilder data(String key, Object value) {
            checkCreateData().put(key, value);
            return this;
        }

        public String getLanguage() {
            return language;
        }

        public FmTemplateBuilder language(String language) {
            this.language = language;
            return this;
        }

        public String[] getDictionaryNames() {
            return dictionaryNames;
        }

        public String getTimeZone() {
            return timeZone;
        }

        /**
         * Prints every date of the document in the given zone, and hands the zone to the template as
         * its {@code timeZone} value. The zone is applied to the rendering itself rather than left to a
         * {@code <#setting time_zone>} in the template, because the template a deployment renders is
         * its database row, which a change in the repository never reaches; a template that sets a zone
         * of its own still has the last word. A blank zone changes nothing.
         *
         * @param timeZone the time zone id, or null
         * @return this builder
         */
        public FmTemplateBuilder timeZone(String timeZone) {
            if (StringUtils.isNotBlank(timeZone)) {
                this.timeZone = timeZone;
                checkCreateData().put(TIME_ZONE_PROPERTY, timeZone);
            }
            return this;
        }

        /**
         * Prints a line of text at the top of the document that {@link #process(ProcessFormat, OutputStream)} makes,
         * the PDF and the debug HTML alike, whichever template it is made from ({@link #withTopNotice}). Null or
         * blank adds nothing.
         *
         * @param topNotice the text of the line, or null
         * @return this builder
         */
        public FmTemplateBuilder topNotice(String topNotice) {
            this.topNotice = topNotice;
            return this;
        }

        public FmTemplateBuilder dictionaryNames(String... dictionaryNames) {
            this.dictionaryNames = dictionaryNames;
            return this;
        }
    }
}
