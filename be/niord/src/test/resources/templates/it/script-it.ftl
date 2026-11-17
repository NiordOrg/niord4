<#-- Test-only Freemarker template for the script-rendering integration tests. -->
<#-- Exercises the directive ?new() instantiation path used by the real templates, plus the -->
<#-- standard data properties (country/draft) that FmTemplateService always injects. -->
<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>
<#assign line = "org.niord.core.script.directive.LineDirective"?new()>
<#assign quote = "org.niord.core.script.directive.QuoteDirective"?new()>
<#assign trailingDot = "org.niord.core.script.directive.TrailingDotDirective"?new()>
<html><body>
<p>POS=<@formatPos lat=55.0 lon=12.0/></p>
<p>LINE=<@line>hello   world</@line></p>
<p>QUOTE=<@quote text="hi"/></p>
<p>DOT=<@trailingDot>foo</@trailingDot></p>
<p>DRAFT=${draft?c}</p>
</body></html>
