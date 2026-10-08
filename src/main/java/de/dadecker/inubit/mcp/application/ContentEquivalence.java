package de.dadecker.inubit.mcp.application;

import java.util.Arrays;

/**
 * Whether two versions of a workspace file have the same reviewed content (0.4.2): what INUBIT
 * rewrites on every import (check-in comment, last update, UIDs) and how it stores an embedded
 * document do not count. {@code null} stands for an absent file.
 *
 * @see de.dadecker.inubit.mcp.domain.port.ImportArchivePort#equivalent
 */
@FunctionalInterface
public interface ContentEquivalence {

    /** Byte equality. */
    ContentEquivalence BYTES = (path, a, b) -> a == null || b == null ? a == b
        : Arrays.equals(a, b);

    boolean equivalent(String path, byte[] a, byte[] b);
}
