package de.dadecker.inubit.mcp.adapter.rest.v81;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The requests of one log query and how their rows are combined (paging rule of research R-9).
 *
 * <ul>
 *   <li>single request: {@code startIndex = offset}, {@code noOfItems = limit}; the response is
 *       the page and its {@code total} the total.
 *   <li>merged requests (a filter expands to several raw values): each request has
 *       {@code startIndex = 0} and {@code noOfItems = offset + limit}; the rows are merged,
 *       sorted by the time field descending (stable), sliced to {@code [offset, offset + limit)},
 *       and {@code total} is the sum of the response totals.
 * </ul>
 */
record LogRequestPlan(List<LogRequest> requests, boolean merged, int offset, int limit) {

    LogRequestPlan {
        requests = List.copyOf(requests);
    }

    /** One POST body with its paging values. */
    record LogRequest(int startIndex, int noOfItems, String body) {
        LogRequest {
            Objects.requireNonNull(body, "body");
        }
    }

    /** The rows of one response (or of the combined page) and the total number of matches. */
    record Window<T>(List<T> rows, long total) {
        Window {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Combines the responses, in request order, to the requested page.
     *
     * @param newestFirst the order of merged rows (time field descending)
     */
    <T> Window<T> combine(List<Window<T>> responses, Comparator<T> newestFirst) {
        if (responses.size() != requests.size()) {
            throw new IllegalArgumentException("expected " + requests.size() + " responses, got "
                + responses.size());
        }
        if (!merged) {
            Window<T> response = responses.get(0);
            List<T> rows = response.rows();
            return rows.size() <= limit ? response
                : new Window<>(rows.subList(0, limit), response.total());
        }
        List<T> all = new ArrayList<>();
        long total = 0;
        for (Window<T> response : responses) {
            all.addAll(response.rows());
            total += response.total();
        }
        all.sort(newestFirst); // stable: ties keep the request order
        int from = Math.min(offset, all.size());
        int to = Math.min(offset + limit, all.size());
        return new Window<>(all.subList(from, to), total);
    }
}
