<#include "common.ftl"/>

<#-- The water depth as it is printed, or empty when none was entered (TMPL8). Missing and an empty or blank text
     count as not entered; a number prints without a thousands separator, a text as it was typed (without ?c,
     which would quote it). -->
<#assign waterDepth = ''/>
<#if params.water_depth?has_content>
    <#assign waterDepth = params.water_depth?is_number?then(params.water_depth?c, params.water_depth?string?trim)/>
</#if>

<@defaultSubjectFieldTemplates/>

<field-template field="part.getDesc('en').details" format="html">
    <#setting locale="en">
    <#if waterDepth?has_content>Water depths down to ${waterDepth}m<#else>Reduced water depths</#if>
    have been observed
    <@renderPositionList geomParam=part lang="en"/>
    <#if params.locality?has_content>in the entrance to ${params.locality}</#if>.<br>
    Mariners are advised to keep well clear.
</field-template>

<#if promulgate('navtex')>
    <field-template field="message.promulgation('navtex').text" update="append">
        <#setting locale="en">
        <@line format="navtex">
            <#if waterDepth?has_content>Water depths down to ${waterDepth}M<#else>REDUCED WATER DEPTHS</#if> OBSERVED
            <@renderPositionList geomParam=part format="navtex" lang="en"/>
            <#if params.locality?has_content>IN ENTRANCE TO ${params.locality}</#if>.
        </@line>
        <@line format="navtex">
            MARINERS ADVISED TO KEEP CLEAR.
        </@line>
    </field-template>
</#if>

<#if promulgate('safetynet')>
    <field-template field="message.promulgation('safetynet').text" update="append">
        <#setting locale="en">
        <@line format="navtex">
            <#if waterDepth?has_content>Water depths down to ${waterDepth}M<#else>REDUCED WATER DEPTHS</#if> OBSERVED
            <@renderPositionList geomParam=part format="navtex" lang="en"/>
            <#if params.locality?has_content>IN ENTRANCE TO ${params.locality}</#if>.
        </@line>
        <@line format="navtex">
            MARINERS ADVISED TO KEEP CLEAR.
        </@line>
    </field-template>
</#if>
