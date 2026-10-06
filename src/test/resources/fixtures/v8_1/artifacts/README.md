# Artifact fixtures (feature 003)

Recorded, neutralized exports of INUBIT 8.1.17 (StartCLI `export`, development stage, 2026-10-06),
used offline by the archive, redaction, round-trip and check tests of feature 003 (research D-14,
tasks T002). They were built from the private spike recordings
(`~/.inubit-mcp/<profile>/spike`, never committed) by a local script that is not part of the
repository, because it contains the customer values it replaces.

| File | What it is | Source recording |
|---|---|---|
| `grp-a.zip` | diagram group export (technical workflows): diagram group `GRP-01` of user `jdoe`, 2 workflows, 9 modules (XSLT Converter, Demultiplexer, Assign), empty `Repository.zip` | a personal diagram group of the spike, exported while `Workflow-0002` was in edit mode |
| `grp-b.zip` | diagram group export (technical workflows): diagram group `GRP-02` of owner `OWNERS`, 4 workflows, 19 modules, `Repository.zip` with 2 schemas | a shared owner's diagram group, trimmed to 4 of its 16 workflows |
| `module-one.zip` | module-only export of one XSLT Converter module (`Module-0023`, also used in `grp-b.zip`), with the empty `workflow/` directory entry and an empty `Repository.zip` | `export --exportModule` of that module |
| `module-smime.zip` | module-only export of one SMIME module (`Module-0029`, untyped `smime.*` secrets, index entry `<Module version="head">` without `type`) | the layout of `module-one.zip` with the SMIME module and its index entry of the stage's module export |
| `cli/export-group-missing.{stdout,stderr,exit}` | StartCLI stdout, stderr and exit code of an export of a non-existent diagram group: `export --exportWorkflowUser '<owner>' --exportWorkflowType 'technical' --exportWorkflowGroup 'MCP-FIXTURE-NO-SUCH-GROUP' --exportFile '<private temporary directory>/group.zip'` | read-only recording |
| `cli/export-module-missing.{stdout,stderr,exit}` | the same for a non-existent module: `export --exportModule 'MCP-FIXTURE-NO-SUCH-MODULE' --exportModuleGroup 'XSLT Converter' --exportModuleUser '<owner>' --exportFile '<private temporary directory>/module.zip'` | read-only recording |
| `defects/<defect>/` | `grp-a.zip` unzipped, with one edit of `workflow/workflow.xml` named in `DEFECT.md` (T003) | derived |
| `xslt/` | stylesheets and XML documents for the XSLT and XML/XSD checks (T003) | written for the tests |

## Contents

`grp-a.zip`

- `Workflow-0001`: XSLT Converter → Demultiplexer → two Assign nodes (4 nodes, 3 edges).
- `Workflow-0002`: a copy with an inserted node; the Demultiplexer has a condition
  (`Module-0008(4)@@@DeMuxInput`, `…@@@ProcessingOrder`) and a `DefaultOutput`
  (`Module-0009(11)`); the workflow carries
  `<CheckoutUser>jdoe</CheckoutUser>` (in edit mode).

`grp-b.zip`

- `Workflow-0003`: 26 nodes (JSON Validator with an `InternalDocument`, REST Connector, XSLT
  Converters, one with saved test values in `xslt.sourceVariables`, Splitter/Joiner, a `Comment`
  node), `Junctures`.
- `Workflow-0004`: FTP Connector (SFTP) and an XSLT Converter (`Module-0023`).
- `Workflow-0005`: AS2 Connector and a `PartnerManagement` node (`ParentModule`).
- `Workflow-0006`: three Web Services Connectors with embedded WSDLs (`WsdlData`, `ValidWsdlData`),
  one of them referencing the repository schemas (`inubitrepository:/Root/OWNERS/xsd/msg.xsd`,
  `…/core.xsd`), an Assign node with an inline stylesheet in its assignments.

The recordings were made like the CLI fixtures of feature 001 (password on stdin, English locale,
stdout and stderr captured separately); they name neither the owner nor the server.

## What was changed

The archive structure is kept as INUBIT produced it: entry names (renamed consistently, module files
stay the lower-case module name), entry order, compression and entry times, the nested
`Repository.zip` (with its archive comment), and no directory entries except the module-only
export's `workflow/`. XML is kept byte for byte except for the values below; embedded XML keeps
INUBIT's escaping (`<` and `&` escaped, `>` literal). ZIP data descriptors are not reproduced.

Names and hosts:

- Diagram groups `GRP-01`, `GRP-02`; workflows `Workflow-0001`…`Workflow-0006`; modules
  `Module-0001`…`Module-0029` (one name per original name across all fixtures); the comment node
  `Note 01`; owner `OWNERS`; users (owner of `grp-a`, `CheckoutUser`, `Deploying User`,
  repository `modificator`) `jdoe`; `ExportUser` `OWNERS`.
- `CheckinComment`: the person-written part is `JD: Fixture` (the `DefaultCommitCommentImport###`
  chains and `#` padding are kept); the export suffix names user `jdoe` and server
  `inubit-dev-1.example.test` (version and export time kept). `UserComment` values are
  `Fixture comment`.
- Host names in URLs are `host01.example.test`…; IPv4 addresses `192.0.2.10`…; namespace and product
  names of the customer's software are replaced by `example.test`, `acme`/`ACME` and fixture names;
  energy identification codes, an SFTP login and an SFTP path by fictitious values.
- `xslt.transformer`: the recordings name the Saxon-EE transformer factory class of
  `com.saxonica.config`; its class name matches a rule of the local identifier lists, so the
  fixtures use `com.saxonica.config.ProfessionalTransformerFactory` instead. (INUBIT itself runs
  Saxon-EE 10 for those modules; `net.sf.saxon.TransformerFactoryImpl` occurs as recorded.)
- Afterwards `tools/neutralize.py` was run with the neutralization map and the synthetic map
  (no further replacement), and `tools/check-identifiers.py` reported no finding. Gzip/base64
  values were decoded and checked against the same lists.

Synthetic content (research D-14: saved test messages, literals, stylesheets and WSDLs are
customer content, so the fixtures carry synthetic documents of the same structure instead):

- `grp-b.zip`, `Workflow-0003`: the assignment literal with `containsEscapedXml="true"` (a recorded
  business message of about 8 500 characters) is a small synthetic SOAP envelope; escaping as
  recorded (`&lt;`, `&gt;`, `&amp;amp;`).
- `grp-b.zip`, `Workflow-0006`: the inline stylesheet of the Assign node's assignment is a
  synthetic XSLT 2.0 template of the same kind (literal result envelope, comments, one of them over
  two lines with tabs); its `name` is `00000000-0000-0000-0000-000000000006`.
- `xslt.stylesheet` of `Module-0010`, `Module-0019`, `Module-0020` and `Module-0023` (also in
  `module-one.zip`): synthetic stylesheets with the constructs of the recorded ones (identity copy
  without namespaces; XSLT 1.0 text output with modes, a recursive named template and `&gt;`/`&lt;`
  in XPath; XSLT 2.0 with typed parameters, `xsl:choose`, `copy-namespaces="no"` and a commented-out
  template; an XSLT 3.0 template), the same whitespace quirks (trailing blanks, blank lines with a
  single space) and no trailing line feed.
- `WsdlData` and `ValidWsdlData` (equal, as recorded) of `Module-0026`…`Module-0028`: synthetic
  document/literal SOAP 1.1 WSDLs (a BizTalk-style one with a `NegotiateAuthentication` policy;
  one importing the repository schemas; one with a transport/username-token security policy).
- `JSONStaticSchema` of `Module-0018`: a synthetic JSON schema (CRLF and tabs, as recorded),
  `documentName` `FixtureSchema.json`, `documentSize` and `JSONStaticSchemaMD5` (the MD5 of the
  decoded schema) updated.
- Service names, namespaces and ids used by these documents and by the module properties around
  them: `OrderService`, `orderData`, `urn:example:fixture:*`, `/ibis/ws/1000000000001/Service-01`.

Content edits (documented; not recorded behaviour):

- `grp-b.zip`, `Workflow-0006`: a `<copy>` whose `<from>` is a `<literal isPassword="true">` was
  added as the first assignment of its `<Assignments>` block (target variable
  `var.fixturePassword`), and the variable `var.fixturePassword` of type `is:password` with a
  `<DefaultValue>` was declared (`xmlns:is` added to `<Variables>`). The recordings contain both
  forms only in other workflows, and `is:password` variables there have no default value.
- `grp-b.zip`, `module/module-0024.xml` (AS2 Connector): `Mime.Decrypt.Password` holds an
  `AESG` value, and `Password` a plain value without `encrypted="true"` (spike §4 found both forms
  on the stage; this module had legacy values).
- `grp-b.zip`, `module/module-0020.xml`: `xslt.source` and `xslt.target` (saved test messages of
  about 1 000 and 55 000 characters) are a small synthetic document.
- `grp-b.zip`, `module/module-0020.xml`: the leaf `var.userPassword` of type `MaskedString` with
  `encrypted="true"` and a synthetic `AES-` value was added to `xslt.sourceVariables` (the form
  occurs in the stage's module export).
- Keystore aliases (`*.Alias`, `smime.keystore.alias`) are `partner`, the alias of the synthetic
  keystore.
- `grp-b.zip`, `Repository.zip`: only the two schemas referenced by `Module-0027` are kept; their
  content is a small synthetic schema, and their metadata (`contentSize`, `contentMD5`,
  `versionComment`, `Description`, `modificator`) matches it.

Not secrets, but replaced: every `type="X509"` value is one of two synthetic self-signed
certificates (`CN=fixture.example.test` and `CN=partner.example.test`, no private key).

## Synthetic secret values

Every secret value of the recordings was replaced by a synthetic value of the same shape before
anything was written; no recorded ciphertext, keystore or password is part of the repository. The
redaction and leak tests (T013, T024) read the block below: one line per replaced value,
`<kind> TAB <fixture> TAB <location> TAB <value>`. Kinds:

| Kind | Shape | Form in the recordings |
|---|---|---|
| `legacy` | 12 characters of base64 | `type="Password" encrypted="true"`, older encoding |
| `AES-` | `AES-` + 24 characters of base64 | `type="Password" encrypted="true"`; `type="MaskedString" encrypted="true"` in `xslt.sourceVariables` (edit) |
| `AESG` | `AESG` + base64 parts separated by `-`, `+` and `:` | `type="Password" encrypted="true"` (edit: the recording of this module has a legacy value here; AESG values occur in the module exports of the same stage) |
| `plain` | `synthetic-plain-NNNN` | `type="Password"` **without** `encrypted` (edit, see above) and the **untyped** properties `SSLKeyStorePasswordRemoteConnector` and `smime.keystore.alias.password` (plain text in the recordings) |
| `keystore` | base64 of a JKS keystore with an EC private key (alias `partner`, store and key password `changeit`) | `type="KeyStore"` and the **untyped** properties `SSLKeyStoreRemoteConnector` and `smime.keystore.data` |
| `literal` | `synthetic-literal-NNNN` | `<literal isPassword="true">` in an assignment (edit) |
| `default` | `synthetic-default-NNNN` | `<DefaultValue>` of a variable of type `is:password` (edit) |
| `sourceVariable` | `synthetic-sv-NNNN` (in `XmlDocument` values inside a small escaped document) | values below `xslt.sourceVariables` (saved test values; empty values stay empty) |

Secret forms that `type="Password"` alone does not cover, and that the redaction must therefore
handle explicitly: the untyped properties `SSLKeyStoreRemoteConnector` (keystore) and
`SSLKeyStorePasswordRemoteConnector` (plain password) of Web Services Connectors, the untyped
`smime.keystore.data` (keystore) and `smime.keystore.alias.password` (plain password) of SMIME
modules (`module-smime.zip`), and `type="MaskedString" encrypted="true"` (`Module-0020`).

```synthetic-secrets
literal	grp-b.zip	workflow Workflow-0006 Assignments	synthetic-literal-0001
default	grp-b.zip	workflow Workflow-0006 Variables/var.fixturePassword	synthetic-default-0001
legacy	grp-b.zip	module/module-0028.xml Password	U1lOMDAwMDAx
plain	grp-b.zip	module/module-0028.xml SSLKeyStorePasswordRemoteConnector	synthetic-plain-0001
keystore	grp-b.zip	module/module-0028.xml SSLKeyStoreRemoteConnector	/u3+7QAAAAIAAAABAAAAAQAHcGFydG5lcgAAAaEQculXAAAAfTB7MAwGCisGAQQBKgIRAQEEa4LMGriVL9mTE3ofAHDEPBQchG2jS3cf4Dx6BS+aBtRXJYz2Fqv8Kq03vmprFvyNkmgS3agNpkOSNJjHf8ZLnU6AwYHv2PAiVKQb2QDtUie8XkZnIXP7MMzQ9+hocKiF5uLP0yIcznheo9WUAAAAAQAFWC41MDkAAAGnMIIBozCCAUigAwIBAgIJAOSOllE6oV0bMAoGCCqGSM49BAMDMEUxCzAJBgNVBAYTAkRFMRcwFQYDVQQKEw5HbG9iZXggRml4dHVyZTEdMBsGA1UEAxMUcGFydG5lci5leGFtcGxlLnRlc3QwHhcNMjYwMTAxMTAwMjA4WhcNMzUxMjMwMTAwMjA4WjBFMQswCQYDVQQGEwJERTEXMBUGA1UEChMOR2xvYmV4IEZpeHR1cmUxHTAbBgNVBAMTFHBhcnRuZXIuZXhhbXBsZS50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfGYdOyPANPBr7lJp5IoT3kc/OlKX5bAmIwuDVwlqCH94ebTEZmWz0/W6pax9nblI5r693UaFdSBYYsqS/evqkqMhMB8wHQYDVR0OBBYEFN+PH/qIUZPbWTfwOGb0b80JUCNZMAoGCCqGSM49BAMDA0kAMEYCIQCl7AJEmF8kaIMesRrZpTr2OFIrWB2Jci1H1+QzUrG/VAIhAKutiHl4F1vPjqAJt0k3lkuGilLGu/NwANeAr+Vke04DAOzH0qVe+y3HVlx6QrlWSrp84gA=
keystore	grp-b.zip	module/module-0028.xml WS-Security.Client.KeyStore	/u3+7QAAAAIAAAABAAAAAQAHcGFydG5lcgAAAaEQculXAAAAfTB7MAwGCisGAQQBKgIRAQEEa4LMGriVL9mTE3ofAHDEPBQchG2jS3cf4Dx6BS+aBtRXJYz2Fqv8Kq03vmprFvyNkmgS3agNpkOSNJjHf8ZLnU6AwYHv2PAiVKQb2QDtUie8XkZnIXP7MMzQ9+hocKiF5uLP0yIcznheo9WUAAAAAQAFWC41MDkAAAGnMIIBozCCAUigAwIBAgIJAOSOllE6oV0bMAoGCCqGSM49BAMDMEUxCzAJBgNVBAYTAkRFMRcwFQYDVQQKEw5HbG9iZXggRml4dHVyZTEdMBsGA1UEAxMUcGFydG5lci5leGFtcGxlLnRlc3QwHhcNMjYwMTAxMTAwMjA4WhcNMzUxMjMwMTAwMjA4WjBFMQswCQYDVQQGEwJERTEXMBUGA1UEChMOR2xvYmV4IEZpeHR1cmUxHTAbBgNVBAMTFHBhcnRuZXIuZXhhbXBsZS50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfGYdOyPANPBr7lJp5IoT3kc/OlKX5bAmIwuDVwlqCH94ebTEZmWz0/W6pax9nblI5r693UaFdSBYYsqS/evqkqMhMB8wHQYDVR0OBBYEFN+PH/qIUZPbWTfwOGb0b80JUCNZMAoGCCqGSM49BAMDA0kAMEYCIQCl7AJEmF8kaIMesRrZpTr2OFIrWB2Jci1H1+QzUrG/VAIhAKutiHl4F1vPjqAJt0k3lkuGilLGu/NwANeAr+Vke04DAOzH0qVe+y3HVlx6QrlWSrp84gA=
legacy	grp-b.zip	module/module-0028.xml WS-Security.Client.KeyStore.Password	U1lOMDAwMDAy
legacy	grp-b.zip	module/module-0028.xml WS-Security.Client.UsernameToken.Password	U1lOMDAwMDAz
legacy	grp-b.zip	module/module-0026.xml Password	U1lOMDAwMDA0
plain	grp-b.zip	module/module-0026.xml SSLKeyStorePasswordRemoteConnector	synthetic-plain-0002
keystore	grp-b.zip	module/module-0026.xml SSLKeyStoreRemoteConnector	/u3+7QAAAAIAAAABAAAAAQAHcGFydG5lcgAAAaEQculXAAAAfTB7MAwGCisGAQQBKgIRAQEEa4LMGriVL9mTE3ofAHDEPBQchG2jS3cf4Dx6BS+aBtRXJYz2Fqv8Kq03vmprFvyNkmgS3agNpkOSNJjHf8ZLnU6AwYHv2PAiVKQb2QDtUie8XkZnIXP7MMzQ9+hocKiF5uLP0yIcznheo9WUAAAAAQAFWC41MDkAAAGnMIIBozCCAUigAwIBAgIJAOSOllE6oV0bMAoGCCqGSM49BAMDMEUxCzAJBgNVBAYTAkRFMRcwFQYDVQQKEw5HbG9iZXggRml4dHVyZTEdMBsGA1UEAxMUcGFydG5lci5leGFtcGxlLnRlc3QwHhcNMjYwMTAxMTAwMjA4WhcNMzUxMjMwMTAwMjA4WjBFMQswCQYDVQQGEwJERTEXMBUGA1UEChMOR2xvYmV4IEZpeHR1cmUxHTAbBgNVBAMTFHBhcnRuZXIuZXhhbXBsZS50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfGYdOyPANPBr7lJp5IoT3kc/OlKX5bAmIwuDVwlqCH94ebTEZmWz0/W6pax9nblI5r693UaFdSBYYsqS/evqkqMhMB8wHQYDVR0OBBYEFN+PH/qIUZPbWTfwOGb0b80JUCNZMAoGCCqGSM49BAMDA0kAMEYCIQCl7AJEmF8kaIMesRrZpTr2OFIrWB2Jci1H1+QzUrG/VAIhAKutiHl4F1vPjqAJt0k3lkuGilLGu/NwANeAr+Vke04DAOzH0qVe+y3HVlx6QrlWSrp84gA=
legacy	grp-b.zip	module/module-0026.xml WS-Security.Client.UsernameToken.Password	U1lOMDAwMDA1
plain	grp-b.zip	module/module-0024.xml Password	synthetic-plain-0003
keystore	grp-b.zip	module/module-0024.xml Mime.Decrypt.Keystore	/u3+7QAAAAIAAAABAAAAAQAHcGFydG5lcgAAAaEQculXAAAAfTB7MAwGCisGAQQBKgIRAQEEa4LMGriVL9mTE3ofAHDEPBQchG2jS3cf4Dx6BS+aBtRXJYz2Fqv8Kq03vmprFvyNkmgS3agNpkOSNJjHf8ZLnU6AwYHv2PAiVKQb2QDtUie8XkZnIXP7MMzQ9+hocKiF5uLP0yIcznheo9WUAAAAAQAFWC41MDkAAAGnMIIBozCCAUigAwIBAgIJAOSOllE6oV0bMAoGCCqGSM49BAMDMEUxCzAJBgNVBAYTAkRFMRcwFQYDVQQKEw5HbG9iZXggRml4dHVyZTEdMBsGA1UEAxMUcGFydG5lci5leGFtcGxlLnRlc3QwHhcNMjYwMTAxMTAwMjA4WhcNMzUxMjMwMTAwMjA4WjBFMQswCQYDVQQGEwJERTEXMBUGA1UEChMOR2xvYmV4IEZpeHR1cmUxHTAbBgNVBAMTFHBhcnRuZXIuZXhhbXBsZS50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfGYdOyPANPBr7lJp5IoT3kc/OlKX5bAmIwuDVwlqCH94ebTEZmWz0/W6pax9nblI5r693UaFdSBYYsqS/evqkqMhMB8wHQYDVR0OBBYEFN+PH/qIUZPbWTfwOGb0b80JUCNZMAoGCCqGSM49BAMDA0kAMEYCIQCl7AJEmF8kaIMesRrZpTr2OFIrWB2Jci1H1+QzUrG/VAIhAKutiHl4F1vPjqAJt0k3lkuGilLGu/NwANeAr+Vke04DAOzH0qVe+y3HVlx6QrlWSrp84gA=
AESG	grp-b.zip	module/module-0024.xml Mime.Decrypt.Password	AESGs1-y+U1lOQUVTRzAwMDAwMDE:U1lOVEhJVjE:U1lOVEhBRVNHVEFHMDAwMQ==
legacy	grp-b.zip	module/module-0013.xml OAUTH_PROPERTY.client_secret	U1lOMDAwMDA2
legacy	grp-b.zip	module/module-0013.xml Password	U1lOMDAwMDA3
AES-	grp-b.zip	module/module-0022.xml Password	AES-U1lOVEgtQUVTLTAwMDAwMDAx
legacy	grp-b.zip	module/module-0022.xml ProxyPValue	U1lOMDAwMDA4
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISWorkflowName	synthetic-sv-0001
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/cache-control	synthetic-sv-0002
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/var.sender	synthetic-sv-0003
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/connection	synthetic-sv-0004
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageMIC	synthetic-sv-0005
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISServerName	synthetic-sv-0006
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/user-agent	synthetic-sv-0007
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/mime-version	synthetic-sv-0008
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/x-is-version	synthetic-sv-0009
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/content-disposition	synthetic-sv-0010
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISModuleId	synthetic-sv-0011
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/content-length	synthetic-sv-0012
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/date	synthetic-sv-0013
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/as2-version	synthetic-sv-0014
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISUserName	synthetic-sv-0015
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISGlobalProcessId	synthetic-sv-0016
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/message-id	synthetic-sv-0017
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageID	synthetic-sv-0018
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/as2-from	synthetic-sv-0019
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/as2-to	synthetic-sv-0020
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/host	synthetic-sv-0021
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/var.creationDateTime	synthetic-sv-0022
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISCurrentTime	synthetic-sv-0023
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/pragma	synthetic-sv-0024
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/disposition-notification-to	synthetic-sv-0025
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageFrom	synthetic-sv-0026
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageTo	synthetic-sv-0027
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/var.eingansnachricht	synthetic-sv-0028
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/content-description	synthetic-sv-0029
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ProcessIdOfRequest	synthetic-sv-0030
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageMICALG	synthetic-sv-0031
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageContentTransferEncoding	synthetic-sv-0032
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/content-type	synthetic-sv-0033
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISProcessId	synthetic-sv-0034
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/var.messageIdentification	synthetic-sv-0035
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageDate	synthetic-sv-0036
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageContentType	synthetic-sv-0037
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ASMessageMDNOptions	synthetic-sv-0038
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/disposition-notification-options	synthetic-sv-0039
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/ISModuleName	synthetic-sv-0040
sourceVariable	grp-b.zip	module/module-0020.xml xslt.sourceVariables/accept	synthetic-sv-0041
AES-	grp-b.zip	module/module-0020.xml xslt.sourceVariables/var.userPassword (MaskedString)	AES-U1lOVEgtQUVTLTAwMDAwMDAy
keystore	module-smime.zip	module/module-0029.xml smime.keystore.data	/u3+7QAAAAIAAAABAAAAAQAHcGFydG5lcgAAAaEQculXAAAAfTB7MAwGCisGAQQBKgIRAQEEa4LMGriVL9mTE3ofAHDEPBQchG2jS3cf4Dx6BS+aBtRXJYz2Fqv8Kq03vmprFvyNkmgS3agNpkOSNJjHf8ZLnU6AwYHv2PAiVKQb2QDtUie8XkZnIXP7MMzQ9+hocKiF5uLP0yIcznheo9WUAAAAAQAFWC41MDkAAAGnMIIBozCCAUigAwIBAgIJAOSOllE6oV0bMAoGCCqGSM49BAMDMEUxCzAJBgNVBAYTAkRFMRcwFQYDVQQKEw5HbG9iZXggRml4dHVyZTEdMBsGA1UEAxMUcGFydG5lci5leGFtcGxlLnRlc3QwHhcNMjYwMTAxMTAwMjA4WhcNMzUxMjMwMTAwMjA4WjBFMQswCQYDVQQGEwJERTEXMBUGA1UEChMOR2xvYmV4IEZpeHR1cmUxHTAbBgNVBAMTFHBhcnRuZXIuZXhhbXBsZS50ZXN0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfGYdOyPANPBr7lJp5IoT3kc/OlKX5bAmIwuDVwlqCH94ebTEZmWz0/W6pax9nblI5r693UaFdSBYYsqS/evqkqMhMB8wHQYDVR0OBBYEFN+PH/qIUZPbWTfwOGb0b80JUCNZMAoGCCqGSM49BAMDA0kAMEYCIQCl7AJEmF8kaIMesRrZpTr2OFIrWB2Jci1H1+QzUrG/VAIhAKutiHl4F1vPjqAJt0k3lkuGilLGu/NwANeAr+Vke04DAOzH0qVe+y3HVlx6QrlWSrp84gA=
plain	module-smime.zip	module/module-0029.xml smime.keystore.alias.password	synthetic-plain-0004
```

## Derived fixtures (T003)

`defects/` holds `grp-a.zip` unzipped (byte-identical entries) with exactly one edit of
`workflow/workflow.xml` each, named in its `DEFECT.md`: `dangling-edge`, `id-collision`,
`demux-key-unmatched`, `missing-module`, `repository-ref-missing`, `variable-unresolved`. Each should
yield exactly its own finding.

`xslt/` holds fictitious documents: `plain.xsl` (no extensions), `standins.xsl` (`Misc:guid()`,
`Formatter:changeDateFormat(value, 'in|out')`, `Formatter:getDateTime(pattern)`,
`UUID:randomUUID()`, `Thread:sleep(n)` in the namespaces of real stylesheets),
`unknown-extension.xsl` (a Java extension without stand-in), `syntax-error.xsl` (static XPath error,
line 7), `repository-import.xsl` (imports `inubitrepository:/Root/OWNERS/xsl/common.xsl`, whose
content is `common.xsl`), `input.xml`, `schema.xsd`, `valid.xml`, `invalid.xml` (two violations,
lines 5 and 6) and `not-well-formed.xml` (fails on line 4). Saxon-HE 10.9 without stand-ins rejects
`standins.xsl` and `unknown-extension.xsl` with `XPST0017` (no reflexive Java calls in HE).

## Observations for later tasks

- `JSONStaticSchemaMD5` of the JSON Validator (`Module-0018`) is the MD5 of the decoded
  `JSONStaticSchema` document: an edit of the embedded schema must update it.
- Module index entries come as `<Module type="technical" version="head">` and as
  `<Module version="head">` (no `type`).
- The XSLT Converters store their stylesheets as escaped XML although `xslt.base64Zipped` is
  `true`.
- Extension calls in the spike corpus beyond the list of T029 (counts of calls): `Misc:encode` with
  2 arguments (3), `Misc:encodeWithCompression` (6), `Misc:setVariable` with 3 arguments (1),
  `ISFunctions:encode` (1); `Formatter:convertDateString` takes 4 or 9 arguments and
  `Formatter:calculateDateDifference` 11.
- Real XSLT Converter modules name either `net.sf.saxon.TransformerFactoryImpl` or the Saxon-EE
  factory in `xslt.transformer`.
