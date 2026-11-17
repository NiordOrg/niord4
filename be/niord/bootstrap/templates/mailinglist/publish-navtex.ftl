
<#include "../messages/message-support.ftl"/>

<#assign navtex=promulgation(message, 'navtex')!>

<#if navtex??>
    <#if navtex.preamble?has_content><@txtToHtml text=navtex.preamble/><br></#if>
    <#if navtex.text?has_content><@txtToHtml text=navtex.text/></#if>
    <#if navtex.priority?? && navtex.priority != 'NONE'>
      <p>&nbsp;</p>
      <p>
        PRIORITY<br>
        ${navtex.priority}
      </p>
    </#if>
</#if>
