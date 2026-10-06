<?xml version="1.0" encoding="UTF-8"?>
<!-- Calls a Java extension function no stand-in exists for: not testable locally. -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:Tool="java:com.example.fixture.UnknownTool"
    exclude-result-prefixes="#all" version="3.0">
  <xsl:output method="xml" encoding="UTF-8"/>
  <xsl:template match="/order">
    <checked valid="{Tool:validate(string(@id), 'fixture')}"/>
  </xsl:template>
</xsl:stylesheet>
