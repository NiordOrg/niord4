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
package org.niord.core.category;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.bootstrap.Given;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.message.MessageService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationException;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.service.BaseService;
import org.niord.core.web.NiordWebException;
import org.niord.model.DataFilter;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.slf4j.Logger;

import jakarta.persistence.NoResultException;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Business interface for executing template categories
 */
@Service
@SuppressWarnings("unused")
public class TemplateExecutionService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TemplateExecutionService.class);

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    PromulgationTypeService promulgationTypeService;

    @Inject
    PromulgationManager promulgationManager;

    @Inject
    MessageService messageService;

    @Inject
    CategoryService categoryService;

    @Inject
    DictionaryService dictionaryService;

    @Inject
    NiordApp app;

    /**
     * *******************************************
     * parameter types functionality
     * *******************************************
     */

    /*
     * The standard parameter types ("text", "number", ...) are code, held in memory and never persisted
     * (docs/code-data-config-plan.md §6.2): they have no id, and are listed read-only. A running deployment still
     * holds the StandardParamType rows the start-up created until C4.1; those rows, and any other row with a
     * standard type's name, are ignored and never deleted (R-CDC1): the in-memory type wins by name. That works
     * because a template parameter names its type and does not refer to the row.
     */

    /**
     * Returns whether the name is a standard parameter type's, without regard to case: a type of one's own may not be
     * called "Text" beside the standard "text". It decides what the administration and the parameter-type import
     * refuse or skip; which type a name reads is decided exactly ({@link #isInMemoryStandardType}).
     * @param name the name
     * @return whether the name is a standard parameter type's, without regard to case
     */
    public static boolean isStandardParamType(String name) {
        return name != null && Arrays.stream(StandardParamType.STANDARD_PARAM_TYPES).anyMatch(name::equalsIgnoreCase);
    }

    /**
     * Returns whether the name is exactly a standard parameter type's, so the in-memory type is read for it. This
     * stays exact: a template parameter names its type exactly, and a row an earlier version let be called "Text"
     * stays a type of its own, listed and readable by its name, until it is renamed.
     * @param name the name
     * @return whether the name is exactly a standard parameter type's
     */
    private static boolean isInMemoryStandardType(String name) {
        return name != null && Arrays.asList(StandardParamType.STANDARD_PARAM_TYPES).contains(name);
    }

    /**
     * Refuses a name another parameter type has without regard to case: the names are unique without regard to case
     * when a type is created or renamed. A standard type's name is refused before this ({@link #isStandardParamType}).
     *
     * @param name the name of the new or renamed type
     * @param id the id of the type being renamed, which may have its own name in other capital letters; null for a new type
     * @throws IllegalArgumentException if another type has the name without regard to case
     */
    private void refuseTakenParamTypeName(String name, Integer id) {
        boolean taken = em.createNamedQuery("ParamType.findByNameIgnoreCase", ParamType.class)
                .setParameter("name", name)
                .getResultList()
                .stream()
                .anyMatch(other -> !Objects.equals(other.getId(), id));
        if (taken) {
            throw new IllegalArgumentException("Another parameter type has the name " + name);
        }
    }

    /**
     * Returns the parameter types: the standard ones from memory, then the database's other rows
     * @return the parameter types
     */
    public List<ParamType> getParamTypes() {

        List<ParamType> types = new ArrayList<>();
        Arrays.stream(StandardParamType.STANDARD_PARAM_TYPES)
                .map(StandardParamType::new)
                .forEach(types::add);
        em.createNamedQuery("ParamType.findAll", ParamType.class)
                .getResultList()
                .stream()
                .filter(type -> !isInMemoryStandardType(type.getName()))
                .forEach(types::add);
        return types;
    }

    /**
     * Returns the parameter type with the given id. Returns null if the parameter type does not exist.
     * @param id the id
     * @return the parameter type with the given id
     */
    public ParamType getParamType(Integer id) {
        return getByPrimaryKey(ParamType.class, id);
    }

    /**
     * Returns the parameter type with the given name. Returns null if the parameter type does not exist.
     * A standard type's name returns the in-memory type, which has no id.
     * <p>
     * The name is matched exactly, as a template parameter names its type. The parameter-type import finds the type an
     * item updates with this look-up, and it must stay exact: with a look-up that ignored case, an item named "lights"
     * would find the type "Lights" and rename it, and the template parameters that name "Lights" would lose their
     * type. Only the checks that a new or renamed type's name is free ignore case ({@link #refuseTakenParamTypeName}).
     * @param name the name
     * @return the parameter type with the given name
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public ParamType getParamTypeByName(String name) {
        if (isInMemoryStandardType(name)) {
            return new StandardParamType(name);
        }
        try {
            return em.createNamedQuery("ParamType.findByName", ParamType.class)
                    .setParameter("name", name)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Creates a new parameter type from the given template
     * @param type the parameter type value object
     * @return the new entity
     */
    @Transactional
    public ParamType createParamType(ParamType type) {

        if (type.isPersisted()) {
            throw new IllegalArgumentException("Cannot create parameter type with existing ID "
                    + type.getId());
        }

        // Ensure validity of the type name
        if (StringUtils.isBlank(type.getName())) {
            throw new IllegalArgumentException("Invalid parameter type name: " + type.getName());
        }
        // A row with a standard type's name would never be read: the in-memory type wins
        if (isStandardParamType(type.getName())) {
            throw new IllegalArgumentException("Parameter type " + type.getName() + " is a standard type");
        }
        // The name is unique without regard to case: a taken one is refused with a sentence (400), not left to the
        // unique key (500), which counts case
        refuseTakenParamTypeName(type.getName(), null);
        refuseIncompleteTemplateParams(type);

        // Replace the dictionary entry values with the persisted once
        updatePersistedValues(type, type);

        type = saveEntity(type);
        log.info("Created parameter type " + type);

        return type;
    }

    /**
     * Updates an existing parameter type from the given template
     * @param type the parameter type value object
     * @return the updated entity
     */
    @Transactional
    public ParamType updateParamType(ParamType type) {

        ParamType original = type.getId() != null ? getParamType(type.getId()) : null;
        if (original == null) {
            throw new IllegalArgumentException("Non-existing parameter type: " + type.getId());
        }
        if (StringUtils.isBlank(type.getName())) {
            throw new IllegalArgumentException("Invalid parameter type name: " + type.getName());
        }
        if (isStandardParamType(type.getName())) {
            throw new IllegalArgumentException("Parameter type " + type.getName() + " is a standard type");
        }
        // Checked before anything is copied into the managed row, so the lookup cannot flush a half-made change. Only
        // a renamed type is checked: a type keeping its name keeps it even where an earlier version let another type
        // have it in other capital letters, and the unique key holds the exact name. Another spelling of its own name
        // is no other type's
        if (!Objects.equals(type.getName(), original.getName())) {
            refuseTakenParamTypeName(type.getName(), original.getId());
        }
        refuseIncompleteTemplateParams(type);

        original.setName(type.getName());

        // Replace the dictionary entry values with the persisted once
        updatePersistedValues(type, original);

        if (original instanceof CompositeParamType to && type instanceof CompositeParamType from) {
            to.getTemplateParams().clear();
            to.getTemplateParams().addAll(from.getTemplateParams());
        }

        original = saveEntity(original);
        log.info("Updated parameter type " + original);

        return original;
    }

    /**
     * Refuses a composite parameter type with a template parameter that has no id or no type: both are required
     * columns, and the save would otherwise fail at the database (500)
     *
     * @param type the parameter type to check; a type that is not composite passes
     * @throws IllegalArgumentException naming the type and the parameter's place (1 for the first)
     */
    private static void refuseIncompleteTemplateParams(ParamType type) {
        if (!(type instanceof CompositeParamType composite) || composite.getTemplateParams() == null) {
            return;
        }
        List<TemplateParam> params = composite.getTemplateParams();
        for (int i = 0; i < params.size(); i++) {
            TemplateParam param = params.get(i);
            if (param == null || StringUtils.isBlank(param.getParamId())) {
                throw new IllegalArgumentException("Parameter " + (i + 1) + " of parameter type " + type.getName()
                        + " needs a parameter id");
            }
            if (StringUtils.isBlank(param.getType())) {
                throw new IllegalArgumentException("Parameter " + param.getParamId() + " of parameter type "
                        + type.getName() + " needs a type");
            }
        }
    }

    /**
     * Refuses a composite parameter type in which two template parameters have the same id: a template's parameters
     * are keyed by their id when it is executed, so the two would share one value. Ids are compared exactly, as the
     * execution keys them. The administration's create and update call this before they save; the bootstrap and the
     * parameter-type import do not, because their rule matches equal ids by their order
     * ({@link #replaceTemplateParams}) and a deployment's files may hold such a type.
     *
     * @param type the parameter type to check; a type that is not composite passes
     * @throws IllegalArgumentException naming the type and the first id that is repeated
     */
    public static void refuseRepeatedTemplateParamIds(ParamType type) {
        if (!(type instanceof CompositeParamType composite) || composite.getTemplateParams() == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (TemplateParam param : composite.getTemplateParams()) {
            String id = param == null ? null : param.getParamId();
            if (id != null && !ids.add(id)) {
                throw new IllegalArgumentException("Parameter type " + type.getName()
                        + " has more than one parameter with the id " + id);
            }
        }
    }

    /**
     * Replaces in a parameter type an earlier archive of the bootstrap run created the values the later archive's
     * type gives, within the first bootstrap's rule (docs/code-data-config-plan.md §7.2): for a composite type, its
     * template parameters ({@link #replaceTemplateParams}); for a list type, its values, when the file gives them (an
     * explicit empty list included, which empties them) and their keys differ from the row's, in order (an equal list
     * is not written, so its dictionary entries are never looked up again). A type whose kind differs between the
     * archives is refused ({@link #checkSameKind}). No template parameter is removed. The outcome is returned for the
     * job log: what was added and what was replaced.
     *
     * @param original the parameter type, as an earlier archive of the run created it
     * @param from     the later archive's parameter type
     * @param given    which fields the later archive's item gives
     * @return what was added and replaced
     * @throws IllegalStateException when there is no {@code given}, or the kinds differ: the job fails
     */
    @Transactional
    public BootstrapRun.Additions replaceInParamType(ParamType original, ParamType from, Given given) {
        if (given == null) {
            throw new IllegalStateException("Parameter type " + from.getName() + ": the first bootstrap's rule needs "
                    + "to know which fields the later archive gives, and the reader set none");
        }
        ParamType type = getParamType(original.getId());
        checkSameKind(type, from, -1, -1);
        String entry = "Parameter type " + type.getName();
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        if (type instanceof CompositeParamType to && from instanceof CompositeParamType file) {
            replaceTemplateParams("", to.getTemplateParams(), file.getTemplateParams(), given, added, replaced);
        } else if (type instanceof ListParamType list && from instanceof ListParamType fileList
                && given.has("values") && !valueKeys(list).equals(valueKeys(fileList))) {
            replaced.add(BootstrapRun.Additions.change("its values", valueKeys(list), valueKeys(fileList)));
            updatePersistedValues(fileList, list);
        }
        if (!added.isEmpty() || !replaced.isEmpty()) {
            saveEntity(type);
        }
        return new BootstrapRun.Additions(entry, added, replaced, List.of());
    }

    /**
     * Refuses a parameter type whose kind (composite, list or standard) differs between the archive of the run that
     * created it and a later one: the first bootstrap's rule replaces values, never a type's kind
     *
     * @param row     the type as created
     * @param file    the later archive's type
     * @param earlier the index of the archive that created it, or -1 when not known
     * @param later   the index of the later archive, or -1 when not known
     * @throws IllegalStateException when the kinds differ, naming the type and both archives
     */
    public static void checkSameKind(ParamType row, ParamType file, int earlier, int later) {
        if (!kind(row).equals(kind(file))) {
            throw new IllegalStateException("Parameter type " + row.getName() + " is a " + kind(row) + " type in "
                    + archive(earlier, "an earlier archive") + " of this bootstrap run and a " + kind(file)
                    + " type in " + archive(later, "a later archive") + ": a later archive cannot change a parameter "
                    + "type's kind");
        }
    }

    private static String kind(ParamType type) {
        return type instanceof CompositeParamType ? "composite" : type instanceof ListParamType ? "list" : "standard";
    }

    private static String archive(int index, String unknown) {
        return index >= 0 ? "archive " + index : unknown;
    }

    /** The keys of a list type's values, in order */
    private static List<String> valueKeys(ListParamType type) {
        return type.getValues().stream().map(v -> v == null ? null : v.getKey()).toList();
    }

    /**
     * Replaces in the template parameters of an entry an earlier archive of the bootstrap run created the values the
     * later archive's template parameters give (§7.2). A file's template parameter is matched by its paramId, and
     * among equal paramIds by their order, the order {@link Given#param} names it by. On a match, its type, mandatory
     * flag and position-list flag are set when the file gives them ({@code given}) and they differ, and its names by
     * language are replaced or added in place; the row is never recreated. A file's template parameter with a new
     * paramId is appended. A row's template parameter the file lacks is kept, and not listed, so an explicit empty
     * list adds nothing and removes nothing.
     *
     * @param what     the words before a new paramId in {@code added} ("template parameter " or "")
     * @param rows     the entry's template parameters; changed in place
     * @param file     the file's template parameters
     * @param given    which fields the file's item gives
     * @param added    receives each new paramId and each language added
     * @param replaced receives each value replaced, the old and the new
     */
    public static void replaceTemplateParams(String what, List<TemplateParam> rows, List<TemplateParam> file,
                                             Given given, List<String> added, List<String> replaced) {
        if (file == null || file.isEmpty()) {
            return;
        }
        List<TemplateParam> appended = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (TemplateParam param : file) {
            String id = param.getParamId();
            int nth = seen.merge(id, 1, Integer::sum) - 1;
            TemplateParam to = rows.stream()
                    .filter(p -> Objects.equals(p.getParamId(), id))
                    .skip(nth)
                    .findFirst()
                    .orElse(null);
            if (to == null) {
                appended.add(param);
                continue;
            }
            String path = Given.param(id, nth);
            if (given.has(path + ".type") && !Objects.equals(param.getType(), to.getType())) {
                replaced.add(BootstrapRun.Additions.change("the " + id + " type", to.getType(), param.getType()));
                to.setType(param.getType());
            }
            if (given.has(path + ".mandatory") && param.isMandatory() != to.isMandatory()) {
                replaced.add(BootstrapRun.Additions.change("the " + id + " mandatory flag", to.isMandatory(),
                        param.isMandatory()));
                to.setMandatory(param.isMandatory());
            }
            if (given.has(path + ".positionList") && param.isPositionList() != to.isPositionList()) {
                replaced.add(BootstrapRun.Additions.change("the " + id + " position-list flag", to.isPositionList(),
                        param.isPositionList()));
                to.setPositionList(param.isPositionList());
            }
            for (TemplateParamDesc desc : param.getDescs()) {
                if (StringUtils.isBlank(desc.getLang()) || StringUtils.isBlank(desc.getName())) {
                    continue;
                }
                TemplateParamDesc existing = to.getDesc(desc.getLang());
                if (existing == null) {
                    to.checkCreateDesc(desc.getLang()).setName(desc.getName());
                    added.add(id + " " + desc.getLang());
                } else if (!Objects.equals(existing.getName(), desc.getName())) {
                    replaced.add(BootstrapRun.Additions.change("the " + id + " " + desc.getLang() + " name",
                            existing.getName(), desc.getName()));
                    existing.setName(desc.getName());
                }
            }
        }
        for (TemplateParam param : appended) {
            rows.add(param);
            added.add(what + param.getParamId());
        }
    }

    /**
     * Deletes the parameter type with the given id.
     * <p>
     * A type that a template category or another composite type uses is refused with a 409 that names them. The uses
     * are by name ({@link TemplateParam#getType()}), with no foreign key, so without this check the type would be
     * deleted and the template form would lose the fields it gave.
     *
     * @param id the id of the parameter type to delete
     * @return true when the type was deleted, false when no parameter type has the id
     * @noinspection all
     */
    @Transactional
    public boolean deleteParamType(Integer id) {

        ParamType type = getByPrimaryKey(ParamType.class, id);
        if (type != null) {
            refuseDeleteOfParamTypeInUse(type);
            remove(type);
            log.info("Deleted parameter type " + id);
            return true;
        }
        return false;
    }

    /**
     * Refuses the delete of a parameter type that a template category or another composite type names as the type of
     * one of its template parameters
     *
     * @param type the parameter type to delete
     * @throws NiordWebException 409, naming the template categories and the composite types that use it
     */
    private void refuseDeleteOfParamTypeInUse(ParamType type) {
        List<String> categories = em.createQuery("SELECT DISTINCT c FROM Category c JOIN c.templateParams p "
                        + "WHERE p.type = :name", Category.class)
                .setParameter("name", type.getName())
                .getResultList()
                .stream()
                .map(TemplateExecutionService::categoryName)
                .sorted()
                .toList();
        List<String> composites = em.createQuery("SELECT DISTINCT t.name FROM CompositeParamType t "
                        + "JOIN t.templateParams p WHERE p.type = :name AND t.id <> :id", String.class)
                .setParameter("name", type.getName())
                .setParameter("id", type.getId())
                .getResultList()
                .stream()
                .sorted()
                .toList();

        List<String> uses = new ArrayList<>();
        if (!categories.isEmpty()) {
            uses.add((categories.size() == 1 ? "the template category " : "the template categories ")
                    + quoted(categories));
        }
        if (!composites.isEmpty()) {
            uses.add((composites.size() == 1 ? "the composite type " : "the composite types ") + quoted(composites));
        }
        if (!uses.isEmpty()) {
            throw new NiordWebException("The parameter type '" + type.getName() + "' is used by "
                    + String.join(" and ", uses) + "; remove it from them first", 409);
        }
    }

    /** A category's name for a message: its English name, else its first name, else its id */
    private static String categoryName(Category category) {
        List<CategoryDesc> descs = category.getDescs() == null ? List.of() : category.getDescs();
        return descs.stream()
                .filter(d -> "en".equals(d.getLang()) && StringUtils.isNotBlank(d.getName()))
                .map(CategoryDesc::getName)
                .findFirst()
                .or(() -> descs.stream().map(CategoryDesc::getName).filter(StringUtils::isNotBlank).findFirst())
                .orElse("id " + category.getId());
    }

    private static String quoted(List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
    }

    /**
     * Updates the persisted values of the parameter type. A value given by its key alone is found among the stored
     * type's values first (DICT6), so it stays the entry of the type's own dictionary though another dictionary holds
     * the key; a new type ({@code dest == source}) has no stored values.
     */
    private void updatePersistedValues(ParamType source, ParamType dest) {
        if (source instanceof ListParamType srclistParamType && dest instanceof ListParamType dstlistParamType) {
            List<DictionaryEntry> stored = dest == source || dstlistParamType.getValues() == null
                    ? List.of()
                    : new ArrayList<>(dstlistParamType.getValues());
            dstlistParamType.setValues(dictionaryService.persistedList(srclistParamType.getValues(), stored));
        }
    }

    /**
     * *******************************************
     * Template Execution
     * *******************************************
     */

    /** A tag start: '&lt;' followed by a letter, '/', '!' or '?' */
    private static final Pattern TAG_START = Pattern.compile("<[A-Za-z/!?]");

    /**
     * Returns if any string in the template parameters contains the start of a markup tag.
     * <p>
     * The parameters come from the request and are printed unescaped into the templates' field templates,
     * several of which are HTML, and the field templates themselves are delimited by tags. So a parameter
     * with markup is refused rather than escaped at every place a template prints one; a '&lt;' that starts
     * no tag, as in "&lt; 2 m", is plain text.
     *
     * @param value the template parameters: maps, lists and scalars as the request body was read
     * @return if a string anywhere in the value contains a tag start
     */
    public static boolean containsMarkup(Object value) {
        if (value instanceof CharSequence text) {
            return TAG_START.matcher(text).find();
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsMarkup(entry.getKey()) || containsMarkup(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (containsMarkup(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Object[] items) {
            return containsMarkup(Arrays.asList(items));
        }
        return false;
    }

    /**
     * Executes a message category template on the given message.
     * If no category template is specified, all the category template of the message are used.
     *
     * @param templateCategory the template to execute
     * @param message  the message to apply the template to
     * @param templateParams the template-specific parameters
     * @return the resulting message
     */
    public SystemMessageVo executeTemplate(Category templateCategory, SystemMessageVo message, List templateParams) throws Exception {

        // Sanity check
        if (templateCategory != null && templateCategory.getType() != CategoryType.TEMPLATE) {
            throw new IllegalArgumentException("Only categories of type TEMPLATE are executable");
        }

        // Either use the specified category or the ones associated with the message
        List<Category> templateCategories =
                templateCategory != null
                ? Collections.singletonList(templateCategory)
                : message.getCategories().stream()
                    .map(c -> categoryService.getCategoryDetails(c.getId()))
                    .filter(Objects::nonNull)
                    .filter(c -> c.getType() == CategoryType.TEMPLATE)
                    .collect(Collectors.toList());

        List<MessagePartVo> detailParts = checkCreateDetailParts(message, templateCategories);

        long t0 = System.currentTimeMillis();

        // Adjust the message prior to executing the template
        preExecuteTemplate(message);

        // Create context data to use with Freemarker templates and JavaScript updates
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("languages", app.getLanguages());
        contextData.put("message", message);

        // Execute the category templates one by one
        for (int x = 0; x < templateCategories.size(); x++) {
            Category template = templateCategories.get(x);
            MessagePartVo detailPart = detailParts.get(x);

            Object params = templateParams != null && x < templateParams.size()
                    ? templateParams.get(x)
                    : new HashMap<>();

            contextData.put("template", template.toVo(SystemCategoryVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage())));
            contextData.put("part", detailPart);
            contextData.put("params", params);

            scriptExecutionService.executeScriptResources(template.getScriptResourcePaths(), contextData);
        }

        // Next, for each associated promulgation type, execute any script resources associated with these
        List<PromulgationType> types = promulgationTypeService.getPromulgationTypes(message, true).stream()
                .filter(type -> !type.getScriptResourcePaths().isEmpty())
                .collect(Collectors.toList());

        for (PromulgationType type : types) {
            BaseMessagePromulgationVo promulgation = message.promulgation(type.getTypeId());

            Map<String, Object> promulgationContextData = new HashMap<>(contextData);
            promulgationContextData.put("promulgation", promulgation);
            promulgationContextData.put("promulgationType", promulgation.getType());

            scriptExecutionService.executeScriptResources(type.getScriptResourcePaths(), promulgationContextData);
        }

        // Adjust the message after executing the template
        postExecuteTemplate(message);

        log.info("Executed " + templateCategories.size() + " templates on message " + message.getId()
                + " in " + (System.currentTimeMillis() - t0) + " ms");

        return message;
    }

    /** Ensures that we have at least 1 message part per template category of type DETAILS **/
    private List<MessagePartVo> checkCreateDetailParts(SystemMessageVo message, List<Category> templateCategories) {
        List<MessagePartVo> detailParts = message.partsOfType(MessagePartType.DETAILS);
        if (detailParts.size() < templateCategories.size()) {
            int noToAdd = templateCategories.size() - detailParts.size();
            for (int x = 0; x < noToAdd; x++) {
                // NB: checkCreatePart() de-dups by type and would only ever create ONE DETAILS part,
                // so create each additional part explicitly to support multi-template execution.
                int index = detailParts.isEmpty() ? x : message.getParts().size();
                MessagePartVo part = new MessagePartVo();
                part.setType(MessagePartType.DETAILS);
                message.checkCreateParts().add(index, part);
            }
        }
        return message.partsOfType(MessagePartType.DETAILS);
    }

    /**
     * Adjust the message prior to executing a template
     * @param message the message to adjust
     */
    private void preExecuteTemplate(SystemMessageVo message) throws PromulgationException {

        // Update base data to ensure that we have all language variants
        message = messageService.updateBaseDate(message);

        // Ensure that description records exists for all supported languages
        message.checkCreateDescs(app.getLanguages());

        // Reset all promulgations
        promulgationManager.resetMessagePromulgations(message);

        // If message areas are undefined, compute them from the message geometry.
        // messageService.adjustMessage(message, MessageService.AdjustmentType.AREAS);
    }

    /**
     * Adjust the message after executing a template
     * @param message the message to adjust
     */
    private void postExecuteTemplate(SystemMessageVo message) throws Exception {

        // Update auto-title fields, etc.
        messageService.adjustMessage(message, MessageService.AdjustmentType.AREAS, MessageService.AdjustmentType.TITLE);

        // If there is only one DETAILS message part, hide the subject
        List<MessagePartVo> detailParts = message.partsOfType(MessagePartType.DETAILS);
        if (detailParts.size() >= 1) {
            detailParts.get(0).setHideSubject(detailParts.size() == 1);
        }

        // Allow the promulgation services to clean up
        promulgationManager.messagePromulgationGenerated(message);
    }

}
