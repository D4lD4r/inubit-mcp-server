TASKS: specs/019-local-knowledge/tasks.md ROUND: D-7 decision

The person chose D-7 option (A) on 2026-11-10. The selected fail-closed option (A), including all-profile credential reads, refusal while any store is unavailable, and the bounded 5 s startup wait when notes exist, is consistent across the behavioral artifacts (data model, contracts, quickstart and tasks needed no change). However, several decision records retain language implying that D-7 is still pending or that B/C remain available choices.

FINDINGS:
- [minor] tasks.md:25-30,120-131 – The completed decision still says the gate "needs" a choice, uses the D-7 wording `"Decision" no longer pending`, and describes later phases as waiting for the answer – rewrite as a completed gate, keeping option (A) as the selected behaviour.
- [minor] plan.md:44-50,224; research.md:344-352 – Present-tense phrases such as "Both are offered to the person," "if the person prefers it," and the ambiguous description of C as "as chosen" make the confirmed choice appear reopenable – describe B and C strictly as rejected alternatives and state that the gate is satisfied.

VERDICT: CHANGES_REQUESTED

RESOLUTION (applied in "Feature 019: D-7 decision (option A)"): tasks.md header, T016 and the implementation strategy describe a passed gate with option (A); plan.md and research.md name B and C as alternatives rejected by the person on 2026-11-10 and the gate as passed. No behavioural artifact changed.
