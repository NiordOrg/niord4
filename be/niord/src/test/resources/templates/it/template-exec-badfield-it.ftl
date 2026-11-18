<#--
  Test-only Freemarker template used by TemplateExecutionServiceITTest: a template written for a language the
  deployment does not have. The English subject can be assigned; the details of the language "xx" cannot, as the
  part has no such description, so the template run fails.
-->
<field-template field="part.getDesc('en').subject" format="text">ok</field-template>

<field-template field="part.getDesc('xx').details" format="html"><p>x</p></field-template>
