<?xml version="1.0" encoding="UTF-8"?>
<!-- Well-formed XML with a static XPath error on line 7 (unclosed function call). -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
  <xsl:output method="xml" encoding="UTF-8"/>
  <xsl:template match="/order">
    <invoice>
      <xsl:value-of select="concat(@id, '-', customer"/>
    </invoice>
  </xsl:template>
</xsl:stylesheet>
