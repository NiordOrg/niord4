<#--
  Test-only Freemarker template used by TemplateExecutionServiceITTest to exercise the
  TemplateExecutionService field-template execution path (FM render -> FieldTemplateProcessor
  -> Nashorn field assignment). It deliberately uses only the "params", "part" and "message"
  context entries so it is independent of the seeded message dictionaries.

  Loaded from the classpath via the FmTemplateLoader classpath fallback; under @TestTransaction
  the resulting ScriptResource row is rolled back.
-->
<field-template field="part.getDesc('en').subject" format="text">
    Light ${params.name!'X'} unlit
</field-template>

<field-template field="part.getDesc('en').details" format="html">
    <p>The light is unlit.</p>
</field-template>
