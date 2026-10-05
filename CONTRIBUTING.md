# Contributing

Thank you for your interest. Issues and pull requests are welcome.

## Build and test

Requires JDK 21+ and Maven 3.9+.

```bash
mvn -B clean verify
```

The default build is fully offline (recorded fixtures, WireMock, a fake StartCLI) and must stay
green; CI runs the same command on every push to `main` and every pull request against `main`. Tests against a real server are
opt-in, read-only and never run against production ([docs/live-tests.md](docs/live-tests.md)).

## How to change things

- **Test first.** Write or extend a failing test, then the implementation (TDD). Every bug fix
  comes with a test that reproduces it.
- Keep INUBIT access behind the version-specific adapters; `PackageBoundaryTest` enforces the
  package boundaries.
- Larger features follow the [Spec Kit](https://github.com/github/spec-kit) workflow: a
  specification, plan, contracts and tasks under `specs/<NNN>-<name>/` (the skills in
  `.claude/skills/` and the templates in `.specify/` support it). The non-negotiable principles are
  in the [constitution](.specify/memory/constitution.md).
- Keep the documentation (`README.md`, `docs/`) and the [changelog](CHANGELOG.md) up to date.

## No customer data, no secrets

This is a customer-neutral product. **Never** put real credentials, tokens, certificates, host
names, user or group names, workflow or module names, log excerpts or other data of a real INUBIT
installation into issues, pull requests, commits, tests or fixtures. Use fictitious values
(`acme`, `globex`, `*.example.test`, `Workflow-0001`, …).

The guard test `NoCustomerIdentifiersTest` scans the repository and the built JAR against local,
never committed identifier lists (a denylist and the maps of `tools/neutralize.py` and
`tools/synthesize_names.py`). With the lists in `~/.config/inubit-mcp/`, every local `mvn verify`
runs it ([docs/release-checks.md](docs/release-checks.md)). If you work with such lists, install the
git hooks once per clone; they run the same check (`tools/check-identifiers.py`) before every
commit and push:

```bash
git config core.hooksPath .githooks
```

CI cannot run the check because the lists are private: there the guard test is skipped. Without
any list the hooks only print a warning.

## Security issues

Do not report vulnerabilities in public issues; see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE) of this project.
