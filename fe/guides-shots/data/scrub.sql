-- scrub.sql: removes the personal data from niord_guides, the copy of niord_test the user-guide screenshots are taken on.
--
-- Run by make-guides-db.sh with psql --single-transaction -v ON_ERROR_STOP=1, on a fresh copy each time, so the result is
-- the same on every run: every invented value is a function of the row's id (or of the original value's sort rank),
-- and the few inserted rows take their ids from the copy's own Hibernate sequences, whose state comes from the dump.
--
-- What is scrubbed (personal data the schema can hold, found in the entity classes and in the data):
--   "User"                     names, e-mail, login (username)        -> invented people on example.org; the four
--                                                                        local Keycloak logins mirror the realm
--   "Comment"                  free text written by staff             -> a neutral sentence from a fixed list
--   "Comment_emailAddresses"   the addresses a comment was mailed to  -> invented people on example.org
--   "Contact", "MailingList_Contact", "MailingList_User"  (empty in niord_test) -> invented recipients, so the lists
--                                                                        look real
--   "MessageFilter".name, "MessageTag".name  saved filters and tags named after a colleague ("Korrektur <name>")
--                                                                     -> "Korrektur <invented first name>"
--   "ScheduledMail", "ScheduledMailRecipient"  the mail queue (bodies and recipients) -> deleted
--   "Setting"                  passwords, logins and keys of third-party services -> emptied
--   "TwitterSettings"          API keys and tokens                    -> emptied
--   "NiordIntegration".url     another Niord's address                -> emptied
--   "IssueOverride".reason, "IssueMember"."reasonNote", "PublicationIssue"."retiredReason",
--   "IssueAuditEntry"."actorLabel"  curator free text                 -> neutral sentence / the invented user's name
--   "MessageTag".name, "MessageFilter".name/parameters  a colleague's initials ("Afventer godkendelse fra <initials>")
--                                                                     -> "fra redaktøren", or invented initials
--   never-public test messages named after a colleague ("Danmark. <first name> diverse.", "TEST_<initials>")
--                                                                     -> deleted with every dependent row, history
--                                                                        included (a gzip snapshot cannot be rewritten
--                                                                        in SQL)
--   a private person given as the contact in a published notice (the notices listed in g_private_contact)
--                                                                     -> the contact becomes Arktisk Kommando; the
--                                                                        notice's history snapshots are deleted and
--                                                                        the name's words leave its search row
--   a private person in a source ("<company> v/<name> <date>") and a private mailbox in two notices (g_private_mail)
--                                                                     -> the company and date stay, the mailbox is an
--                                                                        invented one; history snapshots deleted
--
-- The staff words (initials and names) are read from the copy's own "User" and "Comment_emailAddresses" rows before
-- they are rewritten, kept in ON COMMIT DROP tables and never written anywhere. make-guides-db.sh's check-names.sh
-- reads the same words from niord_test, inside the container, to prove none is left.
--
-- What is not touched: the message content (published content is public; the texts of messages that were never
-- public are scanned and counted by make-guides-db.sh, not rewritten), the message history snapshots (they are
-- message content: a snapshot holds no user field, the history's user is the "User" row it points at; the
-- exceptions are the deleted test messages and the notices with a private contact above), the script resources and
-- their history (templates; the only addresses in them are functional mailboxes of the organisations), organisation
-- names, sources, charts, areas, categories, publications and dictionaries.
--
-- Step 5 of the rig also lives here: the copy's default languages become English, which the base product needs.

SET client_encoding = 'UTF8';

DO $$
BEGIN
    IF current_database() <> 'niord_guides' THEN
        RAISE EXCEPTION 'scrub.sql runs on niord_guides only, not on %', current_database();
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------------------------
-- The fixed lists
-- ---------------------------------------------------------------------------------------------------------------

CREATE TEMP TABLE g_first (i int PRIMARY KEY, name text NOT NULL) ON COMMIT DROP;
INSERT INTO g_first (i, name) VALUES
    (0, 'Alex'), (1, 'Amira'), (2, 'Ben'), (3, 'Carla'), (4, 'Daniel'), (5, 'Elena'), (6, 'Felix'), (7, 'Grace'),
    (8, 'Hannah'), (9, 'Ivan'), (10, 'Julia'), (11, 'Kofi'), (12, 'Laura'), (13, 'Leo'), (14, 'Maya'), (15, 'Nathan'),
    (16, 'Nora'), (17, 'Oscar'), (18, 'Paula'), (19, 'Rafael'), (20, 'Rosa'), (21, 'Samuel'), (22, 'Sara'),
    (23, 'Tobias'), (24, 'Vera'), (25, 'Victor'), (26, 'Wendy'), (27, 'Yusuf'), (28, 'Zoe'), (29, 'Adrian'),
    (30, 'Bianca'), (31, 'Chloe'), (32, 'David'), (33, 'Emil'), (34, 'Fiona'), (35, 'Hugo'), (36, 'Iris'),
    (37, 'Jonas'), (38, 'Lena'), (39, 'Marco'), (40, 'Olivia');

CREATE TEMP TABLE g_last (i int PRIMARY KEY, name text NOT NULL) ON COMMIT DROP;
INSERT INTO g_last (i, name) VALUES
    (0, 'Archer'), (1, 'Baker'), (2, 'Carter'), (3, 'Dalton'), (4, 'Ellis'), (5, 'Fisher'), (6, 'Garner'),
    (7, 'Harper'), (8, 'Irving'), (9, 'Jordan'), (10, 'Keller'), (11, 'Lambert'), (12, 'Mason'), (13, 'Norton'),
    (14, 'Owens'), (15, 'Parker'), (16, 'Quinn'), (17, 'Reed'), (18, 'Sutton'), (19, 'Turner'), (20, 'Upton'),
    (21, 'Vance'), (22, 'Walker'), (23, 'Weston'), (24, 'Young'), (25, 'Abbott'), (26, 'Brooks'), (27, 'Clarke'),
    (28, 'Doyle'), (29, 'Foster'), (30, 'Grant'), (31, 'Hayes'), (32, 'Hughes'), (33, 'Kent'), (34, 'Lowe'),
    (35, 'Marsh'), (36, 'Nash'), (37, 'Page'), (38, 'Price'), (39, 'Rowe'), (40, 'Shaw'), (41, 'Stone'), (42, 'Wells');

CREATE TEMP TABLE g_sentence (i int PRIMARY KEY, text text NOT NULL) ON COMMIT DROP;
INSERT INTO g_sentence (i, text) VALUES
    (0, 'Checked against the chart; the position is confirmed.'),
    (1, 'Please verify the coordinates before publishing.'),
    (2, 'Text updated after review.'),
    (3, 'Ready for proofreading.'),
    (4, 'Awaiting confirmation from the reporting authority.'),
    (5, 'Translation checked.'),
    (6, 'Period of validity adjusted as requested.'),
    (7, 'Light characteristics verified with the source.'),
    (8, 'Possible duplicate of an earlier warning; consider cancelling it.'),
    (9, 'Map geometry corrected.'),
    (10, 'Approved for publication.'),
    (11, 'Follow up when the work has been completed.');

-- What each step changed, printed at the end
CREATE TEMP TABLE g_report (step numeric, tbl text, what text, n bigint) ON COMMIT DROP;

-- ---------------------------------------------------------------------------------------------------------------
-- The staff words, read before step 1 rewrites "User" and step 5 rewrites "Comment_emailAddresses"
-- ---------------------------------------------------------------------------------------------------------------
-- initials: the 2-5 letter local parts of the addresses comments were mailed to (DMA writes its staff's initials
--           there) and the 2-5 letter logins, in upper case, the way they are written in names;
-- names:    the words of the users' first and last names and of the display names in the comment addresses.
-- Logins and mailboxes that are not a person are left out: the four local logins, Arktisk Kommando's login (ako) and
-- DMA's functional mailboxes vagts and vrag ("vrag" is also the Danish word a saved filter searches for).

CREATE TEMP TABLE g_functional_login (login text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO g_functional_login (login) VALUES ('sysadmin'), ('admin'), ('editor'), ('user'), ('ako'), ('vagts'), ('vrag');

CREATE TEMP TABLE g_staff_word ON COMMIT DROP AS
WITH addr AS (
    SELECT coalesce(substring(a FROM '<([^>]*)>'), a) AS mail, btrim(substring(a FROM '^([^<]*)<')) AS display
    FROM (SELECT "emailAddresses" AS a FROM public."Comment_emailAddresses" WHERE "emailAddresses" IS NOT NULL) x)
SELECT DISTINCT w, kind FROM (
    SELECT upper(split_part(mail, '@', 1)) AS w, 'initials' AS kind FROM addr
    UNION ALL
    SELECT upper(username), 'initials' FROM public."User"
    UNION ALL
    SELECT regexp_split_to_table(concat_ws(' ', "firstName", "lastName"), '[^[:alpha:]]+'), 'name' FROM public."User"
    WHERE lower(username) NOT IN (SELECT login FROM g_functional_login)
    UNION ALL
    SELECT regexp_split_to_table(display, '[^[:alpha:]]+'), 'name' FROM addr) x
WHERE (kind = 'initials' AND w ~ '^[[:alpha:]]{2,5}$' AND lower(w) NOT IN (SELECT login FROM g_functional_login))
   OR (kind = 'name' AND length(w) >= 2 AND lower(w) NOT IN ('test', 'user', 'local'));

-- Each initials get stable invented initials (first letter of an invented first name, two of an invented last name)
CREATE TEMP TABLE g_initials ON COMMIT DROP AS
SELECT s.w AS ini, upper(left(f.name, 1) || left(l.name, 2)) AS repl
FROM g_staff_word s
JOIN g_first f ON f.i = abs(hashtext(s.w)) % 41
JOIN g_last l ON l.i = (abs(hashtext(s.w)) / 41) % 43
WHERE s.kind = 'initials';

-- Replaces whole-word initials (letter boundaries, so '_' and digits separate words; upper case only, as initials are
-- written: "op" and "pm" are ordinary words): "fra <initials>" / "til <initials>" becomes "fra redaktøren" (from the
-- editor), any other occurrence the invented initials.
CREATE FUNCTION pg_temp.g_scrub_initials(t text) RETURNS text LANGUAGE plpgsql STABLE AS $$
DECLARE
    r record;
BEGIN
    IF t IS NULL THEN
        RETURN t;
    END IF;
    FOR r IN SELECT ini, repl FROM g_initials ORDER BY length(ini) DESC, ini LOOP
        t := regexp_replace(t, '(?<![[:alpha:]])([Ff]ra|FRA|[Tt]il|TIL)\s+' || r.ini || '(?![[:alpha:]])', '\1 redaktøren', 'g');
        t := regexp_replace(t, '(?<![[:alpha:]])' || r.ini || '(?![[:alpha:]])', r.repl, 'g');
    END LOOP;
    RETURN t;
END $$;

-- ---------------------------------------------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------------------------------------------
-- Roles are not in the database: they come from the Keycloak token (the realm's groups give client roles per domain),
-- and a login finds its "User" row by the token's preferred_username (UserService.currentUser, case-insensitive),
-- creating it when there is none and copying given_name/family_name/email over it when they differ. The four local
-- logins therefore get rows that mirror the realm (be/keycloak/niord-realm-local.json) exactly: nothing changes at
-- their first login. niord_test has rows for sysadmin, admin and editor; the row for "user" is inserted.

CREATE TEMP TABLE g_user_map ON COMMIT DROP AS
SELECT u.id, f.name AS first, l.name AS last, NULL::text AS login
FROM public."User" u
JOIN g_first f ON f.i = u.id % 41
JOIN g_last l ON l.i = (u.id / 41) % 43
WHERE lower(u.username) NOT IN ('sysadmin', 'admin', 'editor', 'user');

-- Two rows that draw the same name keep it (people share names) but get distinct logins.
UPDATE g_user_map m SET login = x.login
FROM (SELECT id,
             lower(first) || '.' || lower(last)
                 || CASE WHEN row_number() OVER w > 1 THEN (row_number() OVER w)::text ELSE '' END AS login
      FROM g_user_map
      WINDOW w AS (PARTITION BY first, last ORDER BY id)) x
WHERE x.id = m.id;

WITH u AS (
    UPDATE public."User" u
    SET username = m.login, "firstName" = m.first, "lastName" = m.last, email = m.login || '@example.org'
    FROM g_user_map m
    WHERE m.id = u.id
    RETURNING 1)
INSERT INTO g_report SELECT 1, 'User', 'invented name, login and e-mail', count(*) FROM u;

WITH u AS (
    UPDATE public."User"
    SET username = lower(username), "firstName" = 'Local', "lastName" = initcap(lower(username)),
        email = lower(username) || '@example.org'
    WHERE lower(username) IN ('sysadmin', 'admin', 'editor', 'user')
    RETURNING 1)
INSERT INTO g_report SELECT 2, 'User', 'local Keycloak logins set to the realm''s values', count(*) FROM u;

WITH u AS (
    INSERT INTO public."User" (id, created, updated, version, username, "firstName", "lastName", email, language)
    SELECT nextval('public."User_SEQ"'), timestamp '2026-01-05 09:00', timestamp '2026-01-05 09:00', 0,
           'user', 'Local', 'User', 'user@example.org', NULL
    WHERE NOT EXISTS (SELECT 1 FROM public."User" WHERE lower(username) = 'user')
    RETURNING 1)
INSERT INTO g_report SELECT 3, 'User', 'row inserted for the local login "user"', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Comments: the fact of a comment stays (message, author, dates, acknowledgement); the text is a neutral sentence.
-- The author is the comment's "User" row, which now carries an invented name.
-- ---------------------------------------------------------------------------------------------------------------

WITH u AS (
    UPDATE public."Comment" c SET comment = s.text
    FROM g_sentence s
    WHERE s.i = c.id % 12
    RETURNING 1)
INSERT INTO g_report SELECT 4, 'Comment', 'text replaced by a neutral sentence', count(*) FROM u;

-- The addresses a comment was mailed to ("Name <address>" or a bare address): each distinct original value becomes
-- one invented person, so a colleague mailed on several comments stays one person.
CREATE TEMP TABLE g_comment_mail ON COMMIT DROP AS
SELECT addr, (dense_rank() OVER (ORDER BY addr) - 1)::int AS k
FROM (SELECT DISTINCT "emailAddresses" AS addr FROM public."Comment_emailAddresses"
      WHERE "emailAddresses" IS NOT NULL) d;

WITH u AS (
    UPDATE public."Comment_emailAddresses" c
    SET "emailAddresses" = lower(f.name) || '.' || lower(l.name) || '@example.org'
    FROM g_comment_mail m
    JOIN g_first f ON f.i = (m.k * 3 + 7) % 41
    JOIN g_last l ON l.i = (m.k * 5 + 2) % 43
    WHERE c."emailAddresses" = m.addr
    RETURNING 1)
INSERT INTO g_report SELECT 5, 'Comment_emailAddresses', 'invented recipient on example.org', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Saved filters and tags named after a colleague: "Korrektur <name>" (proof-reading by <name>)
-- ---------------------------------------------------------------------------------------------------------------

WITH u AS (
    UPDATE public."MessageFilter" mf
    SET name = 'Korrektur ' || f.name
    FROM g_first f
    WHERE mf.name ~ '^Korrektur\s+[A-ZÆØÅ]'
      AND f.i = abs(hashtext(btrim(substring(mf.name FROM '^Korrektur\s+(.*)$')))) % 41
    RETURNING 1)
INSERT INTO g_report SELECT 6, 'MessageFilter', 'name naming a colleague', count(*) FROM u;

WITH u AS (
    UPDATE public."MessageTag" t
    SET name = 'Korrektur ' || f.name
    FROM g_first f
    WHERE t.name ~ '^Korrektur\s+[A-ZÆØÅ]'
      AND f.i = abs(hashtext(btrim(substring(t.name FROM '^Korrektur\s+(.*)$')))) % 41
    RETURNING 1)
INSERT INTO g_report SELECT 7, 'MessageTag', 'name naming a colleague', count(*) FROM u;

-- A colleague's initials in a tag or a saved filter ("Afventer godkendelse fra <initials>": awaiting approval from)
WITH u AS (
    UPDATE public."MessageTag" SET name = pg_temp.g_scrub_initials(name)
    WHERE name IS DISTINCT FROM pg_temp.g_scrub_initials(name)
    RETURNING 1)
INSERT INTO g_report SELECT 7.1, 'MessageTag', 'name with a colleague''s initials', count(*) FROM u;

WITH u AS (
    UPDATE public."MessageFilter"
    SET name = pg_temp.g_scrub_initials(name), parameters = pg_temp.g_scrub_initials(parameters)
    WHERE name IS DISTINCT FROM pg_temp.g_scrub_initials(name)
       OR parameters IS DISTINCT FROM pg_temp.g_scrub_initials(parameters)
    RETURNING 1)
INSERT INTO g_report SELECT 7.2, 'MessageFilter', 'name or parameters with a colleague''s initials', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Never-public test messages named after a colleague ("Danmark. <first name> diverse.", "TEST_<initials>"): deleted
-- with every row that depends on them. A message qualifies when it was never public (no publish date; DRAFT,
-- VERIFIED or DELETED) and one of its titles or subjects, cut into words at every non-letter, holds a staff word and
-- nothing but staff words, "test", "diverse" and the country name. Real drafts that merely contain a name ("Kong
-- Christian X's Bro") keep other words and stay. Deleting rather than rewriting also takes the history snapshots,
-- which are gzip and cannot be rewritten in SQL; the repository folders of never-public messages are already left out
-- by make-guides-home.sh.
-- ---------------------------------------------------------------------------------------------------------------

CREATE TEMP TABLE g_test_msg ON COMMIT DROP AS
WITH np AS (
    SELECT id FROM public."Message"
    WHERE "publishDateFrom" IS NULL AND status IN ('DRAFT', 'VERIFIED', 'DELETED')),
txt AS (
    SELECT np.id, d.title AS t FROM np JOIN public."MessageDesc" d ON d.entity_id = np.id
    UNION ALL
    SELECT np.id, pd.subject FROM np JOIN public."MessagePart" p ON p.message_id = np.id
    JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id),
tok AS (
    SELECT txt.id, txt.t, lower(x) AS x FROM txt, regexp_split_to_table(coalesce(txt.t, ''), '[^[:alpha:]]+') x
    WHERE x <> '')
SELECT DISTINCT id FROM tok
GROUP BY id, t
HAVING bool_and(x IN ('test', 'diverse', 'danmark', 'denmark') OR x IN (SELECT lower(w) FROM g_staff_word))
   AND bool_or(x IN (SELECT lower(w) FROM g_staff_word));

-- A never-public NM draft whose title and subject are three first names and nothing else, in both languages, with no
-- area and no details (found by the third Danish check, 2026-11-11): people or vessels, nobody can tell, so it goes
-- like the test messages. Listed by id so the names are in no file.
INSERT INTO g_test_msg (id)
SELECT m.id FROM public."Message" m
WHERE m.id = 2471513 AND m."publishDateFrom" IS NULL AND m.status = 'DRAFT'
  AND NOT EXISTS (SELECT 1 FROM g_test_msg t WHERE t.id = m.id);

CREATE TEMP TABLE g_test_part ON COMMIT DROP AS
SELECT id, geometry_id FROM public."MessagePart" WHERE message_id IN (SELECT id FROM g_test_msg);
CREATE TEMP TABLE g_test_prom ON COMMIT DROP AS
SELECT id FROM public."BaseMessagePromulgation" WHERE message_id IN (SELECT id FROM g_test_msg);

DELETE FROM public."NavtexMessagePromulgation_NavtexTransmitter"
WHERE "NavtexMessagePromulgation_id" IN (SELECT id FROM g_test_prom);
DELETE FROM public."NavtexMessagePromulgation" WHERE id IN (SELECT id FROM g_test_prom);
DELETE FROM public."AudioMessagePromulgation" WHERE id IN (SELECT id FROM g_test_prom);
DELETE FROM public."SafetyNetMessagePromulgation" WHERE id IN (SELECT id FROM g_test_prom);
DELETE FROM public."TwitterMessagePromulgation" WHERE id IN (SELECT id FROM g_test_prom);
DELETE FROM public."BaseMessagePromulgation" WHERE id IN (SELECT id FROM g_test_prom);
DELETE FROM public."AttachmentDesc"
WHERE entity_id IN (SELECT id FROM public."Attachment" WHERE message_id IN (SELECT id FROM g_test_msg));
DELETE FROM public."Attachment" WHERE message_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."ReferenceDesc"
WHERE entity_id IN (SELECT id FROM public."Reference" WHERE message_id IN (SELECT id FROM g_test_msg));
DELETE FROM public."Reference" WHERE message_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."Comment_emailAddresses"
WHERE "Comment_id" IN (SELECT id FROM public."Comment" WHERE message_id IN (SELECT id FROM g_test_msg));
DELETE FROM public."Comment" WHERE message_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."SecomOutboxEntry"
WHERE dataset_id IN (SELECT id FROM public."S124Dataset" WHERE message_id IN (SELECT id FROM g_test_msg));
DELETE FROM public."S124DatasetVersion"
WHERE dataset_id IN (SELECT id FROM public."S124Dataset" WHERE message_id IN (SELECT id FROM g_test_msg));
DELETE FROM public."S124Dataset" WHERE message_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."IssueMember" WHERE message_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."IssueOverride" WHERE message_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."MessageTag_Message" WHERE messages_id IN (SELECT id FROM g_test_msg);
DELETE FROM public."Message_Area" WHERE "Message_id" IN (SELECT id FROM g_test_msg);
DELETE FROM public."Message_Category" WHERE "Message_id" IN (SELECT id FROM g_test_msg);
DELETE FROM public."Message_Chart" WHERE "Message_id" IN (SELECT id FROM g_test_msg);
DELETE FROM public."DateInterval" WHERE "messagePart_id" IN (SELECT id FROM g_test_part);
DELETE FROM public."MessagePartDesc" WHERE entity_id IN (SELECT id FROM g_test_part);
DELETE FROM public."MessagePart" WHERE id IN (SELECT id FROM g_test_part);
-- the parts' geometry, unless another part still uses it
DELETE FROM public."Feature"
WHERE "featureCollection_id" IN (SELECT geometry_id FROM g_test_part)
  AND "featureCollection_id" NOT IN (SELECT geometry_id FROM public."MessagePart" WHERE geometry_id IS NOT NULL);
DELETE FROM public."FeatureCollection"
WHERE id IN (SELECT geometry_id FROM g_test_part)
  AND id NOT IN (SELECT geometry_id FROM public."MessagePart" WHERE geometry_id IS NOT NULL);
DELETE FROM public."MessageDesc" WHERE entity_id IN (SELECT id FROM g_test_msg);
DELETE FROM public.message_search_text WHERE message_id IN (SELECT id FROM g_test_msg);

WITH u AS (DELETE FROM public."MessageHistory" WHERE message_id IN (SELECT id FROM g_test_msg) RETURNING 1)
INSERT INTO g_report SELECT 7.3, 'MessageHistory', 'history of a test message named after a colleague: deleted', count(*) FROM u;

WITH u AS (DELETE FROM public."Message" WHERE id IN (SELECT id FROM g_test_msg) RETURNING 1)
INSERT INTO g_report SELECT 7.4, 'Message', 'never-public test message named after a colleague: deleted', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- A private person given as the contact in a published notice. The text is public, but the person (with what looks
-- like a mobile number) may be a private one, so the contact becomes Arktisk Kommando, which issues the series.
-- Listed by the notice's short id so the person is named nowhere in this file. The words that are taken out also
-- leave the notice's PostgreSQL search row (the Lucene index is rebuilt from the database at start), and the
-- notice's history snapshots, which still hold them, are deleted. Its promulgations (NAVTEX, audio, SafetyNET) do
-- not name the person.
-- ---------------------------------------------------------------------------------------------------------------

CREATE TEMP TABLE g_private_contact (short_id text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO g_private_contact (short_id) VALUES
    ('GL-027-22');   -- research buoy lost off Disko Island: "if found contact <a named person> (tlf <number>)"

CREATE TEMP TABLE g_contact_part ON COMMIT DROP AS
SELECT m.id AS message_id, pd.id AS desc_id, pd.lang,
       btrim((regexp_match(pd.details, '(?:HENVENDELSE TIL|IF FOUND CONTACT)\s*(?:<br\s*/?>)?\s*([^<]*)', 'i'))[1]) AS removed
FROM public."Message" m
JOIN public."MessagePart" p ON p.message_id = m.id
JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id
WHERE m."shortId" IN (SELECT short_id FROM g_private_contact)
  AND pd.details ~* '(HENVENDELSE TIL|IF FOUND CONTACT)\s*(<br\s*/?>)?\s*[^<\s]';

WITH u AS (
    UPDATE public."MessagePartDesc" pd
    SET details = regexp_replace(pd.details, '((HENVENDELSE TIL|IF FOUND CONTACT)\s*(<br\s*/?>)?\s*)[^<]*',
                                 '\1' || CASE WHEN c.lang = 'da' THEN 'ARKTISK KOMMANDO' ELSE 'JOINT ARCTIC COMMAND' END, 'gi')
    FROM g_contact_part c
    WHERE pd.id = c.desc_id
    RETURNING 1)
INSERT INTO g_report SELECT 7.5, 'MessagePartDesc', 'private contact in a published notice replaced', count(*) FROM u;

WITH u AS (
    UPDATE public.message_search_text s
    SET search_tsv = ts_delete(s.search_tsv, tsvector_to_array(to_tsvector(
            (CASE WHEN s.lang LIKE 'en%' THEN 'english' WHEN s.lang LIKE 'da%' THEN 'danish' ELSE 'simple' END)::regconfig,
            (SELECT string_agg(c.removed, ' ') FROM g_contact_part c WHERE c.message_id = s.message_id))))
    WHERE s.message_id IN (SELECT message_id FROM g_contact_part) AND s.search_tsv IS NOT NULL
    RETURNING 1)
INSERT INTO g_report SELECT 7.6, 'message_search_text', 'the private contact''s words removed', count(*) FROM u;

WITH u AS (
    DELETE FROM public."MessageHistory" WHERE message_id IN (SELECT message_id FROM g_contact_part) RETURNING 1)
INSERT INTO g_report SELECT 7.7, 'MessageHistory', 'history of a notice with a private contact: deleted', count(*) FROM u;

-- A private person in a message's source, after the company ("<company> v/<first name> <last name> <date>", v/ being
-- c/o; six notices of 2024, found by the second Danish check of 2026-11-11), and a private person's mailbox at a
-- municipality in the details of two notices (listed by short id in g_private_mail): the source keeps the company and
-- the date, the mailbox becomes an invented one on example.org. The person is named nowhere in this file; the
-- messages' history snapshots, which still hold the person, are deleted.
CREATE TEMP TABLE g_private_mail (short_id text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO g_private_mail (short_id) VALUES
    ('NM-449-21'), ('NM-468-21');   -- a harbour's works: "contact <a person's mailbox at the municipality>"

CREATE TEMP TABLE g_private_person ON COMMIT DROP AS
SELECT DISTINCT entity_id AS message_id FROM public."MessageDesc" WHERE source ~ '\s+v/\s*\S+\s+\S+'
UNION
SELECT m.id FROM public."Message" m WHERE m."shortId" IN (SELECT short_id FROM g_private_mail);

-- The words that go, for the messages' PostgreSQL search rows: the person's name, the mailbox's local part.
CREATE TEMP TABLE g_private_words ON COMMIT DROP AS
SELECT entity_id AS message_id, (regexp_match(source, '\s+v/\s*(\S+\s+\S+)\s+\d'))[1] AS removed
FROM public."MessageDesc" WHERE source ~ '\s+v/\s*\S+\s+\S+\s+\d'
UNION ALL
SELECT p.message_id, string_agg(DISTINCT replace(split_part(a[1], '@', 1), '.', ' '), ' ')
FROM public."MessagePartDesc" pd
JOIN public."MessagePart" p ON p.id = pd.entity_id
JOIN public."Message" m ON m.id = p.message_id,
     regexp_matches(pd.details, '([A-Za-z0-9._%+-]+@brk\.dk)', 'g') a
WHERE m."shortId" IN (SELECT short_id FROM g_private_mail)
GROUP BY p.message_id;

WITH u AS (
    UPDATE public.message_search_text s
    SET search_tsv = ts_delete(s.search_tsv, tsvector_to_array(to_tsvector(
            (CASE WHEN s.lang LIKE 'en%' THEN 'english' WHEN s.lang LIKE 'da%' THEN 'danish' ELSE 'simple' END)::regconfig,
            (SELECT string_agg(w.removed, ' ') FROM g_private_words w WHERE w.message_id = s.message_id))))
    WHERE s.message_id IN (SELECT message_id FROM g_private_words) AND s.search_tsv IS NOT NULL
    RETURNING 1)
INSERT INTO g_report SELECT 7.75, 'message_search_text', 'a private person''s words removed', count(*) FROM u;

WITH u AS (
    UPDATE public."MessageDesc"
    SET source = regexp_replace(source, '\s+v/\s*\S+\s+\S+(?=\s+\d)', '', 'g')
    WHERE source ~ '\s+v/\s*\S+\s+\S+\s+\d'
    RETURNING 1)
INSERT INTO g_report SELECT 7.8, 'MessageDesc', 'a private person in a source taken out', count(*) FROM u;

WITH u AS (
    UPDATE public."MessagePartDesc" pd
    SET details = regexp_replace(pd.details, '[A-Za-z0-9._%+-]+@brk\.dk', 'harbour.office@example.org', 'g')
    FROM public."MessagePart" p, public."Message" m
    WHERE pd.entity_id = p.id AND p.message_id = m.id AND m."shortId" IN (SELECT short_id FROM g_private_mail)
      AND pd.details ~ '[A-Za-z0-9._%+-]+@brk\.dk'
    RETURNING 1)
INSERT INTO g_report SELECT 7.9, 'MessagePartDesc', 'a private person''s mailbox replaced', count(*) FROM u;

WITH u AS (
    DELETE FROM public."MessageHistory" WHERE message_id IN (SELECT message_id FROM g_private_person) RETURNING 1)
INSERT INTO g_report SELECT 7.95, 'MessageHistory', 'history of a message naming a private person: deleted', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Mailing-list recipients: niord_test has no contacts and no list members, so invented ones are added: 40 contacts
-- and two invented users per list, spread so that every list has 13 to 18 recipients.
-- ---------------------------------------------------------------------------------------------------------------

DELETE FROM public."MailingList_Contact";
DELETE FROM public."MailingList_User";
DELETE FROM public."Contact";

CREATE TEMP TABLE g_contact ON COMMIT DROP AS
SELECT k, f.name || ' ' || l.name AS name, lower(f.name) || '.' || lower(l.name) || '@example.org' AS email,
       CASE WHEN k % 4 = 0 THEN 'en' ELSE 'da' END AS language
FROM generate_series(0, 39) k
JOIN g_first f ON f.i = (k * 3 + 1) % 41
JOIN g_last l ON l.i = (k * 7 + 4) % 43;

WITH u AS (
    INSERT INTO public."Contact" (id, created, updated, version, email, language, name)
    SELECT nextval('public."Contact_SEQ"'), timestamp '2026-01-05 09:00', timestamp '2026-01-05 09:00', 0,
           c.email, c.language, c.name
    FROM (SELECT * FROM g_contact ORDER BY k) c
    RETURNING 1)
INSERT INTO g_report SELECT 8, 'Contact', 'invented contact on example.org', count(*) FROM u;

CREATE TEMP TABLE g_list ON COMMIT DROP AS
SELECT id, (row_number() OVER (ORDER BY id) - 1)::int AS r FROM public."MailingList";

WITH u AS (
    INSERT INTO public."MailingList_Contact" ("MailingList_id", contacts_id)
    SELECT ml.id, ct.id
    FROM g_list ml
    JOIN g_contact gc ON (gc.k + ml.r) % 3 = 0 OR gc.k % 9 = ml.r
    JOIN public."Contact" ct ON ct.email = gc.email
    ORDER BY ml.id, gc.k
    RETURNING 1)
INSERT INTO g_report SELECT 9, 'MailingList_Contact', 'invented list membership', count(*) FROM u;

CREATE TEMP TABLE g_staff ON COMMIT DROP AS
SELECT id, (row_number() OVER (ORDER BY id) - 1)::int AS rn, count(*) OVER () AS n
FROM public."User" WHERE lower(username) NOT IN ('sysadmin', 'admin', 'editor', 'user');

WITH u AS (
    INSERT INTO public."MailingList_User" ("MailingList_id", users_id)
    SELECT DISTINCT ml.id, s.id
    FROM g_list ml
    JOIN g_staff s ON s.rn = (ml.r * 5) % s.n OR s.rn = (ml.r * 5 + 2) % s.n
    RETURNING 1)
INSERT INTO g_report SELECT 10, 'MailingList_User', 'invented user on a list', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- The mail queue: deleted (bodies quote messages and recipients; empty in niord_test when this was written)
-- ---------------------------------------------------------------------------------------------------------------

WITH u AS (DELETE FROM public."ScheduledMailRecipient" RETURNING 1)
INSERT INTO g_report SELECT 11, 'ScheduledMailRecipient', 'deleted', count(*) FROM u;
WITH u AS (DELETE FROM public."ScheduledMail" RETURNING 1)
INSERT INTO g_report SELECT 12, 'ScheduledMail', 'deleted', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Credentials, tokens and third-party accounts
-- ---------------------------------------------------------------------------------------------------------------

WITH u AS (
    UPDATE public."Setting" SET setting_value = '""'
    WHERE (type = 'Password'
           OR setting_key IN ('wmsLogin', 'wmsPassword', 'legacyNwDbUser', 'legacyNwDbUrl', 'legacyNwDbPassword',
                              'pdfEncryptionPassword', 'authServerRealmKey'))
      AND setting_value IS DISTINCT FROM '""'
    RETURNING 1)
INSERT INTO g_report SELECT 13, 'Setting', 'password, login or key emptied', count(*) FROM u;

WITH u AS (
    UPDATE public."TwitterSettings"
    SET "accessToken" = NULL, "accessTokenSecret" = NULL, "apiKey" = NULL, "apiSecret" = NULL
    RETURNING 1)
INSERT INTO g_report SELECT 14, 'TwitterSettings', 'keys and tokens emptied', count(*) FROM u;

WITH u AS (
    UPDATE public."NiordIntegration" SET url = 'https://niord.example.org', active = false
    RETURNING 1)
INSERT INTO g_report SELECT 15, 'NiordIntegration', 'address replaced, deactivated', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Curator free text in the publications model (empty or machine-written in niord_test when this was written)
-- ---------------------------------------------------------------------------------------------------------------

WITH u AS (
    UPDATE public."IssueOverride" o SET reason = s.text FROM g_sentence s
    WHERE o.reason IS NOT NULL AND s.i = o.id % 12 RETURNING 1)
INSERT INTO g_report SELECT 16, 'IssueOverride', 'reason replaced', count(*) FROM u;

WITH u AS (
    UPDATE public."IssueMember" m SET "reasonNote" = s.text FROM g_sentence s
    WHERE m."reasonNote" IS NOT NULL AND s.i = m.id % 12 RETURNING 1)
INSERT INTO g_report SELECT 17, 'IssueMember', 'reason note replaced', count(*) FROM u;

WITH u AS (
    UPDATE public."PublicationIssue" p SET "retiredReason" = s.text FROM g_sentence s
    WHERE p."retiredReason" IS NOT NULL AND s.i = p.id % 12 RETURNING 1)
INSERT INTO g_report SELECT 18, 'PublicationIssue', 'retired reason replaced', count(*) FROM u;

WITH u AS (
    UPDATE public."IssueAuditEntry" a
    SET "actorLabel" = (SELECT usr."firstName" || ' ' || usr."lastName" FROM public."User" usr WHERE usr.id = a.user_id)
    WHERE a."actorLabel" IS NOT NULL AND a."actorKind" = 'USER'
    RETURNING 1)
INSERT INTO g_report SELECT 19, 'IssueAuditEntry', 'actor label of a user replaced', count(*) FROM u;

-- ---------------------------------------------------------------------------------------------------------------
-- Step 5: the base product's default language (the generic application refuses a Danish default)
-- ---------------------------------------------------------------------------------------------------------------

WITH u AS (
    UPDATE public."Setting" SET setting_value = '"en"'
    WHERE setting_key IN ('defaultLanguage', 'infoDefaultLanguage') AND setting_value IS DISTINCT FROM '"en"'
    RETURNING 1)
INSERT INTO g_report SELECT 20, 'Setting', 'defaultLanguage / infoDefaultLanguage set to "en"', count(*) FROM u;

\pset footer off
\echo
\echo '== Rows changed by scrub.sql'
SELECT tbl AS "table", what, n AS rows FROM g_report ORDER BY step;
