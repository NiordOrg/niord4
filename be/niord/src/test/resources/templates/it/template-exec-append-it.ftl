<#--
  Test-only template that APPENDS to the message part subject (update="append"), used to
  exercise TemplateExecutionService.getUpdateScript append semantics:
  field = (field == null) ? content : field + '\n' + content;
-->
<field-template field="part.getDesc('en').subject" format="text" update="append">
appended-line
</field-template>
