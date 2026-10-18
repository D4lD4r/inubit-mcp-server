# INUBIT 9.0 fixtures

**Synthetic.** No INUBIT 9.0 server has been recorded yet (feature 009, research D-15). Every file
here was derived from the recorded 8.1.17 fixtures and stays synthetic until a recorded 9.0
response replaces it.

| File | Derived from | Change |
|---|---|---|
| `rest/system_info.xml` | `../v8_1/rest/system_info.xml` | `Version` = `9.0.7`; every other entry, the namespaces and the document shape (`SystemInformationList` of `SystemInformation name/value`) unchanged. A real 9.0 server may answer with other entries or values |
| `rest/system_info.http` | `../v8_1/rest/system_info.http` | none (`status: 200`, `application/xml;charset=UTF-8`) |
| `cli/version.{stdout,stderr,exit}` | `../v8_1/cli/version.*` | stdout: `CLI 9.0.7` and `Supported inubit Process Engine versions from 9.0.0 to 9.0.7.`; stderr and exit code unchanged. Documented in `../v8_1/cli/README.md` |
