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

package org.niord.web;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.publication.Publication;
import org.niord.core.domain.DomainService;
import org.niord.core.publication.PublicationSearchParams;
import org.niord.core.publication.PublicationService;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.PublicationMemberSetSource;
import org.niord.core.publication.PublicationResolver;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.core.publication.series.criteria.LegacyFilterTranslator;
import org.niord.core.repo.RepositoryService;
import org.niord.core.user.Roles;
import org.niord.core.user.TicketService;
import org.niord.core.user.UserService;
import org.niord.core.util.TextUtils;
import org.niord.core.util.WebUtils;
import org.niord.model.DataFilter;
import org.niord.model.IJsonSerializable;
import org.niord.model.publication.PublicationDescVo;
import org.niord.model.publication.PublicationType;
import org.niord.model.publication.PublicationVo;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import org.niord.core.repo.RepoPaths;
import org.niord.core.repo.RepoStore;
import java.time.Instant;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.niord.core.publication.series.IssueFileService;
import org.niord.core.publication.series.IssuePublicationMapping;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.publication.series.PublicationSearchAdapter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.niord.core.publication.Publication.DEFAULT_EDITION;
import static org.niord.core.util.WebUtils.encodeURIComponent;

/**
 * REST interface for accessing publications.
 */
@RestController
@RequestMapping("/rest/publications")
@Transactional
@PermitAll
public class PublicationRestService extends AbstractBatchableRestService {

    // See http://stackoverflow.com/questions/8092244/regex-to-extract-filename
    private static final Pattern FILE_NAME_HEADER_PATTERN = Pattern.compile("(?<=filename=\").*?(?=\")");

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublicationRestService.class);

    private final PublicationService publicationService;

    private final PublicationResolver publicationResolver;

    private final PublicationSearchAdapter publicationSearchAdapter;

    private final UserService userService;

    private final RepositoryService repositoryService;

    private final RepoStore store;

    private final TicketService ticketService;

    private final DomainService domainService;

    private final NiordApp app;

    PublicationRestService(PublicationService publicationService, PublicationResolver publicationResolver, PublicationSearchAdapter publicationSearchAdapter, UserService userService, RepositoryService repositoryService, RepoStore store, TicketService ticketService, DomainService domainService, NiordApp app) {
        this.publicationService = publicationService;
        this.publicationResolver = publicationResolver;
        this.publicationSearchAdapter = publicationSearchAdapter;
        this.userService = userService;
        this.repositoryService = repositoryService;
        this.store = store;
        this.ticketService = ticketService;
        this.domainService = domainService;
        this.app = app;
    }

    /**
     * Searches publications based on the given search parameters.
     * <p>
     * A caller who is not a signed-in user of a domain reads the PUBLIC tier: ACTIVE publications of
     * main type PUBLICATION in a publishing category, PUBLISHED issues only, whatever the request asks
     * for in those three, and at most {@link MessageSearchParams#MAX_PAGE_SIZE} rows a page. A signed-in
     * user (the admin app's pickers) keeps the unfiltered search.
     */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public PagedSearchResultVo<PublicationVo> searchPublications(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "messagePublication", required = false) MessagePublication messagePublication,
            @RequestParam(name = "mainType", defaultValue = "PUBLICATION") PublicationMainType mainType,
            @RequestParam(name = "type", required = false) PublicationType type,
            @RequestParam(name = "status", required = false) Set<PublicationStatus> statuses,
            @RequestParam(name = "title", defaultValue = "") String title,
            @RequestParam(name = "maxSize", defaultValue = "100") int maxSize,
            @RequestParam(name = "page", defaultValue = "0") int page) {

        PublicationSearchParams params = new PublicationSearchParams()
                .language(lang)
                .mainType(mainType)
                .type(type)
                .statuses(statuses)
                .domain(domain)
                .category(category)
                .messagePublication(messagePublication)
                .title(title);
        params.maxSize(maxSize).page(page);

        boolean publicTier = isPublicTier();
        if (publicTier) {
            toPublicTier(params);
            params.maxSize(publicTierMaxSize(maxSize));
        }

        DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);

        return unionWithIssues(params,
                p -> publicationService.searchPublications(p)
                        .map(row -> row.toVo(PublicationVo.class, dataFilter)),
                issue -> IssuePublicationMapping.toPublicationVo(issue, lang),
                publicTier);
    }

    /**
     * Whether the caller reads the PUBLIC tier of the legacy publication reads.
     * <p>
     * Everyone who is not a signed-in user of a domain: an anonymous caller, and a token sent without
     * the NiordDomain header, which carries no user role. The admin app's pickers always send both, so
     * they keep the unfiltered list; the public site never calls this resource.
     */
    private boolean isPublicTier() {
        return !userService.isCallerInRole(Roles.USER);
    }

    /**
     * Narrows a search to the public tier: ACTIVE, main type PUBLICATION, in a publishing category.
     * <p>
     * Overwrites whatever the request asked for in those three, since each of them was a way to widen
     * an anonymous search to drafts, templates or internal categories. The ACTIVE status is what the
     * issue half reads as PUBLISHED, and the publish flag applies to both halves. The narrower filters
     * (title, type, category, domain, messagePublication) still apply.
     */
    static PublicationSearchParams toPublicTier(PublicationSearchParams params) {
        return params
                .statuses(Set.of(PublicationStatus.ACTIVE))
                .published(Boolean.TRUE)
                .mainType(PublicationMainType.PUBLICATION);
    }

    /**
     * The page size a public-tier read may ask for: within [1, {@link MessageSearchParams#MAX_PAGE_SIZE}], the
     * ceiling of the message searches. The caller chooses it, and both reads load every matching row before
     * they cut one page out of it, so it bounds what one anonymous request serialises. (A size below 1 would
     * otherwise mean the whole list to the search.) The signed-in tier keeps the size it asks for; the admin
     * app's pickers ask for 50 at most.
     *
     * @param requested the requested page size
     * @return the page size to use
     */
    static int publicTierMaxSize(int requested) {
        return Math.clamp(requested, 1, MessageSearchParams.MAX_PAGE_SIZE);
    }

    /**
     * Searches publications based on the given search parameters - returns details information for each publication.
     * <p>
     * ADMIN, like its new-model twin {@code /publication-series/search-details}: the system shape
     * carries the administration configuration of every domain's publications (the message tag
     * filter among it) and every status, which is more than the user tier needs.
     */
    @GetMapping(value = "/search-details", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public PagedSearchResultVo<SystemPublicationVo> searchSystemPublications(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "messagePublication", required = false) MessagePublication messagePublication,
            @RequestParam(name = "mainType", defaultValue = "PUBLICATION") PublicationMainType mainType,
            @RequestParam(name = "type", required = false) PublicationType type,
            @RequestParam(name = "status", required = false) Set<PublicationStatus> statuses,
            @RequestParam(name = "title", defaultValue = "") String title,
            @RequestParam(name = "maxSize", defaultValue = "100") int maxSize,
            @RequestParam(name = "page", defaultValue = "0") int page) {

        PublicationSearchParams params = new PublicationSearchParams()
                .language(lang)
                .mainType(mainType)
                .type(type)
                .statuses(statuses)
                .domain(domain)
                .category(category)
                .messagePublication(messagePublication)
                .title(title);
        params.maxSize(maxSize).page(page);

        DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);

        return unionWithIssues(params,
                p -> publicationService.searchPublications(p)
                        .map(row -> row.toVo(SystemPublicationVo.class, dataFilter)),
                issue -> IssuePublicationMapping.toSystemPublicationVo(issue, lang),
                false);
    }

    /**
     * The legacy half and the issue half, merged, ISSUES FIRST.
     *
     * Issues first because PublicationResolver resolves them first: an imported
     * issue borrows its legacy row's id, so the two halves collide by design and
     * whichever the picker offers has to be the one a citation will resolve to.
     * Offering the legacy row and resolving to the issue would show one title and
     * cite another.
     *
     * Without this an issue with no legacy twin -- every issue created natively
     * in this system -- is resolvable by id and findable by nobody, which makes
     * the current week the one publication an editor cannot cite.
     *
     * MERGED BEFORE PAGING, so the two halves cannot each page independently and
     * leave the merge working on two wrong lists.
     *
     * WHICH IS WHY THE LEGACY HALF IS FETCHED UNPAGED. Prepending the complete
     * issue list to whatever page the legacy query returned put every issue at the
     * top of EVERY page and then cut the result to size, so page 2 began with the
     * same rows page 1 did and no legacy row past the first page was ever
     * reachable -- the paging was dead for the whole archive. Ordering the union
     * once and slicing it afterwards is the only arrangement in which a page means
     * what it says. The legacy query already materialises every matching id before
     * it sublists, so asking it for the whole match set costs the hydration of
     * about eleven hundred rows and no extra round trip.
     *
     * SystemPublicationVo extends PublicationVo, so one instanceof covers both
     * shapes this is called with.
     *
     * For the PUBLIC tier a legacy row an issue has taken over is dropped as
     * well, by the rule the public list applies (see
     * PublicationSearchAdapter.legacyIdsTakenOver): otherwise retiring an
     * imported issue would bring its still-ACTIVE legacy twin back into the
     * anonymous search.
     */
    private <V> PagedSearchResultVo<V> unionWithIssues(
            PublicationSearchParams params,
            java.util.function.Function<PublicationSearchParams, PagedSearchResultVo<V>> legacyHalf,
            java.util.function.Function<PublicationIssue, V> asVo,
            boolean publicTier) {

        int page = params.getPage();
        int maxSize = params.getMaxSize();

        List<PublicationIssue> issues = publicationSearchAdapter.search(params);

        // Widened for the legacy fetch only. The adapter above reads neither
        // field, and nothing downstream reads params again.
        params.maxSize(Integer.MAX_VALUE).page(0);
        PagedSearchResultVo<V> legacy = legacyHalf.apply(params);

        List<V> issueVos = new ArrayList<>();
        List<String> issueIds = new ArrayList<>();
        for (PublicationIssue issue : issues) {
            issueIds.add(issue.getPublicId());
            issueVos.add(asVo.apply(issue));
        }
        // A TWINNED LEGACY ROW FOLLOWS ITS TWIN, and without this it does not.
        //
        // The legacy half carries the old nullable domain column and nothing else,
        // so on its own it answers "domain is null or domain = X" -- the sharing
        // rule the redesign replaced, still running beside the new one. That is
        // invisible while the issue half returns the twin, because the two collide
        // by id and the merge drops the legacy row; it becomes visible exactly when
        // the new rule HIDES the twin, and then the legacy row is what shows the
        // publication that was just hidden.
        //
        // A row with no twin keeps the old rule. It was never imported, so no
        // series states who may cite it, and there is nothing else it could follow.
        Set<String> hidden = new LinkedHashSet<>(publicationSearchAdapter.legacyIdsHiddenFrom(params.getDomain()));
        if (publicTier) {
            hidden.addAll(publicationSearchAdapter.legacyIdsTakenOver());
        }
        return merge(issueVos, issueIds, legacy, page, maxSize, hidden);
    }

    /**
     * The merge itself, without the container.
     *
     * A pure static function so the rules that matter can be asserted directly:
     * issues first, a colliding legacy row dropped, the page taken out of the
     * MERGED order, and a total that counts what matched rather than what fitted.
     * niord-web has no CDI test harness, and the alternative is testing this
     * through two endpoints and a database. Visible because the test that pins it
     * sits with the rest of the publication tests rather than beside this class.
     */
    public static <V> PagedSearchResultVo<V> merge(List<V> issueVos, List<String> issueIds,
                                                   PagedSearchResultVo<V> legacy,
                                                   int page, int maxSize) {
        return merge(issueVos, issueIds, legacy, page, maxSize, Set.of());
    }

    /**
     * The same merge, with the legacy rows whose imported twin is out of scope.
     *
     * `hiddenLegacyIds` is what the visible-from rule decided about publications
     * that have BOTH a legacy row and an imported issue. Dropping them here rather
     * than in the legacy query is what keeps the frozen legacy resource frozen:
     * that query has no availability to consult and nothing to consult it about.
     */
    public static <V> PagedSearchResultVo<V> merge(List<V> issueVos, List<String> issueIds,
                                                   PagedSearchResultVo<V> legacy,
                                                   int page, int maxSize,
                                                   Set<String> hiddenLegacyIds) {
        Set<String> taken = new LinkedHashSet<>(issueIds);
        List<V> merged = new ArrayList<>(issueVos);
        for (V row : legacy.getData()) {
            String id = row instanceof PublicationVo p ? p.getPublicationId() : null;
            // The null id is asked of `taken` and NOT of the hidden set. A row with
            // no id has no twin to follow and survives; asking an immutable set
            // whether it contains null throws, which would take a whole search down
            // over a legacy row that was merely missing a field.
            if (!taken.contains(id) && (id == null || !hiddenLegacyIds.contains(id))) {
                merged.add(row);
            }
        }

        // Long arithmetic: a caller may send any page number, and page * maxSize
        // overflows into a negative index long before it stops being accepted.
        long start = maxSize > 0 ? (long) Math.max(0, page) * maxSize : 0L;
        int from = (int) Math.min(merged.size(), start);
        int to = maxSize > 0 ? (int) Math.min(merged.size(), start + maxSize) : merged.size();
        List<V> rows = new ArrayList<>(merged.subList(from, to));

        PagedSearchResultVo<V> out = new PagedSearchResultVo<>();
        out.setData(rows);
        // What matched, not what fitted. A caller paging on this otherwise stops
        // one page early.
        out.setTotal((long) merged.size());
        out.setSize(rows.size());
        return out;
    }

    /**
     * Returns all publications up to the given limit.
     * <p>
     * The legacy rows only. A caller outside the signed-in tier reads the PUBLIC tier (see
     * {@link #toPublicTier}), without the rows an issue has taken over and within the page-size ceiling
     * (see {@link #publicTierMaxSize}); a signed-in user keeps every row.
     */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<PublicationVo> getAllPublications(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "maxSize", defaultValue = "1000") int maxSize) {

        PublicationSearchParams params = new PublicationSearchParams()
                .language(lang);

        DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);

        if (!isPublicTier()) {
            params.maxSize(maxSize).page(0);
            return publicationService.searchPublications(params)
                    .map(p -> p.toVo(PublicationVo.class, dataFilter))
                    .getData();
        }

        // Fetched unpaged and cut after the taken-over rows are dropped, so the limit counts
        // what is returned rather than what was dropped.
        toPublicTier(params);
        Set<String> takenOver = publicationSearchAdapter.legacyIdsTakenOver();
        return publicationService.searchPublications(params)
                .map(p -> p.toVo(PublicationVo.class, dataFilter))
                .getData().stream()
                .filter(vo -> vo.getPublicationId() == null || !takenOver.contains(vo.getPublicationId()))
                .limit(publicTierMaxSize(maxSize))
                .collect(Collectors.toList());
    }

    /**
     * Returns a system-model version of the publication, which has been associated with a temporary
     * repository folder for new changes.
     *
     * @param publication the publication get an system-model version of
     * @return the system-model version of the publication
     */
    private SystemPublicationVo toSystemPublication(Publication publication) throws IOException {
        SystemPublicationVo publicationVo = publication
                .toVo(SystemPublicationVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));

        // Create a temporary repository folder for the publication
        publicationService.createTempPublicationRepoFolder(publicationVo);

        return publicationVo;
    }

    /**
     * Returns the publication with the given ID
     */
    @GetMapping(value = "/publication/{publicationIds}", produces = "application/json;charset=UTF-8")
    public List<PublicationVo> getPublications(@PathVariable("publicationIds") String publicationIds) throws Exception {
        // Through the shared resolver, one id at a time, so a citation into a
        // publication series renders here as well as on the public site. This
        // endpoint is what the editor calls to draw a stored citation, and a
        // citation that resolves in one place and not the other is worse than
        // one that resolves nowhere.
        //
        // The audience is INTERNAL, unconditionally, and is NOT derived from the
        // caller.
        //
        // This endpoint renders stored citations for the editor, and the previous
        // implementation applied no status or category filter at all -- it returned
        // whatever row the id named. Deriving the tier from the caller narrowed it
        // for anonymous callers, so a citation into a DRAFT, RECORDING or INACTIVE
        // publication silently stopped rendering. That is a behaviour change to an
        // endpoint this redesign is not supposed to touch, and it is the opposite
        // of what a citation renderer wants: a citation that resolves nowhere is
        // exactly the case the reader needs to see.
        //
        // The tier gate belongs on the message SEARCH, where it decides what
        // content is served. Here it only decides whether a title renders.
        //
        // Kept at INTERNAL by the owner's ruling on the 2026-09-24 security review
        // (F-X121, cheap mitigation): the anonymous /search and /all now read the
        // public tier, so an id this endpoint would name is no longer discoverable
        // anonymously. Deriving the audience from the caller is the full fix when
        // it is wanted: userService.currentUser() != null ? INTERNAL : PUBLIC.
        //
        // What an anonymous caller is not handed is the ADDRESS of an unreleased
        // document (F-X85, cheap mitigation): the link and file name of an OPEN
        // issue or a DRAFT/RECORDING legacy row, whose files already sit in the
        // served repository. The title still renders.
        boolean withholdUnreleasedFiles = isPublicTier();
        return Arrays.stream(publicationIds.split(","))
                .map(id -> publicationResolver.publicVo(
                        id, null, PublicationMemberSetSource.Audience.INTERNAL, withholdUnreleasedFiles))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns the editable publication with the given ID
     */
    @GetMapping(value = "/editable-publication/{publicationId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemPublicationVo getSystemPublication(@PathVariable("publicationId") String publicationId) throws Exception {
        Publication publication = publicationService.findByPublicationId(publicationId);
        if (publication == null) {
            throw new NiordWebException("No such publication: " + publicationId, 404);
        }
        return toSystemPublication(publication);
    }

    /**
     * Creates a new publication template with a temporary repository path
     *
     * @return the new publication template
     */
    @GetMapping(value = "/new-publication-template", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemPublicationVo newTemplatePublication(
            @RequestParam(name = "mainType", required = false) PublicationMainType mainType) throws Exception {

        log.info("Creating new publication template");

        // Create a new template publication
        Publication publication = publicationService.newTemplatePublication(mainType);

        return toSystemPublication(publication);
    }

    /**
     * Creates a new publication copy template with a temporary repository path
     *
     * @return the new publication copy template
     */
    @GetMapping(value = "/copy-publication-template/{publicationId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemPublicationVo copyPublicationTemplate(
            @PathVariable("publicationId") String publicationId,
            @RequestParam(name = "nextIssue", defaultValue = "false") boolean nextIssue) throws Exception {

        log.info("Creating copy of publication " + publicationId);

        Publication publication = publicationService.findByPublicationId(publicationId);
        if (publication == null) {
            return null;
        }

        // Create a system model version of the publication
        SystemPublicationVo editPublication = toSystemPublication(publication);

        // Assign a new ID and repoPath
        editPublication.assignNewId();

        // If nextIssue is requested, update accordingly
        if (nextIssue) {
            editPublication.nextIssue();
        }

        // Reset various fields
        editPublication.setStatus(PublicationStatus.DRAFT);
        editPublication.setMessageTag(null);
        editPublication.setRevision(0);
        editPublication.setEdition(DEFAULT_EDITION);
        if (editPublication.getDescs() != null) {
            editPublication.getDescs().forEach(desc -> {
                desc.setLink(null);
                desc.setFileName(null);
            });
        }

        return editPublication;
    }

    /**
     * Creates a new publication
     * @noinspection all
     */
    @PostMapping(value = {"/publication", "/publication/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemPublicationVo createPublication(@RequestBody SystemPublicationVo publication) throws Exception {
        // The category column is NOT NULL: a publication without one failed at the flush with a 500
        if (publication.getCategory() == null || StringUtils.isBlank(publication.getCategory().getCategoryId())) {
            throw new IllegalArgumentException("A publication must name its category");
        }
        // Publication(PublicationVo) copies the main type, type and status over the entity's defaults as they
        // come, and the three columns are NOT NULL: a body without one failed at the flush with a 500 too
        if (publication.getMainType() == null) {
            throw new IllegalArgumentException("A publication must name its main type (mainType)");
        }
        if (publication.getType() == null) {
            throw new IllegalArgumentException("A publication must name its type (type)");
        }
        if (publication.getStatus() == null) {
            throw new IllegalArgumentException("A publication must name its status (status)");
        }
        checkMessageTagFilterChange(publication.getMessageTagFilter(), null);
        return doCreatePublication(publication);
    }

    /** Creates the publication; the caller has checked the message tag filter */
    private SystemPublicationVo doCreatePublication(SystemPublicationVo publication) throws Exception {

        log.info("Creating publication " + publication);

        // Point embedded links to the message repository folder
        publication.toPublicationRepo();

        // Persist the publication
        Publication pub = publicationService.createPublication(new Publication(publication));

        // Copy resources from the temporary editing folder to the repository folder.
        // The folder is the persisted publication's own, derived from its id, never the body's repoPath.
        publication.setPublicationId(publication.getPublicationId());
        publication.setRepoPath(pub.getRepoPath());
        publicationService.updatePublicationFromTempRepoFolder(publication);

        return pub.toVo(SystemPublicationVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /**
     * Updates an existing publication
     * @noinspection all
     */
    @PutMapping(value = "/publication/{publicationId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemPublicationVo updatePublication(
            @PathVariable("publicationId") String publicationId,
            @RequestBody SystemPublicationVo publication) throws Exception {

        if (!Objects.equals(publicationId, publication.getPublicationId())) {
            throw new NiordWebException(400);
        }

        Publication stored = publicationService.findByPublicationId(publicationId);
        checkMessageTagFilterChange(publication.getMessageTagFilter(), stored != null ? stored.getMessageTagFilter() : null);
        return doUpdatePublication(publicationId, publication);
    }

    /** Updates the publication; the caller has checked the id and the message tag filter */
    private SystemPublicationVo doUpdatePublication(String publicationId, SystemPublicationVo publication) throws Exception {

        // Point embedded links to the message repository folder
        publication.toPublicationRepo();

        log.info("Updating publication " + publicationId);
        Publication pub = publicationService.updatePublication(new Publication(publication));

        // Copy resources from the temporary editing folder to the repository folder.
        // The folder is the persisted publication's own, derived from its id, never the body's repoPath.
        publication.setRepoPath(pub.getRepoPath());
        publicationService.updatePublicationFromTempRepoFolder(publication);

        return pub.toVo(SystemPublicationVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /**
     * Refuses a message tag filter change by anyone but a system administrator.
     * <p>
     * The filter is run as script when the publication starts recording and on every message status change
     * while it records. Recording publications are the obsolete legacy way of working, so the field is for
     * system administrators. Anyone else may keep the stored filter or send one of the filters the legacy
     * estate uses (blank included), which the copy and next-issue flows carry over.
     *
     * @param requested the filter in the request
     * @param stored the filter stored on the publication, or null for a new one
     */
    private void checkMessageTagFilterChange(String requested, String stored) {
        if (userService.isCallerInRole(Roles.SYSADMIN)
                || StringUtils.normalizeSpace(StringUtils.defaultString(requested))
                        .equals(StringUtils.normalizeSpace(StringUtils.defaultString(stored)))
                || LegacyFilterTranslator.isKnown(requested)) {
            return;
        }
        throw new NiordWebException("Only a system administrator may change the message tag filter", 403);
    }

    /**
     * Deletes an existing publication
     */
    @DeleteMapping(value = "/publication/{publicationId}")
    @RolesAllowed(Roles.ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePublication(@PathVariable("publicationId") String publicationId) throws Exception {
        log.info("Deleting publication " + publicationId);
        publicationService.deletePublication(publicationId);
    }

    /**
     * Updates the statuses of a publication. A body without a status or naming no publication answers 400, and a
     * transition the publication's status does not allow 409 (both through {@code RestExceptionStatusAdvice}); any
     * other failure is a server error.
     *
     * @param update the status updates
     * @return the updated publication
     */
    @PutMapping(value = "/update-status",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemPublicationVo updatePublicationStatuses(@RequestBody UpdatePublicationStatusParam update) throws Exception {

        log.info("Updating status " + update);

        return publicationService
                .updateStatus(update.getPublicationId(), update.getStatus())
                .toVo(SystemPublicationVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /**
     * Generates a publication report based on the PDF print parameters passed along
     *
     *
     * @param request the servlet request
     * @return the updated publication descriptor
     */
    @PostMapping(value = "/generate-publication-report/{*folder}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public PublicationDescVo generatePublicationReport(
            @PathVariable("folder") String path,
            @RequestBody PublicationDescVo desc,
            HttpServletRequest request) throws Exception {
        // Multi-segment {*folder} capture-the-rest variable arrives with a leading '/'.
        path = WebUtils.trimLeadingSlash(path);
        return generatePublicationReport(path, desc, request.getQueryString());
    }

    private PublicationDescVo generatePublicationReport(String path, PublicationDescVo desc, String queryString) throws Exception {

        // Validate that the path is a temporary repository folder path
        String folder = repositoryService.validateTempRepoPath(path);

        // A file name the descriptor names must be a bare name, checked before the report is rendered
        requireBareDescriptorFileName(desc);

        // The report parameters are the query string; without them the report was called with "?null"
        if (StringUtils.isBlank(queryString)) {
            throw new IllegalArgumentException("The report parameters are missing");
        }

        URL url = new URL(
                app.getBaseUri()
                + "/rest/message-reports/report.pdf?"
                + queryString
                + "&ticket=" + ticketService.createTicket(domainService.currentDomain(), userService.currentUser()));

        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        int responseCode = httpConn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            httpConn.disconnect();
            log.error("Error creating publication report " + queryString);
            throw new NiordWebException("Error creating publication report: " + queryString, 500);
        }

        // If the file name has not been specified in the descriptor, extract it from the
        // "Content-Disposition" header of the report connection
        String fileName = desc.getFileName();
        if (StringUtils.isBlank(fileName)) {
            String disposition = httpConn.getHeaderField("Content-Disposition");
            if (StringUtils.isNotBlank(disposition)) {
                Matcher regexMatcher = FILE_NAME_HEADER_PATTERN.matcher(disposition);
                if (regexMatcher.find()) {
                    fileName = regexMatcher.group();
                }
            }
        }
        fileName = StringUtils.defaultIfBlank(fileName, "publication.pdf");

        // The name comes from the descriptor or from the report's Content-Disposition, which echoes the
        // caller's fileName parameter: reduce it to a bare name inside the temp folder
        String destPath;
        try {
            destPath = folder + "/" + resolveTempFileName(fileName);
        } catch (NiordWebException ex) {
            httpConn.disconnect();
            throw ex;
        }
        // The report's length is known when the renderer sent one; a chunked response is spooled by the store
        long length = httpConn.getContentLengthLong();
        try (InputStream is = httpConn.getInputStream()) {
            Instant now = Instant.now();
            if (length >= 0) {
                store.write(destPath, is, length, now);
            } else {
                store.write(destPath, is, now);
            }
        } catch (IOException ex) {
            log.error("Error generating publication report " + destPath, ex);
            throw new NiordWebException("Error generating publication report: " + destPath, 500);
        } finally {
            httpConn.disconnect();
        }

        desc.setFileName(RepoPaths.nameOf(destPath));
        desc.setLink(repositoryService.getRepoUri(destPath));

        log.info("Generated publication report at destination " + destPath);

        return desc;
    }

    /**
     * Releases a publication, which involves the following steps
     * <ul>
     *     <li>Generate message reports for all languages</li>
     *     <li>Change status from recording to active</li>
     *     <li>Generate the publication to next week if the "nextIssue" parameter is set.</li>
     * </ul>
     *
     * @param publicationId the ID of the publication
     * @return the updated publication
     */
    // No consumes= : this endpoint has no @RequestBody, and a Spring consumes= would make a request whose
    // Content-Type doesn't match (or is absent) miss this mapping. JAX-RS ignored @Consumes for such a call.
    @PutMapping(value = "/release-publication/{publicationId}",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public PublicationVo releasePublication(
            @PathVariable("publicationId") String publicationId,
            @RequestParam(name = "nextIssue", defaultValue = "false") boolean nextIssue,
            HttpServletRequest request) throws Exception {

        Publication pub = publicationService.findByPublicationId(publicationId);
        if (pub == null) {
            throw new NiordWebException("Invalid publication ID " + publicationId, 400);
        }

        // Validate that we can release the publication
        if (pub.getType() != PublicationType.MESSAGE_REPORT ||
                pub.getStatus() != PublicationStatus.RECORDING ||
                pub.getMessageTag() == null ||
                pub.getPrintSettings() == null ||
                pub.getPrintSettings().get("report") == null) {
            throw new NiordWebException("Cannot release publication " + publicationId, 400);
        }
        Map<String, Object> printSettings = pub.getTemplate() != null ? pub.getTemplate().getPrintSettings() : pub.getPrintSettings();

        long t0 = System.currentTimeMillis();

        // Create an editable version of the publication, so that we e.g. work in a temporary folder
        SystemPublicationVo publication = toSystemPublication(pub);

        // The query string of the message-report print request: the tag, the print settings, the
        // "param:"-prefixed report params (the edition among them), lang and fileName.
        List<PublicationDescVo> updateDescs = new ArrayList<>();
        for (PublicationDescVo desc : publication.getDescs()) {
            String printParam = "tag=" + encodeURIComponent(pub.getMessageTag().getTagId());
            for (Map.Entry<String, Object> e : printSettings.entrySet()) {
                printParam = concatParam(printParam, e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Object> e : pub.getReportParams().entrySet()) {
                printParam = concatParam(printParam, "param:" + e.getKey(), e.getValue());
            }
            printParam = concatParam(printParam, "lang", desc.getLang());
            printParam = concatParam(printParam, "param:edition", pub.getEdition());
            printParam = concatParam(printParam, "fileName", desc.getFileName());

            // Generate the report
            String repoPath = publication.getEditRepoPath() + '/' + publication.getRevision();
            updateDescs.add(generatePublicationReport(repoPath, desc, printParam));
        }

        // Having successfully generated reports, we update the editable publication and save it.
        // Both writes below carry the stored message tag filter, so they skip the filter check.
        publication.setDescs(updateDescs);
        doUpdatePublication(publicationId, publication);

        // Make the publication active
        publication = updatePublicationStatuses(new UpdatePublicationStatusParam(publicationId, PublicationStatus.ACTIVE));

        // Create the next issue
        if (pub.getPeriodicalType() != null && nextIssue) {
            SystemPublicationVo nextIssuePub = copyPublicationTemplate(publicationId, true);
            nextIssuePub = doCreatePublication(nextIssuePub);
            updatePublicationStatuses(new UpdatePublicationStatusParam(nextIssuePub.getPublicationId(), PublicationStatus.RECORDING));
        }

        log.info("Released publication " + publicationId + " in " + (System.currentTimeMillis() - t0) + " ms");
        return publication;
    }

    /** Utility function for concatenating a key-value request parameter to the parameter string **/
    private String concatParam(String param, Object k, Object v) {
        param = param != null ? param : "";
        if (k != null && v != null) {
            param += (param.length() > 0) ? '&' : "";
            param += encodeURIComponent(k.toString()) + '=' + encodeURIComponent(v.toString());
        }
        return param;
    }

    /**
     * Uploads a publication file
     *
     * @param request the multi-part form data input request
     * @return the updated publication descriptor
     */
    @PostMapping(value = "/upload-publication-file/{*folder}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public PublicationDescVo uploadPublicationFile(@PathVariable("folder") String path, MultipartHttpServletRequest request) throws Exception {

        // Multi-segment {*folder} capture-the-rest variable arrives with a leading '/'.
        path = WebUtils.trimLeadingSlash(path);

        // Initialise the form parsing parameters
        final Map<String, Object> params = WebUtils.getMultipartFormParams(request);

        // Get hold of the first uploaded publication file, with its size: a write to the store needs the length
        org.springframework.web.multipart.MultipartFile inputFile = request.getMultiFileMap().values().stream()
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new NiordWebException("No uploaded publication file", 400));
        String inputFileName = WebUtils.bareFileName(inputFile.getOriginalFilename());
        if (inputFileName == null) {
            throw new NiordWebException("An uploaded file has no usable file name", 400);
        }

        // Check for the associated publication desc record
        PublicationDescVo desc = Optional.of(params)
                .map(map -> map.get("data"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(item -> {
                    try {
                        return JsonMapper.builderWithJackson2Defaults().build().readValue(item, PublicationDescVo.class);
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .orElseThrow(() -> new NiordWebException("No publication descriptor found", 400));

        // Validate that the path is a temporary repository folder path
        String folder = repositoryService.validateTempRepoPath(path);

        // Both names come from the request. The descriptor's must be a bare name; the uploaded part's is
        // reduced to one (NB: IE includes the path in item.getName()!), inside the temp folder either way.
        requireBareDescriptorFileName(desc);
        String fileName = StringUtils.defaultIfBlank(desc.getFileName(), inputFileName);
        String destPath = folder + "/" + resolveTempFileName(fileName);
        try (InputStream in = inputFile.getInputStream()) {
            store.write(destPath, in, inputFile.getSize(), Instant.now());
        } catch (IOException ex) {
            log.error("Error creating publication file " + destPath, ex);
            throw new NiordWebException("Error creating destination file: " + destPath, 500);
        }

        desc.setFileName(RepoPaths.nameOf(destPath));
        desc.setLink(repositoryService.getRepoUri(destPath));

        log.info("Copied publication file " + inputFileName + " to destination " + destPath);

        return desc;
    }


    /**
     * Refuses a publication descriptor whose file name is not a bare name.
     * <p>
     * The descriptor's name is typed by a client and always stored as a bare name, so one that carries a
     * path is refused outright, before anything is written or rendered. (The name of an uploaded part is
     * reduced instead, by {@link #resolveTempFile}, because a browser may legitimately send a path there.)
     *
     * @param desc the publication descriptor
     * @throws NiordWebException 400 when the descriptor names a file with a path, or nothing usable
     */
    private static void requireBareDescriptorFileName(PublicationDescVo desc) {
        String fileName = desc.getFileName();
        if (StringUtils.isNotBlank(fileName)
                && !fileName.trim().equals(IssueFileService.bareFileName(fileName))) {
            throw new NiordWebException("Invalid publication file name", 400);
        }
    }

    /**
     * Resolves the file a publication descriptor names inside a temporary repository folder.
     * <p>
     * The name comes from the request, so it is reduced to a bare name with the sanitiser the publication
     * issues use for the same problem, and the result must still lie directly in the folder
     * ({@link WebUtils#fileDirectlyIn}): a name carrying a path would otherwise place the file outside the
     * temporary repository folder.
     *
     * @param folder the validated temporary repository folder
     * @param fileName the requested file name
     * @return the file inside the folder
     * @throws NiordWebException 400 when no usable name is left
     */
    private String resolveTempFileName(String fileName) {
        String bare = IssueFileService.bareFileName(fileName);
        if (bare == null) {
            throw new NiordWebException("Invalid publication file name", 400);
        }
        return bare;
    }

    /**
     * Returns all publications for export purposes
     *
     * If not called via Ajax, pass a ticket request parameter along, which
     * can be requested via Ajax call to /rest/tickets/ticket?role=admin
     */
    @GetMapping(value = "/export", produces = "application/json;charset=UTF-8")
    public List<PublicationVo> exportPublications(
            @RequestParam(name = "lang", required = false) String lang) {

        // If a ticket is defined, check if programmatically
        if (!userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException(403);
        }

        PublicationSearchParams params = new PublicationSearchParams().language(lang);
        DataFilter dataFilter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);

        // TODO: Sort templates first - not by title
        return publicationService.searchPublications(params)
                .map(p -> p.toVo(SystemPublicationVo.class, dataFilter))
                .getData()
                .stream()
                .sorted(publicationTitleComparator(lang))
                .collect(Collectors.toList());
    }

    /**
     * Imports an uploaded Publications json file
     *
     * @param request the multi-part form data input request
     * @return a status
     */
    @PostMapping(value = "/upload-publications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.ADMIN)
    public String importPublications(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "publication-import");
    }

    /** Returns a publication name comparator **/
    private Comparator<PublicationVo> publicationTitleComparator(String lang) {
        return (p1, p2) -> {
            String n1 = p1.getDesc(lang) != null ? p1.getDesc(lang).getTitle() : null;
            String n2 = p2.getDesc(lang) != null ? p2.getDesc(lang).getTitle() : null;
            return TextUtils.compareIgnoreCase(n1, n2);
        };
    }

    /***************************
     * Helper classes
     ***************************/

    /** Encapsulates a status change for a publication */
    @SuppressWarnings("unused")
    public static class UpdatePublicationStatusParam implements IJsonSerializable {
        String publicationId;
        PublicationStatus status;

        public UpdatePublicationStatusParam() {
        }

        public UpdatePublicationStatusParam(String publicationId, PublicationStatus status) {
            this.publicationId = publicationId;
            this.status = status;
        }

        @Override
        public String toString() {
            return "{publicationId='" + publicationId + "', status=" + status + "}";
        }

        public String getPublicationId() {
            return publicationId;
        }

        public void setPublicationId(String publicationId) {
            this.publicationId = publicationId;
        }

        public PublicationStatus getStatus() {
            return status;
        }

        public void setStatus(PublicationStatus status) {
            this.status = status;
        }
    }

}
