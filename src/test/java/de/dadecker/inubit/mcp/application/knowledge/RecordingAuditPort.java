package de.dadecker.inubit.mcp.application.knowledge;

import de.dadecker.inubit.mcp.domain.model.AuditOutcome;
import de.dadecker.inubit.mcp.domain.model.AuditRecord;
import de.dadecker.inubit.mcp.domain.port.AuditPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** An audit log in memory that fails for the outcomes in {@link #failing}. */
final class RecordingAuditPort implements AuditPort {

    final List<AuditRecord> records = new ArrayList<>();
    final Set<AuditOutcome> failing = EnumSet.noneOf(AuditOutcome.class);

    @Override
    public void append(AuditRecord record) {
        if (failing.contains(record.outcome())) {
            throw new UncheckedIOException(new IOException("disk full"));
        }
        records.add(record);
    }
}
