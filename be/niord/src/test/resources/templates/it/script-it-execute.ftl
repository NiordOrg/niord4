<#-- Test-only Freemarker template: ?new() of a class outside org.niord.core.script.directive must be refused. -->
<#assign ex = "freemarker.template.utility.Execute"?new()>
<p>OUT=${ex("echo hi")}</p>
