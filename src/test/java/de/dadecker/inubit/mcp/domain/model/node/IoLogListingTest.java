package de.dadecker.inubit.mcp.domain.model.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Feature 024 (T007, FR-020, research D-8): the I/O log listing of a run and its filter. */
class IoLogListingTest {

    private static final List<IoLogEntry> ENTRIES = List.of(
        IoLogEntry.parse("4711", "4711@@@Orders(Module-0001)@@@Input.1"),
        IoLogEntry.parse("4711", "4711@@@Invoices(Module-0002)@@@Input.1"),
        IoLogEntry.parse("4711", "4711@@@Orders(Module-0001)@@@Output.1"),
        IoLogEntry.parse("4711", "4711@@@Module-0002@@@Output.2"),
        IoLogEntry.parse("4711", "4711@@@Orders(Module-0009)@@@Input.2"));

    private static final IoLogListing LISTING = new IoLogListing("node1", ENTRIES);

    @Test
    void holdsAllEntriesInServerOrder() {
        assertThat(LISTING.answeringNode()).isEqualTo("node1");
        assertThat(LISTING.entries()).containsExactlyElementsOf(ENTRIES);
    }

    @Test
    void entriesAreCopied() {
        List<IoLogEntry> list = new ArrayList<>(ENTRIES);
        IoLogListing listing = new IoLogListing("node1", list);

        list.clear();

        assertThat(listing.entries()).hasSize(5);
    }

    @Test
    void withoutModuleTheFilterKeepsEverything() {
        assertThat(LISTING.filter(Optional.empty())).containsExactlyElementsOf(ENTRIES);
    }

    @Test
    void filtersOnTheModuleNameInServerOrder() {
        assertThat(LISTING.filter(Optional.of("Orders")))
            .containsExactly(ENTRIES.get(0), ENTRIES.get(2), ENTRIES.get(4));
    }

    @Test
    void filtersOnTheModuleIdIncludingAnUnparsedModulePart() {
        assertThat(LISTING.filter(Optional.of("Module-0002")))
            .containsExactly(ENTRIES.get(1), ENTRIES.get(3));
        assertThat(LISTING.filter(Optional.of("Module-0009"))).containsExactly(ENTRIES.get(4));
    }

    @Test
    void filterComparesExactly() {
        assertThat(LISTING.filter(Optional.of("orders"))).isEmpty();
        assertThat(LISTING.filter(Optional.of("Order"))).isEmpty();
        assertThat(LISTING.filter(Optional.of("Orders(Module-0001)"))).isEmpty();
    }

    @Test
    void anEmptyListingIsAllowed() {
        assertThat(new IoLogListing("node1", List.of()).filter(Optional.of("Orders"))).isEmpty();
    }

    @Test
    void refusesAnAnsweringNodeOutsideTheNameRule() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IoLogListing("", ENTRIES));
        assertThatIllegalArgumentException().isThrownBy(() -> new IoLogListing("node 1", ENTRIES));
    }
}
