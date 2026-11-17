<#--
  Test-only template: a main category template whose field-template targets a message
  promulgation field. Mirrors the production aton-common.ftl pattern of populating
  message.promulgation('navtex').text from a category template.
-->
<field-template field="message.promulgation('navtex').text" update="append">
MSGPROMMARKER
</field-template>
