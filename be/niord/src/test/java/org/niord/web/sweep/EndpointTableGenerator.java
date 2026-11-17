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

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletRequest;
import org.niord.core.conf.CountryContractGuard;
import org.niord.core.user.Roles;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds every REST handler and keeps the generated half of the endpoint table current.
 *
 * <p>The scan is the condition-blind one of {@code RestControllerSecurityAnnotationTest}: every {@code @Controller}
 * under {@code org.niord}, whatever its conditions (so the SECOM administration interface is in), every method
 * Spring MVC would map, and the JSR-250 annotation resolved as Spring Security resolves it.
 *
 * <p>For each handler it suggests whether the role table can be derived from the annotation: it reads the handler's
 * bytecode, the same-class methods it calls, and the methods of other {@code org.niord} classes (the injected
 * services) it calls directly, with their own same-class callees (two levels), for role checks
 * ({@code isCallerInRole}, the message access helpers, {@code checkUserLoggedIn}), {@code currentDomain()},
 * {@code currentUser()}, tickets and the status codes 401 and 403. What it finds is written into
 * {@code codeCheckEvidence}, with the classes it did not follow; {@code codeCheck} is a suggestion a person confirms.
 *
 * <p>{@link #write(Path)} (run by {@code EndpointTableCompletenessTest} with {@code -Dniord.endpoints.write=true})
 * adds a skeleton entry for every handler without one to the file that claims its controller, refreshes the key,
 * handler, guard, params and the scan's suggestion of every entry, and adds a path the handler gained to
 * {@code alsoAt}. It never removes a path and never touches a decision (kind, rules, answers, case, expect, exempt,
 * known faults, reviewed); it does not rewrite the guard of a reviewed entry (a person decides its cells again,
 * sets {@code reviewed} to false and runs it again), and gives a new handler named like an existing one a skeleton
 * of its own.
 */
public final class EndpointTableGenerator {

    /** The system property that makes the completeness test write the table */
    public static final String WRITE_PROPERTY = "niord.endpoints.write";

    private EndpointTableGenerator() {
    }

    /** One handler at one verb */
    public record Handler(Class<?> controller, Method method, String verb, List<String> paths, String guard,
                          Object params, String codeCheck, List<String> evidence) {

        /** {@code "VERB /path"} of the handler's first path */
        public String key() {
            return verb + " " + paths.getFirst();
        }

        public String name() {
            return controller.getSimpleName() + "." + method.getName();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The scan
    // ------------------------------------------------------------------------------------------- //

    /** Every handler of every controller under {@code org.niord}, condition-blind, ordered by key */
    public static List<Handler> scan() {
        TypeFilter controllers = new AnnotationTypeFilter(Controller.class);
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    /** The include filter alone, without the condition evaluation super applies */
                    @Override
                    protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
                        return controllers.match(metadataReader, getMetadataReaderFactory());
                    }
                };
        scanner.addIncludeFilter(controllers);

        BytecodeScan bytecode = new BytecodeScan();
        List<Handler> handlers = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.niord")) {
            Class<?> controller;
            try {
                controller = Class.forName(candidate.getBeanClassName(), false,
                        EndpointTableGenerator.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            for (Method method : handlerMethods(controller)) {
                handlers.addAll(handlersOf(controller, method, bytecode));
            }
        }
        handlers.sort(Comparator.comparing(Handler::key));
        return handlers;
    }

    /** The methods Spring MVC maps as handlers of the controller (as {@code RestControllerSecurityAnnotationTest}) */
    static Set<Method> handlerMethods(Class<?> controller) {
        return MethodIntrospector.selectMethods(controller,
                (MethodIntrospector.MetadataLookup<Boolean>) method ->
                        AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class) ? Boolean.TRUE : null)
                .keySet();
    }

    private static List<Handler> handlersOf(Class<?> controller, Method method, BytecodeScan bytecode) {
        RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        List<String> paths = combine(onClass == null ? new String[0] : onClass.path(), onMethod.path());
        String guard = guard(controller, method);
        BytecodeScan.Result scan = bytecode.scan(controller, method);
        Object params = params(method, scan.readsParameterMap());
        List<Handler> handlers = new ArrayList<>();
        RequestMethod[] verbs = onMethod.method();
        if (verbs.length == 0) {
            throw new IllegalStateException(controller.getSimpleName() + "." + method.getName()
                    + " maps every verb; the table needs one verb per entry");
        }
        for (RequestMethod verb : verbs) {
            handlers.add(new Handler(controller, method, verb.name(), paths, guard, params,
                    scan.found() ? "found" : "none-found", scan.evidence()));
        }
        return handlers;
    }

    /** The patterns as Spring combines them, the shortest first */
    static List<String> combine(String[] classPaths, String[] methodPaths) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        List<String> cls = classPaths.length == 0 ? List.of("") : Arrays.asList(classPaths);
        List<String> mth = methodPaths.length == 0 ? List.of("") : Arrays.asList(methodPaths);
        Set<String> combined = new TreeSet<>(Comparator.comparingInt(String::length).thenComparing(s -> s));
        for (String c : cls) {
            for (String m : mth) {
                if (c.isEmpty()) {
                    combined.add(parser.parse(m.startsWith("/") || m.isEmpty() ? m : "/" + m).getPatternString());
                } else if (m.isEmpty()) {
                    combined.add(parser.parse(c).getPatternString());
                } else {
                    PathPattern pattern = parser.parse(c).combine(parser.parse(m));
                    combined.add(pattern.getPatternString());
                }
            }
        }
        return new ArrayList<>(combined);
    }

    /** The annotation Spring Security applies, as the table writes it */
    static String guard(Class<?> controller, Method method) {
        Annotation annotation = CountryContractGuard.JSR250.scan(method, controller);
        if (annotation == null) {
            return "none";
        }
        if (annotation instanceof PermitAll) {
            return "PermitAll";
        }
        if (annotation instanceof DenyAll) {
            return "DenyAll";
        }
        RolesAllowed rolesAllowed = (RolesAllowed) annotation;
        List<String> names = new ArrayList<>();
        for (String role : rolesAllowed.value()) {
            names.add(roleConstant(role));
        }
        return "RolesAllowed(" + String.join(",", names) + ")";
    }

    /** The name of the {@link Roles} constant holding the role, or the role itself */
    static String roleConstant(String role) {
        for (Field field : Roles.class.getFields()) {
            try {
                if (Modifier.isStatic(field.getModifiers()) && role.equals(field.get(null))) {
                    return field.getName();
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return role;
    }

    /** The role a {@link Roles} constant name holds */
    static String roleValue(String constant) {
        try {
            return (String) Roles.class.getField(constant).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No role constant " + constant, e);
        }
    }

    private static Object params(Method method, boolean readsParameterMap) {
        if (readsParameterMap) {
            return "*";
        }
        DefaultParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();
        List<String> params = new ArrayList<>();
        for (int i = 0; i < method.getParameterCount(); i++) {
            MethodParameter parameter = new MethodParameter(method, i);
            parameter.initParameterNameDiscovery(names);
            PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            if (requestParam != null && Map.class.isAssignableFrom(parameter.getParameterType())) {
                return "*";
            }
            String name = null;
            if (pathVariable != null) {
                name = pathVariable.name().isEmpty() ? parameter.getParameterName() : pathVariable.name();
            } else if (requestParam != null) {
                name = requestParam.name().isEmpty() ? parameter.getParameterName() : requestParam.name();
            }
            if (name != null && !params.contains(name)) {
                params.add(name);
            }
        }
        return params;
    }

    // ------------------------------------------------------------------------------------------- //
    // Writing the table
    // ------------------------------------------------------------------------------------------- //

    /**
     * Adds skeleton entries for unmapped handlers and refreshes the generated fields of the table in the folder.
     *
     * @return what it did, one line per change
     */
    public static List<String> write(Path folder) throws IOException {
        EndpointTable table = EndpointTable.load(folder);
        List<String> changes = update(table, scan());
        for (Map.Entry<String, EndpointTable.TableFile> file : table.files().entrySet()) {
            EndpointTable.write(file.getValue(), folder.resolve(file.getKey() + ".json"));
        }
        return changes;
    }

    /**
     * Brings the table's generated half level with the handlers, in memory: a handler is matched to the entry that
     * lists one of its paths; one that matches none is taken as moved only when exactly one entry of its name and
     * verb is matched by no handler's path, else it gets a skeleton. The guard of a reviewed entry is not rewritten:
     * its cells were decided for the old annotation.
     *
     * @return what it did, one line per change
     */
    static List<String> update(EndpointTable table, List<Handler> handlers) {
        List<String> changes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<EndpointEntry> seen = new HashSet<>();
        Map<Handler, EndpointEntry> byPath = new IdentityHashMap<>();
        Set<EndpointEntry> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Handler handler : handlers) {
            EndpointEntry entry = byPath(table, handler);
            if (entry != null) {
                byPath.put(handler, entry);
                claimed.add(entry);
            }
        }
        for (Handler handler : handlers) {
            EndpointEntry entry = byPath.get(handler);
            if (entry == null) {
                entry = moved(table, handler, claimed);
                if (entry != null) {
                    claimed.add(entry);
                }
            }
            if (entry == null) {
                String group = table.groupOfController(handler.controller().getSimpleName());
                if (group == null) {
                    problems.add(handler.controller().getSimpleName() + " is claimed by no table file: add it to "
                            + "the controllers of the file of its group and run the generator again");
                    continue;
                }
                entry = skeleton(handler);
                table.files().get(group).entries.add(entry);
                changes.add("added " + entry + " to " + group + ".json");
            } else {
                refresh(entry, handler, changes);
            }
            seen.add(entry);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("\n", problems));
        }
        for (EndpointEntry entry : table.entries()) {
            if (!seen.contains(entry)) {
                changes.add("NOT REMOVED, no handler has the key: " + entry
                        + " (remove it by hand if the handler is gone for good)");
            }
        }
        return changes;
    }

    /** The entry that lists one of the handler's paths at its verb, or null */
    private static EndpointEntry byPath(EndpointTable table, Handler handler) {
        for (String path : handler.paths()) {
            EndpointEntry entry = table.entryAt(handler.verb(), path);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    /**
     * The entry a handler that matches no path moved from: the one entry of its name and verb that no handler's path
     * claims (a new handler named like an existing one gets a skeleton, it does not take the existing entry)
     */
    private static EndpointEntry moved(EndpointTable table, Handler handler, Set<EndpointEntry> claimed) {
        List<EndpointEntry> byName = table.entries().stream()
                .filter(e -> !claimed.contains(e))
                .filter(e -> e.handler.equals(handler.name()) && e.verb().equals(handler.verb()))
                .toList();
        return byName.size() == 1 ? byName.getFirst() : null;
    }

    static EndpointEntry skeleton(Handler handler) {
        EndpointEntry entry = new EndpointEntry();
        entry.key = handler.key();
        entry.handler = handler.name();
        entry.alsoAt = new ArrayList<>(handler.paths().subList(1, handler.paths().size()));
        entry.kind = handler.verb().equals("GET") ? "read" : "write";
        entry.guard = handler.guard();
        entry.codeCheck = handler.codeCheck();
        entry.codeCheckEvidence = new ArrayList<>(handler.evidence());
        entry.params = handler.params();
        entry.caseName = EndpointEntry.PENDING;
        entry.reviewed = false;
        return entry;
    }

    private static void refresh(EndpointEntry entry, Handler handler, List<String> changes) {
        String before = entry.toString();
        if (!entry.key.equals(handler.key()) && !handler.paths().contains(entry.path())) {
            // The handler moved: its old path is kept in alsoAt (only a person removes a path)
            if (!entry.alsoAt.contains(entry.path())) {
                entry.alsoAt.add(entry.path());
            }
            entry.key = handler.key();
            changes.add("new key for " + before + ": " + entry.key + " (the old path is kept in alsoAt)");
        } else if (!entry.key.equals(handler.key()) && handler.paths().contains(entry.path())) {
            // The entry's key is one of the handler's paths; keep it
        }
        for (String path : handler.paths()) {
            if (!entry.paths().contains(path)) {
                entry.alsoAt.add(path);
                changes.add("added the path " + path + " to " + entry);
            }
        }
        if (!handler.name().equals(entry.handler)) {
            changes.add("handler of " + entry.key + ": " + entry.handler + " -> " + handler.name());
            entry.handler = handler.name();
        }
        if (!handler.guard().equals(entry.guard)) {
            if (entry.reviewed) {
                changes.add("guard of " + entry + " NOT written: the annotation is now " + handler.guard()
                        + ", the reviewed entry says " + entry.guard + ". Decide its cells again, set reviewed to false, "
                        + "and run the generator again");
            } else {
                changes.add("guard of " + entry + ": " + entry.guard + " -> " + handler.guard());
                entry.guard = handler.guard();
            }
        }
        if (!handler.params().equals(entry.params)) {
            changes.add("params of " + entry + ": " + entry.params + " -> " + handler.params());
            entry.params = handler.params();
        }
        if (!handler.codeCheck().equals(entry.codeCheck) && !entry.reviewed) {
            changes.add("codeCheck of " + entry + ": " + entry.codeCheck + " -> " + handler.codeCheck());
            entry.codeCheck = handler.codeCheck();
        }
        if (!handler.evidence().equals(entry.codeCheckEvidence)) {
            changes.add("codeCheckEvidence of " + entry);
            entry.codeCheckEvidence = new ArrayList<>(handler.evidence());
        }
    }

    /** Runs {@link #write(Path)} on the source folder of the module the JVM runs in */
    public static void main(String[] args) throws IOException {
        Path folder = args.length > 0 ? Path.of(args[0]) : EndpointTable.SOURCE_FOLDER;
        if (!Files.isDirectory(folder)) {
            throw new IllegalStateException("No table folder " + folder.toAbsolutePath());
        }
        write(folder).forEach(System.out::println);
    }

    // ------------------------------------------------------------------------------------------- //
    // The bytecode scan
    // ------------------------------------------------------------------------------------------- //

    /** Reads method bodies with Spring's repackaged ASM */
    static final class BytecodeScan {

        /** What one method body does */
        private record Body(List<Call> calls, Set<Integer> ints, List<String> strings) {
        }

        /** A call: the owner's internal name, the method's name and descriptor, and the last string loaded before it */
        private record Call(String owner, String name, String desc, String lastString) {
        }

        record Result(boolean found, boolean readsParameterMap, List<String> evidence) {
        }

        private final Map<String, Map<String, Body>> classes = new HashMap<>();

        /** The calls that answer who is calling and in which domain: evidence where they are called, not followed */
        private static final Set<String> PRIMITIVES = Set.of("isCallerInRole", "currentUser", "currentDomain",
                "getTicketDataForCurrentThread", "checkUserLoggedIn");

        /** Names of the helpers that hold a rule: access checks, owner and domain guards */
        private static final java.util.regex.Pattern RULE_HELPER = java.util.regex.Pattern.compile(
                "checkUserLoggedIn|check[A-Za-z]*Access|require[A-Z][A-Za-z]*|assert[A-Z][A-Za-z]*|isAdminIn|isOwner[A-Za-z]*");

        Result scan(Class<?> controller, Method handler) {
            String owner = Type.getInternalName(handler.getDeclaringClass());
            String key = handler.getName() + Type.getMethodDescriptor(handler);
            Set<String> evidence = new LinkedHashSet<>();
            Set<String> notFollowed = new TreeSet<>();
            boolean[] parameterMap = {false};

            // Level one: the handler and the same-class methods it reaches
            Set<Call> level2 = new LinkedHashSet<>();
            walk(owner, key, owner, evidence, parameterMap, level2::add);
            // Level two: the other niord classes' methods it calls, and their own same-class callees
            Set<String> followed = new HashSet<>();
            for (Call call : level2) {
                if (!followed.add(call.owner() + "." + call.name() + call.desc()) || PRIMITIVES.contains(call.name())) {
                    // The primitives themselves (who is calling, in which domain) are evidence at their call, not
                    // a rule to follow into
                    continue;
                }
                String declaring = declaringClass(call.owner(), call.name() + call.desc());
                if (declaring == null) {
                    notFollowed.add(simple(call.owner()));
                    continue;
                }
                walk(declaring, call.name() + call.desc(), declaring, evidence, parameterMap,
                        deeper -> notFollowed.add(simple(deeper.owner())));
            }
            // Reading the current user or domain alone is data (an audit field, a filter), not a check
            boolean found = evidence.stream()
                    .anyMatch(e -> !e.startsWith("currentUser()") && !e.startsWith("currentDomain()"));
            List<String> lines = new ArrayList<>(evidence);
            notFollowed.removeIf(n -> n.endsWith("Vo") || n.equals(simple(owner)));
            if (!notFollowed.isEmpty()) {
                lines.add("not followed: " + String.join(", ", notFollowed));
            }
            return new Result(found, parameterMap[0], lines);
        }

        /** Walks the method and every same-class method it reaches, collecting evidence and the calls out */
        private void walk(String owner, String methodKey, String home, Set<String> evidence, boolean[] parameterMap,
                          java.util.function.Consumer<Call> outside) {
            Deque<String> queue = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            queue.add(methodKey);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!visited.add(current)) {
                    continue;
                }
                // A method of the class or one it inherits (the batch upload helpers of AbstractBatchableRestService)
                String declaring = declaringClass(owner, current);
                Body body = declaring == null ? null : body(declaring, current);
                if (body == null) {
                    continue;
                }
                String where = simple(declaring) + "." + current.substring(0, current.indexOf('('));
                if (body.ints().contains(401) || body.ints().contains(403)) {
                    evidence.add("status 401/403 in " + where);
                }
                for (Call call : body.calls()) {
                    String name = call.name();
                    if (name.equals("isCallerInRole")) {
                        evidence.add("isCallerInRole(" + (call.lastString() == null ? "?"
                                : roleConstant(call.lastString())) + ") in " + where);
                    } else if (call.owner().startsWith("org/niord/") && RULE_HELPER.matcher(name).matches()) {
                        evidence.add(name + " in " + where);
                    } else if (name.equals("currentDomain")) {
                        evidence.add("currentDomain() in " + where);
                    } else if (name.equals("currentUser")) {
                        evidence.add("currentUser() in " + where);
                    } else if (call.owner().endsWith("/TicketService") || name.equals("getTicketDataForCurrentThread")) {
                        evidence.add("ticket (" + name + ") in " + where);
                    } else if (name.startsWith("getParameter") && isServletRequest(call.owner())) {
                        // The handler reads the request's parameters by names the bytecode does not declare
                        parameterMap[0] = true;
                    }
                    if (inHierarchy(owner, call.owner()) || call.owner().equals(home)) {
                        queue.add(name + call.desc());
                    } else if (call.owner().startsWith("org/niord/") && !name.equals("<init>")) {
                        outside.accept(call);
                    }
                }
            }
        }

        /** Whether the other class is the class or one of its niord superclasses */
        private boolean inHierarchy(String owner, String other) {
            for (String c = owner; c != null && c.startsWith("org/niord/"); c = superName(c)) {
                if (c.equals(other)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isServletRequest(String owner) {
            try {
                Class<?> type = Class.forName(owner.replace('/', '.'), false, BytecodeScan.class.getClassLoader());
                return ServletRequest.class.isAssignableFrom(type);
            } catch (ClassNotFoundException e) {
                return false;
            }
        }

        /** The class (the owner or a superclass) that declares the method, or null for an interface or a library */
        private String declaringClass(String owner, String methodKey) {
            String current = owner;
            while (current != null && current.startsWith("org/niord/")) {
                Map<String, Body> methods = methods(current);
                if (methods == null) {
                    return null;
                }
                if (methods.containsKey(methodKey)) {
                    return methods.get(methodKey) == null ? null : current;
                }
                current = superName(current);
            }
            return null;
        }

        private final Map<String, String> superNames = new HashMap<>();

        private String superName(String owner) {
            methods(owner);
            return superNames.get(owner);
        }

        private Body body(String owner, String methodKey) {
            Map<String, Body> methods = methods(owner);
            return methods == null ? null : methods.get(methodKey);
        }

        private Map<String, Body> methods(String owner) {
            if (classes.containsKey(owner)) {
                return classes.get(owner);
            }
            Map<String, Body> methods = null;
            try (InputStream in = BytecodeScan.class.getClassLoader().getResourceAsStream(owner + ".class")) {
                if (in != null) {
                    methods = read(new ClassReader(in), owner);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + owner, e);
            }
            classes.put(owner, methods);
            return methods;
        }

        private Map<String, Body> read(ClassReader reader, String owner) {
            Map<String, Body> methods = new HashMap<>();
            superNames.put(owner, reader.getSuperName());
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                        methods.put(name + descriptor, null);
                        return null;
                    }
                    Body body = new Body(new ArrayList<>(), new HashSet<>(), new ArrayList<>());
                    methods.put(name + descriptor, body);
                    return new MethodVisitor(Opcodes.ASM9) {
                        String lastString;

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String s) {
                                lastString = s;
                                body.strings().add(s);
                            } else if (value instanceof Integer i) {
                                body.ints().add(i);
                            }
                        }

                        @Override
                        public void visitIntInsn(int opcode, int operand) {
                            if (opcode == Opcodes.SIPUSH || opcode == Opcodes.BIPUSH) {
                                body.ints().add(operand);
                            }
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String desc) {
                            if (fieldOwner.equals("org/springframework/http/HttpStatus")) {
                                if (fieldName.equals("FORBIDDEN")) {
                                    body.ints().add(403);
                                } else if (fieldName.equals("UNAUTHORIZED")) {
                                    body.ints().add(401);
                                }
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String callOwner, String callName, String desc,
                                                    boolean isInterface) {
                            body.calls().add(new Call(callOwner, callName, desc, lastString));
                            lastString = null;
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                           Object... arguments) {
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle) {
                                    body.calls().add(new Call(handle.getOwner(), handle.getName(), handle.getDesc(),
                                            null));
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return methods;
        }

        private static String simple(String internalName) {
            String name = internalName.substring(internalName.lastIndexOf('/') + 1);
            int inner = name.indexOf('$');
            return inner > 0 ? name.substring(0, inner) : name;
        }
    }
}
