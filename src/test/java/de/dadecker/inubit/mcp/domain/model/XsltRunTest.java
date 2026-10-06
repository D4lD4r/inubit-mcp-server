package de.dadecker.inubit.mcp.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.XsltRun.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T005: an XSLT run has an output exactly when it passed. */
class XsltRunTest {

    private static final String XSL = "dev/OWNERS/modules/XSLT Converter/M-1/xslt.stylesheet.xsl";
    private static final String INPUT = "dev/OWNERS/samples/order.xml";
    private static final String OUT = ".tests/dev/OWNERS/M-1/order.xml.out";

    @Test
    void aPassedRunHasItsOutputAndTheStandInsItUsed() {
        List<String> standIns = new ArrayList<>(List.of("Misc.guid"));
        XsltRun run = new XsltRun(XSL, INPUT, Optional.of(OUT), Outcome.OK, standIns);

        standIns.add("Formatter.getDateTime");

        assertThat(run.output()).contains(OUT);
        assertThat(run.standInsUsed()).containsExactly("Misc.guid");
        assertThatThrownBy(() -> run.standInsUsed().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void onlyAPassedRunHasAnOutput() {
        assertThatIllegalArgumentException().isThrownBy(() -> new XsltRun(XSL, INPUT,
            Optional.empty(), Outcome.OK, List.of())).withMessageContaining("output");
        assertThatIllegalArgumentException().isThrownBy(() -> new XsltRun(XSL, INPUT,
            Optional.of(OUT), Outcome.NOT_TESTABLE, List.of())).withMessageContaining("output");
        assertThat(new XsltRun(XSL, INPUT, null, Outcome.ERROR, List.of()).output()).isEmpty();
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatNullPointerException().isThrownBy(() -> new XsltRun(null, INPUT,
            Optional.empty(), Outcome.ERROR, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new XsltRun(XSL, null,
            Optional.empty(), Outcome.ERROR, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new XsltRun(XSL, INPUT,
            Optional.empty(), null, List.of()));
    }
}
