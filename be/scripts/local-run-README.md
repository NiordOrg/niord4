# org.niord:niord-local-run

The files a country's launcher takes from the base product for a local run, of the same version as the
`niord-parent` the country builds on. This package is for a local run only. It carries known passwords and the
sample, and it is never an image input: a country deploys with its image and the chart, never with these files.

## Layout

| Path | What it is |
|---|---|
| `scripts/local-run-lib.sh` | the launcher library: the country-neutral functions of a local run |
| `scripts/bootstrap.sh` | posts the bootstrap archives to a new database's backend, once |
| `scripts/load-sample.sh` | loads the sample into a local sample database |
| `sample/niord-sample.sql` | the sample's rows, its attachment files among them |
| `docker-compose.yml` | the shared containers `niord-db`, `niord-keycloak` and `niord-smtp` |
| `keycloak/niord-realm-local.json` | the local realm `niord-keycloak` imports |
| `README.md` | this file |

The backend contract for a country, `country-api.md`, is not here: it is in the country kit's package,
`org.niord:niord-country-kit` of the same version, as `docs/country-api.md`.

## How they fit

- A launcher sources `scripts/local-run-lib.sh`. The library's `bootstrap_database` runs `bootstrap.sh` from its
  own folder, and its `sample_load` runs the loader the launcher names, here `scripts/load-sample.sh`. At every
  start, `bootstrap_database` first runs `ensure_domain_clients` over the country's bootstrap folder (see below).
- `load-sample.sh` reads the sample from `../sample`, and loads it only into the loopback database `niord_sample`
  or `niord_sample_<cc>` (the country's two-letter code, lower case).
- `start_services` takes `docker-compose.yml` and `keycloak/niord-realm-local.json`. It starts only the shared
  containers that are missing or stopped, and never recreates a running one.
- Run the scripts with bash (`bash scripts/load-sample.sh …`). The zip keeps no exec bits, and the library runs
  `bootstrap.sh` and the loader with bash itself.
- The comments inside the copied files name the paths of the base product's own repository (`../start-local.sh`,
  `niord/sample/`); here the files are where the layout above puts them.

## The local Keycloak clients of a country's domains

- When: at the bootstrap step of every start, before anything is posted, whatever the database holds.
- What: for each domain of `be/bootstrap/domains.json` with `"active": true` whose client the shared local realm
  lacks, the library creates the client (its client ID is the domain id, bearer-only like the realm's own domain
  clients), its roles `user`, `editor`, `admin` and `sysadmin` (`editor` holds `user`; `admin` holds `editor`;
  `sysadmin` holds `editor` and `admin`), and gives `sysadmin` to the group `Sysadmin`. A client made by an earlier
  version of the library also has the role `publication-curate`: no endpoint asks for it, and the Users & Contacts
  page does not count it when it decides which groups an administrator may hand out. The library leaves it; delete
  it from the client in the admin console, which also takes it off the composites and the group `Sysadmin`.
- Only the local `sysadmin` is given a role in the new domain; give `admin`, `editor` and `user` theirs in the
  admin console when needed.
- Once and never more: a client that exists is left exactly as it is, nothing in Keycloak is updated or deleted,
  and a second start adds nothing. The base product's archive and a `--sample` run are never read.
- What you see: one `>> Creating the local Keycloak client <id>` line per new client, or a line ending
  `exist: left as they are`. Signed in as `sysadmin` / `sysadmin`, the domain's Sysadmin menu shows; a session
  signed in before the client was made needs a new sign-in.
- Loopback only: `kcadm.sh` runs inside `niord-keycloak` (`docker exec niord-keycloak /opt/keycloak/bin/kcadm.sh`)
  against that container's own `http://localhost:8080/auth`, signed in as Keycloak's local administrator `admin` /
  `admin`; nothing leaves the machine.
- When it fails: a `!!` line names what failed and the start goes on. `docker logs niord-keycloak` says why, and the
  next `./start-local.sh` tries again.
- A client left with roles missing is never repaired by the launcher: delete it in the admin console
  (`http://localhost:8080/auth/admin/`, realm `niord`, Clients) and start again.

## Known passwords: a local trial on this machine only

- Every port of the shared containers is published on 127.0.0.1 only, and Keycloak runs in development mode.
- The passwords are known: each sample user's password is its name (`sysadmin`, `admin`, `editor` and `user`),
  Keycloak's administrator is `admin` / `admin`, and the database user is `niord` / `niord`.
- Never expose these containers or a backend started with them to a network.
- Never use the package as an image input or as a deployment's input.
