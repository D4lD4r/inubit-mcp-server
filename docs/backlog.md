# Backlog

Ideas and follow-ups that are not specified yet. An entry becomes a feature through
`/speckit-specify`, which gives it its own spec, plan and review.

## Trust a new server certificate on first use (builds on feature 006)

**Problem.** A new user gets a profile file but also needs a trust store and a pinned certificate
for every group (stage) whose servers present self-signed certificates. Today these are created by
hand: fetch the certificate with `openssl`, compare its fingerprint, import it with `keytool`
(docs/setup.md, section 5). The alternative is to copy the files from a colleague.
`inubit-cert-check` (feature 006) only covers a certificate that *changes* while a pin already
exists. It does not cover the very first connection.

**Idea.** When a group has a `baseUrl` but no trust store or pin, the server fetches the
certificate on the first connection and shows it to the person:
- host, issuer, validity and SHA-256 fingerprint;
- if the profile already holds an expected pin, the server compares against it automatically.

Only after the person explicitly confirms does the server write the trust store and the pin, with
a backup of the profile file and an audit record. If the person declines, the group stays blocked
with `TLS_ERROR` and a hint. Handing over a profile then needs only the YAML file.

**Constraints.**
- **Never automatic.** The confirmation must come from the person, not from the AI assistant. The
  pin exists to detect a man-in-the-middle, and if the model could confirm through a tool call,
  that protection would be gone. Use the start dialog of feature 006 or MCP elicitation (the server
  asks the user through the client UI). Do not use a tool with a confirmation code that the model
  could return itself.
- **Real security needs a second source.** The person should compare the fingerprint with a value
  from a second source: the pin the colleague passed on, or the value the operators publish.
  Without that comparison this is trust on first use, which only protects later connections.
- Consistent with the constitution (TLS always on, every change audited) and with the
  feature-006 rule "never adopt automatically".
