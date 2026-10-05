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
| `io.modelcontextprotocol.sdk:mcp:2.0.1` | MIT License | https://www.opensource.org/licenses/mit-license.php |
| `io.modelcontextprotocol.sdk:mcp-core:2.0.1` | MIT License | https://www.opensource.org/licenses/mit-license.php |
| `io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1` | MIT License | https://www.opensource.org/licenses/mit-license.php |
| `io.projectreactor:reactor-core:3.7.0` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.reactivestreams:reactive-streams:1.0.4` | MIT No Attribution (MIT-0) | https://spdx.org/licenses/MIT-0.html |
| `tools.jackson.core:jackson-core:3.1.4` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `tools.jackson.core:jackson-databind:3.1.4` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `tools.jackson.dataformat:jackson-dataformat-yaml:3.1.4` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.fasterxml.jackson.core:jackson-annotations:2.21` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `org.snakeyaml:snakeyaml-engine:3.0.1` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `com.networknt:json-schema-validator:3.0.6` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0 |
| `com.ethlo.time:itu:1.14.0` | Apache License, Version 2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `ch.qos.logback:logback-classic:1.5.38` | dual-licensed: Eclipse Public License 2.0 (EPL-2.0) **or** GNU Lesser General Public License 2.1 only (LGPL-2.1-only) | https://www.eclipse.org/legal/epl-v20.html, https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |
| `ch.qos.logback:logback-core:1.5.38` | dual-licensed: EPL-2.0 **or** LGPL-2.1-only | https://www.eclipse.org/legal/epl-v20.html, https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |
| `org.slf4j:slf4j-api:2.0.17` | MIT License | https://opensource.org/license/mit |

`jackson-core` itself bundles code of FastDoubleParser (MIT License, with third-party code under
the Boost Software License 1.0) and Schubfach (MIT License); their license texts are kept in the JAR
as `META-INF/FastDoubleParser-LICENSE`, `META-INF/FastDoubleParser-ThirdParty-LICENSE` and
`META-INF/Schubfach-LICENSE`. `json-schema-validator` contains repackaged classes of Apache
Commons Validator (Apache License 2.0) under `com/networknt/org/apache/commons/validator/`.

### License files inside the JAR

- `META-INF/LICENSE` starts with the Apache License 2.0 of this project, followed unchanged by the
  `META-INF/LICENSE` files of the Jackson libraries (all Apache License 2.0).
- `META-INF/NOTICE` starts with the `NOTICE` of this project, followed unchanged by the
  `META-INF/NOTICE` files of the Jackson libraries.
- `META-INF/LICENSE.txt` is the MIT License of SLF4J (Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)).
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
