<#-- Test-only Freemarker template used by MessageMailService integration tests.
     Echoes the data values that MessageMailService.sendMessageMail binds, so the
     test can assert that template rendering and data binding actually happened. -->
IT-MESSAGE-MAIL lang=${language} to=${mailTo} sender=${mailSender} count=${messages?size}
MESSAGE-BODY:${mailMessage}:END
