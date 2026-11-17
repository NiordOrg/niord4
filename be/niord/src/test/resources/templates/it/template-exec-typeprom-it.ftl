<#--
  Test-only template: a promulgation-type script resource. When TemplateExecutionService runs
  the per-promulgation-type script loop it binds the current promulgation as "promulgation".
  This field-template assigns to that promulgation's text field.
-->
<field-template field="promulgation.text" update="append">
TYPEPROMMARKER
</field-template>
