<#include "common.ftl"/>

<#-- The template serves the RACON and the AIS category. The category it runs for decides, by its own AIS parameter
     (type or id 'ais'), not whether the optional AIS status was filled in (TMPL9); a category without one falls
     back to the status. Null-safe: a parameter without a type or an id must not fail the template. -->
<#assign aisCategory = template?? && template.templateParams?? && template.templateParams?filter(p -> ((p.type)!'') == 'ais' || ((p.paramId)!'') == 'ais')?has_content/>

<#function computeRadioType statusParam >
    <#if aisCategory || statusParam.ais_status??>
        <#return 'AIS' />
    </#if>
    <#return 'RACON' />
</#function>

<#macro renderStatus statusParam format='long' lang='en'>
    <#assign type=computeRadioType(statusParam)/>
    <#if type == 'AIS'>
        <#assign status=statusParam.ais_status! />
    <#else>
        <#assign status=statusParam.racon_status! />
    </#if>
    <#if status??>
        <#assign desc=descForLang(status, lang)!>
        <#if desc?? && desc.longValue?has_content && format == 'long'>
            ${desc.longValue}
        <#elseif desc?? && desc.value?has_content>
            ${desc.value}
        </#if>
    </#if>
</#macro>

<#-- The subject is one line (TMPL15): the positions are joined by a space, and the line break the status macro
     prints before the full stop is taken out by the field template's clean-up of space before a full stop. The #if
     keeps the subject of a warning without a position as empty as it was. -->
<field-template field="part.getDesc('en').subject" format="text">
    <#if (params.positions![])?has_content>
    <#list params.positions as pos>${computeRadioType(pos)} <@renderStatus statusParam=pos format="normal" lang="en"/>.<#sep> </#list>
    </#if>
</field-template>

<field-template field="part.getDesc('en').details" format="html">
    <#list params.positions![] as pos>
        ${computeRadioType(pos)} on
        <@renderAtonType atonParams=pos defaultName="AtoN" format="long" lang="en" capFirst=false/>
        <@renderPositionList geomParam=pos lang="en"/>
        <@renderStatus statusParam=pos format="long" lang="en"/>.<br>
    </#list>
</field-template>

<#if promulgate('navtex')>
    <field-template field="message.promulgation('navtex').text" update="append">
        <#list params.positions![] as pos>
            <@line format="navtex">
                ${computeRadioType(pos)} on
                <@renderAtonType atonParams=pos defaultName="AtoN" format="short" lang="en"/>
                <@renderPositionList geomParam=pos format="navtex" lang="en"/>
                <@renderStatus statusParam=pos format="normal" lang="en"/>.
            </@line>
        </#list>
    </field-template>
</#if>

<#if promulgate('safetynet')>
    <field-template field="message.promulgation('safetynet').text" update="append">
        <#list params.positions![] as pos>
            <@line format="navtex">
                ${computeRadioType(pos)} on
                <@renderAtonType atonParams=pos defaultName="AtoN" format="short" lang="en"/>
                <@renderPositionList geomParam=pos format="navtex" lang="en"/>
                <@renderStatus statusParam=pos format="normal" lang="en"/>.
            </@line>
        </#list>
    </field-template>
</#if>
