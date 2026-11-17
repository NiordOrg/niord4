<#-- Test-only Freemarker template used by MessageMailService integration tests.
     Builds a message link in both forms a message-support.ftl can build: the hash-routed
     form from baseUri, and the message-link form from adminAppUrl. -->
HASH-LINK=<a href="${baseUri}/#/message/NW-001-24">NW-001-24</a>
MESSAGE-LINK=<a href="${adminAppUrl}/messages?messageId=NW-002-24">NW-002-24</a>
