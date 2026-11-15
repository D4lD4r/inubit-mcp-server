package com.inubit.ibis.utils.property.type;

import de.dadecker.inubit.mcp.testing.FakeInubitScript;

/**
 * Test fake (feature 024, research "Static facts", D-10) of the vendor's property, a local value
 * object: its calls are not recorded. The constructor is the fake's own; the helper must not use
 * it. Only the getters of research D-10.
 */
public class IBISProperty {

    static {
        FakeInubitScript.initializing("IBISProperty");
    }

    private final String name;
    private final Class<?> type;
    private final String stringValue;
    private final String displayName;
    private final String shortDescription;
    private final String category;
    private final boolean editable;
    private final boolean deprecated;

    public IBISProperty(String name, Class<?> type, String stringValue, String displayName,
        String shortDescription, String category, boolean editable, boolean deprecated) {
        this.name = name;
        this.type = type;
        this.stringValue = stringValue;
        this.displayName = displayName;
        this.shortDescription = shortDescription;
        this.category = category;
        this.editable = editable;
        this.deprecated = deprecated;
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return type;
    }

    public String getStringValue() {
        return stringValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getShortDescription() {
        return shortDescription;
    }

    public String getCategory() {
        return category;
    }

    public boolean isEditable() {
        return editable;
    }

    public boolean isDeprecated() {
        return deprecated;
    }
}
