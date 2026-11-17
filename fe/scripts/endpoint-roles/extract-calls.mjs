/**
 * Extracts every HTTP call the administration app and the public site make, from their sources,
 * for the endpoint-role guard (`scripts/endpoint-roles.test.mjs`). Ported from the error hunt's
 * wave 1 (`fe-calls.mjs`, a regex scan) to the TypeScript syntax tree, so that a URL built in a
 * helper, a field or another service is followed to where it is called.
 *
 * A call is a site that sends a request or opens an address:
 *
 * - `http.get|post|put|delete|patch|head(url, …)` and `http.request(verb, url, …)` on a receiver
 *   whose name holds `http`;
 * - `httpResource(() => url | { url, params })` and its `.text` / `.blob` / `.arrayBuffer` forms;
 * - `openTicketed(url, role?)` and `openPublic(url)` (the ticketed download service);
 * - `window.open(url)`, `window.location.href = url` and `navigator.navigate(url)` (a download or
 *   an export opened in the browser).
 *
 * A URL is followed through template literals, `+`, local constants, module constants, the class's
 * own fields and methods, `computed(() => …)`, the methods of an injected service
 * (`this.api.exportUrl()`) and an imported function. Whatever is not known (an id, a parameter)
 * becomes `{}`. A field or method that yields a `/rest` address but is reached from no site (an
 * `uploadUrl()` bound in a template, a thumbnail address) is a call of its own: POST when its name
 * says upload, GET otherwise.
 *
 * Each call carries the query parameters it sends where they can be read (an object literal, an
 * `HttpParams`/`URLSearchParams` built in the same function with `.set`/`.append`, a `?name=` in
 * the address); `paramsDynamic` when they are built elsewhere. A ticketed call carries the roles
 * its ticket asks for: the second argument of `openTicketed`, or the `fetch(role)` of the ticket
 * service in the same file (`null` = a ticket with no role).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

/** The source folders searched, relative to fe/, and the application each one belongs to. */
export const SOURCE_ROOTS = [
  { dir: 'ui-apps/admin/src', app: 'admin' },
  { dir: 'ui-apps/info/src', app: 'info' },
  { dir: 'shared-map', app: 'shared' },
  { dir: 'shared-html', app: 'shared' },
];

const HTTP_VERBS = new Set(['get', 'post', 'put', 'delete', 'patch', 'head']);
const RESOURCE_FORMS = new Set(['text', 'blob', 'arrayBuffer']);
const MAX_DEPTH = 8;
const UNKNOWN = '{}';

/** Every non-spec TypeScript source under the roots, relative to fe/ with forward slashes. */
export function sourceFiles(feRoot) {
  const files = [];
  for (const { dir, app } of SOURCE_ROOTS) {
    const abs = path.join(feRoot, dir);
    if (!fs.existsSync(abs)) continue;
    for (const rel of fs.readdirSync(abs, { recursive: true })) {
      const name = String(rel).replace(/\\/g, '/');
      if (!name.endsWith('.ts') || name.endsWith('.spec.ts') || name.endsWith('.d.ts')) continue;
      if (name.split('/').some((part) => part === 'node_modules' || part === 'testing')) continue;
      files.push({ file: `${dir}/${name}`, app });
    }
  }
  return files.sort((a, b) => a.file.localeCompare(b.file));
}

/** The source index: each file's syntax tree, classes, module bindings and imports. */
function index(feRoot) {
  const files = new Map();
  const classes = new Map();
  for (const { file, app } of sourceFiles(feRoot)) {
    const text = fs.readFileSync(path.join(feRoot, file), 'utf8');
    const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    const info = { file, app, sf, bindings: new Map(), imports: new Map(), classes: [] };
    for (const st of sf.statements) {
      if (ts.isVariableStatement(st)) {
        for (const d of st.declarationList.declarations) {
          if (ts.isIdentifier(d.name) && d.initializer)
            info.bindings.set(d.name.text, d.initializer);
        }
      } else if (ts.isFunctionDeclaration(st) && st.name) {
        info.bindings.set(st.name.text, st);
      } else if (ts.isClassDeclaration(st) && st.name) {
        const cls = { name: st.name.text, file, node: st, members: new Map(), types: new Map() };
        for (const m of st.members) {
          const name = m.name && ts.isIdentifier(m.name) ? m.name.text : undefined;
          if (!name && !ts.isConstructorDeclaration(m)) continue;
          if (ts.isPropertyDeclaration(m)) {
            cls.members.set(name, m);
            const injected = injectedClass(m.initializer) ?? typeName(m.type);
            if (injected) cls.types.set(name, injected);
          } else if (ts.isMethodDeclaration(m) || ts.isGetAccessorDeclaration(m)) {
            cls.members.set(name, m);
          } else if (ts.isConstructorDeclaration(m)) {
            for (const p of m.parameters) {
              if (ts.isIdentifier(p.name) && p.modifiers?.length && typeName(p.type)) {
                cls.types.set(p.name.text, typeName(p.type));
              }
            }
          }
        }
        info.classes.push(cls);
        if (!classes.has(cls.name)) classes.set(cls.name, cls);
      } else if (ts.isImportDeclaration(st) && st.importClause?.namedBindings) {
        const from = st.moduleSpecifier.text;
        const nb = st.importClause.namedBindings;
        if (ts.isNamedImports(nb)) {
          for (const el of nb.elements) {
            info.imports.set(el.name.text, { from, name: (el.propertyName ?? el.name).text });
          }
        }
      }
    }
    files.set(file, info);
  }
  return { files, classes };
}

function injectedClass(init) {
  if (init && ts.isCallExpression(init) && ts.isIdentifier(init.expression)) {
    if (
      init.expression.text === 'inject' &&
      init.arguments[0] &&
      ts.isIdentifier(init.arguments[0])
    ) {
      return init.arguments[0].text;
    }
  }
  return undefined;
}

function typeName(type) {
  return type && ts.isTypeReferenceNode(type) && ts.isIdentifier(type.typeName)
    ? type.typeName.text
    : undefined;
}

function lineOf(node) {
  const sf = node.getSourceFile();
  return sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
}

function enclosingClass(node) {
  for (let n = node.parent; n; n = n.parent) if (ts.isClassDeclaration(n)) return n;
  return undefined;
}

function enclosingFunction(node) {
  for (let n = node.parent; n; n = n.parent) {
    if (ts.isFunctionLike(n) && n.body) return n;
  }
  return undefined;
}

/** The expressions a function returns: an arrow's expression body, or each `return` of its block. */
function returned(fn) {
  if (!fn.body) return [];
  if (!ts.isBlock(fn.body)) return [fn.body];
  const out = [];
  const visit = (n) => {
    if (ts.isFunctionLike(n) && n !== fn) return;
    if (ts.isReturnStatement(n) && n.expression) out.push(n.expression);
    ts.forEachChild(n, visit);
  };
  ts.forEachChild(fn.body, visit);
  return out;
}

/** A local `const|let name = …` of the function a node sits in, or of an enclosing one. */
function localBinding(node, name) {
  for (let fn = enclosingFunction(node); fn; fn = enclosingFunction(fn)) {
    for (const p of fn.parameters) if (ts.isIdentifier(p.name) && p.name.text === name) return null;
    let found;
    const visit = (n) => {
      if (found) return;
      if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.name.text === name) {
        found = n.initializer ?? null;
        return;
      }
      if (ts.isFunctionLike(n) && n !== fn) return;
      ts.forEachChild(n, visit);
    };
    ts.forEachChild(fn.body, visit);
    if (found !== undefined) return found;
  }
  return undefined;
}

/** A local `const { name } = init` of the function a node sits in: the initializer and the key. */
function localDestructure(node, name) {
  const fn = enclosingFunction(node);
  if (!fn) return undefined;
  let found;
  const visit = (n) => {
    if (found) return;
    if (ts.isVariableDeclaration(n) && ts.isObjectBindingPattern(n.name) && n.initializer) {
      for (const el of n.name.elements) {
        if (ts.isIdentifier(el.name) && el.name.text === name) {
          const key =
            el.propertyName && ts.isIdentifier(el.propertyName) ? el.propertyName.text : name;
          found = { init: n.initializer, key };
        }
      }
    }
    if (ts.isFunctionLike(n) && n !== fn) return;
    ts.forEachChild(n, visit);
  };
  ts.forEachChild(fn.body, visit);
  return found;
}

/** The parameters of a called function bound to the call's arguments (evaluated in `env`). */
function bindArguments(fn, call, env) {
  if (!fn || !ts.isFunctionLike(fn)) return undefined;
  const bound = new Map();
  fn.parameters.forEach((p, i) => {
    if (ts.isIdentifier(p.name) && call.arguments[i]) {
      bound.set(p.name.text, { node: call.arguments[i], env });
    }
  });
  return bound;
}

function unwrap(node) {
  let n = node;
  while (
    n &&
    (ts.isParenthesizedExpression(n) ||
      ts.isAsExpression(n) ||
      ts.isNonNullExpression(n) ||
      ts.isSatisfiesExpression?.(n) ||
      ts.isTypeAssertionExpression(n))
  ) {
    n = n.expression;
  }
  return n;
}

class Extractor {
  constructor(feRoot) {
    const { files, classes } = index(feRoot);
    this.files = files;
    this.classes = classes;
    /** URL members reached from a site: `file#Class.member` */
    this.reached = new Set();
    /** Sites whose address starts with a parameter of their function: a helper others call. */
    this.wrappers = [];
  }

  fileOf(node) {
    return this.files.get(node.getSourceFile().fileName);
  }

  /** The class declaration and file a `this.<field>` of `node`'s class is typed as. */
  fieldClass(node, field) {
    const decl = enclosingClass(node);
    const own = decl && this.fileOf(node).classes.find((c) => c.node === decl);
    const type = own?.types.get(field);
    return type ? this.classes.get(type) : undefined;
  }

  memberOf(cls, name) {
    return cls?.members.get(name);
  }

  /** The node an imported name refers to, followed into the imported file. */
  importedBinding(node, name) {
    const info = this.fileOf(node);
    const imp = info.imports.get(name);
    if (!imp || !imp.from.startsWith('.')) return undefined;
    const base = path.posix.normalize(path.posix.join(path.posix.dirname(info.file), imp.from));
    const target = this.files.get(`${base}.ts`) ?? this.files.get(`${base}/index.ts`);
    return target?.bindings.get(imp.name);
  }

  /** Marks a URL member reached, so it is not reported as a call of its own. */
  reach(member) {
    if (member?.node) {
      const file = member.node.getSourceFile().fileName;
      this.reached.add(`${file}#${member.cls ?? ''}.${member.name}`);
    }
  }

  /** The values of a member (a field's initializer, or what a method returns). */
  memberValues(m) {
    if (!m) return [];
    if (ts.isPropertyDeclaration(m)) return m.initializer ? [m.initializer] : [];
    if (ts.isVariableDeclaration(m)) return m.initializer ? [m.initializer] : [];
    if (ts.isFunctionLike(m)) return returned(m);
    return [m];
  }

  /**
   * The addresses an expression can evaluate to, each a string with `{}` for what is not known.
   * An expression that names no address evaluates to `[UNKNOWN]`.
   */
  urls(expr, depth = 0, env = undefined) {
    const n = unwrap(expr);
    if (!n || depth > MAX_DEPTH) return [UNKNOWN];
    if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) return [n.text];
    if (ts.isTemplateExpression(n)) {
      let acc = [n.head.text];
      for (const span of n.templateSpans) {
        const parts = this.urls(span.expression, depth + 1, env);
        const next = [];
        for (const a of acc) for (const p of parts) next.push(a + p + span.literal.text);
        acc = next.slice(0, 8);
      }
      return acc;
    }
    if (ts.isBinaryExpression(n) && n.operatorToken.kind === ts.SyntaxKind.PlusToken) {
      const out = [];
      for (const l of this.urls(n.left, depth + 1, env)) {
        for (const r of this.urls(n.right, depth + 1, env)) out.push(l + r);
      }
      return out.slice(0, 8);
    }
    if (ts.isConditionalExpression(n)) {
      return [...this.urls(n.whenTrue, depth + 1, env), ...this.urls(n.whenFalse, depth + 1, env)];
    }
    if (n.kind === ts.SyntaxKind.NullKeyword) return [];
    if (ts.isIdentifier(n) && n.text === 'undefined') return [];
    if (ts.isPropertyAccessExpression(n)) {
      if (n.name.text === 'apiBaseUrl') return ['/rest'];
      if (n.name.text === 'url' && ts.isCallExpression(unwrap(n.expression))) {
        // this.typesRequest().url: the url of the request object the method returns.
        const objects = this.requestObjects(n.expression, depth + 1);
        if (objects.length) return objects.flatMap((o) => this.urls(o, depth + 1, env));
      }
      if (n.expression.kind === ts.SyntaxKind.ThisKeyword) {
        const cls = this.ownClass(n);
        const m = this.memberOf(cls, n.name.text);
        if (m && ts.isPropertyDeclaration(m) && m.initializer && !injectedClass(m.initializer)) {
          return this.followMember(cls, n.name.text, m, depth);
        }
      }
      return [UNKNOWN];
    }
    if (ts.isIdentifier(n)) {
      if (env?.has(n.text)) {
        const bound = env.get(n.text);
        return this.urls(bound.node, depth + 1, bound.env);
      }
      // A parameter or a local named apiBaseUrl: the configuration's base, /rest.
      if (n.text === 'apiBaseUrl') return ['/rest'];
      const local = localBinding(n, n.text);
      if (local) return this.urls(local, depth + 1, env);
      if (local === null) return [UNKNOWN];
      const destructured = localDestructure(n, n.text);
      if (destructured?.key === 'url') {
        return this.requestObjects(destructured.init, depth + 1).flatMap((o) =>
          this.urls(o, depth + 1, env),
        );
      }
      const mod = this.fileOf(n).bindings.get(n.text) ?? this.importedBinding(n, n.text);
      if (mod && !ts.isFunctionLike(mod)) return this.urls(mod, depth + 1);
      return [UNKNOWN];
    }
    if (ts.isCallExpression(n)) return this.callUrls(n, depth, env);
    if (ts.isArrowFunction(n) || ts.isFunctionExpression(n)) {
      return returned(n).flatMap((r) => this.urls(r, depth + 1, env));
    }
    if (ts.isObjectLiteralExpression(n)) {
      const url = n.properties.find(
        (p) => p.name && ts.isIdentifier(p.name) && p.name.text === 'url',
      );
      if (url && ts.isPropertyAssignment(url)) return this.urls(url.initializer, depth + 1, env);
    }
    return [UNKNOWN];
  }

  ownClass(node) {
    const decl = enclosingClass(node);
    return decl ? this.fileOf(node).classes.find((c) => c.node === decl) : undefined;
  }

  followMember(cls, name, m, depth) {
    this.reach({ node: m, cls: cls?.name, name });
    return this.memberValues(m).flatMap((v) => this.urls(v, depth + 1));
  }

  /** The member a call expression invokes, with its class: `this.m()`, `this.f.m()`, `fn()`. */
  callee(call) {
    const c = unwrap(call.expression);
    if (ts.isPropertyAccessExpression(c)) {
      const recv = unwrap(c.expression);
      if (recv.kind === ts.SyntaxKind.ThisKeyword) {
        const cls = this.ownClass(call);
        return { cls, name: c.name.text, node: this.memberOf(cls, c.name.text) };
      }
      if (
        ts.isPropertyAccessExpression(recv) &&
        recv.expression.kind === ts.SyntaxKind.ThisKeyword
      ) {
        const cls = this.fieldClass(call, recv.name.text);
        return { cls, name: c.name.text, node: this.memberOf(cls, c.name.text) };
      }
      return { name: c.name.text };
    }
    if (ts.isIdentifier(c)) {
      const node = this.fileOf(call).bindings.get(c.text) ?? this.importedBinding(call, c.text);
      return { name: c.text, node, module: true };
    }
    return {};
  }

  callUrls(call, depth, env) {
    const c = unwrap(call.expression);
    if (ts.isIdentifier(c) && c.text === 'encodeURIComponent') return [UNKNOWN];
    if (ts.isIdentifier(c) && c.text === 'computed' && call.arguments[0]) {
      return this.urls(call.arguments[0], depth + 1, env);
    }
    const target = this.callee(call);
    const bound = bindArguments(target.node, call, env);
    if (target.node && (ts.isFunctionLike(target.node) || ts.isPropertyDeclaration(target.node))) {
      this.reach({ node: target.node, cls: target.cls?.name, name: target.name });
      const values = ts.isPropertyDeclaration(target.node)
        ? this.memberValues(target.node)
        : returned(target.node);
      return values.flatMap((v) => this.urls(v, depth + 1, bound));
    }
    if (target.node && ts.isArrowFunction(target.node)) {
      this.reach({ node: target.node, name: target.name });
      return returned(target.node).flatMap((v) => this.urls(v, depth + 1, bound));
    }
    return [UNKNOWN];
  }

  /** The object literals a request expression evaluates to (`{ url, params }` of an httpResource). */
  requestObjects(expr, depth = 0) {
    const n = unwrap(expr);
    if (!n || depth > MAX_DEPTH) return [];
    if (ts.isObjectLiteralExpression(n)) return [n];
    if (ts.isConditionalExpression(n)) {
      return [
        ...this.requestObjects(n.whenTrue, depth + 1),
        ...this.requestObjects(n.whenFalse, depth + 1),
      ];
    }
    if (ts.isArrowFunction(n) || ts.isFunctionExpression(n)) {
      return returned(n).flatMap((r) => this.requestObjects(r, depth + 1));
    }
    if (ts.isIdentifier(n)) {
      const local = localBinding(n, n.text);
      return local ? this.requestObjects(local, depth + 1) : [];
    }
    if (ts.isCallExpression(n)) {
      const target = this.callee(n);
      if (target.node && ts.isFunctionLike(target.node)) {
        this.reach({ node: target.node, cls: target.cls?.name, name: target.name });
        return returned(target.node).flatMap((r) => this.requestObjects(r, depth + 1));
      }
    }
    return [];
  }

  /** The query parameter names an options or request object sends, and whether some are unseen. */
  paramsOf(objectNode, site) {
    const out = { names: new Set(), dynamic: false };
    const n = unwrap(objectNode);
    if (!n || !ts.isObjectLiteralExpression(n)) return out;
    const prop = n.properties.find(
      (p) => p.name && ts.isIdentifier(p.name) && p.name.text === 'params',
    );
    if (!prop) return out;
    if (ts.isShorthandPropertyAssignment(prop)) {
      this.paramsFromExpression(prop.name, site, out);
    } else if (ts.isPropertyAssignment(prop)) {
      this.paramsFromExpression(prop.initializer, site, out);
    }
    return out;
  }

  paramsFromExpression(expr, site, out, depth = 0) {
    const n = unwrap(expr);
    if (!n || depth > MAX_DEPTH) return;
    if (ts.isObjectLiteralExpression(n)) {
      for (const p of n.properties) {
        if (ts.isSpreadAssignment(p)) {
          const inner = unwrap(p.expression);
          if (ts.isConditionalExpression(inner)) {
            this.paramsFromExpression(inner.whenTrue, site, out, depth + 1);
            this.paramsFromExpression(inner.whenFalse, site, out, depth + 1);
          } else if (ts.isObjectLiteralExpression(inner)) {
            this.paramsFromExpression(inner, site, out, depth + 1);
          } else {
            out.dynamic = true;
          }
        } else if (p.name && (ts.isIdentifier(p.name) || ts.isStringLiteral(p.name))) {
          out.names.add(p.name.text);
        }
      }
      return;
    }
    if (ts.isConditionalExpression(n)) {
      this.paramsFromExpression(n.whenTrue, site, out, depth + 1);
      this.paramsFromExpression(n.whenFalse, site, out, depth + 1);
      return;
    }
    if (ts.isIdentifier(n)) {
      const destructured = localDestructure(site, n.text);
      if (destructured?.key === 'params') {
        for (const o of this.requestObjects(destructured.init)) {
          const inner = this.paramsOf(o, o);
          inner.names.forEach((x) => out.names.add(x));
          out.dynamic ||= inner.dynamic;
        }
        return;
      }
      const local = localBinding(site, n.text);
      if (local === null) {
        out.dynamic = true;
        return;
      }
      if (local) this.paramsFromExpression(local, site, out, depth + 1);
      this.setCalls(site, n.text, out);
      return;
    }
    if (ts.isNewExpression(n)) {
      const arg = n.arguments?.[0] && unwrap(n.arguments[0]);
      if (arg && ts.isObjectLiteralExpression(arg)) {
        for (const p of arg.properties) {
          if (!p.name || !ts.isIdentifier(p.name)) continue;
          if (p.name.text === 'fromObject' && ts.isPropertyAssignment(p)) {
            this.paramsFromExpression(p.initializer, site, out, depth + 1);
          } else if (p.name.text === 'fromString') {
            out.dynamic = true;
          }
        }
      } else if (arg) {
        out.dynamic = true;
      }
      return;
    }
    if (ts.isCallExpression(n)) {
      // new HttpParams().set('a', …).set('b', …)
      const c = unwrap(n.expression);
      if (ts.isPropertyAccessExpression(c) && ['set', 'append'].includes(c.name.text)) {
        const key = n.arguments[0] && unwrap(n.arguments[0]);
        if (key && ts.isStringLiteral(key)) out.names.add(key.text);
        else out.dynamic = true;
        this.paramsFromExpression(c.expression, site, out, depth + 1);
        return;
      }
      // const params = this.buildParams(…): what that method sets on the object it returns.
      const target = this.callee(n);
      if (target.node && ts.isFunctionLike(target.node) && target.node.body) {
        for (const r of returned(target.node)) this.paramsFromExpression(r, r, out, depth + 1);
        return;
      }
      out.dynamic = true;
      return;
    }
    out.dynamic = true;
  }

  /** `<name>.set('k', …)` / `.append('k', …)` / `<name> = <name>.set(…)` in the function of `site`. */
  setCalls(site, name, out) {
    const fn = enclosingFunction(site);
    if (!fn) return;
    const visit = (n) => {
      if (ts.isCallExpression(n)) {
        const c = unwrap(n.expression);
        if (
          ts.isPropertyAccessExpression(c) &&
          ['set', 'append'].includes(c.name.text) &&
          ts.isIdentifier(unwrap(c.expression)) &&
          unwrap(c.expression).text === name
        ) {
          const key = n.arguments[0] && unwrap(n.arguments[0]);
          if (key && ts.isStringLiteral(key)) out.names.add(key.text);
          else out.dynamic = true;
        }
      }
      if (
        ts.isBinaryExpression(n) &&
        n.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
        ts.isElementAccessExpression(n.left) &&
        ts.isIdentifier(n.left.expression) &&
        n.left.expression.text === name
      ) {
        const key = unwrap(n.left.argumentExpression);
        if (ts.isStringLiteral(key)) out.names.add(key.text);
        else out.dynamic = true;
      }
      if (
        ts.isBinaryExpression(n) &&
        n.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
        ts.isPropertyAccessExpression(n.left) &&
        ts.isIdentifier(n.left.expression) &&
        n.left.expression.text === name
      ) {
        out.names.add(n.left.name.text);
      }
      ts.forEachChild(n, visit);
    };
    ts.forEachChild(fn.body, visit);
  }

  /** The roles the ticket service is asked for in a file: `*ticket*.fetch(role?)`. */
  ticketRolesInFile(sf) {
    const roles = new Set();
    const visit = (n) => {
      if (ts.isCallExpression(n)) {
        const c = unwrap(n.expression);
        if (
          ts.isPropertyAccessExpression(c) &&
          c.name.text === 'fetch' &&
          /ticket/i.test(c.expression.getText(sf))
        ) {
          const arg = n.arguments[0] && unwrap(n.arguments[0]);
          const values = !arg
            ? [null]
            : ts.isConditionalExpression(arg)
              ? [arg.whenTrue, arg.whenFalse].map(unwrap)
              : [arg];
          for (const v of values) {
            if (v === null || (ts.isIdentifier(v) && v.text === 'undefined')) roles.add(null);
            else if (ts.isStringLiteral(v)) roles.add(v.text);
            else roles.add('?');
          }
        }
      }
      ts.forEachChild(n, visit);
    };
    visit(sf);
    return [...roles];
  }

  /** Every site of the sources, as calls. */
  sites() {
    const calls = [];
    for (const info of this.files.values()) {
      const visit = (n) => {
        const site = this.site(n, info);
        if (site) calls.push(...site);
        ts.forEachChild(n, visit);
      };
      visit(info.sf);
    }
    calls.push(...this.wrapperCalls());
    return calls;
  }

  /** The calls of each helper whose address starts with one of its parameters, at its callers. */
  wrapperCalls() {
    const out = [];
    const wrappers = new Map(this.wrappers.map((w) => [w.fn, w]));
    if (wrappers.size === 0) return out;
    for (const info of this.files.values()) {
      const visit = (n) => {
        if (ts.isCallExpression(n)) {
          const target = this.callee(n);
          const w = target.node && wrappers.get(target.node);
          if (w) {
            const base = { app: info.app, file: info.file, line: lineOf(n) };
            const env = bindArguments(w.fn, n, undefined);
            const via = `${w.via} via ${target.name}`;
            const calls = this.build(
              base,
              w.verb,
              via,
              w.urlExpr,
              w.optionsExpr,
              w.siteNode,
              undefined,
              false,
              env,
            );
            // The caller's own extra parameters: an object literal passed as `extraParams` or `params`.
            const extra = new Set();
            for (const arg of n.arguments) {
              const o = unwrap(arg);
              if (!ts.isObjectLiteralExpression(o)) continue;
              for (const p of o.properties) {
                const v = ts.isPropertyAssignment(p) && unwrap(p.initializer);
                if (
                  v &&
                  ts.isObjectLiteralExpression(v) &&
                  ['extraParams', 'params'].includes(p.name.getText())
                ) {
                  for (const q of v.properties)
                    if (q.name) extra.add(q.name.getText().replace(/['"]/g, ''));
                }
              }
            }
            for (const c of calls) c.params = [...new Set([...c.params, ...extra])].sort();
            out.push(...calls);
          }
        }
        ts.forEachChild(n, visit);
      };
      visit(info.sf);
    }
    return out;
  }

  site(n, info) {
    const base = { app: info.app, file: info.file, line: lineOf(n) };
    if (ts.isCallExpression(n)) {
      const c = unwrap(n.expression);
      const name = ts.isPropertyAccessExpression(c)
        ? c.name.text
        : ts.isIdentifier(c)
          ? c.text
          : '';
      const recv = ts.isPropertyAccessExpression(c) ? unwrap(c.expression) : undefined;
      const recvText = recv ? recv.getText(info.sf) : '';
      if (recv && HTTP_VERBS.has(name) && /http/i.test(recvText) && n.arguments[0]) {
        const verb = name.toUpperCase();
        const options = n.arguments[['post', 'put', 'patch'].includes(name) ? 2 : 1];
        return this.build(base, verb, 'http', n.arguments[0], options, n);
      }
      if (recv && name === 'request' && /http/i.test(recvText) && n.arguments[1]) {
        const v = unwrap(n.arguments[0]);
        const verb = ts.isStringLiteral(v) ? v.text.toUpperCase() : '?';
        return this.build(base, verb, 'http', n.arguments[1], n.arguments[2], n);
      }
      const isResource =
        (ts.isIdentifier(c) && c.text === 'httpResource') ||
        (recv && ts.isIdentifier(recv) && recv.text === 'httpResource' && RESOURCE_FORMS.has(name));
      if (isResource && n.arguments[0]) return this.resource(base, n.arguments[0], n);
      if ((name === 'openTicketed' || name === 'openPublic') && n.arguments[0]) {
        const role = n.arguments[1] && unwrap(n.arguments[1]);
        const ticketRoles =
          name === 'openPublic'
            ? undefined
            : [role ? (ts.isStringLiteral(role) ? role.text : '?') : null];
        return this.build(base, 'GET', name, n.arguments[0], undefined, n, ticketRoles);
      }
      if (ts.isIdentifier(c) && c.text === 'fetch' && n.arguments[0]) {
        // The global fetch (the Keycloak configuration read before Angular starts).
        const init = n.arguments[1] && unwrap(n.arguments[1]);
        const method =
          init &&
          ts.isObjectLiteralExpression(init) &&
          init.properties.find((p) => p.name?.getText() === 'method');
        const m = method && ts.isPropertyAssignment(method) && unwrap(method.initializer);
        const verb = m && ts.isStringLiteral(m) ? m.text.toUpperCase() : 'GET';
        return this.build(base, verb, 'fetch', n.arguments[0], undefined, n);
      }
      if (name === 'open' && recvText === 'window' && n.arguments[0]) {
        return this.build(base, 'GET', 'window', n.arguments[0], undefined, n, undefined, true);
      }
      if (name === 'navigate' && /navigator/i.test(recvText) && n.arguments[0]) {
        return this.build(base, 'GET', 'window', n.arguments[0], undefined, n, undefined, true);
      }
    }
    if (
      ts.isBinaryExpression(n) &&
      n.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
      /^window\.location\.href$/.test(n.left.getText(info.sf))
    ) {
      return this.build(base, 'GET', 'window', n.right, undefined, n, undefined, true);
    }
    return undefined;
  }

  resource(base, arg, site) {
    const objects = this.requestObjects(arg);
    if (objects.length === 0) return this.build(base, 'GET', 'httpResource', arg, undefined, site);
    const out = [];
    for (const o of objects) {
      const url = o.properties.find(
        (p) => p.name && ts.isIdentifier(p.name) && p.name.text === 'url',
      );
      if (!url) continue;
      const method = o.properties.find(
        (p) => p.name && ts.isIdentifier(p.name) && p.name.text === 'method',
      );
      const m = method && ts.isPropertyAssignment(method) && unwrap(method.initializer);
      const verb = m && ts.isStringLiteral(m) ? m.text.toUpperCase() : 'GET';
      const urlExpr = ts.isPropertyAssignment(url) ? url.initializer : url.name;
      out.push(...this.build(base, verb, 'httpResource', urlExpr, o, o));
    }
    return out;
  }

  /**
   * The calls of one site: each address its URL expression can evaluate to. A site whose address
   * cannot be followed is kept with `path: null`; a non-REST address (an asset, another origin) is
   * dropped. `optional` sites (a browser navigation) are dropped unless they name a REST address.
   */
  build(base, verb, via, urlExpr, optionsExpr, siteNode, ticketRoles, optional = false, env) {
    const params = optionsExpr
      ? this.paramsOf(optionsExpr, siteNode)
      : { names: new Set(), dynamic: false };
    const out = [];
    for (const raw of new Set(this.urls(urlExpr, 0, env))) {
      const [p, q = ''] = raw.split('?');
      if (!env && p.startsWith(`/rest${UNKNOWN}`) && enclosingFunction(siteNode)) {
        // getByIdsCsv('/areas/search', ids): the call sites of the helper are the calls.
        this.wrappers.push({
          fn: enclosingFunction(siteNode),
          verb,
          via,
          urlExpr,
          optionsExpr,
          siteNode,
        });
        continue;
      }
      if (!p.startsWith('/rest')) {
        if (optional || !p.startsWith(UNKNOWN)) continue;
        out.push({ ...base, verb, via, path: null, expression: urlExpr.getText(), params: [] });
        continue;
      }
      const query = { names: new Set(params.names), dynamic: params.dynamic };
      for (const m of q.matchAll(/(?:^|&)([\w:.-]+)=/g)) query.names.add(m[1]);
      if (q.includes(UNKNOWN)) {
        // ?${params.toString()} or ?${query}: the names set on that object in the function.
        const ids = this.queryIdentifiers(urlExpr);
        if (ids.length === 0) query.dynamic = true;
        for (const id of ids) this.paramsFromExpression(id, id, query);
      }
      const { names, dynamic } = query;
      const ticketed = ticketRoles !== undefined || names.has('ticket');
      let roles = ticketRoles;
      if (ticketed && roles === undefined) roles = this.ticketRolesInFile(siteNode.getSourceFile());
      names.delete('ticket');
      out.push({
        ...base,
        verb,
        via,
        path: p,
        params: [...names].sort(),
        paramsDynamic: dynamic,
        ...(ticketed ? { ticketRoles: roles?.length ? roles : ['?'] } : {}),
      });
    }
    return out;
  }

  /** The identifiers a URL template interpolates after its `?`: `params` of `?${params.toString()}`. */
  queryIdentifiers(urlExpr, depth = 0) {
    const ids = [];
    if (depth > MAX_DEPTH) return ids;
    const top = unwrap(urlExpr);
    if (ts.isCallExpression(top)) {
      const target = this.callee(top);
      if (target.node && ts.isFunctionLike(target.node)) {
        for (const r of returned(target.node)) ids.push(...this.queryIdentifiers(r, depth + 1));
      }
      return ids;
    }
    if (ts.isConditionalExpression(top)) {
      ids.push(...this.queryIdentifiers(top.whenTrue, depth + 1));
      ids.push(...this.queryIdentifiers(top.whenFalse, depth + 1));
      return ids;
    }
    const visit = (n) => {
      if (ts.isTemplateExpression(n)) {
        let afterQuery = n.head.text.includes('?');
        for (const span of n.templateSpans) {
          if (afterQuery) {
            let e = unwrap(span.expression);
            if (ts.isCallExpression(e) && ts.isPropertyAccessExpression(e.expression)) {
              e = unwrap(e.expression.expression);
            }
            if (ts.isIdentifier(e)) ids.push(e);
          }
          if (span.literal.text.includes('?')) afterQuery = true;
        }
      }
      ts.forEachChild(n, visit);
    };
    visit(top);
    if (ts.isIdentifier(top)) {
      const local = localBinding(top, top.text);
      if (local) ids.push(...this.queryIdentifiers(local, depth + 1));
    }
    return ids;
  }

  /**
   * URL members reached from no site: class fields and methods, and module bindings, whose value
   * is a `/rest` address. Each is a call of its own: POST when its name says upload, else GET.
   */
  unreachedMembers() {
    const out = [];
    for (const info of this.files.values()) {
      const candidates = [];
      for (const cls of info.classes) {
        for (const [name, m] of cls.members) {
          if (ts.isPropertyDeclaration(m) && (!m.initializer || injectedClass(m.initializer)))
            continue;
          candidates.push({ cls: cls.name, name, node: m });
        }
      }
      for (const [name, node] of info.bindings) {
        if (ts.isFunctionLike(node) || ts.isFunctionDeclaration(node))
          candidates.push({ name, node });
        else candidates.push({ name, node: node.parent });
      }
      for (const cand of candidates) {
        const key = `${info.file}#${cand.cls ?? ''}.${cand.name}`;
        if (this.reached.has(key)) continue;
        const values = ts.isVariableDeclaration(cand.node)
          ? [cand.node.initializer]
          : this.memberValues(cand.node);
        const urls = [...new Set(values.flatMap((v) => this.urls(v)))].filter((u) =>
          u.startsWith('/rest/'),
        );
        if (urls.length === 0) continue;
        // A prefix (a base address other members extend) is no call of its own.
        if (this.isPrefixOnly(info, cand.name)) continue;
        const verb = /upload/i.test(cand.name) ? 'POST' : 'GET';
        for (const u of urls) {
          const [p, q = ''] = u.split('?');
          out.push({
            app: info.app,
            file: info.file,
            line: lineOf(cand.node),
            verb,
            via: `member ${cand.cls ? `${cand.cls}.` : ''}${cand.name}`,
            path: p,
            params: [...q.matchAll(/(?:^|&)([\w:.-]+)=/g)].map((m) => m[1]).sort(),
            paramsDynamic: q.includes(UNKNOWN),
          });
        }
      }
    }
    return out;
  }

  /** True when every use of a member in its file is inside a template literal or a `+` (a prefix). */
  isPrefixOnly(info, name) {
    let uses = 0;
    let prefix = 0;
    const visit = (n) => {
      const isUse =
        (ts.isPropertyAccessExpression(n) &&
          n.expression.kind === ts.SyntaxKind.ThisKeyword &&
          n.name.text === name) ||
        (ts.isIdentifier(n) &&
          n.text === name &&
          !(n.parent && ts.isPropertyAccessExpression(n.parent) && n.parent.name === n) &&
          !(
            n.parent &&
            (ts.isVariableDeclaration(n.parent) || ts.isPropertyDeclaration(n.parent))
          ));
      if (isUse) {
        uses++;
        const p = n.parent;
        if (
          (p && ts.isTemplateSpan(p)) ||
          (p && ts.isBinaryExpression(p) && p.operatorToken.kind === ts.SyntaxKind.PlusToken)
        ) {
          prefix++;
        }
      }
      ts.forEachChild(n, visit);
    };
    visit(info.sf);
    return uses > 0 && uses === prefix;
  }
}

/**
 * Every call of the administration app, the public site and the shared folders.
 * `path` is the address without its query, with `{}` for each part not known, or null when the
 * site's address could not be followed (`expression` then holds its source).
 */
export function extractCalls(feRoot) {
  const ex = new Extractor(feRoot);
  const calls = ex.sites();
  calls.push(...ex.unreachedMembers());
  const seen = new Set();
  return calls
    .filter((c) => {
      const key = JSON.stringify(c);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line);
}

/** Every file that gates on a role of the auth facade or a route's `rolesRequired`. */
export const GATE_WORDS =
  /\b(?:hasRole|hasAnyRole|isEditor|hasDomainUserRole|adminIn|canManageUsers|isAuthenticated|rolesRequired)\b/;

// node scripts/endpoint-roles/extract-calls.mjs: prints the calls as JSON.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const feRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  process.stdout.write(`${JSON.stringify(extractCalls(feRoot), null, 1)}\n`);
}
