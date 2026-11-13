/**
 * Feature 022: StartCLI administration commands on INUBIT 8.1/9.0 and the parsers of their
 * answers. Each parser reads one block of a stdin session ({@code CliSessionParser}) and answers
 * an unknown text with {@code UNEXPECTED_RESPONSE} and a masked excerpt, never with a guessed
 * value (spec edge cases).
 */
package de.dadecker.inubit.mcp.adapter.cli.admin;
