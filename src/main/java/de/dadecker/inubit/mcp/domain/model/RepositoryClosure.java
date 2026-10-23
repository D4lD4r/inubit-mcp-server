package de.dadecker.inubit.mcp.domain.model;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The repository files that start references reach within the owner's repository root
 * {@code /Root/<owner>/} (research D-3, data-model.md).
 *
 * <p>A worklist follows the references; each path is looked up once, so a cycle ends. A path counts
 * as inside the owner only in canonical form: {@code /Root/<owner>/} followed by non-empty segments
 * other than {@code .} and {@code ..}. Every other reference (another owner's root, a path outside
 * {@code /Root}, a non-canonical form such as {@code /Root/<owner>/../other/x.xsd}, which the
 * resolvers refuse too) goes to {@link #outsideOwner} and is not followed. A path inside the owner
 * whose file is missing (lookup empty) is kept in {@link #included} and not followed.
 *
 * @param included     the repository paths inside the owner's root that were reached, missing
 *                     files included
 * @param outsideOwner the references that were not followed because they leave the owner's root,
 *                     each once, in the order reached
 * @param referencedBy for each included path the referring files, as workspace-relative paths
 *                     with {@code /} (e.g. {@code dev/jdoe/workflows/Orders/Receive.xml})
 */
public record RepositoryClosure(SortedSet<String> included, List<RepositoryReference> outsideOwner,
    Map<String, SortedSet<String>> referencedBy) {

    private static final String ROOT = "Root";

    public RepositoryClosure {
        included = Collections.unmodifiableSortedSet(new TreeSet<>(included));
        outsideOwner = List.copyOf(outsideOwner);
        SortedMap<String, SortedSet<String>> copy = new TreeMap<>();
        referencedBy.forEach((path, from) ->
            copy.put(path, Collections.unmodifiableSortedSet(new TreeSet<>(from))));
        referencedBy = Collections.unmodifiableSortedMap(copy);
    }

    /**
     * The closure of {@code start} within {@code /Root/<owner>/}.
     *
     * @param referencesOf the references of the file at a repository path, empty if the file is
     *                     missing
     */
    public static RepositoryClosure of(String owner, Collection<RepositoryReference> start,
        Function<String, Optional<List<RepositoryReference>>> referencesOf) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(referencesOf, "referencesOf");
        Deque<RepositoryReference> worklist = new ArrayDeque<>(start);
        SortedSet<String> included = new TreeSet<>();
        SequencedSet<RepositoryReference> outside = new LinkedHashSet<>();
        Map<String, SortedSet<String>> referencedBy = new TreeMap<>();
        while (!worklist.isEmpty()) {
            RepositoryReference reference = worklist.removeFirst();
            String path = reference.to();
            if (!insideOwner(owner, path)) {
                outside.add(reference);
                continue;
            }
            referencedBy.computeIfAbsent(path, p -> new TreeSet<>())
                .add(reference.from().toRelativePath().toString().replace('\\', '/'));
            if (included.add(path)) {
                referencesOf.apply(path).ifPresent(worklist::addAll);
            }
        }
        return new RepositoryClosure(included, List.copyOf(outside), referencedBy);
    }

    private static boolean insideOwner(String owner, String path) {
        String[] segments = path.split("/", -1);
        if (segments.length < 4 || !segments[0].isEmpty() || !segments[1].equals(ROOT)
            || !segments[2].equals(owner)) {
            return false;
        }
        for (int i = 3; i < segments.length; i++) {
            if (segments[i].isEmpty() || segments[i].equals(".") || segments[i].equals("..")) {
                return false;
            }
        }
        return true;
    }
}
