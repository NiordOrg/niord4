<#include "message-support.ftl"/>

<html>
<head>

    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <title>${text("pdf.list.title")}</title>

    <@pageSizeStyle frontPage=frontPage!true/>
    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">
    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">

</head>
<body>


<#-- The moment the list was made, by the language's date-and-time text, in the zone the print is dated in -->
<#assign generated = .now?string(text('msg.time.date_time_format'))/>

<@renderDefaultHeaderAndFooter headerText="Generated ${generated}" frontPage=frontPage!true/>


<#if frontPage!true>

    <h1>${text("pdf.list.title")}</h1>

    <div style="margin: 1cm 0">
        <table border="0">
            <tr>
                <th align="right" valign="top" nowrap>Generated: &nbsp;</th>
                <td>${generated}</td>
            </tr>
            <tr>
                <th align="right" valign="top" nowrap>Criteria: &nbsp;</th>
                <td><small>${searchCriteria}</small></td>
            </tr>
            <tr>
                <th align="right" valign="top" nowrap>Result: &nbsp;</th>
                <td>${messages?size} messages</td>
            </tr>
        </table>
    </div>

    <@renderTOC messages=messages areaHeadings=areaHeadings />

    <div class="page-break">&nbsp;</div>

</#if>

<@renderMessageList messages=messages areaHeadings=areaHeadings/>

</body>
</html>
