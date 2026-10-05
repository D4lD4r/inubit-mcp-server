package de.dadecker.inubit.mcp.adapter.rest.v81;

import java.util.Objects;

/** A mapped item and whether text inside it was cut to fit the size bounds. */
record Bounded<T>(T value, boolean cut) {

    Bounded {
        Objects.requireNonNull(value, "value");
    }
}
