/**
 * The credential store adapters of feature 011 (research R-1 to R-5): one implementation of the
 * {@code config} port {@code CredentialStore} per operating system — macOS Keychain through
 * {@code /usr/bin/security}, Windows Credential Manager through {@code CredReadW} (JNA), Linux Secret
 * Service through {@code secret-tool} — and a store for every other system that is never
 * available. The only package that uses JNA; it starts processes only through the
 * {@code ProcessLauncher} interface. Values are wrapped in {@code infra.Secret} as soon as they are
 * read and never appear in a process argument or a log.
 */
package de.dadecker.inubit.mcp.adapter.os;
