<?xml version="1.0" encoding="UTF-8"?>
<!-- Imports a stylesheet from the INUBIT repository (resolved from the workspace's repository/;
     the imported file is common.xsl). -->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:common="urn:example:fixture:common"
    exclude-result-prefixes="#all" version="3.0">
  <xsl:import href="inubitrepository:/Root/OWNERS/xsl/common.xsl"/>
  <xsl:output method="xml" encoding="UTF-8" indent="yes"/>
  <xsl:template match="/order">
    <invoice order="{common:label(@id)}"/>
  </xsl:template>
</xsl:stylesheet>
