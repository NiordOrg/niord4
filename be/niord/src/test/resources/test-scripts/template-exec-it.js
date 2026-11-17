/*
 * Test-only JavaScript resource used by TemplateExecutionServiceITTest to exercise the
 * TemplateExecutionService JavaScript execution path (JsResourceService + Nashorn bindings).
 *
 * The TemplateExecutionService puts "message", "part" and "params" into the context data, and
 * JsResourceService additionally binds "em" and "messageService". This script mutates the
 * message part details from the supplied template parameters.
 *
 * Loaded from the classpath via the JsResourceService classpath fallback; under @TestTransaction
 * the resulting ScriptResource row is rolled back.
 */
part.getDesc('en').setDetails('JS-' + params.get('name'));
