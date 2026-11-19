--liquibase formatted sql
--changeset niord:013-user-username-case-insensitive splitStatements:false
-- A user name is unique without regard to letter case (org.niord.core.user.User). Every look-up of a user by name
-- compares lower(username) and expects one row (UserService.findByUsername), but the baseline's unique constraint
-- user_username was on the column itself, so two rows whose names differ only by case could be stored (by a write
-- straight into the database), and then every request of that user failed. The constraint gives way to a unique
-- index on lower(username), which also serves those look-ups; no query compares the column itself, so no index on
-- it is kept.
--
-- A database that already holds such a pair is refused before anything changes, with a message that names the
-- names: the changeset runs in one transaction, so it is applied whole or not at all, and the application does not
-- start until a person has resolved each pair by hand and started it again.
--
-- Schema only, no data (D2). Once applied this changeset is never edited, its comments included: every database
-- records its checksum, which ChangelogChecksumPinTest pins.
DO $check$
DECLARE
    pairs text;
BEGIN
    SELECT string_agg(names, '; ' ORDER BY names) INTO pairs FROM (
        SELECT string_agg(username, ', ' ORDER BY username) AS names FROM public."User"
        GROUP BY lower(username) HAVING count(*) > 1) same_name;
    IF pairs IS NOT NULL THEN
        RAISE EXCEPTION 'Niord cannot make user names unique without regard to letter case: these users of the table "User" have one name in different case: %. Keep one row of each group and give the others another username by hand (their messages, comments and history keep pointing to them), then start Niord again.', pairs;
    END IF;
END
$check$;
ALTER TABLE public."User" DROP CONSTRAINT user_username;
CREATE UNIQUE INDEX user_username_lower ON public."User" (lower(username));
