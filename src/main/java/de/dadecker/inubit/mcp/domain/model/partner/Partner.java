package de.dadecker.inubit.mcp.domain.model.partner;

import java.util.List;
import java.util.stream.Stream;

/**
 * One partner of a communication cloud (data-model.md → Partner).
 *
 * @param fields      partner fields in configured order ({@code Id}, {@code Name}, …); a masked
 *                    field stays in the list as {@link SettingValue.Masked}
 * @param entries     protocol entries in export order; never merged
 * @param maskedCount number of {@link SettingValue.Masked} values in fields and entries (FR-009)
 */
public record Partner(List<Setting> fields, List<ProtocolEntry> entries, int maskedCount) {

    public Partner {
        fields = List.copyOf(fields);
        entries = List.copyOf(entries);
        int counted = countMasked(fields, entries);
        if (maskedCount != counted) {
            throw new IllegalArgumentException(
                "maskedCount " + maskedCount + " differs from the " + counted + " masked values");
        }
    }

    /** A partner whose {@code maskedCount} is counted from its fields and entries. */
    public Partner(List<Setting> fields, List<ProtocolEntry> entries) {
        this(fields, entries, countMasked(fields, entries));
    }

    private static int countMasked(List<Setting> fields, List<ProtocolEntry> entries) {
        Stream<Setting> entrySettings = entries.stream().flatMap(e -> Stream.concat(
            e.protocolSettings().stream(), e.additionalParameters().stream()));
        return (int) Stream.concat(fields.stream(), entrySettings)
            .filter(s -> s.value() instanceof SettingValue.Masked)
            .count();
    }
}
