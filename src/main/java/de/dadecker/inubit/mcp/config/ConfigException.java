package de.dadecker.inubit.mcp.config;

/** The configuration file cannot be found, read or parsed. Messages never contain values. */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
