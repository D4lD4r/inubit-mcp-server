# Third-party notices

INUBIT MCP Server is licensed under the Apache License, Version 2.0 (see `LICENSE` and `NOTICE`).
This file lists the third-party software it contains or is developed with.

## 1. Libraries bundled in the executable JAR

The release artifact `inubit-mcp-server-<version>.jar` is a shaded ("fat") JAR. It contains the
following libraries (the runtime dependencies of `pom.xml`, as resolved by
`mvn dependency:list -DincludeScope=runtime`). The license of each library is taken from the
`<licenses>` section of its own POM (or of its parent POM) and cross-checked against the
`Bundle-License` header and the license files in its JAR.

| Library (groupId:artifactId:version) | License | License URL |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp:2.1.0` | MIT License | https://www.opensource.org/licenses/mit-license.php |
| `io.modelcontextprotocol.sdk:mcp-core:2.1.0` | MIT License | https://www.opensource.org/licenses/mit-license.php |
| `io.modelcontextprotocol.sdk:mcp-json-jackson3:2.1.0` | MIT License | https://www.opensource.org/licenses/mit-license.php |
| `io.projectreactor:reactor-core:3.7.0` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.reactivestreams:reactive-streams:1.0.4` | MIT No Attribution (MIT-0) | https://spdx.org/licenses/MIT-0.html |
| `tools.jackson.core:jackson-core:3.1.7` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `tools.jackson.core:jackson-databind:3.1.7` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `tools.jackson.dataformat:jackson-dataformat-yaml:3.1.7` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.fasterxml.jackson.core:jackson-annotations:2.21` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.snakeyaml:snakeyaml-engine:3.0.1` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.networknt:json-schema-validator:3.0.6` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| `com.ethlo.time:itu:1.14.0` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `ch.qos.logback:logback-classic:1.5.38` | dual-licensed: Eclipse Public License 2.0 (EPL-2.0) **or** GNU Lesser General Public License 2.1 only (LGPL-2.1-only) | https://www.eclipse.org/legal/epl-v20.html, https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |
| `ch.qos.logback:logback-core:1.5.38` | dual-licensed: EPL-2.0 **or** LGPL-2.1-only | https://www.eclipse.org/legal/epl-v20.html, https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |
| `org.slf4j:slf4j-api:2.0.17` | MIT License | https://opensource.org/license/mit |
| `net.sf.saxon:Saxon-HE:10.9` | Mozilla Public License 2.0 (MPL-2.0) | https://www.mozilla.org/MPL/2.0/ |
| `software.amazon.awssdk:annotations:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:auth:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:aws-core:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:aws-json-protocol:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:aws-query-protocol:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:checksums:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:checksums-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:endpoints-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:http-auth:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:http-auth-aws:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:http-auth-aws-eventstream:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:http-auth-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:http-client-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:identity-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:json-utils:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:metrics-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:profiles:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:protocol-core:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:regions:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:retries:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:retries-spi:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:sdk-core:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:ssm:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:sso:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:ssooidc:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:sts:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:third-party-jackson-core:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:url-connection-client:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:utils:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.awssdk:utils-lite:2.54.3` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |
| `software.amazon.eventstream:eventstream:1.0.1` | Apache License, Version 2.0 | https://aws.amazon.com/apache2.0 |

`jackson-core` itself bundles code of FastDoubleParser (MIT License, with third-party code under
the Boost Software License 1.0) and Schubfach (MIT License); their license texts are kept in the JAR
as `META-INF/FastDoubleParser-LICENSE`, `META-INF/FastDoubleParser-ThirdParty-LICENSE` and
`META-INF/Schubfach-LICENSE`. `json-schema-validator` contains repackaged classes of Apache
Commons Validator (Apache License 2.0) under `com/networknt/org/apache/commons/validator/`.

`Saxon-HE` contains code derived from Apache Jakarta Regexp (Apache License 2.0, package
`net/sf/saxon/regex/`) and DTD and entity files of the W3C (Copyright W3C; where stated, under the
W3C Software Notice and License) below `net/sf/saxon/data/w3c/`, as their file headers state.

The AWS SDK for Java 2.0 (`software.amazon.awssdk:*` and `software.amazon.eventstream:eventstream`,
https://github.com/aws/aws-sdk-java-v2) is used with `url-connection-client` as its only HTTP
client; `netty-nio-client`, `apache-client` and `apache5-client` are excluded and not bundled.
`third-party-jackson-core` contains jackson-core 2.21.4 (Apache License 2.0), repackaged under
`software/amazon/awssdk/thirdparty/jackson/core/`; its FastDoubleParser and Schubfach license files
are identical to those of `jackson-core` above.

### License files inside the JAR

- `META-INF/LICENSE` starts with the Apache License 2.0 of this project, followed unchanged by the
  `META-INF/LICENSE` files of the Jackson libraries, including the repackaged jackson-core of the
  AWS SDK (all Apache License 2.0).
- `META-INF/NOTICE` starts with the `NOTICE` of this project, followed unchanged by the
  `META-INF/NOTICE` files of the Jackson libraries, including the repackaged jackson-core of the
  AWS SDK.
- `META-INF/LICENSE.txt` is the MIT License of SLF4J (Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)).
  The AWS SDK JARs ship a `META-INF/LICENSE.txt` of the same name with the Apache License 2.0 text
  (the same license text as in `META-INF/LICENSE`); it is not kept as a separate file.
- `META-INF/NOTICE.txt` is the NOTICE of the AWS SDK for Java 2.0 (identical in every SDK JAR; its
  text is reproduced below).
- `META-INF/THIRD-PARTY-NOTICES.md` is this file.

### MIT License (MCP Java SDK)

The MCP Java SDK (`io.modelcontextprotocol.sdk:*`, https://github.com/modelcontextprotocol/java-sdk)
does not ship a license file in its JARs. Its source files carry the notice
"Copyright 2024-2025 the original author or authors."; its POM declares the MIT License, and the
upstream repository's LICENSE reads:

```text
MIT License

Copyright (c) 2025 the original author or authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Logback (EPL-2.0 or LGPL-2.1-only)

Logback is Copyright (C) 1999-2026, QOS.ch, and is used unmodified. The licensee may choose either
license. Its source code is available at https://github.com/qos-ch/logback and as
`logback-classic-1.5.38-sources.jar` / `logback-core-1.5.38-sources.jar` on Maven Central
(https://repo.maven.apache.org/maven2/ch/qos/logback/).

### AWS SDK for Java 2.0 (Apache-2.0)

The AWS SDK for Java 2.0 is used unmodified under the Apache License, Version 2.0. Its NOTICE
(`META-INF/NOTICE.txt` of every SDK JAR, kept in the executable JAR under the same name) reads:

```text
AWS SDK for Java 2.0
Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.

This product includes software developed by
Amazon Technologies, Inc (http://www.amazon.com/).

**********************
THIRD PARTY COMPONENTS
**********************
This software includes third party software subject to the following copyrights:
- XML parsing and utility functions from JetS3t - Copyright 2006-2009 James Murty.
- PKCS#1 PEM encoded private key parsing and utility functions from oauth.googlecode.com - Copyright 1998-2010 AOL Inc.
- Apache Commons Lang - https://github.com/apache/commons-lang
- Netty Reactive Streams - https://github.com/playframework/netty-reactive-streams
- Jackson-core - https://github.com/FasterXML/jackson-core
- Jackson-dataformat-cbor - https://github.com/FasterXML/jackson-dataformats-binary

The licenses for these third party components are included in LICENSE.txt

- For Apache Commons Lang see also this required NOTICE:
  Apache Commons Lang
  Copyright 2001-2020 The Apache Software Foundation

  This product includes software developed at
  The Apache Software Foundation (https://www.apache.org/).
```

### Saxon-HE (MPL-2.0)

Saxon-HE is Copyright (c) Saxonica Limited and is used unmodified; its source files state that they
are subject to the Mozilla Public License, v. 2.0, and are "Incompatible With Secondary Licenses".
The license text is at https://www.mozilla.org/MPL/2.0/. Its source code is available as
`Saxon-HE-10.9-sources.jar` on Maven Central
(https://repo.maven.apache.org/maven2/net/sf/saxon/Saxon-HE/10.9/). The JAR registers Saxon as a
`javax.xml.transform.TransformerFactory` service (`META-INF/services`), which the shaded JAR keeps.

## 2. Development tooling (not part of the JAR)

The directories `.specify/` and `.claude/skills/` were generated by
[GitHub Spec Kit](https://github.com/github/spec-kit) (`specify-cli` 1.0.11, see
`.specify/init-options.json`) and are used only to write the specifications in `specs/`. They are
not compiled, packaged or shipped in the JAR. Spec Kit is licensed under the MIT License (license
file of the `specify-cli` 1.0.11 distribution; `.specify/extensions/git/extension.yml` declares
`license: MIT`). The project constitution `.specify/memory/constitution.md` and the specifications
in `specs/` are part of this project and licensed under the Apache License 2.0.

```text
MIT License

Copyright GitHub, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Test-only dependencies (JUnit, AssertJ, WireMock and their transitive dependencies) and the Maven
build plugins are used during the build only and are not part of the JAR.
