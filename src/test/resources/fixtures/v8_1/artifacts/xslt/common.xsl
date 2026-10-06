<?xml version="1.0" encoding="UTF-8"?>
<!-- The repository file imported by repository-import.xsl (repository path
     /Root/OWNERS/xsl/common.xsl). -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:common="urn:example:fixture:common"
    exclude-result-prefixes="#all" version="3.0">
  <xsl:function name="common:label" as="xs:string">
    <xsl:param name="id"/>
    <xsl:sequence select="concat('ORDER-', $id)"/>
  </xsl:function>
</xsl:stylesheet>
