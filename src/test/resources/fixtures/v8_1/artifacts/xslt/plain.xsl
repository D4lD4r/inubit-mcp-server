<?xml version="1.0" encoding="UTF-8"?>
<!-- Plain XSLT 3.0, no extension functions: input.xml -> an invoice with the order total. -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
  <xsl:output method="xml" encoding="UTF-8" indent="yes"/>
  <xsl:template match="/order">
    <invoice order="{@id}">
      <customer><xsl:value-of select="customer"/></customer>
      <total currency="EUR"><xsl:value-of select="format-number(sum(item/(@quantity * @price)), '0.00')"/></total>
    </invoice>
  </xsl:template>
</xsl:stylesheet>
