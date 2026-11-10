# Contract delta: configuration and command line (feature 019)

Research: D-1 (shape), D-2 (overlap), D-3 (permissions, `--check-config`), D-7 (credentials).

## Profile key

```yaml
knowledge:
  directory: ~/Notes/inubit/acme   # optional; default ~/.inubit-mcp/<profile>/knowledge
  recording: false                 # strict YAML boolean; absent = true
```

- Top-level only; both keys optional; the block may be absent. Any other key is
  `Unknown key 'x' at knowledge`.
- `directory`: same binding as `workspace` (`~` expanded; must be absolute after expansion, else
  `knowledge.directory <path> must be absolute after expansion of ~ (e.g. ~/Notes/inubit/acme)`).
  The default is filled by the loader.
- A non-boolean `recording` (for example `"false"`, `0`) is an error:
  `Invalid value at knowledge.recording in <file>: expected true or false`.
- No environment variable, no command-line switch. Read at start only (FR-012).

## Validation errors (FR-002)

Each is a configuration error (the server does not start; `--check-config` lists it and exits 1).
`<K>` = the knowledge directory as configured (absolute, normalized), `<P>` = the other path.

| Relation | Text |
|---|---|
| equal | `The knowledge directory <K> is the same directory as the <what> <P>; set knowledge.directory to a folder of its own` |
| inside | `The knowledge directory <K> is inside the <what> <P>; set knowledge.directory to a folder outside it` |
| contains | `The knowledge directory <K> contains the <what> <P>; set knowledge.directory to a folder that does not contain it` |

`<what>` is one of: `workspace`, `audit directory`, `backup directory`, `deployment ledger
directory`, `package directory`, `server data directory ~/.inubit-mcp`, `configuration
directory`, `folder of the configuration file`, and for another profile `workspace of profile
'<p>' (<file>)`, `audit directory of profile '<p>' (<file>)`, `data directory of profile '<p>'`,
`knowledge directory of profile '<p>' (<file>)`. For `~/.inubit-mcp` only *equal* and *contains*
apply; inside `~/.inubit-mcp/<own profile>` is allowed, also where that directory lies inside the
folder of the configuration file (a `--config` file in the home directory).

Two configuration files of the **same** profile name (an existing warning) may share the knowledge
directory; no additional message.

## Start-up warnings (stderr, WARN)

- Permissions (POSIX), once at start and in `--check-config`:
  `The knowledge directory <K> is <readable|writable|readable and writable> by <group|others|group and others>; run chmod 700 <K> (the server does not change permissions)`
- Permissions (Windows): the 013 wording of `PrivateFileSystem.check` with `knowledge directory`
  as the object.
- Not a directory / not readable: `The knowledge directory <K> is not usable: <problem>; the
  knowledge tools report it until it is fixed` (the server still starts; FR-002 errors are the
  only start-blocking ones).
- Symbolic links inside: none at start (reported per call, D-9).

## `--check-config` (stdout)

One line after `Workspace:`:

```text
Knowledge: /Users/op/.inubit-mcp/acme/knowledge (absent), recording on, 0 notes
Knowledge: /Users/op/Notes/inubit/acme (ok), recording off, 12 notes
Knowledge: /Users/op/Notes/inubit/acme (not readable), recording on, 0 notes
```

- No note name, title or content is printed; no credential is read (FR-003, D-3).
- The count is the number of notes `read_knowledge` would list (D-9).
- A relative `knowledge.directory` shows `(not usable: see the errors)`, as the `Workspace:` line.

## Instructions at start (D-7, D-8)

With at least one note, `serve` waits up to 5 s for the profile's credentials before building the
instructions (the reads continue in the background). Log at INFO when not ready:
`Knowledge note list withheld from the instructions: credentials of <nodes> not read yet`.
