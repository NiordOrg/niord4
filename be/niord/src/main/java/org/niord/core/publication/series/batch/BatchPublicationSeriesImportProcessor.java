/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series.batch;

import org.springframework.stereotype.Component;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.core.publication.series.PublicationDomainGuard;
import org.niord.core.publication.series.PublicationSeries;
import org.niord.core.publication.series.PublicationSeriesService;
import org.niord.core.publication.series.IssueLifecycleService;
import org.niord.core.publication.series.SeriesAvailability;
import org.niord.core.publication.series.SeriesAvailabilityResolver;
import org.niord.core.publication.series.SeriesStatus;
import org.niord.core.publication.series.SeriesValidator;
import org.niord.core.publication.series.vo.SystemPublicationSeriesVo;

import java.util.List;

/**
 * Turns one imported series document into an entity to persist, or drops it.
 *
 * UPSERT BY seriesId. That is the authored external handle -- the same key the
 * citations and the import/export use -- so re-importing a file updates the
 * series it describes rather than creating a second one beside it.
 *
 * NEVER ACTIVE. An imported series arrives DRAFT whatever the file says, and an
 * existing one keeps the status it already had. Activation is a transition that
 * validates against S-1..S-20, and a file that could set it would route around
 * every one of them -- which is exactly what an import from another environment
 * would do, since the file was written by a system where those rules passed
 * against different categories, domains and reports.
 *
 * The two references are resolved by id and REFUSED when they do not exist. A
 * category decides where the series appears on the public page and a domain
 * carries the timezone its cut-offs are read in; inventing either would produce
 * a series that looks imported and is quietly wrong. Refusing one row does not
 * fail the job -- the other rows in the file are still worth importing, and the
 * log says which was dropped and why.
 *
 * DOMAIN-SCOPED PER ROW, like the interactive writes: a row may only create or
 * update a series in the domain the import was started from (domainRefusal). A
 * row aimed at another domain's series is dropped the same way.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchPublicationSeriesImportProcessor")
public class BatchPublicationSeriesImportProcessor extends AbstractItemHandler {

    @Inject
    PublicationSeriesService seriesService;

    @Inject
    PublicationCategoryService categoryService;

    @Inject
    DomainLookupService domainLookupService;

    // The sharing list, resolved the one way both editors resolve it.
    @Inject
    SeriesAvailabilityResolver availabilityResolver;

    @Override
    public Object processItem(Object item) {
        SystemPublicationSeriesVo vo = (SystemPublicationSeriesVo) item;

        if (vo.getSeriesId() == null || vo.getSeriesId().isBlank()) {
            getLog().warning("Skipping a series with no seriesId: it is the upsert key, so "
                    + "importing it would create a new series on every run");
            return null;
        }

        PublicationCategory category = null;
        if (vo.getCategoryId() != null && !vo.getCategoryId().isBlank()) {
            category = categoryService.findByCategoryId(vo.getCategoryId());
            if (category == null) {
                getLog().warning("Skipping series " + vo.getSeriesId() + ": no publication category '"
                        + vo.getCategoryId() + "' in this installation");
                return null;
            }
        }

        Domain domain = null;
        if (vo.getDomainId() != null && !vo.getDomainId().isBlank()) {
            domain = domainLookupService.findByDomainId(vo.getDomainId());
            if (domain == null) {
                getLog().warning("Skipping series " + vo.getSeriesId() + ": no domain '"
                        + vo.getDomainId() + "' in this installation, and the domain carries the "
                        + "timezone the cut-offs are read in");
                return null;
            }
        }

        // The series this one compiles, by its immutable seriesId -- the only
        // handle that survives an export and an import into another installation.
        //
        // A NAMED SOURCE THAT IS NOT HERE DROPS THE ROW, exactly as an unknown
        // category or domain does, and for the same reason: a compilation whose
        // source is missing resolves nothing, so importing it would produce a
        // series that looks configured and publishes empty. The file may also
        // simply list the source AFTER the compilation, in which case the row is
        // dropped with a sentence naming what to do about it.
        PublicationSeries source = null;
        if (vo.getSourceSeriesId() != null && !vo.getSourceSeriesId().isBlank()) {
            source = seriesService.findBySeriesId(vo.getSourceSeriesId().trim());
            if (source == null) {
                getLog().warning("Skipping series " + vo.getSeriesId() + ": it compiles '"
                        + vo.getSourceSeriesId() + "', and no series of that id is in this "
                        + "installation yet -- import the source first");
                return null;
            }
        }

        // ForUpdate: this processor runs outside a transaction, and the update below
        // reads the stored series' lazy collections, which must come loaded.
        PublicationSeries existing = seriesService.findBySeriesIdForUpdate(vo.getSeriesId());

        // THE DOMAIN GUARD THE REST WRITES APPLY, before anything is changed. The
        // row is skipped with a sentence, like every other refusal in this class,
        // and it is asked before updateFromVo, so a refused row's stored series is
        // never touched, not even in memory.
        String refusal = domainRefusal(existing, domain, job == null ? null : job.getDomain());
        if (refusal != null) {
            getLog().warning("Skipping series " + vo.getSeriesId() + ": " + refusal);
            return null;
        }

        PublicationSeries series = existing == null ? new PublicationSeries() : existing;

        SeriesStatus keep = existing == null ? SeriesStatus.DRAFT : existing.getStatus();
        series.updateFromVo(vo);

        // AN ABSENT categoryId LEAVES THE CATEGORY ALONE; it does not clear it.
        //
        // The column is NOT NULL, so writing null here does not drop a category:
        // it throws inside the writer's flush, which stops the job at that row
        // (the rows after it are not imported), and the log names a Java
        // property rather than the file. A document that omits the field is not
        // asking for the series to leave its category; a NEW series with no
        // category has nowhere to go at all, and that row is dropped with a
        // sentence rather than stopping the import.
        if (category != null) {
            series.setCategory(category);
        } else if (series.getCategory() == null) {
            getLog().warning("Skipping series " + vo.getSeriesId() + ": no categoryId, and every "
                    + "series belongs to a category -- it decides where the series appears on the "
                    + "public page");
            return null;
        }

        // AN ABSENT domainId LEAVES THE OWNER ALONE, exactly as the category is
        // left alone and for the same reason: the column is NOT NULL, so writing
        // null does not clear an owner -- it fails inside the writer's flush and
        // stops the job at that row. A new series with no owner has no desk at all,
        // and that row is dropped with a sentence of its own below.
        if (domain != null) {
            series.setDomain(domain);
        }

        // AN ABSENT sourceSeriesId CLEARS IT, unlike the two references above.
        // Those sit on NOT NULL columns where absence can only mean "unchanged";
        // this one is the operand of an optional regime, and a document that says
        // nothing about it describes a series that compiles nothing. Leaving a
        // stale source behind would make the imported series disagree with the
        // file it was imported from.
        series.setSourceSeries(source);
        // The sharing list, through the resolver both editors use, so a file and a
        // form mean the same thing: deduplicated, the owner stripped, an inactive
        // domain kept and an unknown one refused. A document that says nothing
        // about availability changes neither the setting nor the list.
        //
        // THE REFUSAL IS CAUGHT AND TURNED INTO A SKIP, which is this class's
        // contract rather than a softening of the rule. One document naming a
        // domain this installation does not have would otherwise stop the job at
        // that row, with the rows after it not imported, and name a Java
        // property rather than the file.
        try {
            availabilityResolver.apply(series, vo);
        } catch (IssueLifecycleService.TransitionRefusedException e) {
            getLog().warning("Skipping series " + vo.getSeriesId() + ": " + e.getMessage());
            return null;
        }
        if ((vo.getAvailability() == null || vo.getAvailability().isBlank()) && existing == null) {
            // A file written by hand, or by an older export, says nothing about
            // sharing. A generated series is the owner's; anything else is a
            // reference other desks cite, which is what it was before this field
            // existed. The editor picks the same defaults.
            series.setAvailability(SeriesAvailability.defaultFor(series.getContentMode()));
        }

        series.setStatus(keep);

        // THE SAME GATE THE REST SAVES APPLY. A draft may be incomplete; it may
        // not be wrong. Without this a file could store a release mode the system
        // cannot honour, or a report parameter the issue supplies, and the series
        // would look imported until somebody tried to activate it -- at which
        // point the error names a field nobody typed.
        List<SeriesValidator.FieldError> hard = SeriesValidator.hardRules(series);
        if (!hard.isEmpty()) {
            getLog().warning("Skipping series " + vo.getSeriesId() + ": " + hard.size()
                    + " rule(s) fail -- " + hard);
            return null;
        }

        // And C-4, which every REST save refuses in any status: an operand that
        // names no area, category, chart, domain or message series here. A
        // misspelt message-series operand would otherwise shrink the query
        // silently, and the issues would publish without the notices it meant.
        //
        // A skip here, after updateFromVo, changes nothing stored: the series
        // found above is detached (this processor runs outside a transaction), so
        // only the writer, which never sees a skipped row, could write it.
        //
        // ASKED THROUGH THE SERVICE, not with the static call: the resolver reads a
        // domain operand's lazy message-series collection, which needs the
        // service's transaction -- on this thread a valid 'domain' operand would
        // throw LazyInitializationException and fail the whole job.
        List<SeriesValidator.FieldError> dangling = seriesService.danglingOperandsOf(series);
        if (!dangling.isEmpty()) {
            getLog().warning("Skipping series " + vo.getSeriesId() + ": " + dangling.size()
                    + " criteria operand(s) name nothing in this installation -- " + dangling);
            return null;
        }

        // And an ACTIVE series may not be edited into incompleteness, exactly as
        // the update endpoint refuses. An imported document never ACTIVATES a
        // series, but it can arrive against one that is already active.
        if (keep == SeriesStatus.ACTIVE) {
            // Through the service as well, for the same reason as C-4 above.
            List<SeriesValidator.FieldError> errors = seriesService.activationErrorsOf(series);
            if (!errors.isEmpty()) {
                getLog().warning("Skipping series " + vo.getSeriesId() + ": it is ACTIVE and the "
                        + "document would leave it failing " + errors.size() + " rule(s) -- " + errors);
                return null;
            }
        }

        getLog().info((existing == null ? "Importing new series " : "Updating series ")
                + vo.getSeriesId());
        return series;
    }

    /**
     * Why this row may not be written from the caller's domain, or null when it may.
     *
     * The rule the interactive saves apply through PublicationDomainGuard: an
     * existing series is written only from the domain that owns it (assertWritable),
     * and a series is only placed into the caller's own domain (assertMayAssign). The
     * caller is the domain the upload was made from, which the batch job records --
     * the request's current domain is not available on the batch thread. Together the
     * two refuse an overwrite, a takeover and a create into another domain, so an
     * import never moves an owner.
     *
     * A pure function of the three so it can be tested without a batch job.
     *
     * @param existing the stored series of this seriesId, or null for a new one
     * @param named    the domain the document names, or null when it names none
     * @param caller   the domain the import was started from, or null for none
     */
    static String domainRefusal(PublicationSeries existing, Domain named, Domain caller) {
        String callerId = caller == null ? null : caller.getDomainId();
        if (existing != null && !PublicationDomainGuard.writable(existing.getDomain(), caller)) {
            return "it belongs to the domain '"
                    + (existing.getDomain() == null ? "" : existing.getDomain().getDomainId())
                    + "', and the import was started from "
                    + (callerId == null ? "no domain" : "'" + callerId + "'")
                    + ". Import it from that domain.";
        }
        if (named != null && (callerId == null || !callerId.equals(named.getDomainId()))) {
            return "it names the domain '" + named.getDomainId() + "', and the import was started from "
                    + (callerId == null ? "no domain" : "'" + callerId + "'")
                    + ". A publication may only be placed in the domain it is being administered from.";
        }
        return null;
    }
}
