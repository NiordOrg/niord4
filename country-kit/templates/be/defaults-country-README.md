# defaults/country/

**Defaults only: a running deployment may differ, and keeps its values.**

This folder holds the country's defaults, which replace the base product's for a new database:

- `settings.json`: the country's settings file. `values` holds the values init was given (the
  country, the organisation, the languages, the map's centre and the Niord home), and
  `useBaseDefault` lists every other key of the base product's settings file, each with the reason
  the base product's default is right here.
- `texts/<lang>/<bundle>.properties`, once the country words a text of its own: its own keys,
  prefixed with its code, and its own wording of a base product key.

Start-up only adds a key a deployment lacks; it never changes a value a deployment has. A change
here reaches new databases, and a running deployment is changed on its Settings page.

`CountryDefaultsChecksTest`, in the country build group that `./mvnw verify` runs, checks the file
against the base product's catalog: every key declared and of its type, no state key and no
secret, every key of the base product's file set or listed with a reason, and every required key
set.

The rules are the country API's, in `.niord/kit/docs/country-api.md`, The settings file.
