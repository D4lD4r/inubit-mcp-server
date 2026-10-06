<?xml version="1.0" encoding="UTF-8"?>
<!-- Calls the INUBIT extension functions the local stand-ins serve, in the namespaces and with the
     arities found in real stylesheets (spike section 7): a GUID, a date conversion
     ('<input pattern>|<output pattern>'), the current time, a random UUID and a sleep. -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:Misc="java:com.inubit.ibis.xsltext.Misc"
    xmlns:Formatter="java:com.inubit.ibis.xsltext.Formatter"
    xmlns:UUID="java:java.util.UUID"
    xmlns:Thread="java:java.lang.Thread"
    exclude-result-prefixes="#all" version="3.0">
  <xsl:output method="xml" encoding="UTF-8" indent="yes"/>
  <xsl:template match="/order">
    <message>
      <id><xsl:value-of select="Misc:guid()"/></id>
      <correlation><xsl:value-of select="string(UUID:randomUUID())"/></correlation>
      <orderDate><xsl:value-of select="Formatter:changeDateFormat(string(@date), 'yyyy-MM-dd|dd.MM.yyyy')"/></orderDate>
      <createdAt><xsl:value-of select="Formatter:getDateTime('yyyy-MM-dd HH:mm:ss')"/></createdAt>
      <xsl:sequence select="Thread:sleep(xs:integer(10))"/>
    </message>
  </xsl:template>
</xsl:stylesheet>
