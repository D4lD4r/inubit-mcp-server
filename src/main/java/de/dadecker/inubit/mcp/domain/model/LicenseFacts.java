package de.dadecker.inubit.mcp.domain.model;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The license facts of an INUBIT server (data-model.md → ServerSettings). {@code expiryDate}
 * and {@code licensee} come from {@code GET /license}; {@code licensedThreads} comes from
 * {@code /metrics} like in {@code get_health}, and from {@code GET /license} only when
 * {@code /metrics} is not licensed. Each one is empty while its source failed, so the licensed
 * threads are still reported when {@code GET /license} is refused (FR-013).
 *
 * @param licensee the licensee, masked by {@link SecretRules} (research D-19)
 */
public record LicenseFacts(Optional<LocalDate> expiryDate, Optional<Long> licensedThreads,
    Optional<String> licensee) {

    public LicenseFacts {
        expiryDate = expiryDate == null ? Optional.empty() : expiryDate;
        licensedThreads = licensedThreads == null ? Optional.empty() : licensedThreads;
        licensee = licensee == null ? Optional.empty() : licensee;
    }
}
