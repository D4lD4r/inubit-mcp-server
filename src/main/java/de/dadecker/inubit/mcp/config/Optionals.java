package de.dadecker.inubit.mcp.config;

import java.util.List;
import java.util.Optional;

/** Normalisation helpers for YAML-bound records (absent values may arrive as {@code null}). */
final class Optionals {

    private Optionals() {
    }

    static <T> Optional<T> orEmpty(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }

    static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    static <T> List<T> copyOrEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
