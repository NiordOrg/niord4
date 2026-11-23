<#include "message-support.ftl"/>

<html>
<head>

    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <title>${text("pdf.list.title")}</title>

    <@pageSizeStyle />
    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">
    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">

    <style type="text/css" media="all">
        table.contact-info {
            margin-bottom: 5mm;
            font-size: 11px;
        }
        table.contact-info tr td {
            padding: 3mm;
        }
    </style>

</head>
<body>

<@renderDefaultHeaderAndFooter headerText="${text('pdf.nm_almanac.title')} - ${text('pdf.nm_almanac.subtitle')}"/>

<h1 style="margin-top: 5mm">${text("pdf.nm_almanac.title")}</h1>

<h5>${text("pdf.nm_almanac.desc")}</h5>

<table class="first-page-info-line" style="margin-top: 5mm">
    <tr>
        <td>
            ${.now?string(text('msg.time.date_format'))}
            <#-- The edition's label is the text pdf.edition; a deployment without it prints the English word -->
            <#if edition?? && edition != '1'> - <#attempt>${text('pdf.edition', edition)}<#recover>Version ${edition}</#attempt></#if>
        </td>
        <td align="right">${text("pdf.nm_almanac.subtitle")}</td>
    </tr>
</table>

<div>
  <#include "nm-almanac-report-pdf-intro.ftl">
</div>

<div>
    <h2>${text("pdf.toc")}</h2>
    <table width="100%" cellpadding="1" cellspacing="0">
        <#list messages as msg>
            <#if msg.descs?has_content && msg.descs[0].title?has_content>
                <tr>
                    <td style="width: 2cm" valign="top">
                        <a href='#msg_${msg.id}'><#if msg.shortId?has_content>${msg.shortId}</#if></a>
                    </td>
                    <td width="*">
                        <div class="toc"><a href='#msg_${msg.id}'>${msg.descs[0].title}</a></div>
                    </td>
                </tr>
            </#if>
        </#list>
    </table>
</div>


<div class="page-break">&nbsp;</div>

<@renderMessageList messages=messages areaHeadings=areaHeadings/>

</body>
</html>
