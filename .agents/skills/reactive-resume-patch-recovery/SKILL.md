---
description: Recover reactive-resume_apply_resume_patch failures (stringified ops, oversized batches, rejected paths) without corrupting hidden items, keyword arrays, or metrics.
when_to_use: Any time `reactive-resume_apply_resume_patch` errors, ops "land" on the wrong item, or the planned tailor-resume patch is large (50+ ops) — during any tailor-resume run.
---

## Context

`tailor-resume` §34 says "apply ALL changes in ONE RFC 6902 batch", but three failure
modes recur in practice (Bitcap, Recare/RECRUITEE, DCS sessions):

1. Large payloads arrive serialized as a string → tool rejects with
   "Operations need to be a real JSON array".
2. Planned paths don't match the stored schema (whole-array replace at `/skills`
   rejected; even leaf paths under `/skills` fail — skills actually live under `sections`).
3. Index misfires: ops written against plan positions land on a different (often hidden)
   item, silently overwriting it.

Persistent memories cover value-stringification and index pre-verification; this skill
adds the isolation/recovery flow on top.

## Steps

1. **Pre-flight** — `reactive-resume_read_resume` the target NOW and count every array
   index against that output (experience items, project items, skills sections/items).
   Never count against the plan, memory, or a stale read. Note which items are hidden —
   they occupy indices too.

2. **Attempt the single consolidated batch** (tailor-resume §34).

3. **"Operations need to be a real JSON array"** → payload-size serialization failure:
   - Confirm single-op health with a non-mutating RFC 6902 `test` op on a leaf value
     from the just-read JSON.
   - If `test` passes, split the patch into sequential batches (~5-15 ops each), applying
     in order. This is the "technically required" exception to one-batch.

4. **Path rejected (4xx / "path invalid")** → schema mismatch, not a transient error:
   - Read the raw stored document to get real paths. `read_resume` output is the
     document; if still unclear, inspect the DB row:
     `docker exec reactive_resume-postgres-1 psql -U postgres -d <dbname>` — connection
     details (dbname, credentials) in `~/projects/reactive-resume/.env.local`
     (`DATABASE_URL`). Verify table/column names from the live schema (`\dt`), do not
     assume them.
   - Known shape: **skills live under `sections`, not `skills`**; tags/name/slug are NOT
     in the document path at all.

5. **Achieving display order without whole-array replace:** per-item leaf ops that keep
   each item's `id` and swap `name`/`keywords` by position, e.g.
   `{"op":"replace","path":"/sections/3/items/0/name","value":"..."}`.

6. **Name/slug/tags** → separate tool `reactive-resume_update_resume` (dedicated
   metadata tool), never a document patch.

7. **Value-type traps** (from persistent memory, still apply):
   - Every `value` gets stringified → booleans are rejected; to "hide"/"unhide", rewrite
     the fields of already-visible items in place instead of toggling `hidden`.
   - `replace` with a string[] value stores `[]` and wipes the array → emit one
     `{"op":"add","path":".../keywords/-","value":"X"}` op per element.

8. **Mandatory re-read after every patch (batch or full):** verify ops landed at the
   intended indices, keyword arrays are still real arrays, hidden items untouched,
   metrics/dates/links preserved. If a misfire clobbered an item: restore it from the
   base resume (the duplicate source), then re-apply the intended ops at the correct
   indices.

9. **Cleanup pass** — check restored hidden items for stray elements leaked during the
   misfire (e.g. a keyword like `Spring AI` left in a hidden project's keywords) and
   remove them.

## Gotchas

- Re-read is the ONLY way index misfires surface — the tool reports success. The DCS
  session caught `items/1` (hidden Opencode-X) being overwritten by content planned for
  `items/2` (Throttle) only on re-read.
- Never blindly re-run a failed batch: isolate first (`test` op → single op → small
  batch) or you may double-apply the ops that did land.
- A failed large batch and a failed path error are different problems — do not split
  batches for a path error (step 4), and do not change paths for a string-array error
  (step 3).
- After tags updates, re-read confirms tags only via the metadata/update tool —
  `read_resume` shows no `/tags` path, so absence there is not evidence of failure.

## Templates

Isolate payload failure:

```json
[{"op": "test", "path": "/profile", "value": "<value copied from read_resume>"}]
```

Split + leaf-order skills patch (no whole-array replace):

```json
[
  {"op": "replace", "path": "/sections/3/items/0/name", "value": "Languages"},
  {"op": "replace", "path": "/sections/3/items/0/keywords", "value": ["Java"]},
  {"op": "add",     "path": "/sections/3/items/0/keywords/-", "value": "Spring Boot"}
]
```

(Note: the `keywords` `replace` above is only safe when immediately verified on re-read;
the per-element `add` form is the reliable pattern for existing arrays.)
