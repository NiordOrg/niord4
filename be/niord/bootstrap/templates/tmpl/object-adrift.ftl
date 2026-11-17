<#include "common.ftl"/>
<#include "markings.ftl"/>

<#macro renderObject defaultName format='long' lang='en' capFirst=false>
    <#assign result=defaultName!''/>
    <#if params.object?has_content>
        <#assign desc=descForLang(params.object, lang)!>
        <#if desc?? && format == 'long'>
            <#assign result=desc.longValue/>
        <#elseif desc??>
            <#assign result=desc.value/>
        </#if>
    </#if>
    <#if capFirst>
        <#assign result=result?cap_first/>
    </#if>
    ${result}
</#macro>

<#-- The list values whose English name is plural; a deployment that adds its own adds their keys here -->
<#assign pluralObjects=['object.adrift.logs', 'object.adrift.fishing_stakes']/>
<#assign objectVerb=(params.object?? && params.object.key?? && pluralObjects?seq_contains(params.object.key))?then('have', 'has')/>


<#-- The subject is one line (TMPL15): renderObject prints its name on a line of its own, so it is captured and trimmed -->
<#assign objectName><@renderObject defaultName="Object" format="short" lang="en" capFirst=true/></#assign>
<field-template field="part.getDesc('en').subject" format="text">
    ${objectName?trim} adrift
</field-template>

<field-template field="part.getDesc('en').details" format="html">
    <@renderObject defaultName="an object" format="long" lang="en" capFirst=true/>
    ${objectVerb} been observed adrift <@renderPositionList geomParam=part lang="en"/>
    <#if params.date??><@renderDate date=params.date lang="en" tz="UTC"/></#if>.
    <#if params.cancelDate??>
        <p>Cancel this warning <@renderDate date=params.cancelDate lang="en" tz="UTC"/>.</p>
    </#if>
</field-template>

<#if promulgate('navtex')>
    <field-template field="message.promulgation('navtex').text" update="append">
        <@line format="navtex">
            <@renderObject defaultName="an object" format="short" lang="en" capFirst=true/>
            ADRIFT <@renderPositionList geomParam=part format="navtex" lang="en"/>
            <#if params.date??><@renderDate date=params.date lang="en" format="navtex"/></#if>.
        </@line>
    </field-template>
</#if>

<#if promulgate('safetynet')>
    <field-template field="message.promulgation('safetynet').text" update="append">
        <@line format="navtex">
            <@renderObject defaultName="an object" format="short" lang="en" capFirst=true/>
            ADRIFT <@renderPositionList geomParam=part format="navtex" lang="en"/>
            <#if params.date??><@renderDate date=params.date lang="en" format="navtex"/></#if>.
        </@line>
    </field-template>
</#if>
