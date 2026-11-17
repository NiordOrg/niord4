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
package org.niord.core.script;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The check every imported template and script passes (docs/code-data-config-plan.md §6.4, §6.5): the Script
 * resources import, its batch job and the bootstrap run it on every file, and refuse a file it finds a reason in.
 *
 * <p>The path: not blank, at most {@value #MAX_PATH_LENGTH} characters (the column's size), no {@code ..} segment,
 * no backslash, no leading {@code /}, and {@code .ftl} or {@code .js}. A script ({@code *.js}): none of the
 * {@link #SCRIPT_RULES}. A template ({@code *.ftl}): it parses with the configuration every template is rendered with
 * ({@link FmTemplateService#newConfiguration}), and every {@code ?new} names, as a string literal, a class that
 * {@link DirectiveClassResolver#isDirective} admits and that exists. A parse alone does not catch a disallowed
 * {@code ?new}: the resolver is consulted only when the {@code ?new} is evaluated, at render time.
 *
 * <p><b>A guard against carelessness, not a boundary.</b> Scripts run on {@link NashornEngines#trusted()}, and string
 * concatenation defeats any token list, as do the bracket forms ({@code Java["type"](...)}, {@code em["remove"](x)});
 * the check scans the raw text, comments included, and refuses the listed tokens and packages only. The boundary is
 * that only a sysadmin imports, and that every import is recorded with its user in the history.
 */
public final class ScriptResourceCheck {

    /** The longest path a row can hold ({@code ScriptResource.path} is {@code varchar(255)}) */
    public static final int MAX_PATH_LENGTH = 255;

    /**
     * The types a script may look up with {@code CdiUtils.getBean(<type>.class)}; {@code be/docs/country-api.md} names
     * them for a country (docs/code-data-config-plan.md §6.5)
     */
    public static final List<String> ALLOWED_BEAN_TYPES = List.of(
            "org.niord.core.message.MessageService",
            "org.niord.core.promulgation.NavtexPromulgationService",
            "org.niord.core.promulgation.SafetyNetPromulgationService",
            "org.niord.core.area.AreaService",
            "org.niord.core.message.MessageSearchService");

    /** The {@code java.} packages a script may name; every other {@code java.} and {@code javax.} package is refused */
    public static final List<String> ALLOWED_JAVA_PACKAGES = List.of("java.util.", "java.time.", "java.text.", "java.math.");

    /**
     * The sub-packages of {@code java.util.} a script may not name: files ({@code zip}, {@code jar}, a
     * {@code logging.FileHandler}), service loading ({@code spi}) and threads ({@code concurrent}). No shipped script
     * names a sub-package of {@code java.util.}.
     */
    public static final List<String> REFUSED_JAVA_UTIL_PACKAGES = List.of("zip", "jar", "logging", "spi", "concurrent");

    /**
     * The packages under the {@code org}, {@code com}, {@code net} and {@code edu} roots, which the script engine
     * exposes, that a script may name: niord-base's own, which the bootstrap's scripts and the countries' use
     */
    public static final List<String> ALLOWED_OTHER_PACKAGES = List.of("org.niord.core.", "org.niord.model.");

    /** One rule of the script check: what it finds, and why that is refused */
    public record Rule(Pattern pattern, String reason) {
    }

    /** The rules a script must pass, in the order they are reported */
    public static final List<Rule> SCRIPT_RULES = List.of(
            rule("\\bPackages\\b", "Packages reaches any Java class"),
            rule("\\bJava\\s*\\.\\s*type\\b", "Java.type reaches any Java class"),
            rule("\\bJava\\s*\\.\\s*extend\\b", "Java.extend subclasses a Java class"),
            rule("(?<![\\w.$])(?!" + ALLOWED_JAVA_PACKAGES.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                            + ")javax?\\.[\\w$]+(?:\\.[\\w$]+)*",
                    "a script names only the Java packages " + String.join(", ", ALLOWED_JAVA_PACKAGES)),
            rule("(?<![\\w.$])java\\s*\\.\\s*util\\s*\\.\\s*(?:" + String.join("|", REFUSED_JAVA_UTIL_PACKAGES)
                            + ")\\b[\\w$.]*",
                    "a script names no java.util sub-package of " + String.join(", ", REFUSED_JAVA_UTIL_PACKAGES)),
            rule("(?<![\\w.$])(?!" + ALLOWED_OTHER_PACKAGES.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                            + ")(?:org|com|net|edu)\\.[\\w$]+(?:\\.[\\w$]+)*",
                    "a script names, outside java., only the packages " + String.join(", ", ALLOWED_OTHER_PACKAGES)),
            rule("\\bjava\\s*\\.\\s*lang\\s*\\.\\s*Runtime\\b|\\bRuntime\\s*\\.\\s*getRuntime\\b",
                    "Runtime starts processes"),
            rule("\\bProcessBuilder\\b", "ProcessBuilder starts processes"),
            rule("\\bjavax\\s*\\.\\s*script\\b", "javax.script starts another script engine"),
            rule("\\bjdk\\s*\\.\\s*nashorn\\b", "jdk.nashorn reaches the script engine's internals"),
            rule("\\bgetClass\\s*\\(|\\bforName\\s*\\(|\\bgetDeclared\\w*\\s*\\(|\\bsetAccessible\\s*\\("
                    + "|\\.\\s*getMethod\\s*\\(|\\.\\s*getField\\s*\\(|\\bMethodHandles\\b", "reflection reaches any class"),
            rule("\\beval\\s*\\(", "eval runs a string as code"),
            rule("\\bFunction\\s*\\(", "Function( runs a string as code"),
            rule("(?<![\\w$])(?:load|loadWithNewGlobal)\\s*\\(\\s*(?![\"']" + JsResourceService.NIORD_LOAD_SCHEME + ")",
                    "a script loads only a '" + JsResourceService.NIORD_LOAD_SCHEME + "' path, written as a string literal"),
            rule("\\bem\\s*\\.\\s*(?:persist|merge|remove|flush|createNativeQuery|executeUpdate)\\b",
                    "a script does not write through the entity manager"),
            rule("\\.\\s*executeUpdate\\s*\\(", "a script does not run an update query"),
            rule("\\bCdiUtils\\s*\\.\\s*getBean\\s*\\(\\s*(?!(?:" + ALLOWED_BEAN_TYPES.stream()
                            .map(type -> Pattern.quote(type + ".class")).collect(Collectors.joining("|")) + ")\\s*\\))",
                    "CdiUtils.getBean looks up only " + ALLOWED_BEAN_TYPES.stream()
                            .map(type -> type.substring(type.lastIndexOf('.') + 1)).collect(Collectors.joining(", "))
                            + ", each named as <type>.class"));

    /** A literal class name before {@code ?new}, in single or double quotes */
    private static final Pattern LITERAL_NEW = Pattern.compile("([\"'])([^\"']*)\\1\\s*\\?\\s*new\\b");

    /** Any {@code ?new} */
    private static final Pattern ANY_NEW = Pattern.compile("\\?\\s*new\\b");

    /** The longest token a reason quotes */
    private static final int MAX_TOKEN = 120;

    private ScriptResourceCheck() {
    }

    private static Rule rule(String regex, String reason) {
        return new Rule(Pattern.compile(regex), reason);
    }

    /**
     * Checks one template or script, as an import gives it
     *
     * @param path    its path, {@code templates/...}
     * @param content its content
     * @return why it is refused, each reason naming the token or class it found; empty when it is accepted
     */
    public static List<String> check(String path, String content) {
        List<String> reasons = checkPath(path);
        if (!reasons.isEmpty()) {
            return reasons;
        }
        String text = content == null ? "" : content;
        return path.endsWith(".js") ? checkScript(text) : checkTemplate(path, text);
    }

    /** The path rules; an empty list when the path is accepted */
    static List<String> checkPath(String path) {
        List<String> reasons = new ArrayList<>();
        if (path == null || path.isBlank()) {
            reasons.add("the path is blank");
            return reasons;
        }
        if (path.length() > MAX_PATH_LENGTH) {
            reasons.add("the path is longer than " + MAX_PATH_LENGTH + " characters");
        }
        if (path.contains("\\")) {
            reasons.add("the path has a backslash");
        }
        if (path.startsWith("/")) {
            reasons.add("the path starts with '/'");
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                reasons.add("the path has a '..' segment");
                break;
            }
        }
        if (!path.endsWith(".ftl") && !path.endsWith(".js")) {
            reasons.add("the path ends neither in .ftl nor in .js");
        }
        return reasons;
    }

    /**
     * Checks a script against the {@link #SCRIPT_RULES}
     *
     * @param content the script
     * @return one reason for each rule it breaks, naming the first token found; empty when it is accepted
     */
    public static List<String> checkScript(String content) {
        List<String> reasons = new ArrayList<>();
        for (Rule rule : SCRIPT_RULES) {
            Matcher m = rule.pattern().matcher(content);
            if (m.find()) {
                reasons.add("uses " + token(content, m) + ": " + rule.reason());
            }
        }
        return reasons;
    }

    /** The text found, with its argument list when it is a call, kept short */
    private static String token(String content, Matcher m) {
        int end = m.end();
        int next = end;
        while (next < content.length() && Character.isWhitespace(content.charAt(next))) {
            next++;
        }
        boolean call = m.group().stripTrailing().endsWith("(") || (next < content.length() && content.charAt(next) == '(');
        if (call) {
            int close = content.indexOf(')', end);
            int line = content.indexOf('\n', m.start());
            if (close >= 0 && (line < 0 || close < line)) {
                end = close + 1;
            }
        }
        String token = content.substring(m.start(), end).replaceAll("\\s+", " ").strip();
        return token.length() > MAX_TOKEN ? token.substring(0, MAX_TOKEN) + "…" : token;
    }

    /**
     * Checks a template: the parse, and each {@code ?new}
     *
     * @param name    the template's name, used in the parse's messages
     * @param content the template
     * @return why it is refused; empty when it is accepted
     */
    public static List<String> checkTemplate(String name, String content) {
        List<String> reasons = new ArrayList<>();
        try {
            new Template(name, new StringReader(content), Parser.CONFIGURATION);
        } catch (IOException e) {
            reasons.add("does not parse: " + e.getMessage());
        }

        int literals = 0;
        Matcher literal = LITERAL_NEW.matcher(content);
        while (literal.find()) {
            literals++;
            String className = literal.group(2);
            if (!DirectiveClassResolver.isDirective(className)) {
                reasons.add("?new of " + className + ", which is not a directive");
            } else {
                try {
                    Class.forName(className, false, ScriptResourceCheck.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    reasons.add("?new of " + className + ", which does not exist");
                }
            }
        }
        long all = ANY_NEW.matcher(content).results().count();
        if (all != literals) {
            reasons.add((all - literals) + " ?new not preceded by a string literal");
        }
        return reasons;
    }

    /** The configuration the parse uses, made when first needed; its loader resolves nothing, so no include is read */
    private static final class Parser {
        static final Configuration CONFIGURATION = FmTemplateService.newConfiguration(new StringTemplateLoader());
    }
}
