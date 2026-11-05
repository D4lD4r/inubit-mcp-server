# MCP tools delta: `export_archives` (feature 015)

One new tool. No existing tool changes. Research: R-1 (names), R-12/R-13 (audit, checks),
R-14 (result), R-16 (announcement).

## Announcement and annotations

- Announced when at least one node has `cliHome` (the condition of `export_artifacts`), also when
  every production group lacks the opt-in.
- Annotations: `title` "Export Workbench archives of workflows", `readOnlyHint: false`,
  `destructiveHint: false`, `idempotentHint: false`, `openWorldHint: true`.

## Description (model-facing)

> Export the technical workflows of one diagram group of one {group} or {node} as one
> Workbench-importable archive per workflow (`<name>.diagram.zip`) into a local folder you name.
> The archives contain the workflows' secrets IN CLEAR TEXT; set acknowledgeClearTextSecrets to
> true only when the user wants that. The folder must be an absolute path outside the workspace,
> the server's directories, git working trees and network paths; existing files are never
> replaced. Read-only for INUBIT; does not touch the workspace.

## Input schema (`schemas/export_archives.input.json`)

```json
{
  "type": "object",
  "required": ["target", "diagramGroup", "targetFolder"],
  "properties": {
    "target": { "type": "string", "pattern": "^[a-z0-9][a-z0-9-]{0,31}(/[a-z0-9][a-z0-9-]{0,31})?$",
                "description": "Id of one {group} (its first {node} is used) or of one {node}" },
    "owner": { "type": "string", "pattern": "^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$",
               "description": "INUBIT user or user group owning the workflows; default: inventory.owner of the {node}" },
    "diagramGroup": { "type": "string", "pattern": "^[A-Za-z0-9_.][A-Za-z0-9_.\\- ]{0,199}$",
                      "description": "The diagram group whose technical workflows are archived" },
    "workflows": { "type": "array", "minItems": 1, "maxItems": 50, "uniqueItems": true,
                   "items": { "type": "string", "pattern": "^[^\\u0000-\\u001F\\u007F]{1,200}$" },
                   "description": "Only these workflows (case-sensitive names); default: all technical workflows of the diagram group, at most 50" },
    "targetFolder": { "type": "string", "minLength": 1, "maxLength": 4096,
                      "description": "Absolute path of the local folder for the archives; created owner-only if missing" },
    "acknowledgeClearTextSecrets": { "type": "boolean",
                      "description": "Must be true: the archives contain passwords, keys and other secrets in clear text" },
    "reason": { "type": "string", "pattern": "^[^\\r\\n]{1,500}$",
                "description": "Why the archives are exported; stored in the audit log" }
  },
  "additionalProperties": false
}
```

## Output schema (`schemas/export_archives.output.json`)

```json
{
  "type": "object",
  "required": ["node", "owner", "diagramGroup", "targetFolder", "auditId", "clearTextSecrets",
               "archives", "failed", "notWritten", "truncated", "warnings"],
  "properties": {
    "node": { "type": "string" },
    "owner": { "type": "string" },
    "diagramGroup": { "type": "string" },
    "targetFolder": { "type": "string", "description": "Absolute path of the folder" },
    "auditId": { "type": "string" },
    "clearTextSecrets": { "type": "string" },
    "archives": { "type": "array", "items": {
      "type": "object",
      "required": ["workflow", "file", "path", "size", "moduleCount", "modules",
                   "missingModuleCount", "missingModules", "repositoryFileCount",
                   "repositoryPaths", "warnings"],
      "properties": {
        "workflow": { "type": "string" },
        "file": { "type": "string", "description": "File name in targetFolder" },
        "path": { "type": "string", "description": "Absolute path of the archive" },
        "size": { "type": "integer", "minimum": 0 },
        "moduleCount": { "type": "integer", "minimum": 0 },
        "modules": { "type": "array", "items": { "type": "object",
          "required": ["name", "pluginType"],
          "properties": { "name": { "type": "string" }, "pluginType": { "type": "string" } },
          "additionalProperties": false } },
        "missingModuleCount": { "type": "integer", "minimum": 0 },
        "missingModules": { "type": "array", "items": { "type": "object",
          "required": ["name"], "properties": { "name": { "type": "string" } },
          "additionalProperties": false } },
        "repositoryFileCount": { "type": "integer", "minimum": 0 },
        "repositoryPaths": { "type": "array", "items": { "type": "string" } },
        "warnings": { "type": "array", "items": { "type": "string" } }
      },
      "additionalProperties": false } },
    "failed": { "type": "array", "items": { "type": "object",
      "required": ["workflow", "code", "message"],
      "properties": { "workflow": { "type": "string" }, "code": { "type": "string" },
                      "message": { "type": "string" } },
      "additionalProperties": false } },
    "notWritten": { "type": "array", "items": { "type": "string" } },
    "truncated": { "type": "boolean" },
    "warnings": { "type": "array", "items": { "type": "string" } }
  },
  "additionalProperties": false
}
```

`clearTextSecrets` is always: "The archives contain the secrets of these workflows (passwords, keys,
certificates, saved test messages) in clear text. Do not share, attach or commit them as they are;
delete them when no longer needed."

## Example

`export_archives(target: "dev/node1", diagramGroup: "GRP-02", workflows: ["Workflow-0006"],
targetFolder: "/Users/jdoe/archives", acknowledgeClearTextSecrets: true, reason: "backup before test")`

```json
{"node":"dev/node1","owner":"OWNERS","diagramGroup":"GRP-02","targetFolder":"/Users/jdoe/archives",
 "auditId":"…","clearTextSecrets":"The archives contain …",
 "archives":[{"workflow":"Workflow-0006","file":"Workflow-0006.diagram.zip",
   "path":"/Users/jdoe/archives/Workflow-0006.diagram.zip","size":48213,"moduleCount":4,
   "modules":[{"name":"Module-0026","pluginType":"Web Services Connector"}, "…"],
   "missingModuleCount":0,"missingModules":[],"repositoryFileCount":2,
   "repositoryPaths":["/Root/OWNERS/xsd/core.xsd","/Root/OWNERS/xsd/msg.xsd"],"warnings":[]}],
 "failed":[],"notWritten":[],"truncated":false,"warnings":[]}
```

## Errors (tool errors; nothing written)

| Code | When | Moment |
|---|---|---|
| `TARGET_UNKNOWN`, `INVALID_INPUT` | unknown or malformed target | static |
| `NOT_CONFIGURED` | no owner in the call and no `inventory.owner` | static |
| `PRODUCTION_PROTECTED` | production group without `archives.productionOptIn` (message names the setting) | static |
| `INVALID_INPUT` | acknowledgement missing/false; workflow-name syntax or limits; `reason`; folder rules (R-9) | static |
| `INTERNAL` | the audit record could not be written (for a refusal: carries the original code and message) | static / before StartCLI |
| `UNSUPPORTED_VERSION` | version gate (as `export_artifacts`) | after `PENDING` |
| `CLI_UNAVAILABLE`, `AUTH_FAILED`, `TIMEOUT`, `NOT_FOUND`, `UNEXPECTED_RESPONSE` | StartCLI export as `export_artifacts` (same messages, also the 128 MiB limit) | export |
| `INVALID_INPUT` | unknown workflow names; > 50 workflows without `workflows`; file name > 255 bytes; colliding file names (between workflows or with the folder) | after export, before the first write |

Write failures are **not** errors: they return the result with `failed[]` / `notWritten[]`
(FR-011).

## Audit

`capability: export_archives`, `step: EXECUTE`; outcomes `REFUSED` | `PENDING` → `EXECUTED` |
`REFUSED` | `FAILED` | `PARTIALLY_COMPLETED` (new). Inputs: `owner`, `diagramGroup`,
`targetFolder`, `workflow.<n>`, `file.<n>`, `acknowledgeClearTextSecrets`, `reason`. Schema
violations are not audited.
