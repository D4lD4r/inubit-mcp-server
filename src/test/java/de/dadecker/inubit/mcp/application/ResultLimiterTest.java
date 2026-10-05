package de.dadecker.inubit.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.Page;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ResultLimiterTest {

    private final ResultLimiter limiter = ResultLimiter.withDefaults();

    private static List<String> items(int count) {
        return IntStream.range(0, count).mapToObj(i -> "item" + i).toList();
    }

    private static List<String> itemsOfSize(int count, int size) {
        return IntStream.range(0, count).mapToObj(i -> "x".repeat(size)).toList();
    }

    private static int chars(Page<String> page) {
        return page.items().stream().mapToInt(String::length).sum();
    }

    @Test
    void defaultsAreTheDocumentedLimits() {
        assertThat(limiter.maxItems()).isEqualTo(100);
        assertThat(limiter.maxChars()).isEqualTo(50_000);
        assertThat(ResultLimiter.MAX_MESSAGE_CHARS).isEqualTo(2_000);
        assertThat(ResultLimiter.MAX_ITEM_CHARS).isEqualTo(4_000);
    }

    @Test
    void pagesWithOffsetAndLimit() {
        Page<String> page = limiter.page(items(250), 50, 50, String::length);

        assertThat(page.items()).hasSize(50).first().isEqualTo("item50");
        assertThat(page.offset()).isEqualTo(50);
        assertThat(page.limit()).isEqualTo(50);
        assertThat(page.total()).hasValue(250);
        assertThat(page.totalIsLowerBound()).isFalse();
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).hasValue(100);
    }

    @Test
    void lastPageHasNoNextOffset() {
        Page<String> page = limiter.page(items(250), 240, 50, String::length);

        assertThat(page.items()).hasSize(10).last().isEqualTo("item249");
        assertThat(page.nextOffset()).isEmpty();
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void offsetBeyondTheEndGivesAnEmptyPage() {
        Page<String> page = limiter.page(items(5), 10, 50, String::length);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).hasValue(5);
        assertThat(page.nextOffset()).isEmpty();
    }

    @Test
    void limitAboveMaxItemsIsCappedAndMarkedTruncated() {
        Page<String> page = limiter.page(items(250), 0, 500, String::length);

        assertThat(page.items()).hasSize(100);
        assertThat(page.limit()).isEqualTo(100);
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).hasValue(100);
    }

    @Test
    void limitAboveMaxItemsIsNotTruncationWhenNothingWasCut() {
        Page<String> page = limiter.page(items(30), 0, 500, String::length);

        assertThat(page.items()).hasSize(30);
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).isEmpty();
    }

    @Test
    void maxCharsCutsThePageAndSetsTheNextOffset() {
        Page<String> page = limiter.page(itemsOfSize(100, 1_000), 0, 100, String::length);

        assertThat(page.items()).hasSize(50);
        assertThat(chars(page)).isLessThanOrEqualTo(50_000);
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).hasValue(50);
        assertThat(page.total()).hasValue(100);
    }

    @Test
    void anItemAboveTheItemBoundIsAProgrammingError() {
        List<String> oversized = itemsOfSize(1, ResultLimiter.MAX_ITEM_CHARS + 1);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> limiter.page(oversized, 0, 10, String::length))
            .withMessageContaining(String.valueOf(ResultLimiter.MAX_ITEM_CHARS));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> limiter.bound(oversized, 0, 10, OptionalLong.of(1), String::length));
    }

    @Test
    void pagingAlwaysMakesProgressWithMaximalItems() {
        ResultLimiter smallest = new ResultLimiter(10, ResultLimiter.MAX_ITEM_CHARS);

        Page<String> page = smallest.page(itemsOfSize(3, ResultLimiter.MAX_ITEM_CHARS), 0, 10,
            String::length);

        assertThat(page.items()).hasSize(1);
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).hasValue(1);
    }

    @Test
    void limitsBelowTheItemBoundAreRejected() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ResultLimiter(10, ResultLimiter.MAX_ITEM_CHARS - 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResultLimiter(0, 50_000));
    }

    @Test
    void shareSplitsTheCharBudgetOfOneToolResultAcrossServers() {
        ResultLimiter share = limiter.share(4);

        assertThat(share.maxChars()).isEqualTo(12_500);
        assertThat(share.maxItems()).isEqualTo(100);
        assertThat(limiter.share(1).maxChars()).isEqualTo(50_000);
    }

    @Test
    void sharesTogetherStayWithinTheToolResultBudget() {
        int servers = 5;
        ResultLimiter share = limiter.share(servers);

        int total = 0;
        for (int i = 0; i < servers; i++) {
            total += chars(share.page(itemsOfSize(100, 1_500), 0, 100, String::length));
        }

        assertThat(total).isLessThanOrEqualTo(limiter.maxChars());
    }

    @Test
    void shareNeverDropsBelowTheItemBound() {
        assertThat(limiter.share(50).maxChars()).isEqualTo(ResultLimiter.MAX_ITEM_CHARS);
        assertThatIllegalArgumentException().isThrownBy(() -> limiter.share(0));
    }

    @Test
    void invalidOffsetOrLimitIsInvalidInput() {
        assertThatThrownBy(() -> limiter.page(items(5), -1, 10, String::length))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().code()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> limiter.page(items(5), 0, 0, String::length))
            .isInstanceOfSatisfying(ToolErrorException.class,
                e -> assertThat(e.error().message()).contains("limit"));
    }

    @Test
    void boundsARemotelyFetchedPage() {
        Page<String> page = limiter.bound(items(10), 20, 10, OptionalLong.of(100), String::length);

        assertThat(page.items()).hasSize(10);
        assertThat(page.offset()).isEqualTo(20);
        assertThat(page.total()).hasValue(100);
        assertThat(page.nextOffset()).hasValue(30);
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void remoteShortPageIsNotTruncatedEvenIfTheTotalIsLarger() {
        Page<String> page = limiter.bound(items(4), 0, 10, OptionalLong.of(100), String::length);

        assertThat(page.items()).hasSize(4);
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).hasValue(4);
    }

    @Test
    void remotePageCutByMaxItemsIsTruncated() {
        Page<String> page = limiter.bound(items(500), 0, 500, OptionalLong.of(500), String::length);

        assertThat(page.items()).hasSize(100);
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).hasValue(100);
    }

    @Test
    void remotePageWithUnknownTotalIsALowerBound() {
        Page<String> full = limiter.bound(items(10), 0, 10, OptionalLong.empty(), String::length);
        Page<String> partial = limiter.bound(items(4), 0, 10, OptionalLong.empty(), String::length);

        assertThat(full.total()).isEmpty();
        assertThat(full.totalIsLowerBound()).isTrue();
        assertThat(full.nextOffset()).hasValue(10);
        assertThat(partial.nextOffset()).isEmpty();
    }

    @Test
    void remotePageWithMoreItemsThanTheLimitIsSlicedWithoutTruncation() {
        Page<String> page = limiter.bound(items(15), 0, 10, OptionalLong.of(15), String::length);

        assertThat(page.items()).hasSize(10);
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).hasValue(10);
    }

    @Test
    void logMessagesAreTruncatedTo2000CharsWithAMarker() {
        String message = "m".repeat(2_500);

        String truncated = ResultLimiter.truncateMessage(message);

        assertThat(truncated).hasSize(2_000).endsWith(ResultLimiter.TRUNCATION_MARKER);
        assertThat(truncated).startsWith("mmmm");
    }

    @Test
    void shortMessagesAreKept() {
        String message = "m".repeat(2_000);

        assertThat(ResultLimiter.truncateMessage(message)).isSameAs(message);
        assertThat(ResultLimiter.truncateMessage(null)).isNull();
    }

    @Test
    void truncationDoesNotSplitASurrogatePair() {
        int keep = ResultLimiter.MAX_MESSAGE_CHARS - ResultLimiter.TRUNCATION_MARKER.length();
        String message = "a".repeat(keep - 1) + "😀" + "b".repeat(100);

        String truncated = ResultLimiter.truncateMessage(message);

        int markerStart = truncated.length() - ResultLimiter.TRUNCATION_MARKER.length();
        String kept = truncated.substring(0, markerStart);
        assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 1))).isFalse();
        assertThat(truncated.length()).isLessThanOrEqualTo(ResultLimiter.MAX_MESSAGE_CHARS);
    }
}
