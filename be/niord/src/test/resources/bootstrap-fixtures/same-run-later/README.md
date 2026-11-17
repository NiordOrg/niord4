Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

A test archive (BootstrapSameRunITTest): the later archive of one bootstrap run. Within the first bootstrap's rule,
each value it gives replaces the one the earlier archive of the run created, a value it leaves out is kept, and what
is new is added. Each entry is there on purpose:

- `templates/it/same-run-a.ftl` (replaced), `same-run-b.ftl` (equal, not written) and `same-run-c.ftl` (new).
- `it-dict.json`: `k1` replaces its `en` and long value and adds `da`; `k2` replaces its `da`; `k3` is new; `k5`
  leaves out the AtoN filter and `k6` spells its language `EN`, both counted unchanged; `k7` replaces its value and
  leaves out its long value and AtoN filter (kept); `k8` replaces its AtoN filter; `k9` gives its `en` empty and a
  new `da` empty, both counted as left out (an empty value never clears a value nor adds a language), so `k9` is
  counted unchanged.
- `categories.json`: `It root` replaces its `fr` name and its sort order, adds `da` and a domain link, and leaves
  out `de` (kept); `It child` adds `da` to itself and to `it_p`, replaces `it_p`'s `en` name and its script path,
  adds the template parameter `it_q`, and names no domain (its link is kept); `It sibling` and `It equal` are equal;
  `It inactive` gets the new active child `It new child`; `It flags` restates `it_m` with its name only (its flags
  and type kept) and gives `it_n`'s position list false (replaced), and leaves out its sort order (kept) and its
  active flag (the earlier one's is true, the default, so `It inactive kept` is the case that shows the flag kept);
  `It deactivated` gives active false (replaced). `It changes` gives the type `TEMPLATE`, the AtoN filter
  `it-filter-b`, the editor fields `it-b`, `it-c` and the standard field `it-y` (each replaced) and an MRN (filled);
  `It mrn` gives another MRN (kept: a matching key); `It emptied` gives its editor fields as an explicit empty list
  (emptied) and leaves out its standard fields (kept); `It inactive kept` leaves out its active flag (kept
  inactive); `It parent` gives active false (replaced), which makes its active child `It active child` inactive
  too, and its job-log line names the child; `It activated child` gives active true (replaced) under an `It inactive
  parent` that leaves out its active flag, which makes the parent active too, and the child's job-log line names the
  parent.
- `it-dict.json` also holds the dictionary `web`, which restates the start-up default text `menu.atons` with
  another `en`: no archive of the run created that key, so start-up's rules keep it (`kept 1 (not ticked)`), and the
  job log says so on a line of its own.
- `param-types.json`: `it_composite` adds `da` to `p1` and to the second `p1`, by order, replaces `p1`'s `en`, adds
  `p3` and lacks `p2` (kept); `it_same` is equal; `it_mandatory` restates `m1` with names only, so it stays mandatory
  and of type `text`, and gains `da`; `it_list` gives the values `k1`, `k2` (replaced); `it_typed` gives `t1` the
  type `number` (replaced); `it_list_emptied` gives its values as an explicit empty list (emptied).
- `promulgation-types.json`: `it-navtex` replaces its priority and adds the domain `it-later`, listed twice (added
  once), and names the unknown domain `it-missing` (not found); `it-domains-only` gives its typeId and a domain
  only (every value kept, the domain added); `it-flags` gives active false, priority 0 and the requirement
  `MANDATORY` (replaced and filled); `it-changes` gives the name `It changes renamed`, the service `twitter`, the
  language `da`, the requirement `MANDATORY`, the message types `COASTAL_WARNING`, `NAVAREA_WARNING` and the script
  path `same-run-b.ftl` (each replaced) and leaves out its priority and active flag (kept); `it-emptied` gives its
  message types and script paths as explicit empty lists (emptied).
- `reports.json`: `it-report` replaces its name and the property `b`, leaves out `a` (kept), adds the domain
  `it-later`, listed twice (added once), and names `it-missing` (not found); `it-report-changes` replaces its sort
  order and template path, adds the parameter `q`, restates `p` equal and leaves out its name (kept).
