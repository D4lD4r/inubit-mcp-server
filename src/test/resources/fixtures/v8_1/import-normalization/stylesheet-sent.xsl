<?xml version="1.0" encoding="UTF-8"?>
<!-- Builds the test input of Workflow-0002.
     The placeholders are replaced from the workflow variables. -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:misc="java:org.example.Misc" exclude-result-prefixes="misc" version="2.0">
  <xsl:output method="xml" encoding="UTF-8"/>
  <xsl:param name="var.test.partner"/>
  <xsl:template match="/">
    <testInput xmlns:env="http://schemas.xmlsoap.org/soap/envelope/"
               xmlns:acme="http://acme.example.test/core/1.0">
      <acme:partner><xsl:value-of select="$var.test.partner"/></acme:partner>
      <acme:separator><xsl:text> </xsl:text></acme:separator>
    </testInput>
  </xsl:template>
  <xsl:template match="text()">
    <xsl:value-of select="replace(replace(.,
      '#partner#', $var.test.partner),
      '#now#', string(current-dateTime()))"/>
  </xsl:template>
</xsl:stylesheet>
