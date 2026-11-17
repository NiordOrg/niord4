Data, not code. Loaded only into a fresh deployment (bootstrap), only by a sysadmin's import (releases), or
only into the demo database (sample). A running deployment's data may differ from these files and is the
truth; nothing here ever changes it.

A test archive (BootstrapSameRunITTest): the earlier archive of one bootstrap run, whose entries the later archive of
the run replaces values in and adds to (the first bootstrap's rule). Each entry is there on purpose:

- `templates/it/same-run-a.ftl`, which the later archive replaces, and `same-run-b.ftl`, which it restates equal.
- `it-dict.json`: `k1` (`en` with a long value), whose `en` and long value the later archive replaces and which gains
  `da`; `k2` (`en`, `da`), whose `da` it replaces; `k5`, with an AtoN filter the later archive leaves out, and `k6`,
  whose `en` it spells `EN`, both counted unchanged; `k7` (`en`, a long value, an AtoN filter), whose value it
  replaces and whose long value and AtoN filter it leaves out (kept); `k8`, whose AtoN filter it replaces; `k9`
  (`en`), whose `en` the later archive gives empty and to which it gives an empty `da`, counted unchanged.
- `categories.json`: `It root` (`en`, `fr`, `de`, a domain link) with the children `It child` (a template category
  with `it_p`, a domain link and a script path) and `It sibling`, so the root is imported once for each; `de` is a
  language the later archive leaves out (kept). `It inactive`, inactive, which the later archive's new active child
  makes active. `It flags` (`it_m` mandatory and a position list, `it_n` a position list). `It deactivated`, active,
  which the later archive makes inactive. `It equal`, which the later archive restates equal in every value, and
  its twin `It earlier only`, which it does not name: the two keep the same row version. `It changes` (a category
  with the AtoN filter `it-filter-a`, the editor field `it-a`, the standard field `it-x` and no MRN), whose type,
  AtoN filter and both lists the later archive replaces and whose MRN it fills. `It mrn`, with an MRN, which the
  later archive restates with another (kept: a matching key). `It emptied` (the editor field `it-a`, the standard
  field `it-x`), whose editor fields the later archive empties with an explicit empty list and whose standard
  fields it leaves out (kept). `It inactive kept`, inactive, which the later archive restates leaving out its active
  flag (kept inactive). `It parent`, active, with the active child `It active child`, which the later archive makes
  inactive, and with it the child. `It inactive parent`, inactive, with the inactive child `It activated child`,
  which the later archive makes active, and with it the parent.
- `param-types.json`: `it_composite` (`p1`, `p2`, and a second `p1`); `it_same`, which the later archive restates
  equal; `it_mandatory` (`m1`, mandatory, of type `text`), which the later archive restates with names only;
  `it_list`, a list type with the value `k1`; `it_typed` (`t1`, of type `text`), whose parameter's type the later
  archive replaces; `it_list_emptied`, a list type with the value `k1`, which the later archive empties.
- `promulgation-types.json`: `it-navtex`, whose priority the later archive replaces and to which it adds domains;
  `it-domains-only`, which the later archive restates with its typeId and a domain only (every value kept);
  `it-flags` (priority 6, active, no requirement), which the later archive makes inactive, gives priority 0 and the
  requirement `MANDATORY` (replaced and filled); `it-changes` (`navtex`, `en`, `OPTIONAL`, the message type
  `LOCAL_WARNING`, the script path `same-run-a.ftl`), whose name, service, language, requirement, message types and
  script paths the later archive replaces; `it-emptied` (a message type and a script path), whose message types and
  script paths the later archive empties with explicit empty lists.
- `reports.json`: `it-report` (the properties `a` and `b`), whose name and property `b` the later archive replaces
  and to which it adds domains; `a`, which it leaves out, is kept. `it-report-changes` (sort order 1, the template
  `same-run-a.ftl`, the parameter `p`), whose sort order and template path the later archive replaces and to which
  it adds the parameter `q`.
