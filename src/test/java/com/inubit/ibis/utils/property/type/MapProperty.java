package com.inubit.ibis.utils.property.type;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test fake (feature 024, research "Static facts") of the vendor's map property, a local value
 * object: its calls are not recorded. The vendor's class extends {@code ContainerProperty},
 * itself an {@link IBISProperty}; the fake extends {@link IBISProperty} directly. Its own string
 * value is {@code null}.
 */
public class MapProperty extends IBISProperty {

    static {
        FakeInubitScript.initializing("MapProperty");
    }

    private final Map<String, IBISProperty> properties = new LinkedHashMap<>();

    public MapProperty(String name) {
        super(name, MapProperty.class, null, null, null, null, false, false);
    }

    public synchronized void setProperty(IBISProperty property) {
        properties.put(property.getName(), property);
    }

    /** The property named {@code name}, or {@code null}. */
    public IBISProperty get(String name) {
        return properties.get(name);
    }

    /** The names in the order they were set. */
    public List<String> getPropertyNames() {
        return new ArrayList<>(properties.keySet());
    }
}
