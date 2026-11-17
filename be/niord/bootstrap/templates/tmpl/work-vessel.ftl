
<#-- ***************************************  -->
<#-- Renders information about a work vessel  -->
<#-- ***************************************  -->

<#macro renderWorkVessel vessel lang="en" format="details">
    <#setting locale=lang>

    <#if vessel?has_content && (vessel.name?has_content || vessel.callSign?has_content)>

        <#assign csFormat=(format == 'audio')?then('verbose','normal')/>

        <#if lang == "en">

            <#if vessel.name?has_content>
                <#if format == 'navtex'>
                    By <@quote text=vessel.name/>
                <#elseif format == 'audio'>
                    Work is carried out by <@quote text=vessel.name/>
                <#else>
                    Work is carried out by <@quote text=vessel.name format="angular"/>
                </#if>
            <#elseif format == 'navtex'>
                By vessel with
            <#else>
                Work is carried out by the vessel with
            </#if>
            <#if vessel.callSign?has_content>call sign <@callSignFormat callSign=vessel.callSign format=csFormat/></#if>.
            <#if vessel.guardVessels!false>
                <#if format == 'navtex'>
                    Guard vessels in area.
                <#else>
                    Guard vessels will be in the area.
                </#if>
            </#if>
            <#if vessel.contact!false>
                <#if format == 'navtex'>
                    Listening VHF CH 16
                    <#if vessel.channel?has_content>and ${vessel.channel}</#if>.
                <#else>
                    The <#if vessel.guardVessels!false>vessels are<#else>vessel is</#if>
                    listening on VHF channel 16
                    <#if vessel.channel?has_content>and ${vessel.channel}</#if>.
                </#if>
            </#if>
        </#if>
    </#if>

    <#-- The minimum distance without a grouping comma, which in NAVTEX reads as a decimal sign ("1,000M"); a text as
         it was typed (?c would quote it) (TMPL14) -->
    <#if vessel?has_content && vessel.minDist?has_content>
        <#local minDist=vessel.minDist?is_number?then(vessel.minDist?c, vessel.minDist?string)/>
    </#if>
    <#if format == 'navtex'>
        MARINERS REQUESTED TO PASS WITH CAUTION
        <#if vessel.minDist?has_content>
            KEEPING MINIMUM DISTANCE ${minDist}${(vessel.minDistType!'m')?upper_case}
        </#if>.
    <#else>
        Mariners are requested to pass with caution
        <#if vessel.minDist?has_content>
            and keep a minimum distance of ${minDist}${(vessel.minDistType!'m')}
        </#if>.
    </#if>
</#macro>
