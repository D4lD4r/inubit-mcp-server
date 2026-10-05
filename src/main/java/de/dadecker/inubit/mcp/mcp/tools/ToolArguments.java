package de.dadecker.inubit.mcp.mcp.tools;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Names;
import de.dadecker.inubit.mcp.domain.model.ToolError;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads arguments that the SDK has already validated against the tool's input schema (types,
 * enums, patterns, bounds), applying the schema defaults.
 */
final class ToolArguments {

    private final Map<String, Object> arguments;

    ToolArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }

    String string(String name) {
        return String.valueOf(arguments.get(name));
    }

    Optional<String> optionalString(String name) {
        return Optional.ofNullable(arguments.get(name)).map(String::valueOf);
    }

    boolean flag(String name) {
        return Boolean.TRUE.equals(arguments.get(name));
    }

    int integer(String name, int defaultValue) {
        return optionalInteger(name).orElse(defaultValue);
    }

    /**
     * An integer argument; a value that is not integral or outside the {@code int} range is
     * {@code INVALID_INPUT} (never wrapped, review D2).
     */
    Optional<Integer> optionalInteger(String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return Optional.empty();
        }
        BigDecimal number;
        try {
            number = new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw invalid(name, value);
        }
        try {
            return Optional.of(number.intValueExact());
        } catch (ArithmeticException e) {
            throw invalid(name, value);
        }
    }

    private static ToolErrorException invalid(String name, Object value) {
        return new ToolErrorException(ToolError.of(ErrorCode.INVALID_INPUT,
            name + " must be an integer between " + Integer.MIN_VALUE + " and "
                + Integer.MAX_VALUE + " within the bounds of the input schema, got "
                + Names.quote(String.valueOf(value)),
            "The value is out of range",
            "Use the bounds of the tool's input schema (e.g. offset 0…9999, limit 1…100)"));
    }

    List<String> strings(String name) {
        Object value = arguments.get(name);
        List<String> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(element -> values.add(String.valueOf(element)));
        }
        return values;
    }
}
