-- english.sql: the copy's English-language texts made English. Applied by make-guides-db.sh after prepare.sql, in one
-- transaction; running it again changes nothing (every statement is guarded by the value it replaces).
--
-- The owner's ruling (2026-11-11): an English-language text that is in Danish is an error, and no picture of the user
-- guides shows Danish text. Not Danish in this sense: proper nouns (place, harbour, vessel and company names), the base
-- map's own labels, and codes (a language code, a series id, a tag used as an identifier). An English text that only
-- differs from the base product's wording is Denmark's own choice and stays. The copy is the scrubbed copy of the
-- Danish test data; the same Danish values are in Denmark's own data, listed for a human to correct there, and nothing
-- here touches any database but niord_guides.
--
-- Where a rule exists, this file holds the rule (the dictionaries' swapped languages, the Danish abbreviation EfS, the
-- Naval Staff's ice-service annex, the messages' internal publications, 'signal mast N ved', Danish month names in
-- dates). Where only a translation exists, it holds the rows, keyed by the row's natural key and guarded by the value it
-- replaces. The value lists between the -- <NAME> markers are generated (danish-audit's gen-values.mjs, from the audit's
-- findings and the base product's English texts); this file reads no file when it runs.
--
-- After a second check (2026-11-11) it also: replaces recurring Danish phrases in every English message text
-- (guides.english_phrase), blanks the Danish audio texts of messages no longer live, deletes the Danish rows of the
-- editors that show every stored language (sources, mailing lists and triggers, template parameters, dictionaries;
-- their values kept in guides.danish_saved for the rig's guard), sets every contact and user to English, deletes the
-- Danish mails of the queue and the Danish template variants (*_da.ftl), and takes a standard the owner keeps out of the
-- guides out of a type's name and the dictionaries. The message history snapshots are gzip: english-history.mjs, run
-- after this file, rewrites them.
--
-- After a third check (2026-11-11) it also: gives every position of a message (Feature.properties) an English name
-- where it had only another language's and translates the Danish descriptions among them (guides.english_feature_rule),
-- adds phrase rules for single Danish words in titles and subjects ('Vrag.', 'Kabelarbejde') and makes the almanac's
-- two corrections rules over every version, translates the Danish words in NAVTEX texts (guides.english_navtex), and
-- takes the Danish half out of the warnings' mail templates and makes the voice-broadcast mail's template English.
--
-- After the fourth check (2026-11-11), a reading of every live message (every status but EXPIRED, CANCELLED and
-- DELETED), it puts every Danish text that reading found right: 'EfS' becomes 'NtM' in the English message texts too
-- (not where a text spells the Danish title out or explains the abbreviation), 'Skydeområde Signalmast' becomes 'Firing
-- Area signal mast', the positions' 'Signalmast.' and 'trafiksignal' are English, the NM Annex B/1 2026 gets an English
-- description, the phrase rules gain the words the reading found ('Kaj 210', 'Tyra Gasfelt', 'Tranum Skydeterræn',
-- 'forfyr', ...), the NAVTEX rule for 'UP TIL' takes any white space, and three saved filters search in English. The
-- rule (guides.english_messages) is stricter for a message that is no longer live, and the file ends by checking that it
-- turns down no live message beyond guides.not_english.
--
-- After the fifth check (2026-11-11), a reading of the live messages' history, the phrase and feature rules gain the
-- Danish words older English versions of twelve live messages hold ('1) - 4) og 5) - 8)', 'Tlf.', 'gul', 'rød
-- spirtønde med topbetegnelse', 'N-enden af Marmorkaj', ...); english-history.mjs applies them to the snapshots and
-- stops when a snapshot of a live message still holds a Danish English text.
--
-- It also makes guides.english_messages(domain, status), the rule the rig picks its example messages by
-- (support/backend.ts): a message is English throughout when it has an English description, no field is blank in
-- English while the Danish one is filled (the administration app shows the Danish text then), no English field holds a
-- Danish word, apart from the live messages read as names only (guides.names_only), no name of its positions does,
-- and it is not listed in guides.not_english; its audio and NAVTEX texts are judged apart (promulgation_danish).
--
-- The backend keeps the dictionaries, the settings, the promulgation types and the script resources in memory: start it
-- after this file runs (data/README.md: on a copy a backend runs on, start the rig again).

\set ON_ERROR_STOP on
SET client_encoding = 'UTF8';
SET client_min_messages = warning;

CREATE TEMP TABLE english_counts (n serial, grp text, rows int) ON COMMIT DROP;

-- ============================================================================================================
-- Dictionaries (Dictionary -> DictionaryEntry -> DictionaryEntryDesc). The administration app shows a web or message
-- row over its own English bundle; PDFs, mails and templates print every row as it is.
-- ============================================================================================================

-- The base product's English text for each key the copy holds: the backend's default
-- (be/niord/src/main/resources/defaults/base/texts/en/<dictionary>.properties), else, for web and message, the
-- administration app's bundle (fe/languages/en/i18n/admin/en.json).
CREATE TEMP TABLE english_base_text (dict text, key text, value text, PRIMARY KEY (dict, key)) ON COMMIT DROP;
INSERT INTO english_base_text VALUES
  -- <BASE_TEXTS>
  ('mail', 'mail.active_nav_warnings', 'Active Navigational Warnings'),
  ('mail', 'mail.comment.subject', 'Comment from'),
  ('mail', 'mail.date_format.long', 'd MMMM yyyy'),
  ('mail', 'mail.date_format.short', 'dd-MM-yyyy'),
  ('mail', 'mail.dear.user', 'Dear {0}'),
  ('mail', 'mail.field.date', 'Date'),
  ('mail', 'mail.field.warning', 'Warning'),
  ('mail', 'mail.greetings.best.regards', 'Kind regards,<br>{0}'),
  ('mail', 'mail.navtex.cancel', 'CANCEL NAV WARN'),
  ('mail', 'mail.navwarn.cancel', 'Cancel navigational warning'),
  ('mail', 'mail.safetynet.cancel', 'CANCEL NAV WARN'),
  ('message', 'msg.area.general', 'General Messages'),
  ('message', 'msg.attachment.title', 'Attachment for message {0}'),
  ('message', 'msg.cancellation.title', 'Cancellation of {0}'),
  ('message', 'msg.field.areas', 'Areas'),
  ('message', 'msg.field.attachments', 'Attachments'),
  ('message', 'msg.field.categories', 'Categories'),
  ('message', 'msg.field.charts', 'Charts'),
  ('message', 'msg.field.created_date', 'Created date'),
  ('message', 'msg.field.description', 'Description'),
  ('message', 'msg.field.details', 'Details'),
  ('message', 'msg.field.event_dates', 'Event dates'),
  ('message', 'msg.field.follow_up_date', 'Follow-up date'),
  ('message', 'msg.field.id', 'ID'),
  ('message', 'msg.field.layout', 'Layout'),
  ('message', 'msg.field.note', 'Note'),
  ('message', 'msg.field.orig_info', 'Org. Information'),
  ('message', 'msg.field.part_types', 'Part Types'),
  ('message', 'msg.field.positions', 'Positions'),
  ('message', 'msg.field.prohibition', 'Prohibition'),
  ('message', 'msg.field.promulgation', 'Promulgation'),
  ('message', 'msg.field.publication', 'Publication'),
  ('message', 'msg.field.publish_date', 'Publish date'),
  ('message', 'msg.field.published', 'Published'),
  ('message', 'msg.field.publish_from_date', 'Publish from'),
  ('message', 'msg.field.references', 'References'),
  ('message', 'msg.field.signals', 'Signals'),
  ('message', 'msg.field.source', 'Source'),
  ('message', 'msg.field.status', 'Status'),
  ('message', 'msg.field.subject', 'Subject'),
  ('message', 'msg.field.thumbnail', 'Thumbnail'),
  ('message', 'msg.field.time', 'Time'),
  ('message', 'msg.field.title', 'Title'),
  ('message', 'msg.field.type', 'Type'),
  ('message', 'msg.field.updated_date', 'Last updated'),
  ('message', 'msg.firing_exercises.subject', 'Firing exercises. Warning.'),
  ('message', 'msg.kind.CANCELLATION', 'Cancellations'),
  ('message', 'msg.kind.WARNING', 'Warnings'),
  ('message', 'msg.reference.cancellation', '(cancelled)'),
  ('message', 'msg.reference.reference', '(reference)'),
  ('message', 'msg.reference.repetition', '(repetition)'),
  ('message', 'msg.reference.repetition_new_time', '(repetition with new time)'),
  ('message', 'msg.reference.update', '(updated repetition)'),
  ('message', 'msg.status.CANCELLED', 'Cancelled'),
  ('message', 'msg.status.DELETED', 'Deleted'),
  ('message', 'msg.status.DRAFT', 'Draft'),
  ('message', 'msg.status.EXPIRED', 'Expired'),
  ('message', 'msg.status.PUBLISHED', 'Published'),
  ('message', 'msg.status.VERIFIED', 'Verified'),
  ('message', 'msg.time.date_format', 'd MMMM yyyy'),
  ('message', 'msg.time.date_time_format', 'd MMMM yyyy, ''hours'' HH:mm'),
  ('message', 'msg.time.from_date', 'From {{fromDate}}'),
  ('message', 'msg.time.time_format', 'HH:mm'),
  ('message', 'msg.time.to_date', 'Until {{toDate}}'),
  ('message', 'msg.time.until_further_notice', 'Until further notice'),
  ('message', 'msg.type.COASTAL_WARNING', 'Coastal'),
  ('message', 'msg.type.LOCAL_WARNING', 'Local'),
  ('message', 'msg.type.MISCELLANEOUS_NOTICE', 'Misc.'),
  ('message', 'msg.type.NAVAREA_WARNING', 'NAVAREA'),
  ('message', 'msg.type.PERMANENT_NOTICE', 'Perm.'),
  ('message', 'msg.type.PRELIMINARY_NOTICE', 'Prelim.'),
  ('message', 'msg.type.SUBAREA_WARNING', 'Subarea'),
  ('message', 'msg.type.TEMPORARY_NOTICE', 'Temp.'),
  ('pdf', 'pdf.accumulated.added_by_hand', 'Added by hand'),
  ('pdf', 'pdf.accumulated.cutoff', 'Cut-off'),
  ('pdf', 'pdf.accumulated.no_messages', 'No messages are in this issue.'),
  ('pdf', 'pdf.accumulated.period', 'Published'),
  ('pdf', 'pdf.accumulated.title', 'Accumulated Notices to Mariners'),
  ('pdf', 'pdf.details.title', 'Message'),
  ('pdf', 'pdf.fa', 'Firing Practice Areas'),
  ('pdf', 'pdf.fa.subtitle', 'Annex to Notices to Mariners'),
  ('pdf', 'pdf.fa.title', 'Firing Practice Areas at Sea'),
  ('pdf', 'pdf.list.title', 'Message List'),
  ('pdf', 'pdf.misc_nm', 'Announcements, etc.'),
  ('pdf', 'pdf.nm', 'Notices to Mariners'),
  ('pdf', 'pdf.nm_abbrev', 'NtM'),
  ('pdf', 'pdf.nm_almanac.desc', 'General information for the waters these notices cover'),
  ('pdf', 'pdf.nm_almanac.subtitle', 'Annex to Notices to Mariners'),
  ('pdf', 'pdf.nm_almanac.title', 'NtM Almanac'),
  ('pdf', 'pdf.nm_annex.nm_ref', 'Annex to Notices to Mariners {0}'),
  ('pdf', 'pdf.nm_tp', 'Preliminary (P) and Temporary (T) Notices to Mariners'),
  ('pdf', 'pdf.nw', 'Navigational Warnings'),
  ('pdf', 'pdf.page', 'page'),
  ('pdf', 'pdf.toc', 'Table of Contents'),
  ('pdf', 'pdf.translation', 'Translation'),
  ('pdf', 'pdf.volume', 'Volume {0}'),
  ('pdf', 'pdf.week', 'Week'),
  ('template', 'cancellation.message', 'Cancel message {0}'),
  ('template', 'cancellation.this_message', 'Cancel this message {0}'),
  ('template', 'navwarn.date.day_prefix', 'the'),
  ('template', 'navwarn.date.day_suffix', 'th,st,nd,rd,th,th,th,th,th,th,th,th,th,th,th,th,th,th,th,th,th,st,nd,rd,th,th,th,th,th,th,th,st'),
  ('template', 'navwarn.date.from', 'from'),
  ('template', 'navwarn.date.html_day_suffix', '<sup>{{suffix}}</sup>'),
  ('template', 'navwarn.date.time_interval_prefix', ''),
  ('template', 'navwarn.date.time_prefix', 'at'),
  ('template', 'navwarn.date.to', 'to'),
  ('template', 'navwarn.date.until', 'until'),
  ('template', 'navwarn.date.until_further_notice', 'until further notice'),
  ('template', 'position.between_pos', 'between pos.'),
  ('template', 'position.between_positions', 'between positions'),
  ('template', 'position.deg', 'degrees'),
  ('template', 'position.east', 'East'),
  ('template', 'position.in_area', 'in the area'),
  ('template', 'position.in_pos', 'in pos.'),
  ('template', 'position.in_position', 'in position'),
  ('template', 'position.in_positions', 'in positions'),
  ('template', 'position.min', 'min.'),
  ('template', 'position.north', 'North'),
  ('template', 'position.south', 'South'),
  ('template', 'position.west', 'West'),
  ('template', 'term.and', 'and'),
  ('web', 'editor.auto_generate', 'Auto generate'),
  ('web', 'editor.back_to_list', 'Back to list'),
  ('web', 'editor.clear', 'Clear'),
  ('web', 'editor.hide_subject', 'Hide subject'),
  ('web', 'editor.new_nm', 'New NM'),
  ('web', 'editor.new_nm_template', 'New NM from Template'),
  ('web', 'editor.new_nw', 'New NW'),
  ('web', 'editor.new_nw_template', 'New NW from Template'),
  ('web', 'editor.recently_edited', 'Recently edited drafts'),
  ('web', 'editor.reload_msg', 'Reload Message'),
  ('web', 'editor.save_msg', 'Save Message'),
  ('web', 'editor.save_verified', 'When saving a verified message, it becomes a draft message. Proceed?'),
  ('web', 'editor.section.message_part', 'Part {{part}}'),
  ('web', 'editor.section.misc', 'Miscellaneous'),
  ('web', 'editor.section.preamble', 'Preamble'),
  ('web', 'editor.status.cancel_msg', 'Cancel Message'),
  ('web', 'editor.status.copy_msg', 'Copy Message'),
  ('web', 'editor.status.delete_msg', 'Delete Message'),
  ('web', 'editor.status.publish', 'Publish'),
  ('web', 'editor.status.publish_msg', 'Publish Message'),
  ('web', 'editor.status.publish_short', 'Publish'),
  ('web', 'editor.status.verify_msg', 'Verify Message'),
  ('web', 'editor.status.verify_short', 'Verify'),
  ('web', 'footer.cookies', 'Cookies'),
  ('web', 'footer.copyright', '&copy; 2017 EfficienSea 2'),
  ('web', 'footer.disclaimer', 'Disclaimer'),
  ('web', 'footer.link', 'http://efficiensea2.org'),
  ('web', 'front_page.brand', 'Nautical Information Directory'),
  ('web', 'front_page.version_error', 'Please refresh web-browser'),
  ('web', 'front_page.welcome', 'Welcome to the'),
  ('web', 'lang.en', 'English'),
  ('web', 'menu.action', 'Action'),
  ('web', 'menu.action.copy', 'Copy...'),
  ('web', 'menu.action.edit', 'Edit...'),
  ('web', 'menu.action.export', 'Export...'),
  ('web', 'menu.action.export_selection', 'Export selection...'),
  ('web', 'menu.action.fields.collapse', 'Collapse fields'),
  ('web', 'menu.action.fields.expand', 'Expand fields'),
  ('web', 'menu.action.fields.more', 'More fields:'),
  ('web', 'menu.action.import', 'Import...'),
  ('web', 'menu.action.mail', 'E-mail selection...'),
  ('web', 'menu.action.message.compare', 'Compare...'),
  ('web', 'menu.action.print', 'Print...'),
  ('web', 'menu.action.print.selection', 'Print selection...'),
  ('web', 'menu.action.report', 'Report...'),
  ('web', 'menu.action.select.all', 'Select all'),
  ('web', 'menu.action.select.none', 'Clear selection'),
  ('web', 'menu.action.sort_area', 'Sort area...'),
  ('web', 'menu.action.tag.add', 'Add to tag...'),
  ('web', 'menu.action.tag.add_named', 'Add to "{{tag}}"'),
  ('web', 'menu.action.tag.add_selection', 'Add selection to tag...'),
  ('web', 'menu.action.tag.remove_named', 'Remove from "{{tag}}"'),
  ('web', 'menu.action.tag.remove_selection', 'Remove selection from tag...'),
  ('web', 'menu.action.update_status', 'Update selection status...'),
  ('web', 'menu.admin', 'Admin'),
  ('web', 'menu.atons', 'AtoNs'),
  ('web', 'menu.brand', 'Niord'),
  ('web', 'menu.editor', 'Editor'),
  ('web', 'menu.filter', 'Filter'),
  ('web', 'menu.filter.area', 'Area'),
  ('web', 'menu.filter.aton', 'AtoN'),
  ('web', 'menu.filter.category', 'Category'),
  ('web', 'menu.filter.chart', 'Chart'),
  ('web', 'menu.filter.clear', 'Clear Filter'),
  ('web', 'menu.filter.comments', 'Comments'),
  ('web', 'menu.filter.date', 'Date'),
  ('web', 'menu.filter.domain', 'Domain'),
  ('web', 'menu.filter.kind', 'Kind'),
  ('web', 'menu.filter.messageSeries', 'Message Series'),
  ('web', 'menu.filter.promulgationType', 'Promulgation'),
  ('web', 'menu.filter.publication', 'Publication'),
  ('web', 'menu.filter.reference', 'Reference'),
  ('web', 'menu.filter.save', 'Save Filter...'),
  ('web', 'menu.filter.status', 'Status'),
  ('web', 'menu.filter.tag', 'Tag'),
  ('web', 'menu.filter.text', 'Text'),
  ('web', 'menu.filter.type', 'Type'),
  ('web', 'menu.filter.user', 'User'),
  ('web', 'menu.login', 'Login'),
  ('web', 'menu.logout', 'Logout'),
  ('web', 'menu.messages', 'Messages'),
  ('web', 'menu.schedule', 'Schedule'),
  ('web', 'menu.sysadmin', 'Sysadmin'),
  ('web', 'msg.copy.include_reference', 'Include reference to copied message'),
  ('web', 'msg.filter.all', 'All'),
  ('web', 'msg.filter.reset', 'Reset'),
  ('web', 'msg.hide_attachments', 'Hide attachments'),
  ('web', 'msg.hide_internal_publications', 'Hide internal'),
  ('web', 'msg.hide_positions', 'Hide positions'),
  ('web', 'msg.hide_time', 'Hide time'),
  ('web', 'msg.list.load_more', 'Load {{fromMessageNo}} - {{toMessageNo}} of {{totalMessageNo}} messages'),
  ('web', 'msg.no_pos_messages', 'Additional Messages'),
  ('web', 'msg.show_attachments', 'Show attachments'),
  ('web', 'msg.show_internal_publications', 'Show internal'),
  ('web', 'msg.show_positions', 'Show positions'),
  ('web', 'msg.show_time', 'Show time'),
  ('web', 'promulgation.confirm.generate', 'Generate a proposal for the ''{{type}}'' promulgation?'),
  ('web', 'promulgation.confirm.generate_all', 'Generate a proposal for all active promulgations?'),
  ('web', 'promulgation.undefined', 'Promulgation undefined'),
  ('web', 'publication_type.LINK', 'Link'),
  ('web', 'publication_type.MESSAGE_REPORT', 'Report'),
  ('web', 'publication_type.NONE', 'None'),
  ('web', 'publication_type.REPOSITORY', 'Uploaded file'),
  ('web', 'report.execute', 'Execute'),
  ('web', 'reports.report', 'Report'),
  ('web', 'reports.schedule', 'Schedule'),
  ('web', 'reports.title', 'Execute Mailing List Report'),
  ('web', 'source.date_format', 'D MMMM YYYY'),
  ('web', 'template.aton.type', 'Type'),
  ('web', 'template.categories', 'Categories'),
  ('web', 'template.caution.min_dist', 'Minimum dist. for mariners'),
  ('web', 'template.caution.title', 'Caution'),
  ('web', 'template.execute', 'Execute Templates'),
  ('web', 'template.general', 'General'),
  ('web', 'template.markings.bearing', 'Bearing'),
  ('web', 'template.markings.dist', 'Dist. (m)'),
  ('web', 'template.markings.light_char', 'Light Char.'),
  ('web', 'template.markings.title', 'Markings'),
  ('web', 'template.markings.type', 'Markings used'),
  ('web', 'template.markings.type.bouys', 'Buoys'),
  ('web', 'template.markings.type.other_markings', 'Other markings'),
  ('web', 'template.markings.type.unmarked', 'Unmarked'),
  ('web', 'template.parent_category', 'Parent Category'),
  ('web', 'template.sample_message', 'Example '),
  ('web', 'template.select_category', 'Select Category'),
  ('web', 'template.selected_categories', 'Selected Categories'),
  ('web', 'template.select_template', 'Select Template'),
  ('web', 'template.vessel.contact', 'Contact vessel on VHF channel 16 + channel'),
  ('web', 'template.vessel.guard_vessels', 'Guard Vessels'),
  ('web', 'template.vessel.guard_vessels_in_area', 'Guard vessels in area'),
  ('web', 'template.vessel.title', 'Work Vessel'),
  ('web', 'term.add', 'Add'),
  ('web', 'term.add_selected', 'Add Selected'),
  ('web', 'term.and', 'and'),
  ('web', 'term.call_sign', 'Call Sign'),
  ('web', 'term.cancel', 'Cancel'),
  ('web', 'term.close', 'Close'),
  ('web', 'term.color', 'Color'),
  ('web', 'term.contact', 'Contact'),
  ('web', 'term.format', 'Format'),
  ('web', 'term.generate', 'Generate'),
  ('web', 'term.generate_all', 'Generate All'),
  ('web', 'term.help', 'help'),
  ('web', 'term.name', 'Name'),
  ('web', 'term.new_window', 'New Window'),
  ('web', 'term.priority', 'Priority'),
  ('web', 'term.remove_all', 'Remove All'),
  ('web', 'term.remove_selected', 'Remove Selected'),
  ('web', 'term.result', 'Result'),
  ('web', 'view_mode.comments', 'Comments'),
  ('web', 'view_mode.details', 'Details'),
  ('web', 'view_mode.edit', 'Edit'),
  ('web', 'view_mode.grid', 'Grid'),
  ('web', 'view_mode.history', 'History'),
  ('web', 'view_mode.map', 'Map'),
  ('web', 'view_mode.selection', 'Selection'),
  ('web', 'view_mode.status', 'Status'),
  ('web', 'view_mode.table', 'List')
  -- </BASE_TEXTS>
;

-- Rule: a key whose English value is not the base product's English text while its Danish value is has its two
-- languages swapped: the English row takes the base text, the Danish row the old English value (editor.status.
-- verify_short: 'Verificér' / 'Verify', the editor's status button).
WITH s AS (
    SELECT en.id AS en_id, da.id AS da_id, en.value AS old_en, b.value AS base
      FROM public."DictionaryEntry" e
      JOIN public."Dictionary" d ON d.id = e.dictionary_id
      JOIN english_base_text b ON b.dict = d.name AND b.key = e.dict_key
      JOIN public."DictionaryEntryDesc" en ON en.entity_id = e.id AND en.lang = 'en'
      JOIN public."DictionaryEntryDesc" da ON da.entity_id = e.id AND da.lang = 'da'
     WHERE en.value IS DISTINCT FROM b.value AND da.value = b.value),
u_da AS (UPDATE public."DictionaryEntryDesc" x SET value = s.old_en FROM s WHERE x.id = s.da_id RETURNING 1),
u_en AS (UPDATE public."DictionaryEntryDesc" x SET value = s.base FROM s WHERE x.id = s.en_id RETURNING 1)
INSERT INTO english_counts (grp, rows)
SELECT 'dictionaries: languages swapped (English and Danish rows)', (SELECT count(*) FROM u_da) + (SELECT count(*) FROM u_en);

-- Rule: 'EfS' is the Danish abbreviation of the Notices to Mariners (Efterretninger for Søfarende); in English it is
-- NtM. The almanac's 'EfS A' takes the base product's title (pdf.nm_almanac.title), any other 'EfS' becomes 'NtM'.
-- The owner kept 'EfS A' as Denmark's English value on 2026-11-03; the ruling of 2026-11-11 puts no
-- Danish text in the guides, so the copy takes the base title, and Denmark's own value is the owner's decision.
WITH u AS (
    UPDATE public."DictionaryEntryDesc"
       SET value = regexp_replace(regexp_replace(value, '\mEfS A\M', (
                       -- <NM_ALMANAC>
                       'NtM Almanac'
                       -- </NM_ALMANAC>
                   ), 'g'), '\mEfS\M', 'NtM', 'g')
     WHERE lang = 'en' AND value ~ '\mEfS\M'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'dictionaries: EfS in English values', count(*) FROM u;

-- A Greenland key whose English value is the Danish one (read only by Greenland's mailing-list template, as the heading
-- of the mail's Danish half; no picture shows that mail). The wording follows its sibling mail.navwarn.cancel.gl.
WITH u AS (
    UPDATE public."DictionaryEntryDesc" x
       SET value = 'Greenlandic navigational warning'
      FROM public."DictionaryEntry" e
      JOIN public."Dictionary" d ON d.id = e.dictionary_id
     WHERE x.entity_id = e.id AND d.name = 'mail' AND e.dict_key = 'mail.navwarn.update.gl' AND x.lang = 'en'
       AND x.value = 'Grønlandsk navigationsadvarsel'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'dictionaries: Danish English values', count(*) FROM u;

-- ============================================================================================================
-- Publications: series, publications and issues (their English names and titles; the stored files keep their names,
-- or their downloads break).
-- ============================================================================================================

-- Rule, for every English name, title and their patterns: the Naval Staff's ice-service annex is named in English
-- (the document itself stays Danish only, which the name says); 'EfS' becomes 'NtM' as in the dictionaries, the weekly
-- 'EfS 51-52 2016' after the weekly series' own English pattern ('NtM Week ${week} - ${year}').
CREATE FUNCTION pg_temp.english_publication(t text) RETURNS text LANGUAGE sql IMMUTABLE AS $f$
    SELECT regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(t,
             'Meddelelse fra Marinestaben om istjeneste samt om ismeldinger m\.m\.',
             'Naval Staff notice on the ice service and ice reports', 'g'),
             ' for vinteren ', ' for the winter of ', 'g'),
             '\mEfS A\M', (SELECT value FROM english_base_text WHERE dict = 'pdf' AND key = 'pdf.nm_almanac.title'), 'g'),
             '\mEfS (\d+-\d+) (\d{4})\M', 'NtM Week \1 - \2', 'g'),
             '\mEfS\M', 'NtM', 'g')
$f$;

WITH u AS (
    UPDATE public."PublicationSeriesDesc"
       SET name = pg_temp.english_publication(name),
           "nameSuggestionPattern" = pg_temp.english_publication("nameSuggestionPattern")
     WHERE lang = 'en'
       AND (name IS DISTINCT FROM pg_temp.english_publication(name)
            OR "nameSuggestionPattern" IS DISTINCT FROM pg_temp.english_publication("nameSuggestionPattern"))
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: English names', count(*) FROM u;

-- The ice-service annex's file-name pattern for new English issues (the existing issues' files keep their names).
WITH u AS (
    UPDATE public."PublicationSeriesDesc" x
       SET "fileNamePattern" = 'Ice-Annex-${year}.pdf'
      FROM public."PublicationSeries" s
     WHERE x.entity_id = s.id AND s."seriesId" = 'nm-annex-ice-service' AND x.lang = 'en'
       AND x."fileNamePattern" = 'Isbilag-${year}.pdf'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: English file-name pattern', count(*) FROM u;

WITH u AS (
    UPDATE public."PublicationDesc"
       SET title = pg_temp.english_publication(title),
           "titleFormat" = pg_temp.english_publication("titleFormat")
     WHERE lang = 'en'
       AND (title IS DISTINCT FROM pg_temp.english_publication(title)
            OR "titleFormat" IS DISTINCT FROM pg_temp.english_publication("titleFormat"))
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publications: English titles', count(*) FROM u;

WITH u AS (
    UPDATE public."PublicationIssueDesc"
       SET name = pg_temp.english_publication(name)
     WHERE lang = 'en' AND name IS DISTINCT FROM pg_temp.english_publication(name)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication issues: English names', count(*) FROM u;

-- ============================================================================================================
-- Charts: Chart.name has one language, which the English interface shows as it is (the Charts page, the editor's
-- Charts field, the series' chart picker). The ordinary Danish words are translated ('Grønlands Vestkyst', 'Havneplaner',
-- 'N-lige del', 'og', 'syd for'); names stay, the seas' Danish names among them (Østersøen, Lillebælt), as in the 76
-- titles made of names only ('Østersøen. Femern Bælt'), which stay as they are; and seven Faroese titles get back the
-- 'ð' the source data stores as '?'. Keyed by the chart number.
-- ============================================================================================================
WITH v (chart_number, old_name, new_name) AS (VALUES
  -- <CHARTS>
  ('1000', 'Grønlands Vestkyst', 'West Coast of Greenland'),
  ('1001', 'Grønlands Sydkyst. Kap Poul Løvenørn - Kap Farvel - Godthåb', 'South Coast of Greenland. Kap Poul Løvenørn - Kap Farvel - Godthåb'),
  ('101', 'Kattegat, Nordlige del', 'Kattegat, Northern Part'),
  ('102', 'Kattegat, Sydlige del', 'Kattegat, Southern Part'),
  ('1100', 'Grønlands Vestkyst. Kap Farvel - Arsuk', 'West Coast of Greenland. Kap Farvel - Arsuk'),
  ('1103', 'Kap Farvel området. Prins Chr. Sund - Frederiksdal', 'Kap Farvel Area. Prins Chr. Sund - Frederiksdal'),
  ('1113', 'Grønlands Vestkyst. Igdlukasik - Sydprøven', 'West Coast of Greenland. Igdlukasik - Sydprøven'),
  ('1114', 'Grønlands Vestkyst. Sydprøven - Julianehåb', 'West Coast of Greenland. Sydprøven - Julianehåb'),
  ('1115', 'Grønlands Vestkyst. Julianehåb – Narssarssuaq og Igaliko Fjord', 'West Coast of Greenland. Julianehåb – Narssarssuaq and Igaliko Fjord'),
  ('1116', 'Grønlands Vestkyst. Julianehåb - Mågeløb', 'West Coast of Greenland. Julianehåb - Mågeløb'),
  ('1117', 'Grønlands Vestkyst. Mågeløb - Kobberminebugt', 'West Coast of Greenland. Mågeløb - Kobberminebugt'),
  ('1130', 'Grønlands Vestkyst. Frederiksdal', 'West Coast of Greenland. Frederiksdal'),
  ('1131', 'Grønlands Vestkyst. Nanortalik', 'West Coast of Greenland. Nanortalik'),
  ('1132', 'Grønlands Vestkyst. Julianehåb', 'West Coast of Greenland. Julianehåb'),
  ('1133', 'Grønlands Vestkyst. Nanortalik - Qeqertasussuk (Nanortalik - Thomsen Ø)', 'West Coast of Greenland. Nanortalik - Qeqertasussuk (Nanortalik - Thomsen Ø)'),
  ('1136', 'Kitaat Sineriaa - (Grønlands Vestkyst) - The West Coast of Greenland - (Grønlands Vestkyst) Ikersuaq – Narsaq - (Bredefjord - Narsaq)', 'Kitaat Sineriaa - The West Coast of Greenland - Ikersuaq – Narsaq - (Bredefjord - Narsaq)'),
  ('1137', 'Kitaata Sineriaa - (Grønlands Vestkyst) - Simiutaq - Inussuttuut', 'Kitaata Sineriaa - (West Coast of Greenland) - Simiutaq - Inussuttuut'),
  ('1138', 'Kitaata Sineriaa - (Grønlands Vestkyst) - Qaqqaligaatsiaq Fjord - Killavaat Nuugaatsiaat (Qaqqaligaatsiaq Fjord - Kap Desolation)', 'Kitaata Sineriaa - (West Coast of Greenland) - Qaqqaligaatsiaq Fjord - Killavaat Nuugaatsiaat (Qaqqaligaatsiaq Fjord - Kap Desolation)'),
  ('1139', 'Grønlands Vestkyst. Kap Desolation - Storø', 'West Coast of Greenland. Kap Desolation - Storø'),
  ('114', 'Kattegat. Farvandet nord for Fyn', 'Kattegat. Waters North of Funen'),
  ('1145', 'Grønlands Vestkyst. Kobberminebugt', 'West Coast of Greenland. Kobberminebugt'),
  ('1146', 'Grønlands Vestkyst. Arsuk Fjord', 'West Coast of Greenland. Arsuk Fjord'),
  ('1150', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('1151', 'Umiarsualiviit assingi (Havneplaner / Harbour plans)', 'Umiarsualiviit assingi (Harbour Plans)'),
  ('1152', 'Grønlands Vestkyst, Havneplaner', 'West Coast of Greenland, Harbour Plans'),
  ('1160', 'Grønlands Vestkyst. Qaarsup Ikerasaa - Paggivik', 'West Coast of Greenland. Qaarsup Ikerasaa - Paggivik'),
  ('1200', 'Grønlands Vestkyst. Arsuk - Grædefjord', 'West Coast of Greenland. Arsuk - Grædefjord'),
  ('124', 'Kattegat, Syd for Anholt', 'Kattegat, South of Anholt'),
  ('1250', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('1251', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('129', 'Kattegat, Sydøstlige del', 'Kattegat, Southeastern Part'),
  ('131', 'Sundet, Nordlige del', 'Sundet, Northern Part'),
  ('1311', 'Nuup Kangerlua - Kangilleq (Godthåbsfjord, E-lige del) Nuup Kangerlua, Eastern Part', 'Nuup Kangerlua - Kangilleq (Godthåbsfjord, Eastern Part)'),
  ('1312', 'Nuup Kangerlua - Killeq (Godthåbsfjord, W-lige del)', 'Nuup Kangerlua - Killeq (Godthåbsfjord, Western Part)'),
  ('132', 'Sundet, S-lige del', 'Sundet, Southern Part'),
  ('133', 'Sundet, midterste del', 'Sundet, Central Part'),
  ('1330', 'Kangerluarsorutsimut Pulammagiaa (Indsejlingen til Kangerluarsoruseq) Approaches to Kangerluarsoruseq', 'Kangerluarsorutsimut Pulammagiaa (Approaches to Kangerluarsoruseq)'),
  ('1351', 'Umiarsualiviit assingi (Havneplaner) Harbour Plans', 'Umiarsualiviit assingi (Harbour Plans)'),
  ('1352', 'Umiarsualiviit assingi (Havneplaner) Harbour Plans', 'Umiarsualiviit assingi (Harbour Plans)'),
  ('1400', 'Grønlands Vestkyst. Hamborgerland – Rifkol', 'West Coast of Greenland. Hamborgerland – Rifkol'),
  ('141', 'Storebælt, N-lige del', 'Storebælt, Northern Part'),
  ('1410', 'Grønlands Vestkyst, Hamborgerland - Uummannaarsussuaq', 'West Coast of Greenland, Hamborgerland - Uummannaarsussuaq'),
  ('1411', 'Grønlands Vestkyst, Søndre Strømfjord, Serminnguaq - Kangerlussuup Umiarsualivia', 'West Coast of Greenland, Søndre Strømfjord, Serminnguaq - Kangerlussuup Umiarsualivia'),
  ('1412', 'Grønlands Vestkyst, Inussuttusup Tunua - Sisimiut (Anders Olsen Sund - Holsteinborg)', 'West Coast of Greenland, Inussuttusup Tunua - Sisimiut (Anders Olsen Sund - Holsteinborg)'),
  ('1416', 'Grønlands Vestkyst. Færinge Nordhavn – Tugtulik', 'West Coast of Greenland. Færinge Nordhavn – Tugtulik'),
  ('142', 'Storebælt, S-lige del', 'Storebælt, Southern Part'),
  ('1430', 'Grønlands Vestkyst, Sisimiut (Holsteinsborg)', 'West Coast of Greenland, Sisimiut (Holsteinsborg)'),
  ('1450', 'Grønlands Vestkyst, Havneplaner', 'West Coast of Greenland, Harbour Plans'),
  ('1451', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('1461', 'Grønlands vestkyst, Indsejlingen til Søndre Strømfjord', 'West Coast of Greenland, Approaches to Søndre Strømfjord'),
  ('1500', 'Grønlands Vestkyst. Rifkol – Hareø', 'West Coast of Greenland. Rifkol – Hareø'),
  ('151', 'Lillebælt, N-lige del', 'Lillebælt, Northern Part'),
  ('1510', 'Grønlands Vestkyst. Tugtulik – Egedesminde', 'West Coast of Greenland. Tugtulik – Egedesminde'),
  ('1511', 'Grønlands Vestkyst. Egedesminde – Godhavn', 'West Coast of Greenland. Egedesminde – Godhavn'),
  ('1512', 'Grønlands Vestkyst. Egedesminde – Christianshåb', 'West Coast of Greenland. Egedesminde – Christianshåb'),
  ('1513', 'Grønlands Vestkyst. Christianshåb – Jakobshavn', 'West Coast of Greenland. Christianshåb – Jakobshavn'),
  ('152', 'Lillebælt, S-lige del og Farvandet syd for Fyn', 'Lillebælt, Southern Part and Waters South of Funen'),
  ('1530', 'Grønlands Vestkyst. Egedesminde', 'West Coast of Greenland. Egedesminde'),
  ('155', 'Lillebælt. Als Sund og Sønderborg Bugt', 'Lillebælt. Als Sund and Sønderborg Bugt'),
  ('1550', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('1551', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('1552', 'Grønlands Vestkyst. Havneplaner', 'West Coast of Greenland. Harbour Plans'),
  ('158', 'Lillebælt. Snævringen og Kolding Fjord', 'Lillebælt. Snævringen and Kolding Fjord'),
  ('160', 'Smålandsfarvandet, Vestlige del', 'Smålandsfarvandet, Western Part'),
  ('1600', 'Grønlands Vestkyst. Hareø – Prøven', 'West Coast of Greenland. Hareø – Prøven'),
  ('161', 'Smålandsfarvandet, Nordøstlige del, Bøgestrømmen', 'Smålandsfarvandet, Northeastern Part, Bøgestrømmen'),
  ('1610', 'Grønlands Vestkyst. Umanak – Mârmorilik', 'West Coast of Greenland. Umanak – Mârmorilik'),
  ('162', 'Smålandsfarvandet, SE-lige del', 'Smålandsfarvandet, Southeastern Part'),
  ('1650', 'Umiarsualiviit assingi (Havneplaner) Harbour Plans', 'Umiarsualiviit assingi (Harbour Plans)'),
  ('1700', 'Grønlands Vestkyst. Prøven – Holms Ø', 'West Coast of Greenland. Prøven – Holms Ø'),
  ('171', 'Farvandet syd for Fyn. Svendborg Sund', 'Waters South of Funen. Svendborg Sund'),
  ('1710', 'Grønlands Vestkyst. Prøven – Upernavik', 'West Coast of Greenland. Prøven – Upernavik'),
  ('172', 'Farvandet syd for Fyn. Rudkøbing Løb', 'Waters South of Funen. Rudkøbing Løb'),
  ('188', 'Østersøen omkring Bornholm', 'Østersøen around Bornholm'),
  ('190', 'Østersøen. Faxe Bugt og Præstø Fjord', 'Østersøen. Faxe Bugt and Præstø Fjord'),
  ('195', 'Østersøen, W-lige del', 'Østersøen, Western Part'),
  ('197', 'Østersøen. Gedser Rev og Kadetrenden', 'Østersøen. Gedser Rev and Kadetrenden'),
  ('198', 'Østersøen. Faxe Bugt og Hjelm Bugt', 'Østersøen. Faxe Bugt and Hjelm Bugt'),
  ('2000', 'Grønlands Østkyst', 'East Coast of Greenland'),
  ('2100', 'Grønlands Østkyst. Kap Farvel – Kap Herluf Trolle', 'East Coast of Greenland. Kap Farvel – Kap Herluf Trolle'),
  ('2130', 'Prins Christian Sund, E-lige indsejling', 'Prins Christian Sund, Eastern Approach'),
  ('2250', 'Grønlands Østkyst. Havneplaner', 'East Coast of Greenland. Harbour Plans'),
  ('2351', 'Grønlands Østkyst. Havneplaner', 'East Coast of Greenland. Harbour Plans'),
  ('2400', 'Grønlands Østkyst. Kap Gustav Holm – Kap Vedel', 'East Coast of Greenland. Kap Gustav Holm – Kap Vedel'),
  ('2500', 'Grønlands Østkyst. Kap Garde – Scoresbysund', 'East Coast of Greenland. Kap Garde – Scoresbysund'),
  ('2600', 'Grønlands Østkyst. Kap Brewster – Kap Simpson', 'East Coast of Greenland. Kap Brewster – Kap Simpson'),
  ('2650', 'Grønlands Østkyst. Havneplaner', 'East Coast of Greenland. Harbour Plans'),
  ('2701', 'Grønlands Østkyst. Kap Simpson – Hold with Hope', 'East Coast of Greenland. Kap Simpson – Hold with Hope'),
  ('2702', 'Grønlands Østkyst. Hold With Hope – Shannon', 'East Coast of Greenland. Hold With Hope – Shannon'),
  ('2730', 'Grønlands Østkyst. Vega Sund, E-lige del', 'East Coast of Greenland. Vega Sund, Eastern Part'),
  ('2750', 'Grønlands Østkyst. Havneplaner', 'East Coast of Greenland. Harbour Plans'),
  ('2801', 'Grønlands Østkyst. Shannon – Skærfjorden', 'East Coast of Greenland. Shannon – Skærfjorden'),
  ('3000', 'Grønlands Nordkyst. Thule - Danmarkshavn', 'North Coast of Greenland. Thule - Danmarkshavn'),
  ('3100', 'Grønlands Vestkyst. Holms Ø – Saunders Ø', 'West Coast of Greenland. Holms Ø – Saunders Ø'),
  ('3130', 'Grønlands Vestkyst. North Star Bugt', 'West Coast of Greenland. North Star Bugt'),
  ('3200', 'Grønlands Vestkyst. Kap York – Kap Chalon', 'West Coast of Greenland. Kap York – Kap Chalon'),
  ('3210', 'Grønlands Vestkyst. Kap Radcliff – Kap Cleveland', 'West Coast of Greenland. Kap Radcliff – Kap Cleveland'),
  ('80', 'Sjóøki? vi? Føroyar (Farvandene omkring Færøerne) Waters around the Faroe Islands', 'Sjóøkið við Føroyar (Waters around the Faroe Islands)'),
  ('82', 'Føroyar, Nor?ari partur (Færøerne, N-lige del) Faroe Islands Northern Part', 'Føroyar, Norðari partur (Faroe Islands, Northern Part)'),
  ('83', 'Føroyar, Sunnari partur (Færøerne, S-lige del) Faroe Islands Southern Part', 'Føroyar, Sunnari partur (Faroe Islands, Southern Part)'),
  ('84', 'Føroyar. Havnir og akkerspláss, útnyr?ingsøki? (Havne og ankerpladser, NW-lige del) Harbours and Anchorages, Northwestern Part', 'Føroyar. Havnir og akkerspláss, útnyrðingsøkið (Harbours and Anchorages, Northwestern Part)'),
  ('85', 'Føroyar. Havnir og akkerspláss, landnyr?ingsøki? (Havne og ankerpladser, NE-lige del) Harbours and Anchorages, Northeastern Part', 'Føroyar. Havnir og akkerspláss, landnyrðingsøkið (Harbours and Anchorages, Northeastern Part)'),
  ('86', 'Føroyar. Havnir og akkerspláss, su?urøki? (Havne og ankerpladser, S-lige del) Harbours and Anchorages, Southern Part', 'Føroyar. Havnir og akkerspláss, suðurøkið (Harbours and Anchorages, Southern Part)'),
  ('93', 'Nordsøen. Esbjerg - Hanstholm inkl. offshore olie- og gasfelter', 'Nordsøen. Esbjerg - Hanstholm incl. Offshore Oil and Gas Fields'),
  ('C', 'Danmark med omgivende farvande', 'Denmark with Surrounding Waters'),
  ('D', 'Danmark, Søgrænser', 'Denmark, Maritime Boundaries'),
  ('F', 'Føroyar vi? Havøkjum (Færøerne med maritime zoner) Faroe Islands with Maritime Zones', 'Føroyar við Havøkjum (Faroe Islands with Maritime Zones)'),
  ('G', 'Kalaallit Nunaat - Imartallu avatangiisui (Grønland med omgivende farvande) Greenland with Surrounding Waters', 'Kalaallit Nunaat - Imartallu avatangiisui (Greenland with Surrounding Waters)'),
  ('X101', 'Kattegat, Nordlige del', 'Kattegat, Northern Part'),
  ('X102', 'Kattegat, S-lige del', 'Kattegat, Southern Part'),
  ('X124', 'Kattegat, Syd for Anholt', 'Kattegat, South of Anholt'),
  ('X129', 'Kattegat, SE-lige del', 'Kattegat, Southeastern Part'),
  ('X131', 'Sundet, N-lige del / Sundet, Northern Part', 'Sundet, Northern Part')
  -- </CHARTS>
), u AS (
    UPDATE public."Chart" c SET name = v.new_name FROM v
     WHERE c."chartNumber" = v.chart_number AND c.name = v.old_name
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'charts: names', count(*) FROM u;

-- ============================================================================================================
-- Message tags and saved filters: one-language names staff typed, shown as they are (the tags dialog and filter for
-- every user of a domain; a user's own private tags and filters). The names the scrub gave an invented first name keep
-- it. The public tags' names (nm-annex-istjeneste-2017, -2018) are identifiers that publications may name, and stay.
-- ============================================================================================================
WITH v (tag_id, old_name, new_name) AS (VALUES
  -- <TAGS>
  ('18722b73-49ff-4dd7-b768-68767fdb3edb', 'Klar til korrektur', 'Ready for proofreading'),
  ('cc8eb623-7e18-4f1d-bc28-03e219f1d9e7', 'Under udarbejdelse', 'In preparation'),
  ('b8d210bc-a977-4623-8788-ca80cd016c90', 'Klar til korrektur', 'Ready for proofreading'),
  ('479de911-e72b-4155-b4d7-04fb4848dac9', 'Verificeret afventer udsendelse', 'Verified, awaiting release'),
  ('e12952c8-a26c-4ae4-b3d3-b8c16e72b57e', 'Ret', 'To be corrected'),
  ('970403a5-a586-42ae-a496-e8afa90b8415', 'Klar til Korrektur', 'Ready for proofreading'),
  ('f035da82-5a1e-4c85-81b0-5ef9d8ae5d3f', 'Afventer godkendelse fra redaktøren', 'Awaiting the editor''s approval'),
  ('60612e08-5a55-4eef-bf2f-4003b87f4617', 'Korrektur Daniel', 'Proofreading Daniel'),
  ('7c049373-bb00-46fb-8067-f3839e080ee7', 'Klar til korrektur', 'Ready for proofreading'),
  ('b80569eb-8dc3-42b8-bec5-cbf8ccbb911f', 'Klar til korrektur', 'Ready for proofreading'),
  ('68cd106e-c0f2-4b67-876e-4dc45cb5ca2d', 'Klar til korrektur', 'Ready for proofreading'),
  ('24c36cde-3447-498b-a456-d2a44d62b790', 'Korrektur Vera', 'Proofreading Vera'),
  ('fb3793ed-d9a1-4513-a75a-356b283bb9a3', 'Korrektur Zoe', 'Proofreading Zoe'),
  ('0f4a35d3-ab73-40f2-a84b-a23192e6e2c7', 'Korrektur Vera', 'Proofreading Vera'),
  ('3a989ad6-b6b0-49bc-9263-2d20459a4cd7', 'X-Kort 2020', 'X-charts 2020'),
  ('29bc6d30-5126-4bc0-be34-713473d72596', 'Korrektur Rafael', 'Proofreading Rafael'),
  ('939ae95b-1c15-41d7-8ad3-73a311ca92a1', 'søg', 'search'),
  ('47718102-1638-4678-96db-cd4452f2975e', 'søg', 'search'),
  ('45564328-5e0c-4e58-9ad8-ada2b697f4ff', 'søg', 'search'),
  ('b5183b75-8937-4350-9cb9-d067d0520586', 'nyt privat test', 'new private test'),
  ('5f4c2d50-7998-40b9-ae75-91c9b318d390', 'Korrektur Leo', 'Proofreading Leo'),
  ('cd1231dc-c77d-4640-821a-d56b32960acd', 'Korrektur Rosa', 'Proofreading Rosa')
  -- </TAGS>
), u AS (
    UPDATE public."MessageTag" t SET name = v.new_name FROM v
     WHERE t."tagId" = v.tag_id AND t.name = v.old_name
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'message tags: names', count(*) FROM u;

-- Keyed by the owner and the name (unique together); a name the owner already has is not given twice.
WITH v (user_id, old_name, new_name) AS (VALUES
  -- <FILTERS>
  (123218, 'Aktive P&T', 'Active P&T'),
  (131567, 'Kladde', 'Draft'),
  (132218, 'Specialafmærkning', 'Special marks'),
  (132221, 'Specialafmærkning', 'Special marks'),
  (125295, 'PogT oversigt', 'P&T overview'),
  (136519, 'anker', 'anchor'),
  (136519, 'vrag', 'wreck'),
  (136519, 'Klar', 'Ready'),
  (137883, 'Indhold 2017', 'Contents 2017'),
  (131567, 'klar til korrektur', 'ready for proofreading'),
  (131567, 'Korrektur Adrian', 'Proofreading Adrian'),
  (113044, 'Alle', 'All'),
  (232795, 'klar til korrektur', 'ready for proofreading'),
  (123218, 'Klar til korrektur', 'Ready for proofreading'),
  (131567, 'Søg EfS nr.', 'Search NtM no.'),
  (123227, 'Klar til korrektur', 'Ready for proofreading'),
  (123227, 'Find EfS meddelelse', 'Find NtM notice'),
  (389210, 'Klar til korrektur', 'Ready for proofreading'),
  (389210, 'Find i Efs', 'Find in NtM'),
  (132221, 'KLar til korrektur', 'Ready for proofreading'),
  (125295, 'Korrektur Daniel', 'Proofreading Daniel'),
  (129755, 'Find EfS', 'Find NtM'),
  (132221, 'Find efs 2', 'Find NtM 2'),
  (734971, 'klar til korrektur', 'ready for proofreading'),
  (734971, 'Korrektur Jonas', 'Proofreading Jonas'),
  (734971, 'Søg', 'Search'),
  (759426, 'Klar til korrektur', 'Ready for proofreading'),
  (759426, 'X-Kort', 'X-charts'),
  (131567, 'X-kort 2020', 'X-charts 2020'),
  (125295, 'Klar til korrektur', 'Ready for proofreading'),
  (1433722, 'Klar til korrektur', 'Ready for proofreading'),
  (1433722, 'Korrektur Rafael', 'Proofreading Rafael'),
  (1760614, 'klar til korrektur', 'ready for proofreading'),
  (1760614, 'Korrektur Adrian', 'Proofreading Adrian'),
  (1758065, 'klar til korrektur', 'ready for proofreading'),
  (1759095, 'Klar til korrektur', 'Ready for proofreading'),
  (1760614, 'søg', 'search'),
  (1785897, 'Klar til korrektur*', 'Ready for proofreading*'),
  (1759095, 'Søg', 'Search'),
  (1478624, 'Klar til korr.', 'Ready for proofr.'),
  (1478624, 'Søgning', 'Search'),
  (2115855, 'Klar til korrektur', 'Ready for proofreading'),
  (2115855, 'Søgning', 'Search'),
  (1757868, 'Korrektur', 'Proofreading'),
  (2524767, 'klar', 'ready'),
  (1881025, 'Klar til korrektur', 'Ready for proofreading'),
  (2587001, 'Klar til korrektur', 'Ready for proofreading')
  -- </FILTERS>
), u AS (
    UPDATE public."MessageFilter" f SET name = v.new_name FROM v
     WHERE f.user_id = v.user_id AND f.name = v.old_name
       AND NOT EXISTS (SELECT 1 FROM public."MessageFilter" g WHERE g.user_id = v.user_id AND g.name = v.new_name)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'saved filters: names', count(*) FROM u;

-- Three saved filters search with a Danish word, which applying the filter puts into the messages' search box (the
-- fourth check, 2026-11-11): the English word, which the English search index matches. Keyed by the filter's id.
WITH v (filter_id, old_query, new_query) AS (VALUES
  (136521, 'query=vrag', 'query=wreck'),
  (136520, 'query=anker', 'query=anchor'),
  (132222, 'query=specialafm%C3%A6rkning', 'query=special')
), u AS (
    UPDATE public."MessageFilter" f
       SET parameters = v.new_query || substring(f.parameters FROM length(v.old_query) + 1)
      FROM v
     WHERE f.id = v.filter_id
       AND (f.parameters = v.old_query OR f.parameters LIKE v.old_query || '&%')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'saved filters: Danish query words', count(*) FROM u;

-- ============================================================================================================
-- Messages: the English description rows (MessageDesc), part rows (MessagePartDesc) and reference rows (ReferenceDesc).
-- The administration app fills a blank English field with the Danish one, so a blank English field shows Danish too.
-- The rows first (the live messages the audit read), then the rules, which reach every message.
-- ============================================================================================================

-- A field set: the whole English value, guarded by the whole old value.
CREATE TEMP TABLE english_msg_set (tbl text, col text, entity_id int, old_value text, new_value text) ON COMMIT DROP;
INSERT INTO english_msg_set VALUES
  -- <MSG_SET>
  ('MessageDesc', 'title', 2459134, 'Denmark. The Baltic Sea. Object adrift.', 'Denmark. The Baltic Sea. Rødby Havn. Object adrift.'),
  ('MessageDesc', 'title', 2459146, 'Denmark. The Baltic Sea.', 'Denmark. The Baltic Sea. Rødby Havn. Buoy adrift.'),
  ('MessageDesc', 'title', 2605759, 'Denmark. Kattegat. Light unlit.', 'Denmark. Kattegat. Skagen. Light unlit.'),
  ('MessageDesc', 'title', 2605770, 'Denmark. Kattegat. Light unlit.', 'Denmark. Kattegat. Skagen. Light unlit.'),
  ('MessageDesc', 'title', 1329305, 'Denmark. The Baltic Sea. Bornholm SE, S og SW. Rock deployment along gas pipelines.', 'Denmark. The Baltic Sea. Bornholm SE, S and SW. Rock deployment along gas pipelines.'),
  ('MessageDesc', 'vicinity', 1329305, 'Bornholm SE, S og SW', 'Bornholm SE, S and SW'),
  ('MessageDesc', 'title', 2541168, 'Denmark. Kattegat. Odense Fjord. Odense Kanal. Kaj nr. 43. Reduced depth.', 'Denmark. Kattegat. Odense Fjord. Odense Kanal. Quay No. 43. Reduced depth.'),
  ('MessageDesc', 'vicinity', 2541168, 'Odense Fjord. Odense Kanal. Kaj nr. 43', 'Odense Fjord. Odense Kanal. Quay No. 43'),
  ('MessageDesc', 'title', 2554895, 'Denmark. The Waters South of Zealand. Vordingborg Havn. Indsejling til Vordingborg Havn. Dredging.', 'Denmark. The Waters South of Zealand. Vordingborg Havn. Entrance to Vordingborg Havn. Dredging.'),
  ('MessageDesc', 'vicinity', 2554895, 'Vordingborg Havn. Indsejling til Vordingborg Havn', 'Vordingborg Havn. Entrance to Vordingborg Havn'),
  ('MessageDesc', 'source', 1318341, 'By & Havn 25 Januar 2021', 'By & Havn 25 January 2021'),
  ('MessageDesc', 'source', 1424589, 'Grenaa Havn A/S 7. juni 2022', 'Grenaa Havn A/S 7 June 2022'),
  ('MessageDesc', 'source', 2223695, 'DEMA 21 Marts 2025', 'DEMA 21 March 2025'),
  ('MessageDesc', 'source', 2553046, 'JD-Contractors A/S 5 Juni 2026', 'JD-Contractors A/S 5 June 2026'),
  ('MessageDesc', 'source', 2563480, 'Rambøll 24 Juni 2026', 'Rambøll 24 June 2026'),
  ('MessageDesc', 'source', 2418027, 'DMI januar 2025', 'DMI January 2025'),
  ('MessageDesc', 'title', 2403497, 'Denmark. The Baltic Sea. Seabed surveys with AUV.', 'Denmark. The Baltic Sea. Rødby. Seabed surveys with AUV.'),
  ('MessageDesc', 'title', 2572302, 'Denmark. Kattegat. Buoyed measuring equipment deployed.', 'Denmark. Kattegat. Odense Havn. Buoyed measuring equipment deployed.'),
  ('MessageDesc', 'title', 2586466, 'Denmark. Skagerrak. Hanstholm NE. Cable Works. Virtuel AtoN oprettet.', 'Denmark. Skagerrak. Hanstholm NE. Cable Works. Virtual AtoN established.'),
  ('MessageDesc', 'title', 2530173, 'Denmark. Kattegat. Port of Aarhus. Bassin 3. Construction works.', 'Denmark. Kattegat. Port of Aarhus. Basin 3. Construction works.'),
  ('MessageDesc', 'vicinity', 2530173, 'Port of Aarhus. Bassin 3', 'Port of Aarhus. Basin 3'),
  -- The fourth check (2026-11-11): A/20 2026's source names the command by its Danish abbreviation (A/21's: JACMD).
  ('MessageDesc', 'source', 2417937, 'Tusass and AKO January 2025', 'Tusass and JACMD January 2025')
  -- </MSG_SET>
;
-- A long text (a part's details) corrected in place: the smallest stretch of whole words that differs, found once.
CREATE TEMP TABLE english_msg_replace (tbl text, col text, entity_id int, find text, repl text) ON COMMIT DROP;
INSERT INTO english_msg_replace VALUES
  -- <MSG_REPLACE>
  ('MessagePartDesc', 'details', 861133, 'og', 'or by'),
  ('MessagePartDesc', 'details', 1379978, 'with af draft', 'with a draft'),
  ('MessagePartDesc', 'details', 1396207, 'og pos. 54&deg; 58,016''N - 011&deg; 51,967''E&nbsp;are off station.<br />It is recommended to use the main shipping lane between <span>pos.&nbsp;</span><span>54&deg; 58,46''N - 011&deg; 52,57''E og', 'and pos. 54&deg; 58,016''N - 011&deg; 51,967''E&nbsp;are off station.<br />It is recommended to use the main shipping lane between <span>pos.&nbsp;</span><span>54&deg; 58,46''N - 011&deg; 52,57''E and'),
  ('MessagePartDesc', 'details', 2586532, 'og', 'and'),
  ('MessagePartDesc', 'details', 2595763, 'oktober', 'October'),
  ('MessagePartDesc', 'details', 2422966, 'ved Ejstrup Strand.</span></li>
<li><span>57&deg; 12,38''N - 009&deg; 30,22''E, signal mast 2 ved', 'at Ejstrup Strand.</span></li>
<li><span>57&deg; 12,38''N - 009&deg; 30,22''E, signal mast 2 at'),
  ('MessagePartDesc', 'details', 2422986, 'ved Ejstrup Strand.</span></li>
<li><span>57&deg; 12,38''N - 009&deg; 30,22''E, signal mast 2 ved', 'at Ejstrup Strand.</span></li>
<li><span>57&deg; 12,38''N - 009&deg; 30,22''E, signal mast 2 at'),
  ('MessagePartDesc', 'details', 2608898, 'ved Ejstrup Strand.</span></li>
<li><span>57&deg; 12,38''N - 009&deg; 30,22''E, signal mast 2 ved', 'at Ejstrup Strand.</span></li>
<li><span>57&deg; 12,38''N - 009&deg; 30,22''E, signal mast 2 at'),
  ('MessagePartDesc', 'details', 2423170, 'og', 'and'),
  ('MessagePartDesc', 'details', 2423248, 'Qimusseriarsuaq og 14', 'Qimusseriarsuaq and 14'),
  ('MessagePartDesc', 'details', 2585150, 'og', 'and'),
  ('MessagePartDesc', 'details', 2423257, 'Tlf', 'Tel'),
  ('MessagePartDesc', 'details', 2423263, 'Tlf', 'Tel'),
  ('MessagePartDesc', 'details', 2423276, 'Tlf', 'Tel'),
  ('MessagePartDesc', 'details', 2324939, 'presence of USV''en', 'presence of the USV'),
  ('MessagePartDesc', 'details', 2517027, '<td>1. bassin</td>', '<td>Basin 1</td>'),
  -- The fourth check (2026-11-11), keyed by the part: A/2 2026's Danish abbreviation SKR (A/1 defines it as Chart
  -- corrections, Denmark), A/18's AKO (the same text's JACMD), A/20's Grønland in JRCC Greenland, A/21's BRT (A/19's
  -- GT), and the Danish descriptions of three lights in two NM drafts' position tables (as their positions' names).
  ('MessagePartDesc', 'details', 2585140, 'and/or in SKR.', 'and/or in the Chart Corrections (SKR).'),
  ('MessagePartDesc', 'details', 2585140, 'In SKR positions refer', 'In the Chart Corrections, positions refer'),
  ('MessagePartDesc', 'details', 2585140, 'information on the SKR, see', 'information on the Chart Corrections, see'),
  ('MessagePartDesc', 'details', 2423263, 'AKO manages', 'JACMD manages'),
  ('MessagePartDesc', 'details', 2423276, 'JRCC Gr&oslash;nland', 'JRCC Greenland'),
  ('MessagePartDesc', 'details', 2423283, '20 BRT', '20 GT'),
  ('MessagePartDesc', 'details', 1678265, 'Kerteminde Marina N-lige d&aelig;kmoles hoved', 'Kerteminde Marina, head of the northern breakwater'),
  ('MessagePartDesc', 'details', 1678265, 'Kerteminde Marina B&oslash;lgebryderens N-ende', 'Kerteminde Marina, northern end of the breakwater'),
  ('MessagePartDesc', 'details', 2096236, 'Kronl&oslash;b N-lige, N-enden af Marmorkaj', 'Kronl&oslash;bet, N side, N end of Marmorkaj'),
  ('MessagePartDesc', 'details', 2096236, 'Kronl&oslash;b S-lige, Marmorkaj', 'Kronl&oslash;bet, S side, Marmorkaj'),
  -- An NM draft's title and vicinity write the canal's name with a Danish lower-case 'kanal'; its details spell it.
  ('MessageDesc', 'title', 2326447, 'Frederiksholm kanal', 'Frederiksholms Kanal'),
  ('MessageDesc', 'vicinity', 2326447, 'Frederiksholm kanal', 'Frederiksholms Kanal')
  -- </MSG_REPLACE>
;
-- The almanac's 'se' (see) before a web address (A/22) and its 'Vest' (west) in the table of Greenland's coasts (A/19),
-- found by the second check (2026-11-11), are phrase rules below (35, 36): every version of the two parts holds them.
-- A blank English field filled with the English text of the Danish one.
CREATE TEMP TABLE english_msg_fill (tbl text, col text, entity_id int, value text) ON COMMIT DROP;
INSERT INTO english_msg_fill VALUES
  -- <MSG_FILL>
  ('MessageDesc', 'vicinity', 2459134, 'Rødby Havn'),
  ('MessageDesc', 'vicinity', 2459146, 'Rødby Havn'),
  ('MessageDesc', 'vicinity', 2605759, 'Skagen'),
  ('MessageDesc', 'vicinity', 2605770, 'Skagen'),
  ('MessageDesc', 'vicinity', 2403497, 'Rødby'),
  ('MessageDesc', 'vicinity', 2572302, 'Odense Havn'),
  ('MessageDesc', 'title', 1158930, 'Stones Guldborgsund.'),
  ('MessageDesc', 'title', 1391038, 'Mussel farming.'),
  ('MessageDesc', 'title', 2436727, 'Third Limfjord crossing - temporary quay.'),
  ('MessageDesc', 'title', 2563258, 'Measuring buoys Østbroen.'),
  ('MessagePartDesc', 'subject', 2459170, 'Buoy adrift'),
  ('MessagePartDesc', 'details', 589150, '<p><strong>dd.mm. 2019</strong> lights as stated will be established in positions 1) and 2).</p>
<table class="position-table">
<tbody>
<tr>
<td class="pos-index">1)</td>
<td class="pos-col">56&deg; 00.017''N - 008&deg; 07.759''E</td>
<td>Iso.R.4s</td>
</tr>
<tr>
<td class="pos-index">2)</td>
<td class="pos-col">55&deg; 59.951''N - 008&deg; 07.208''E</td>
<td>Iso.R.4s</td>
</tr>
</tbody>
</table>'),
  ('MessagePartDesc', 'details', 1617481, '<p>In the period&nbsp;</p>'),
  ('MessagePartDesc', 'details', 1435630, '<p>In the period 1 August - 1 November 2022, seabed surveys will be carried out in an area bounded by a line through positions 1) - 15).</p>
<table class="position-table">
<tbody>
<tr>
<td class="pos-index">1)</td>
<td class="pos-col">56&deg; 05.623''N - 012&deg; 28.339''E</td>
</tr>
<tr>
<td class="pos-index">2)</td>
<td class="pos-col">56&deg; 05.631''N - 012&deg; 28.566''E</td>
</tr>
<tr>
<td class="pos-index">3)</td>
<td class="pos-col">56&deg; 05.600''N - 012&deg; 28.746''E</td>
</tr>
<tr>
<td class="pos-index">4)</td>
<td class="pos-col">56&deg; 05.594''N - 012&deg; 28.926''E</td>
</tr>
<tr>
<td class="pos-index">5)</td>
<td class="pos-col">56&deg; 05.508''N - 012&deg; 29.204''E</td>
</tr>
<tr>
<td class="pos-index">6)</td>
<td class="pos-col">56&deg; 05.493''N - 012&deg; 29.786''E</td>
</tr>
<tr>
<td class="pos-index">7)</td>
<td class="pos-col">56&deg; 05.407''N - 012&deg; 30.214''E</td>
</tr>
<tr>
<td class="pos-index">8)</td>
<td class="pos-col">56&deg; 05.516''N - 012&deg; 30.219''E</td>
</tr>
<tr>
<td class="pos-index">9)</td>
<td class="pos-col">56&deg; 06.507''N - 012&deg; 31.141''E</td>
</tr>
<tr>
<td class="pos-index">10)</td>
<td class="pos-col">56&deg; 07.874''N - 012&deg; 35.433''E</td>
</tr>
<tr>
<td class="pos-index">11)</td>
<td class="pos-col">56&deg; 08.118''N - 012&deg; 35.283''E</td>
</tr>
<tr>
<td class="pos-index">12)</td>
<td class="pos-col">56&deg; 08.247''N - 012&deg; 35.252''E</td>
</tr>
<tr>
<td class="pos-index">13)</td>
<td class="pos-col">56&deg; 08.385''N - 012&deg; 35.103''E</td>
</tr>
<tr>
<td class="pos-index">14)</td>
<td class="pos-col">56&deg; 08.336''N - 012&deg; 34.238''E</td>
</tr>
<tr>
<td class="pos-index">15)</td>
<td class="pos-col">56&deg; 07.205''N - 012&deg; 31.761''E</td>
</tr>
</tbody>
</table>
<p>The work will be carried out from the vessels&nbsp;</p>'),
  ('MessagePartDesc', 'details', 1129842, '<p>In the period xx - xx 2021, anchors will be deployed on the seabed in an area bounded by a line through positions 1) - 4) and in an area bounded by a line through positions 5) - 8). In positions 9) and 10) buoyage will be deployed as stated/the guard vessel&nbsp;&raquo;Vessel&laquo; (xxxx) will be present in the area. The vessel can be contacted on VHF channel 16.&nbsp;</p>
<table class="position-table">
<tbody>
<tr>
<td class="pos-index">1)</td>
<td class="pos-col">54&deg; 41.309''N - 014&deg; 39.087''E</td>
</tr>
<tr>
<td class="pos-index">2)</td>
<td class="pos-col">54&deg; 41.356''N - 014&deg; 40.014''E</td>
</tr>
<tr>
<td class="pos-index">3)</td>
<td class="pos-col">54&deg; 40.819''N - 014&deg; 40.095''E</td>
</tr>
<tr>
<td class="pos-index">4)</td>
<td class="pos-col">54&deg; 40.772''N - 014&deg; 39.168''E</td>
</tr>
<tr>
<td class="pos-index">5)</td>
<td class="pos-col">54&deg; 40.328''N - 014&deg; 39.251''E</td>
</tr>
<tr>
<td class="pos-index">6)</td>
<td class="pos-col">54&deg; 40.375''N - 014&deg; 40.178''E</td>
</tr>
<tr>
<td class="pos-index">7)</td>
<td class="pos-col">54&deg; 39.838''N - 014&deg; 40.259''E</td>
</tr>
<tr>
<td class="pos-index">8)</td>
<td class="pos-col">54&deg; 39.791''N - 014&deg; 39.332''E</td>
</tr>
</tbody>
</table>'),
  ('MessagePartDesc', 'details', 2269973, '<p>In the period beginning June - ending August 2025, maintenance works will be carried out in an area bounded by a line through positions 1) - 4)&nbsp;</p>
<table class="position-table">
<tbody>
<tr>
<td class="pos-index">1)</td>
<td class="pos-col">55&deg; 27.207''N - 008&deg; 27.287''E</td>
</tr>
<tr>
<td class="pos-index">2)</td>
<td class="pos-col">55&deg; 27.202''N - 008&deg; 27.308''E</td>
</tr>
<tr>
<td class="pos-index">3)</td>
<td class="pos-col">55&deg; 27.197''N - 008&deg; 27.305''E</td>
</tr>
<tr>
<td class="pos-index">4)</td>
<td class="pos-col">55&deg; 27.203''N - 008&deg; 27.284''E</td>
</tr>
</tbody>
</table>')
  -- </MSG_FILL>
;
DO $$
DECLARE
    r record;
    c int;
    n_set int := 0;
    n_replace int := 0;
    n_fill int := 0;
BEGIN
    FOR r IN SELECT * FROM english_msg_set LOOP
        EXECUTE format('UPDATE public.%I SET %I = $1 WHERE entity_id = $2 AND lang = ''en'' AND %I = $3',
                       r.tbl, r.col, r.col) USING r.new_value, r.entity_id, r.old_value;
        GET DIAGNOSTICS c = ROW_COUNT;
        n_set := n_set + c;
    END LOOP;
    FOR r IN SELECT * FROM english_msg_replace LOOP
        EXECUTE format('UPDATE public.%I SET %I = replace(%I, $1, $2) WHERE entity_id = $3 AND lang = ''en'' AND strpos(%I, $1) > 0',
                       r.tbl, r.col, r.col, r.col) USING r.find, r.repl, r.entity_id;
        GET DIAGNOSTICS c = ROW_COUNT;
        n_replace := n_replace + c;
    END LOOP;
    FOR r IN SELECT * FROM english_msg_fill LOOP
        EXECUTE format('UPDATE public.%I SET %I = $1 WHERE entity_id = $2 AND lang = ''en'' AND coalesce(%I, '''') = ''''',
                       r.tbl, r.col, r.col) USING r.value, r.entity_id;
        GET DIAGNOSTICS c = ROW_COUNT;
        n_fill := n_fill + c;
    END LOOP;
    INSERT INTO english_counts (grp, rows) VALUES ('messages: English fields set', n_set),
                                                  ('messages: English details corrected in place', n_replace),
                                                  ('messages: blank English fields filled', n_fill);
END $$;

-- A part's English subject, keyed by its message (one part each).
WITH v (message_id, old_value, new_value) AS (VALUES
  -- <MSG_PART_SUBJECT>
  (2586466, 'Cable Works. Virtuel AtoN oprettet.', 'Cable Works. Virtual AtoN established.')
  -- </MSG_PART_SUBJECT>
), u AS (
    UPDATE public."MessagePartDesc" x SET subject = v.new_value
      FROM public."MessagePart" p, v
     WHERE x.entity_id = p.id AND p.message_id = v.message_id AND x.lang = 'en' AND x.subject = v.old_value
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English part subjects set', count(*) FROM u;

-- The English publication of four live notices was blank while the Danish one links a publication: the English takes
-- the same link, with the publication's English name where it has one (the Danish List of Lights).
WITH u AS (
    UPDATE public."MessageDesc" en
       SET publication = replace(da.publication, '>Dansk Fyrliste, nr. ', '>Danish List of Lights, no. ')
      FROM public."MessageDesc" da
     WHERE da.entity_id = en.entity_id AND da.lang = 'da' AND en.lang = 'en'
       AND en.entity_id IN (2559037, 1241349, 1486820, 2577318)
       AND coalesce(en.publication, '') = '' AND coalesce(da.publication, '') <> ''
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English publication links', count(*) FROM u;

-- Rows inserted where a message, part or reference has a Danish text and no English row at all (drafts of niord-nm,
-- one reference of a live notice). The ids come from the tables' own sequences, as the backend takes them.
WITH v (message_id, title) AS (VALUES
  -- <MSG_INSERT_DESC>
  (862452, 'Seabed surveys.'),
  (2535182, 'Wreck.')
  -- </MSG_INSERT_DESC>
), u AS (
    INSERT INTO public."MessageDesc" (id, lang, title, entity_id)
    SELECT nextval('public."MessageDesc_SEQ"'), 'en', v.title, v.message_id FROM v
     WHERE EXISTS (SELECT 1 FROM public."Message" m WHERE m.id = v.message_id)
       AND NOT EXISTS (SELECT 1 FROM public."MessageDesc" d WHERE d.entity_id = v.message_id AND d.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English description rows inserted', count(*) FROM u;

WITH v (part_id, subject) AS (VALUES
  -- <MSG_INSERT_PART>
  (778187, 'Rerouting'),
  (778252, 'Rerouting ver 2'),
  (779069, 'Rerouting ver 3'),
  (862466, 'Seabed surveys'),
  (1158933, 'Stones Guldborgsund'),
  (1293584, 'Seabed surveys'),
  (1339206, 'Femern S 14 March'),
  (1391041, 'Mussel farming'),
  (2436730, 'Third Limfjord crossing - temporary quay'),
  (2554907, 'Wreck'),
  (2563261, 'Measuring buoys Østbroen')
  -- </MSG_INSERT_PART>
), u AS (
    INSERT INTO public."MessagePartDesc" (id, lang, subject, entity_id)
    SELECT nextval('public."MessagePartDesc_SEQ"'), 'en', v.subject, v.part_id FROM v
     WHERE EXISTS (SELECT 1 FROM public."MessagePart" p WHERE p.id = v.part_id)
       AND NOT EXISTS (SELECT 1 FROM public."MessagePartDesc" d WHERE d.entity_id = v.part_id AND d.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English part rows inserted', count(*) FROM u;

WITH v (reference_id, description) AS (VALUES
  -- <MSG_INSERT_REF>
  (2546089, 'Positions 1) - 6)')
  -- </MSG_INSERT_REF>
), u AS (
    INSERT INTO public."ReferenceDesc" (id, lang, description, entity_id)
    SELECT nextval('public."ReferenceDesc_SEQ"'), 'en', v.description, v.reference_id FROM v
     WHERE EXISTS (SELECT 1 FROM public."Reference" r WHERE r.id = v.reference_id)
       AND NOT EXISTS (SELECT 1 FROM public."ReferenceDesc" d WHERE d.entity_id = v.reference_id AND d.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English reference rows inserted', count(*) FROM u;

-- NM Annex B/1 2026 (message 2160519, published), the Naval Command's ice-service notice, has no English text at all,
-- so the administration app shows its Danish title, source, details (the whole annex) and attachment caption in
-- English (the fourth check, 2026-11-11). The notice is published in Danish only: it gets an English title after its
-- series' English name, an English source (FKO as A/5 2026 writes it in English), an English subject, one line of
-- details that says so, and an English caption for the attached document (whose stored file name stays). Its English
-- internal publication follows from the rule below.
WITH u AS (
    INSERT INTO public."MessageDesc" (id, lang, title, source, entity_id)
    SELECT nextval('public."MessageDesc_SEQ"'), 'en',
           'Naval Staff notice on the ice service and ice reports for the winter of 2026 (Danish Only).',
           'DC DNK December 2025', m.id
      FROM public."Message" m
     WHERE m.id = 2160519
       AND NOT EXISTS (SELECT 1 FROM public."MessageDesc" d WHERE d.entity_id = m.id AND d.lang = 'en')
    RETURNING 1),
p AS (
    INSERT INTO public."MessagePartDesc" (id, lang, subject, details, entity_id)
    SELECT nextval('public."MessagePartDesc_SEQ"'), 'en', 'Ice service and ice reports for the winter of 2026',
           '<p>Published in Danish only; see the attached document.</p>', mp.id
      FROM public."MessagePart" mp
     WHERE mp.id = 2425690 AND mp.message_id = 2160519
       AND NOT EXISTS (SELECT 1 FROM public."MessagePartDesc" d WHERE d.entity_id = mp.id AND d.lang = 'en')
    RETURNING 1),
a AS (
    INSERT INTO public."AttachmentDesc" (id, lang, caption, entity_id)
    SELECT nextval('public."AttachmentDesc_SEQ"'), 'en', 'Ice annex - 2026', at.id
      FROM public."Attachment" at
     WHERE at.id = 2425688 AND at.message_id = 2160519
       AND NOT EXISTS (SELECT 1 FROM public."AttachmentDesc" d WHERE d.entity_id = at.id AND d.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows)
SELECT 'messages: NM Annex B/1 2026 English rows inserted',
       (SELECT count(*) FROM u) + (SELECT count(*) FROM p) + (SELECT count(*) FROM a);

-- Rule: a blank English internal publication whose Danish one holds only the journal and AtoN numbers ('[J.nr. ...]',
-- '[AFM, nr. ...]') takes them in the publications' own English formats ('[J. No. ...]', '[AtoN no ...]'), inside the
-- same links (about 14,400 messages, every published navigational warning among them).
WITH u AS (
    UPDATE public."MessageDesc" e
       SET "internalPublication" = replace(replace(d."internalPublication", '[J.nr. ', '[J. No. '), '[AFM, nr. ', '[AtoN no ')
      FROM public."MessageDesc" d
     WHERE d.entity_id = e.entity_id AND d.lang = 'da' AND e.lang = 'en'
       AND coalesce(e."internalPublication", '') = '' AND coalesce(d."internalPublication", '') <> ''
       AND btrim(regexp_replace(regexp_replace(regexp_replace(d."internalPublication", '<[^>]*>', '', 'g'),
                 '\[(J\.nr\.|AFM, nr\.)[^\]]*\]\.?', '', 'g'), '(\s|&nbsp;)+', '', 'g')) = ''
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English internal publications (rule)', count(*) FROM u;

-- NM draft 2074713's Danish internal publication holds the same two numbers, but its AtoN bracket is never closed, so
-- the rule above leaves it: its English takes the Danish markup with the labels in English, as the rule writes them.
WITH u AS (
    UPDATE public."MessageDesc" e
       SET "internalPublication" = replace(replace(d."internalPublication", '[J.nr. ', '[J. No. '), '[AFM, nr. ', '[AtoN no ')
      FROM public."MessageDesc" d
     WHERE d.entity_id = e.entity_id AND d.lang = 'da' AND e.lang = 'en' AND e.entity_id = 2074713
       AND coalesce(e."internalPublication", '') = '' AND coalesce(d."internalPublication", '') <> ''
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: English internal publication of NM draft 2074713', count(*) FROM u;

-- Rule: the firing areas' signal masts are 'signal mast N ved <beach>' in English (about 2,075 messages of the firing
-- areas and their exercises): 'ved' is 'at'.
WITH u AS (
    UPDATE public."MessagePartDesc"
       SET details = regexp_replace(details, '(signal ?mast \d+) ved ', '\1 at ', 'gi')
     WHERE lang = 'en' AND details ~* 'signal ?mast \d+ ved '
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: signal mast ''ved'' (rule)', count(*) FROM u;

-- Rule: a Danish date in an English text ('7. juni 2022', '21 Marts 2025', 'januar 2025'): the English month name, and
-- no ordinal dot after the day.
CREATE FUNCTION pg_temp.english_dates(t text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $f$
DECLARE
    m text[];
BEGIN
    IF t IS NULL THEN RETURN NULL; END IF;
    FOREACH m SLICE 1 IN ARRAY ARRAY[['januar', 'January'], ['februar', 'February'], ['marts', 'March'],
                                      ['april', 'April'], ['maj', 'May'], ['juni', 'June'], ['juli', 'July'],
                                      ['august', 'August'], ['september', 'September'], ['oktober', 'October'],
                                      ['november', 'November'], ['december', 'December']] LOOP
        -- a day before the month: '7. juni', '21 Marts', '19. august'
        t := regexp_replace(t, '\m(\d{1,2})\.?((?:\s|&nbsp;)+)' || m[1] || '\M', '\1\2' || m[2], 'gi');
        -- a year after it: 'januar 2025', 'Februar 2024'
        t := regexp_replace(t, '\m' || m[1] || '((?:\s|&nbsp;)+\d{4})\M', m[2] || '\1', 'gi');
    END LOOP;
    RETURN t;
END
$f$;

WITH u AS (
    UPDATE public."MessageDesc"
       SET title = pg_temp.english_dates(title), vicinity = pg_temp.english_dates(vicinity),
           source = pg_temp.english_dates(source)
     WHERE lang = 'en'
       AND (title IS DISTINCT FROM pg_temp.english_dates(title)
            OR vicinity IS DISTINCT FROM pg_temp.english_dates(vicinity)
            OR source IS DISTINCT FROM pg_temp.english_dates(source))
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: Danish dates in English titles and sources (rule)', count(*) FROM u;

WITH u AS (
    UPDATE public."MessagePartDesc"
       SET subject = pg_temp.english_dates(subject), details = pg_temp.english_dates(details)
     WHERE lang = 'en'
       AND (subject IS DISTINCT FROM pg_temp.english_dates(subject)
            OR details IS DISTINCT FROM pg_temp.english_dates(details))
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'messages: Danish dates in English parts (rule)', count(*) FROM u;

-- ============================================================================================================
-- Settings and the audio broadcast
-- ============================================================================================================

-- The languages a message is written in: the base product's ["en"]. With Danish beside it, every editor field, the
-- admin drawers' names and descriptions, the comparison dialog and the status page show a Danish input under the
-- English one, whatever the message says.
WITH u AS (
    UPDATE public."Setting" SET setting_value = '["en"]'
     WHERE setting_key = 'modelLanguages' AND setting_value IS DISTINCT FROM '["en"]'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'settings: modelLanguages', count(*) FROM u;

-- The audio broadcast's language: the base product's 'en'. Denmark broadcasts in Danish, so its audio text is Danish
-- in every interface language.
WITH u AS (
    UPDATE public."PromulgationType" SET language = 'en'
     WHERE "typeId" = 'audio' AND language IS DISTINCT FROM 'en'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'promulgation types: audio language', count(*) FROM u;

-- ============================================================================================================
-- The rule the rig picks its example messages by (schema guides, beside the application's tables; the application
-- never reads it).
-- ============================================================================================================
CREATE SCHEMA IF NOT EXISTS guides;

-- A text as a reader sees it: entities decoded, tags and whitespace collapsed.
CREATE OR REPLACE FUNCTION guides.clean(x text) RETURNS text LANGUAGE sql IMMUTABLE AS $f$
  SELECT btrim(regexp_replace(regexp_replace(regexp_replace(
    replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(coalesce(x, ''),
      '&aelig;', 'æ'), '&oslash;', 'ø'), '&aring;', 'å'), '&AElig;', 'Æ'), '&Oslash;', 'Ø'), '&Aring;', 'Å'),
      '&nbsp;', ' '), '&amp;', '&'), '&deg;', '°'), '&eacute;', 'é'), '&shy;', ''),
    '<[^>]*>', ' ', 'g'), '&[a-zA-Z]+;', ' ', 'g'), '\s+', ' ', 'g'))
$f$;

-- A text with a Danish word in it (web addresses left out): a Danish function or trade word, a Danish month name, or a
-- lower-case word with æ, ø or å. Deliberately broad: it also turns down an English text that carries a Danish name
-- (Odense Kanal, Nyt Løb), which costs nothing where thousands of messages qualify (measured by the audit: 42 % of its
-- hits hold Danish, 0.5 % of its non-hits do). The words from kanal on were added after the second check (2026-11-11),
-- which found them in accepted messages ('VHF kanal 16', 'LOKAL TIME', 'In the periode', 'Mindre dybde'); the words
-- from vrag on after the third (2026-11-11), which found them in titles, subjects, position names and NAVTEX texts
-- ('Vrag.', 'Kabelarbejde', 'usikker', 'Planlagt', 'test objekt', 'Red buoy observeret', 'BØJE NR 28'); the words from
-- signalmast on after the fourth (2026-11-11), which found them in old messages the rule accepted ('Signalmast.',
-- 'Allinge Havn trafiksignal', 'Bassin dredged', 'bropille', 'Afmærket havbrug', 'SE-lige klapside', 'Bølgebryder E-lige
-- ende', 'S-lige molehoved', 'GROENLANDS SYDVESTKYST', '20 SOEMIL') and in the live messages it read ('Tranum
-- Skydeterræn', 'Oksbøl Kaserne', 'Valdemar Oliefelt', 'Tyra Gasfelt', 'Kraka Feltet', 'Femern Overblik', 'arbejdshavn');
-- the words from oprettet on after the sixth (2026-11-11), which found them in older versions of published NMs
-- ('Virtual AtoN oprettet', 'Indsejlingen Randers Fjrod', 'Klapplads').
CREATE OR REPLACE FUNCTION guides.looks_danish(x text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $f$
  WITH c AS (
    SELECT btrim(regexp_replace(guides.clean(regexp_replace(coalesce(x, ''), 'href="[^"]*"', '', 'g')),
                 '\S*(https?://|www\.|@)\S*', ' ', 'g')) AS t)
  SELECT lower(t) ~ '\m(og|er|af|til|på|ved|med|ikke|eller|mellem|samt|skal|kan|bliver|blive|være|været|har|denne|dette|disse|efter|fra|som|det|den|der|et|ud|når|hvor|også|kun|alle|grader|syd|øst|vest|nordlige|sydlige|østlige|vestlige|afmærkning\w*|tilrådes|slukket|forsvundet|sejlads\w*|skibsfart\w*|farvand\w*|område\w*|perioden|arbejde\w*|udlagt|udføres|lystønde\w*|vanddybde\w*|advarsel|annullér|annuller|kl|følgende|nærmere|bemærk\w*|nr|stk|jf|iht|ifm|tlf|vedr|bl|mv|dvs|hhv|januar|februar|marts|maj|juni|juli|oktober|kanal|lokal|periode|inspektion|boringer|dybde|mindre|kyst|lukning|indtil|vil|afsluttet|vinter|telefon|dato|inoperativ|platformen|nyt|virtuel|bestemmelser|generelle|pkt|savnet|navigationsadvars\w*|tilføjet|manuelt|geotekniske|maschin|vrag|kabelarbejde|grundstødt|usikker|planlagt|objekt|observeret|reduceret|undervandshindring|vedligeholdelses\w*|lukket|topbetegnelse|krydstopbetegnelse|midtfarvands\w*|overgangspunkt\w*|sundets|bøje\w*|internationalt|signalmast\w*|trafiksignal\w*|skydeområde\w*|skydeterræn\w*|bassin\w*|bropille\w*|havbrug\w*|klapside\w*|bølgebryder\w*|molehoved\w*|gennemsejling\w*|sydvestkyst\w*|soemil|kaserne|oliefelt\w*|gasfelt\w*|feltet|overblik|arbejdshavn\w*|oprettet|indsejling\w*|klapplads\w*)\M'
      OR t ~ '(^|[^[:alpha:]])[a-zæøå]*[æøå][a-zæøå]*'
    FROM c
$f$;

-- A NAVTEX text with a Danish word in it. NAVTEX is written in capitals, where looks_danish reads every Danish place
-- name as Danish (THYBORØN KANAL, BUOY VEST 26 A), so this looks for the Danish words a NAVTEX text of the copy holds
-- besides names: 'RØD BØJE NR. 16', 'LIGHT 7504 OG INTERNATIONALT L5660', 'HIGHT OP TIL 11700 METER'.
CREATE OR REPLACE FUNCTION guides.navtex_danish(x text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $f$
  SELECT upper(coalesce(x, '')) ~ '\m(OG|OP\s+TIL|UP\s+TIL|PÅ|IKKE|BØJE\w*|\w*BØJE|RØD|GRØN|GUL|LYSTØNDE\w*|SLUKKET|FORSVUNDET|UDLAGT|VRAG|INTERNATIONALT)\M'
      OR upper(coalesce(x, '')) ~ '\mNR\.?\s*\d'
$f$;

-- Live messages whose Danish-looking words the audit read and found to be names, codes or web addresses only. An entry
-- exempts every text field of the message, not only the name it was added for (not the names of its positions, which
-- the rule reads on their own, since the third check of 2026-11-11): the second check (2026-11-11) took out the
-- two drafts whose light descriptions are Danish ('N-lige dækmoles hoved', 'N-enden af Marmorkaj'; 175 drafts of
-- niord-nm qualify without them) and the three firing-area messages whose 'observationspost' the phrase rules below
-- translate (their 'Storehøj' is a name and looks Danish to no rule).
CREATE TABLE IF NOT EXISTS guides.names_only (message_id int PRIMARY KEY, why text NOT NULL);
INSERT INTO guides.names_only VALUES
  (2391894, 'source: Kerteminde Havne og Marina (an organisation)'),
  (2494821, 'Kolding Marina Syd (a place)'),
  (2542169, 'source: A/S Havnen ved Kolby Kås (a company)'),
  (2556494, 'Københavns Havn, Tuborg Syd (places)'),
  (2595772, 'Bogense Havn og Marina (a harbour''s name)'),
  (2411341, 'titles of Danish and Nordic publications'),
  (2417587, '"Farvandsefterretninger", the service''s name'),
  (2417653, 'a web address written with spaces'),
  (2417956, 'Afmærkning af danske farvande, Efterretninger for Søfarende (publication titles)'),
  (2417999, 'Den danske Lods (a publication title), Nørresundby (an address)'),
  -- The fourth check (2026-11-11) read every live message; these three hold names only where the rule sees Danish.
  (2506028, 'Hestehoved Dyb. Nyt Løb (a channel''s name)'),
  (2541168, 'Odense Kanal (a place)'),
  (2326447, 'Frederiksholms Kanal, Slotholmskanalen (places)')
ON CONFLICT (message_id) DO NOTHING;
DELETE FROM guides.names_only WHERE message_id IN (1678254, 2095937, 2419152, 2419173, 2608860);

-- Messages that are no English text a reader can follow, whatever the words: never picked. The NM draft of three first
-- names and nothing else (title and subject the same in both languages, no area, no details; people or vessels, nobody
-- can tell: scrub.sql deletes it from the next copy).
CREATE TABLE IF NOT EXISTS guides.not_english (message_id int PRIMARY KEY, why text NOT NULL);
INSERT INTO guides.not_english VALUES
  (2471513, 'NM draft: a title and subject of three first names only'),
  (1084147, 'GL-004-21 (expired): its English title and vicinity are the Danish and the English text run into each other'),
  (1593012, 'NM draft with no description in any language and no text in its parts: nothing to show'),
  (2471614, 'NM draft with no description in any language and no text in its parts: nothing to show'),
  (2498871, 'NM draft with no description in any language and no text in its parts: nothing to show')
ON CONFLICT (message_id) DO NOTHING;

-- The messages of a domain in a status, each with english_ok (English throughout, by the rule above, the names of its
-- positions included: the editor's Positions rows, the details dialog, the history and the maps show them) and
-- promulgation_danish (it carries an audio broadcast or NAVTEX text in Danish, which the editor's promulgation panel,
-- the status page, the live preview and the details dialog show). A position's name is the one the administration app
-- shows in English (message-geometry.service.ts, findCoordName): the 'name:<n>:en' key, else any other language's.
--
-- A message that is no longer live (EXPIRED, CANCELLED, DELETED) was not read by anyone, so the rule is stricter for
-- it (the fourth check, 2026-11-11): it is also turned down when an English title, vicinity, part subject or position
-- name is the Danish one word for word and is not made of names only (guides.only_names), or when its NAVTEX text holds
-- a Danish word. Thousands of old messages qualify without them.
CREATE OR REPLACE FUNCTION guides.only_names(x text) RETURNS boolean LANGUAGE sql IMMUTABLE AS $f$
  SELECT guides.clean(x) !~ '(^|[^A-Za-z0-9ÆØÅæøåÉéÜüÖöÄä])[a-zæøåéüöä]'
$f$;

DROP FUNCTION IF EXISTS guides.english_messages(text, text);
CREATE FUNCTION guides.english_messages(p_domain text, p_status text)
RETURNS TABLE (message_id int, uid text, short_id text, english_ok boolean, promulgation_danish boolean)
LANGUAGE sql STABLE AS $f$
WITH m AS (
    SELECT DISTINCT msg.id, msg.uid, msg."shortId"
      FROM public."Message" msg
      JOIN public."Domain_MessageSeries" dm ON dm."messageSeries_id" = msg."messageSeries_id"
      JOIN public."Domain" d ON d.id = dm."Domain_id"
     WHERE d."domainId" = p_domain AND msg.status = p_status),
f AS (
    SELECT m.id AS mid, 'message' AS kind, x.entity_id AS eid, v.fld, x.lang, v.val
      FROM m JOIN public."MessageDesc" x ON x.entity_id = m.id
     CROSS JOIN LATERAL (VALUES ('title', x.title), ('vicinity', x.vicinity), ('source', x.source),
                                ('publication', x.publication), ('internalPublication', x."internalPublication")) v (fld, val)
    UNION ALL
    SELECT m.id, 'part', p.id, v.fld, x.lang, v.val
      FROM m JOIN public."MessagePart" p ON p.message_id = m.id JOIN public."MessagePartDesc" x ON x.entity_id = p.id
     CROSS JOIN LATERAL (VALUES ('subject', x.subject), ('details', x.details)) v (fld, val)
    UNION ALL
    SELECT m.id, 'reference', r.id, 'description', x.lang, x.description
      FROM m JOIN public."Reference" r ON r.message_id = m.id JOIN public."ReferenceDesc" x ON x.entity_id = r.id
    UNION ALL
    SELECT m.id, 'attachment', a.id, 'caption', x.lang, x.caption
      FROM m JOIN public."Attachment" a ON a.message_id = m.id JOIN public."AttachmentDesc" x ON x.entity_id = a.id),
g AS (
    SELECT mid, fld, max(val) FILTER (WHERE lang = 'en') AS en, max(val) FILTER (WHERE lang = 'da') AS da
      FROM f GROUP BY mid, kind, eid, fld),
s AS (
    SELECT mid,
           bool_or(guides.clean(en) = '' AND guides.clean(da) <> '') AS missing,
           bool_or(guides.clean(en) <> '' AND guides.looks_danish(en)) AS danish,
           bool_or(fld IN ('title', 'vicinity', 'subject') AND guides.clean(en) <> ''
                   AND lower(guides.clean(en)) = lower(guides.clean(da)) AND NOT guides.only_names(en)) AS same
      FROM g GROUP BY mid),
fk AS (
    SELECT m.id AS mid, ft.id AS fid, j.key, j.value,
           coalesce(substring(j.key FROM '^name(:\d+)?:[A-Za-z]+$'), '') AS slot
      FROM m JOIN public."MessagePart" p ON p.message_id = m.id
      JOIN public."Feature" ft ON ft."featureCollection_id" = p.geometry_id,
           jsonb_each_text(ft.properties::jsonb) j
     WHERE j.key ~ '^name(:\d+)?:[A-Za-z]+$' AND coalesce(j.value, '') <> ''),
fn AS (
    SELECT mid, bool_or(guides.looks_danish(shown)) AS danish,
           bool_or(shown = da AND NOT guides.only_names(shown)) AS same
      FROM (SELECT mid, fid, slot,
                   coalesce(max(value) FILTER (WHERE key = 'name' || slot || ':en'), min(value)) AS shown,
                   max(value) FILTER (WHERE key = 'name' || slot || ':da') AS da
              FROM fk GROUP BY mid, fid, slot) x
     GROUP BY mid),
au AS (
    SELECT b.message_id AS mid,
           bool_or(guides.looks_danish(a.text) OR guides.navtex_danish(n.text)) AS danish,
           bool_or(guides.navtex_danish(n.text)) AS navtex
      FROM m JOIN public."BaseMessagePromulgation" b ON b.message_id = m.id
      LEFT JOIN public."AudioMessagePromulgation" a ON a.id = b.id AND coalesce(a.text, '') <> ''
      LEFT JOIN public."NavtexMessagePromulgation" n ON n.id = b.id AND coalesce(n.text, '') <> ''
     GROUP BY b.message_id)
SELECT m.id, m.uid, m."shortId",
       EXISTS (SELECT 1 FROM public."MessageDesc" x WHERE x.entity_id = m.id AND x.lang = 'en')
         AND NOT coalesce(s.missing, false)
         AND NOT EXISTS (SELECT 1 FROM guides.not_english e WHERE e.message_id = m.id)
         AND (NOT coalesce(s.danish, false) OR EXISTS (SELECT 1 FROM guides.names_only n WHERE n.message_id = m.id))
         AND NOT coalesce(fn.danish, false)
         AND (p_status NOT IN ('EXPIRED', 'CANCELLED', 'DELETED')
              OR NOT (coalesce(s.same, false) OR coalesce(fn.same, false) OR coalesce(au.navtex, false))),
       coalesce(au.danish, false)
  FROM m LEFT JOIN s ON s.mid = m.id LEFT JOIN fn ON fn.mid = m.id LEFT JOIN au ON au.mid = m.id
$f$;

-- ============================================================================================================
-- The audio broadcast texts of the live navigational warnings, generated again in English by the backend with the
-- Audio type set to English (POST /rest/promulgations/generate/audio): a Danish text is replaced, keyed by the
-- message's uid.
-- ============================================================================================================
CREATE TEMP TABLE english_audio (uid text, text text) ON COMMIT DROP;
INSERT INTO english_audio VALUES
  -- <AUDIO>
  ('0067b666-f806-496e-95f4-2c14cc6a7edc', 'The special buoy in position 54 degrees 37.7 min. North - 011 degrees 34.5 min. East is missing.'),
  ('00fe637a-f608-45fd-9b8d-1f3328b1ebb5', 'The light Skagen fyr in position 57 degrees 44.1 min. North - 010 degrees 37.8 min. East is unlit.'),
  ('033ac064-aeeb-46e6-a9c2-67d58e930823', 'The port hand buoy in position 55 degrees 49.1 min. North - 012 degrees 03.4 min. East is off station.'),
  ('04f9fecd-e1e0-491a-8c2f-4e1767a6a757', 'An uncharted obstruction is reported in position 55 degrees 10.8 min. North - 011 degrees 13.4 min. East. The 
depth above the obstruction is 2m. The obstruction is unmarked.
 Mariners are advised to keep well clear.'),
  ('067bb9f2-989e-4a14-a257-01effae9daab', 'The buoy in position 55 degrees 30.2 min. North - 009 degrees 36.9 min. East is off station.'),
  ('06d0d70b-54e3-4b0d-9a86-549ee8c6a409', 'Two uncharted marine farms have been observed at position 55 degrees 18.1 min. North - 010 degrees 48.1 min. East 
and 55 degrees 18.1 min. North - 010 degrees 48.2 min. East . The marine farms are visible above sea level. 
The marine farms arebuoyed with four yellow buoys. 
 Mariners are advised to keep well clear.'),
  ('1040c21d-90fa-4ce3-a89c-62983c2d6cb6', 'A sailboat with a draught of 2m has touched bottom in position 55 degrees 07.2 min. North - 012 degrees 14.2 min. East.
 Mariners are advised to keep well clear.'),
  ('139c3bcc-4647-4c71-96eb-664a8fca8921', 'The special buoy in position 54 degrees 57.3 min. North - 012 degrees 04.1 min. East is off station.'),
  ('20e46e78-1206-4f6a-8193-52bcd944df37', 'The north cardinal buoy in position 55 degrees 49.2 min. North - 012 degrees 03.4 min. East is off station.'),
  ('2fb81c4c-9928-4674-975a-1ad84f562700', 'The mole head light Aggersund Broen in position 56 degrees 60.0 min. North - 009 degrees 17.6 min. East is unlit.'),
  ('33fc0b55-1302-4681-b79b-5711b1cf20d0', 'Two uncharted stone depots have been observed at position 56 degrees 35.0 min. North - 010 degrees 16.5 min. East. The stones are visible above the sea surface. The stones are unmarked.
 Mariners are advised to keep well clear.'),
  ('36a44764-961b-4e7a-b640-2665ef0ad5b4', 'The starboard hand buoy in position 55 degrees 05.7 min. North - 014 degrees 40.6 min. East is missing.'),
  ('4bd5e387-28e4-4af1-8cf0-f7cf17c37f3c', 'The light Skagen Fyr in position 57 degrees 44.1 min. North - 010 degrees 37.8 min. East is unlit.'),
  ('5f753a3a-7651-47b4-8b94-d459015d0ddf', 'The green light buoys in position 55 degrees 02.29 min. North - 011 degrees 44.73 min. East and position 55 degrees 02.20 min. North 
- 011 degrees 42.87 min. East, the red light buoys in position 55 degrees 02.40 min. North - 011 degrees 44.78 min. East and position 55 degrees 02.25 min. North - 011 degrees 42.83 min. East, and the red day mark in position 55 degrees 02.11 min. North - 011 degrees 42.33 min. East, are missing.'),
  ('662da6bc-b870-4b43-963e-71733fd8a7af', 'The green mole head light in position 56 degrees 12.6 min. North - 010 degrees 17.5 min. East is unlit.
 The red mole head light in position 56 degrees 12.6 min. North - 010 degrees 17.6 min. East is unlit.'),
  ('719a7767-c91d-4542-94dc-39f514f67529', 'The green sector light in position 55 degrees 36.8 min. North - 012 degrees 30.7 min. East is unlit.'),
  ('8c464034-9f86-4f69-99cb-6d724aeef61b', 'An object has been observed adrift in position 54 degrees 37.4 min. North - 011 degrees 17.5 min. East the 16th 
February 2026 at 0422 UTC.
Cancel this warning the 16th February 2026 at 1622 UTC.'),
  ('90e0519f-04ff-487c-b450-9737e31fc689', 'Water depths down to 11m have been observed in the entrance to Kalundborg 
Sydhavn position 55 degrees 40.3 min. North - 011 degrees 05.3 min. East.
 Mariners are advised to keep well clear.'),
  ('938602d0-bcfd-4bb4-99fa-3f26f5df0b8d', 'An uncharted obstruction is reported in position 56 degrees 34.9 min. North - 008 degrees 20.0 min. East. The 
obstruction is visible above the sea surface. The obstruction is buoyed with a 
yellow buoy.
 Mariners are advised to keep well clear.'),
  ('9464e570-6b60-4b9e-a4df-2fcd92ba6921', 'The Lindeskov front and rear light in position 55 degrees 16.1 min. North - 011 degrees 14.7 min. East is 
unreliable.'),
  ('97f8d27a-90f2-4a81-be30-c2895d6472cb', 'The light Skagen Fyr in position 57 degrees 44.1 min. North - 010 degrees 37.8 min. East is unlit.'),
  ('99f40175-759f-42ea-84a9-7daa33142787', 'The front and rear light in position 55 degrees 27.0 min. North - 010 degrees 39.6 min. East is unlit.'),
  ('9d4c645c-1a86-42d7-90e0-a28805735f9c', 'The special buoy in position 54 degrees 36.4 min. North - 011 degrees 33.6 min. East is missing.'),
  ('a292f781-8155-4e48-b8e2-0210227544c0', 'The port hand buoy No. 6 in position 55 degrees 30.6 min. North - 010 degrees 30.5 min. East is missing.'),
  ('ac24b876-79c0-4cd2-900c-81a03c78c964', 'The special buoy in position 55 degrees 34.6 min. North - 012 degrees 29.3 min. East is missing.'),
  ('afb7884f-1de0-4d43-947c-d7c594d0e05f', 'The starboard hand buoy in position 55 degrees 55.3 min. North - 010 degrees 39.2 min. East is missing.'),
  ('b1276ba6-a6d7-40a9-9d09-83a585d94fe4', 'The starboard hand buoy in position 55 degrees 31.1 min. North - 009 degrees 56.9 min. East is off station.'),
  ('b339673b-478b-4e08-badd-11572bb22bd6', 'The south cardinal light buoy in position 56 degrees 07.2 min. North - 011 degrees 07.9 min. East is unlit.'),
  ('bd64978b-c3c6-4d16-ab23-c150c6d58399', 'The green light in position 54 degrees 59.0 min. North - 010 degrees 28.9 min. East is unlit.'),
  ('cd23a713-ca96-4566-92cf-856b650f8527', 'Water depths down to 2m have been observed in position 54 degrees 50.9 min. North - 012 degrees 11.6 min. East.
 Mariners are advised to keep well clear.'),
  ('d05b67a3-26b8-4874-9e29-0b384c01517f', 'Water depths down to 1.6m have been observed in position 55 degrees 08.7 min. North - 010 degrees 57.4 min. East.
 Mariners are advised to keep well clear.'),
  ('d566e6cc-ad79-4b92-a477-100ddbe531b2', 'The south cardinal buoy in position 55 degrees 02.0 min. North - 010 degrees 27.4 min. East is missing.'),
  ('d7304651-300e-4743-ac00-233e82b391d0', 'The buoys in the channel between position 55 degrees 03.2 min. North - 008 degrees 30.3 min. East and 55 degrees 05.2 min. North 
- 008 degrees 34.5 min. East can not be expected to be on station.'),
  ('d8c02bb8-3fee-4078-8655-fecae8faf790', 'A mast with sails has sunk in position 55 degrees 01.7 min. North - 010 degrees 27.4 min. East. The depth above 
the mast and sail is unknown. The mast is marked with a white fender.
 Mariners are advised to keep well clear.'),
  ('e51e6f22-b662-4a74-b3e2-b69d9a5bb1c2', 'The port hand light buoy in position 55 degrees 40.1 min. North - 011 degrees 05.0 min. East is unlit.'),
  ('e62132d6-bfb4-497a-a9b6-7b3ab86d18b6', 'A yellow buoy has been observed in position 56 degrees 42.1 min. North - 008 degrees 49.1 min. East.'),
  ('e7628b56-ddc4-4e9b-9b7c-80320a7cdf1d', 'A red buoy has been observed adrift in position 56 degrees 02.2 min. North - 010 degrees 40.5 min. East the 6th 
July 2026 at 0830 UTC.
Cancel this warning the 6th July 2026 at 2030 UTC.'),
  ('f6f2ed3d-f1e5-41f9-b8ce-4be4a379e8fc', 'Due to ice, the floating buoyage in Augustenborg Fjord at position 54 degrees 57.9 min. North - 
009 degrees 49.0 min. East can not be expected to be on station.')
  -- </AUDIO>
;
DELETE FROM english_audio WHERE uid IS NULL;
WITH u AS (
    UPDATE public."AudioMessagePromulgation" a SET text = v.text
      FROM public."BaseMessagePromulgation" b, public."Message" m, english_audio v
     WHERE a.id = b.id AND b.message_id = m.id AND m.uid = v.uid
       AND a.text IS DISTINCT FROM v.text AND guides.looks_danish(a.text)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'audio texts: Danish texts replaced', count(*) FROM u;

-- The audio texts of the messages that are no longer live (4,826 cancelled, 591 expired and 419 deleted navigational
-- warnings): blanked where they are Danish. The editor's status pages show them; no English text exists for them, and
-- the generator works on live messages only.
WITH u AS (
    UPDATE public."AudioMessagePromulgation" a SET text = ''
      FROM public."BaseMessagePromulgation" b, public."Message" m
     WHERE a.id = b.id AND b.message_id = m.id AND m.status NOT IN ('DRAFT', 'VERIFIED', 'PUBLISHED')
       AND coalesce(a.text, '') <> '' AND guides.looks_danish(a.text)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'audio texts: Danish texts of messages no longer live blanked', count(*) FROM u;

-- ============================================================================================================
-- Phrase rules (the second check, 2026-11-11): Danish words and phrases in English message texts that recur, each
-- replaced wherever it stands in an English title, vicinity, source, part subject or details, reference description or
-- attachment caption, by a pattern with word boundaries. Kept in guides.english_phrase because english-history.mjs
-- applies the same rules to the English entries of the message history snapshots.
-- ============================================================================================================
CREATE TABLE IF NOT EXISTS guides.english_phrase (n int PRIMARY KEY, pattern text NOT NULL, repl text NOT NULL, why text NOT NULL);
TRUNCATE guides.english_phrase;
INSERT INTO guides.english_phrase VALUES
  (1, '\mperiode\M', 'period', '''In the periode'', ''the time periode'' (some 40 notices)'),
  (2, '\mInspektion\M', 'Inspection', '''Inspektion of pipeline'''),
  (3, '\minspektion\M', 'inspection', '''Diving inspektion'', ''inspektion will be carried out'''),
  (4, '\mGeotekniske boringer\M', 'Geotechnical drillings', 'a title and subject (NM-332-20)'),
  (5, '\mMindre dybde\M', 'Reduced depth', 'titles and subjects (NM-769-26 and others)'),
  (6, '\mdrillings afsluttet\M', 'drillings completed', 'NM-909-23'),
  (7, '\m([NSEW])-[Kk]yst\M', '\1-coast', '''Greenland. E-kyst, Carlsberg Fjord'', ''W-kyst'', ''Sjællands N-kyst'' (as ''E-coast'' elsewhere)'),
  (8, '\mBro lukning\M', 'Bridge closure', 'a title and subject'),
  (9, '\minoperativ\M', 'inoperative', '''NAVTEX transmitter inoperativ'''),
  (10, '\mStart-dato\M', 'Start date', 'a table heading'),
  (11, '\mSlut-dato\M', 'End date', 'a table heading'),
  (12, '\mTidsinterval\M', 'Time interval', 'a table heading'),
  (13, '\mIndtil (\d|week\M)', 'Until \1', '''Indtil 1 January 2021'', ''Indtil week 32'''),
  (14, '\mvinter (season|condition)\M', 'winter \1', '''a vinter season'', ''the resent vinter condition'''),
  (15, '\mVinter season\M', 'Winter season', '''Vinter season: 1 November - 31 March'''),
  (16, '"Generelle bestemmelser"', '"General provisions"', 'a quoted section of a bridge regulation'),
  (17, '"General provisions" pkt\.', '"General provisions" item', 'pkt. (punkt) is item'),
  (18, '\mClosure og fiord\M', 'Closure of fiord', 'a Greenland notice''s subject: a typo of ''of'''),
  (19, '\mDeploying og floating\M', 'Deploying of floating', 'a title and subject: a typo of ''of'''),
  (20, '\mNorth og Fynen\M', 'North of Funen', 'a title: a typo of ''of'''),
  (21, '\mvil be\M', 'will be', '''anchors vil be deployed'', ''stones vil be deployed'''),
  (22, '\mVHF kanal\M', 'VHF channel', '''contacted on VHF kanal 16'' (draft 734627 and some 25 others)'),
  (23, '\mLOKAL TIME\M', 'LOCAL TIME', 'Greenland''s firing exercises (GL-043-23 and others)'),
  (24, '\m[Ll]okal time\M', 'local time', '''between 1030 - 1230 Lokal time'''),
  (25, '\mMASCHIN GUN\M', 'MACHINE GUN', 'GL-043-23'),
  (26, '\mVirtuel\M', 'Virtual', '''Special Mark Virtuel'', ''Virtuel AtoN'''),
  (27, '\mvirtuel\M', 'virtual', '''A virtuel AIS AtoN'''),
  (28, '\mplatformen\M', 'the platform', '''platformen Maersk Resolve'''),
  (29, '\mFyr slukket\M', 'Light unlit', 'titles and subjects'),
  (30, '\mdato og tid\M', 'date and time', '''(dato og tid (UTC))'' in the ice-report notices'),
  (31, '([0-9]''E) kyst\M', '\1 coast', 'a beacon''s place in a list of positions'),
  (32, '\m([NSEW]{1,2}) og ([NSEW]{1,2})\M', '\1 and \2', '''Bornholm E og S'', ''Saltholm NW og SE'', ''Gniben E og W'''),
  (33, '\mobservationspost Syd\M', 'observation post South', 'the firing areas'' signal masts (some 2,470 messages): the southern observation post (Syd is the Danish word for south; the fourth check, 2026-11-11)'),
  -- The third check (2026-11-11): single Danish words and phrases in English titles and subjects, the almanac's two
  -- words in every version of its parts (the live parts were corrected by id before), and the positions' names.
  (34, '(A/S - )se (<a)', '\1see \2', 'the almanac''s ship chandlers: ''A/S - se <a href ...www.weilbach...'' (9 parts, A/26 2017-2025)'),
  (35, '<p>Vest, north of', '<p>West, north of', 'the almanac''s table of Greenland''s coasts (9 parts, A/19 2019-2025)'),
  (36, '\mVrag\M', 'Wreck', '''Denmark. The Sound. Sundby Hage. Vrag.'' (NM-756-26 and some 15 others), ''Vrag'' as a position''s name'),
  (37, '\mKabelarbejde\M', 'Cable work', 'titles and subjects (NM-103-22 and some 10 others)'),
  (38, '\mGrundstødt pram\M', 'Barge aground', 'a legacy navigational warning''s title and subject'),
  (39, '\mVedligeholdelsesarbejde\M', 'Maintenance work', '''Snaptun Havn. Vedligeholdelsesarbejde.'''),
  (40, '\mReduceret vanddyde\M', 'Reduced depth', '''Horsens Harbour. Reduceret vanddyde.'''),
  (41, '\mUndervandshindring\M', 'Underwater obstruction', '''The North Sea. Central Part. Undervandshindring.'''),
  (42, '\mLukket for passage\M', 'Closed for passage', 'a subject'),
  (43, '\mobserveret\M', 'observed', '''Red buoy observeret'''),
  (44, '\mobjekt\M', 'object', '''Deploying of test objekt'''),
  (45, '\mSkydeområde (E[KS])\M', 'Firing area \1', '''Skydeområde EK R 80 Kallesmærsk E'' in titles'),
  (46, '\mN-lige del\M', 'Northern Part', 'as the chart titles'),
  (47, '\mS-lige del\M', 'Southern Part', '''Little Belt. S-lige del.'''),
  (48, '\mE-lige del\M', 'Eastern Part', 'as the chart titles'),
  (49, '\mW-lige del\M', 'Western Part', 'as the chart titles'),
  (50, '^Danmark\. ', 'Denmark. ', 'a title''s first word, the country''s Danish name'),
  (51, '\mBassin (\d+)\M', 'Basin \1', '''Bassin 11'', ''Bassin 3'' (as NM-412-26 by its row)'),
  (52, '\m(\d+)\. bassin\M', 'Basin \1', '''1. bassin'', ''5. bassin'''),
  (53, '\mSundets nordlige overgangspunkt\M', 'the Sound''s northern crossing point', 'a position''s name (NM draft 2596620)'),
  (54, '\mSundets sydlige overgangspunkt\M', 'the Sound''s southern crossing point', 'a position''s name (NM draft 2596620)'),
  -- The fourth check (2026-11-11), a reading of every live message. 'EfS', the Danish abbreviation of the Notices to
  -- Mariners, is 'NtM', as in the dictionaries and the publications, except where a text spells the Danish title out
  -- or explains the abbreviation: '(EfS)', 'EfS (Danish NtM)', and A/1's list of abbreviations ('EfS' before
  -- 'Efterretninger for Søfarende'). A stored file name is in no column these rules read.
  (55, '\mEfS A\. General\M', 'NtM Almanac. General', '''EfS A. General information'', the almanac''s title as the publication''s (NtM Almanac)'),
  (56, '\mEfS\M(?!\)| \(Danish NtM\)|</td>\s*<td>Efterretninger| Efterretninger)', 'NtM', '''Explanatory notes to EfS.'', ''announced in EfS'' (A/2, A/3, A/4, A/8, A/9, A/22 2026 and some 150 older texts)'),
  (57, '\mSkydeomr(å|&aring;)de Signalmast\M', 'Firing Area signal mast', '''Nymindegab Skydeområde Signalmast S'' (NM-657-26), ''Oksbøl Skydeområde Signalmast N'': Danish words beside the place, not a name'),
  (58, '\mSkydeterr(æ|&aelig;)n\M', 'Firing Range', '''Tranum Skydeterræn'' (FA/EK-D-370 2026, FA/EK-R-48 2026 and its exercise)'),
  (59, 'Firing Safety Unit at Oksb(ø|&oslash;)l Kaserne\M', 'Firing Safety Element in Camp Oksb\1l', 'FA/EK-R-44 2026, as the other firing areas name the office'),
  (60, '\mflashes pr\. minute\M', 'flashes per minute', 'FA/15 2026: pr. is the Danish abbreviation of per'),
  (61, '\mKaj (\d)', 'Quay \1', '''Korsør Havn. Kaj 210'' (NM-042-21), ''Fredericia Havn. Kaj 42'''),
  (62, '\mFeltet\M', 'Field', '''Harald Feltet'' (NM-808-26), ''Kraka Feltet'''),
  (63, '\mGasfelt\M', 'Gas Field', '''Tyra Gasfelt'' (NM-808-26 and some 10 older ones)'),
  (64, '\mOliefelt\M', 'Oil Field', '''Valdemar Oliefelt SW'', ''Dan Oliefelt E'' (two NM drafts)'),
  (65, '\mFovrfelt grunde\M', 'Fovrfelt Ground', 'NM-658-26, as the copy''s other notices name the place'),
  (66, '(^|\. )Jernbanebroen over Limfjorden\M', '\1The railway bridge across the Liim Fiord', 'NM-711-26''s title and vicinity, a position''s name: as three other notices write it'),
  (67, '\mJernbanebroen over Limfjorden\M', 'the railway bridge across the Liim Fiord', 'the same inside a sentence'),
  (68, '\mOverblik\M', 'Overview', '''Femern Overblik'' (an NM draft)'),
  (69, '\marbejdshavn\M', 'work harbour', '''Femern arbejdshavn'' (an NM draft''s position table)'),
  (70, '\mydre bagfyr\M', 'outer rear light', '''Qeqertarsuaq ydre bagfyr (Godhavn)'' (an NM draft); in capitals it is the List of Lights'' name and stays'),
  (71, '\mydre forfyr\M', 'outer front light', 'the same draft'),
  (72, '\mbagfyr\M', 'rear light', '''Grønnedal NW bagfyr, F.R'' (an NM draft): in lower case a common noun'),
  (73, '\mforfyr\M', 'front light', '''Grønnedal NW forfyr, F.R'''),
  (74, '\mAnkerm(æ|&aelig;)rkefyt\M', 'anchorage mark light', '''Grønnedal Ankermærkefyt, F.G'', a misspelt common noun beside the two above'),
  (75, '\mtrafiksignal\M', 'traffic signal', '''Allinge Havn trafiksignal'' (an NM draft), as its expired copies write it'),
  -- The fifth check (2026-11-11), the history of the live messages: older English versions whose Danish words the
  -- current texts no longer hold (the editor's History page shows them; english-history.mjs applies these rules).
  (76, '(\d\)) og (\d+\))', '\1 and \2', '''positions 1) - 4) og 5) - 8)'' (NM-737-26''s older versions)'),
  (77, '(''E) og (pos\.)', '\1 and \2', 'og between a longitude and the next position (an NM draft''s older versions)'),
  (78, '\mchannel 16 og phone\M', 'channel 16 or phone', '''VHF channel 16 og phone'' (an NM draft''s older versions)'),
  (79, '(\+45 [0-9 ]+) og (\+45)', '\1 and \2', '''tel. +45 72196040 og +45 7285 0370'' (A/7 2026''s older versions)'),
  (80, '\mQimusseriarsuaq og (\d)', 'Qimusseriarsuaq and \1', '''13. Qimusseriarsuaq og 14. Kiatak'' (A/16 2026''s older versions)'),
  (81, '</a> og <a ', '</a> and <a ', 'two links joined by the Danish ''og'' (A/11 2026''s older versions)'),
  (82, '\mTlf\.', 'Tel.', '''Tlf. 3915 7315'', ''Tlf.: +299 364000'' (A/17, A/18 and A/20 2026''s older versions)'),
  (83, '\mKronl(ø|&oslash;)b N-lige, N-enden af Marmorkaj\M', 'Kronl\1bet, N side, N end of Marmorkaj', 'an NM draft''s light table, as its current text writes it'),
  (84, '\mKronl(ø|&oslash;)b S-lige, Marmorkaj\M', 'Kronl\1bet, S side, Marmorkaj', 'the same table''s next row'),
  (85, '\mwith af draft\M', 'with a draft', '''vessels with af draft of more than 8.8m'' (an NM draft''s older versions)'),
  (86, '\mKerteminde Marina\.? N-lige d(æ|&aelig;)kmoles hoved\M', 'Kerteminde Marina, head of the northern breakwater', 'an NM draft''s light table in its older versions, as its current text and the feature rule write it (found by english-history.mjs''s self-check)'),
  (87, '\mKerteminde Marina B(ø|&oslash;)lgebryderens N-ende\M', 'Kerteminde Marina, northern end of the breakwater', 'the same table''s next row'),
  -- The sixth check (2026-11-11): older versions of four published NMs, which the editor's History page shows.
  (88, '\mAtoN oprettet\M', 'AtoN established', '''Cable Works. Virtual AtoN oprettet.'' (NM-728-26''s older versions)'),
  (89, '\mof USV''en(\s|&nbsp;)', 'of the USV\1', '''Periodic presence of USV''''en Sentinel'' (NM-674-25''s older versions), as its current text writes it'),
  (90, '\mIndsejlingen Randers Fjrod\M', 'Approach to Randers Fjord', '''Indsejlingen Randers Fjrod'' (NM-419-25''s older versions)'),
  (91, '\mKlapplads\M', 'Dumping ground', '''Langelandsbæltet. Klapplads.'' (NM-617-25''s older versions); the copy writes ''Dumping ground'' elsewhere'),
  -- The picture review (2026-11-12): English misspellings in the test data's own texts, which a public manual's
  -- pictures would repeat. They are not Danish; they are put right in the copy only.
  (92, '\mContstruction\M', 'Construction', 'a Faroe Islands NM shown as a sample notice'),
  (93, '\mprohibited aread\M', 'prohibited areas', 'NM-053-26: ''Two buoyed prohibited aread are established'''),
  (94, '\mcontinuosly\M', 'continuously', 'NM-053-26: ''being moved continuosly as required'''),
  (95, '\mChannelsmoved\M', 'Channels moved', 'NM-776-24''s title'),
  (96, '\marround\M', 'around', 'NM-510-26');

CREATE FUNCTION pg_temp.english_phrases(t text) RETURNS text LANGUAGE plpgsql STABLE AS $f$
DECLARE
    r record;
BEGIN
    IF t IS NULL THEN RETURN NULL; END IF;
    FOR r IN SELECT pattern, repl FROM guides.english_phrase ORDER BY n LOOP
        t := regexp_replace(t, r.pattern, r.repl, 'g');
    END LOOP;
    RETURN t;
END
$f$;

-- One pattern for the whole list, to find the rows worth rewriting (no pattern uses a back-reference).
CREATE TEMP TABLE english_phrase_any ON COMMIT DROP AS
SELECT '(' || string_agg(pattern, ')|(' ORDER BY n) || ')' AS pattern FROM guides.english_phrase;

DO $$
DECLARE
    r record;
    any_pattern text := (SELECT pattern FROM english_phrase_any);
    c int;
    total int := 0;
BEGIN
    FOR r IN SELECT * FROM (VALUES ('MessageDesc', 'title'), ('MessageDesc', 'vicinity'), ('MessageDesc', 'source'),
                                   ('MessagePartDesc', 'subject'), ('MessagePartDesc', 'details'),
                                   ('ReferenceDesc', 'description'), ('AttachmentDesc', 'caption')) v (tbl, col) LOOP
        EXECUTE format('UPDATE public.%I SET %I = pg_temp.english_phrases(%I) WHERE lang = ''en'' AND %I ~ $1 '
                       || 'AND %I IS DISTINCT FROM pg_temp.english_phrases(%I)', r.tbl, r.col, r.col, r.col, r.col, r.col)
            USING any_pattern;
        GET DIAGNOSTICS c = ROW_COUNT;
        total := total + c;
    END LOOP;
    INSERT INTO english_counts (grp, rows) VALUES ('messages: phrase rules', total);
END $$;

-- ============================================================================================================
-- The positions' names (the third check, 2026-11-11): a message part's geometry is a FeatureCollection of Features,
-- whose properties (JSON text) name a feature ('name:<lang>') and each of its points ('name:<n>:<lang>'). The editor's
-- Positions rows, the history, the details dialog and the maps show them; the administration app shows the English key,
-- else the feature's own language's, else any (message-geometry.service.ts, findCoordName), so a point named only in
-- Danish shows its Danish name. Every name gets an English key where it has only another language's, and every
-- English key gets the rules below, then the phrase rules above. A position's name that is a name (an AtoN of the List
-- of Lights, 'Rønne Havn Forfyr', 'Skagen N-lige indermole') stays; a description in Danish is translated in the
-- English words the copy's other positions use ('yellow spar buoy with cross topmark', 'coast', 'wreck').
-- ============================================================================================================
CREATE TABLE IF NOT EXISTS guides.english_feature_rule (n int PRIMARY KEY, pattern text NOT NULL, repl text NOT NULL, why text NOT NULL);
TRUNCATE guides.english_feature_rule;
INSERT INTO guides.english_feature_rule VALUES
  (1, '^grøn lystønde$', 'green light buoy', 'NW draft 2475799 (uid 5f753a3a-...)'),
  (2, '^rød lystønde$', 'red light buoy', 'NW draft 2475799'),
  (3, '^grøn$', 'green', 'NW draft 2475799'),
  (4, '^rød$', 'red', 'NW draft 2475799'),
  (5, '^usikker$', 'uncertain', 'NM draft 619900, only in the Danish key'),
  (6, '^Udlagt$', 'Deployed', 'NM draft 915901'),
  (7, '^Planlagt$', 'Planned', 'NM draft 915901'),
  (8, '^kyst$', 'coast', 'some 15 cancelled and expired notices (''coast'' in 500 others)'),
  (9, '^kaj$', 'quay', 'three notices (''quay'' in 150 others)'),
  (10, '^bulk kaj\.$', 'bulk quay.', 'two notices'),
  (11, '^bagbåke$', 'rear beacon', 'a notice'),
  (12, '^forfyr$', 'front light', 'a notice'),
  (13, '^bagfyr$', 'rear light', 'a notice'),
  (14, '^midtfarvandsafmærkning$', 'safe water mark', 'a notice'),
  (15, '^ny$', 'new', 'a notice'),
  (16, '^Måleudstyr$', 'Measuring equipment', 'two notices'),
  (17, '^Rødt blink \+ skilt$', 'Red flashing light + sign', 'a draft'),
  (18, '^Grønt blink$', 'Green flashing light', 'a draft'),
  (19, '^13m uddybningsområde\.$', '13m dredging area.', 'four notices'),
  (20, '^Område ([NS]), Area \1$', 'Area \1', 'four notices: the Danish and the English name in one'),
  (21, '^N-lige signalmast\.$', 'Northern signal mast.', 'the firing areas'' northern signal mast, published among them (as ''Northern signal mast.'' elsewhere)'),
  (22, '^S-lige molehoved\.$', 'Southern mole head.', 'eight cancelled notices'),
  (23, '^Jylland NW-kyst - S-kyst, Norge\.$', 'Jutland NW coast - S coast, Norway.', 'a deleted notice'),
  (24, '^Jammerbugt NNW, dansk-norsk grænse\.$', 'Jammerbugt NNW, Danish-Norwegian border.', 'a deleted notice'),
  (25, '^N-S-gående bælte ud for Jyllands W-kyst\.$', 'N-S belt off the W coast of Jutland.', 'a deleted notice'),
  (26, '^Molefyr, Fl\.G\.3s Mole head ligt, Fl\.G\.3s$', 'Mole head light, Fl.G.3s', 'the Danish and the English name in one'),
  (27, '^Kerteminde Marina\.? N-lige dækmoles hoved$', 'Kerteminde Marina, head of the northern breakwater', 'a draft and a notice (as section 5 of the list for Denmark)'),
  (28, '^Kerteminde Marina Bølgebryderens N-ende$', 'Kerteminde Marina, northern end of the breakwater', 'a draft'),
  (29, '^E-kardinal stage$', 'E-cardinal spar buoy', 'a notice'),
  (30, '^gul stage med krydstopbetegnelse', 'yellow spar buoy with cross topmark', 'some 10 notices (601 others write it in English)'),
  (31, '^gul stage med topbetegnelse', 'yellow spar buoy with topmark', 'a notice'),
  (32, '^grøn stage med topbetegnelse', 'green spar buoy with topmark', 'some 5 notices'),
  (33, '^rød stage med topbetegnelse', 'red spar buoy with topmark', 'three notices'),
  (34, '^Gul stage', 'Yellow spar buoy', 'two notices'),
  (35, '^Vrag$', 'Wreck', 'NM draft 1328589: eleven positions, only in the Danish key'),
  (36, '^S-lige signalmast\.$', 'Southern signal mast.', 'the firing areas'' southern signal mast, published among them'),
  (37, '^Signalmast\.$', 'Signal mast.', 'FA/11 2026 and 275 cancelled or expired firing areas and exercises (the fourth check, 2026-11-11)'),
  (38, '^Signalmast (\d)', 'Signal mast \1', 'a numbered signal mast''s name'),
  -- The fifth check (2026-11-11): the English names of older versions of live messages (the editor's History page).
  (39, '^rød spirtønde med topbetegnelse$', 'red spar buoy with topmark', 'an NM draft''s older versions'),
  (40, '^grøn spirtønde med topbetegnelse$', 'green spar buoy with topmark', 'an NM draft''s older versions'),
  (41, '^gul$', 'yellow', 'NM-403-25''s older versions');

CREATE FUNCTION pg_temp.english_feature(t text) RETURNS text LANGUAGE plpgsql STABLE AS $f$
DECLARE
    r record;
BEGIN
    IF t IS NULL THEN RETURN NULL; END IF;
    FOR r IN SELECT pattern, repl FROM guides.english_feature_rule ORDER BY n LOOP
        t := regexp_replace(t, r.pattern, r.repl);
    END LOOP;
    RETURN pg_temp.english_phrases(t);
END
$f$;

-- Every feature's properties with an English key for each name that has none (the value of the key the app would show:
-- the feature's own language's, else the first) and the English keys given the rules.
CREATE FUNCTION pg_temp.english_properties(p text) RETURNS text LANGUAGE plpgsql STABLE AS $f$
DECLARE
    j jsonb;
    out jsonb;
    k record;
    en_key text;
    src text;
BEGIN
    IF p IS NULL OR p !~ '"name' THEN RETURN p; END IF;
    j := p::jsonb;
    out := j;
    FOR k IN SELECT DISTINCT substring(key FROM '^(name(?::\d+)?):[A-Za-z]+$') AS slot
               FROM jsonb_object_keys(j) key WHERE key ~ '^name(:\d+)?:[A-Za-z]+$' LOOP
        en_key := k.slot || ':en';
        IF out ? en_key THEN
            out := jsonb_set(out, ARRAY[en_key], to_jsonb(pg_temp.english_feature(out ->> en_key)));
        ELSE
            src := coalesce(j ->> (k.slot || ':' || coalesce(j ->> 'language', '')),
                            (SELECT value FROM jsonb_each_text(j) WHERE key ~ ('^' || k.slot || ':[A-Za-z]+$')
                              ORDER BY key LIMIT 1));
            IF coalesce(src, '') <> '' THEN
                out := out || jsonb_build_object(en_key, pg_temp.english_feature(src));
            END IF;
        END IF;
    END LOOP;
    IF out = j THEN RETURN p; END IF;
    RETURN out::text;
END
$f$;

WITH u AS (
    UPDATE public."Feature" SET properties = pg_temp.english_properties(properties)
     WHERE properties ~ '"name' AND properties IS DISTINCT FROM pg_temp.english_properties(properties)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'positions: names made English (features)', count(*) FROM u;

-- ============================================================================================================
-- NAVTEX texts (the third check, 2026-11-11): Danish words beside the buoys' names in the NAVTEX texts of cancelled and
-- deleted warnings, which the editor's status page and promulgation panel show ('SAFE WATER LIGHT BUOY BØJE 5', 'PORT
-- HAND LIGHT BUOY RØD BØJE NR. 16', 'LIGHT 7504 OG INTERNATIONALT L5660'). Kept in guides.english_navtex for
-- english-history.mjs.
-- ============================================================================================================
CREATE TABLE IF NOT EXISTS guides.english_navtex (n int PRIMARY KEY, pattern text NOT NULL, repl text NOT NULL, why text NOT NULL);
TRUNCATE guides.english_navtex;
INSERT INTO guides.english_navtex VALUES
  (1, '\mBUOY RØD BØJE\M', 'BUOY', 'NW-372-23, NW-399-23: ''PORT HAND LIGHT BUOY RØD BØJE NR. 16'''),
  (2, '\mBUOY BØJE\M', 'BUOY', 'NW-104-20, NW-296-24, NW-331-25 and others: ''SAFE WATER LIGHT BUOY BØJE 5'''),
  (3, '\mRØD BØJE\M', 'RED BUOY', 'any other'),
  (4, '\mrød bøje\M', 'red buoy', 'a deleted warning whose NAVTEX text is only that'),
  (5, '\mMIDTFARVANDSBØJE\M', 'SAFE WATER BUOY', 'a deleted warning'),
  (6, '\mBØJE\M', 'BUOY', 'NW-241-22: ''LIGHT BØJE NR 28'''),
  (7, '\mNR\.?\s*(\d)', 'NO. \1', '''BØJE NR.16'', ''LIGHT BUOY NR. 22'': the Danish abbreviation of number'),
  (8, '\mOG INTERNATIONALT\M', 'AND INTERNATIONAL', 'GL-053-25'),
  (9, '\mOG\M', 'AND', 'NW-267-23 and a deleted copy: ''V7A2093 OG OCEAN MARLIN'''),
  (10, '\m(OP|UP)(\s+)TIL\s+(\d)', 'UP\2TO \3', 'GL-021-25 ''HIGHT OP TIL 11700 METER'', GL-039-26 ''UP TIL 100 X 1000 METERS'' (a line break between UP and TIL, which the line keeps)');

CREATE FUNCTION pg_temp.english_navtex(t text) RETURNS text LANGUAGE plpgsql STABLE AS $f$
DECLARE
    r record;
BEGIN
    IF t IS NULL THEN RETURN NULL; END IF;
    FOR r IN SELECT pattern, repl FROM guides.english_navtex ORDER BY n LOOP
        t := regexp_replace(t, r.pattern, r.repl, 'g');
    END LOOP;
    RETURN t;
END
$f$;

WITH u AS (
    UPDATE public."NavtexMessagePromulgation" SET text = pg_temp.english_navtex(text)
     WHERE text IS DISTINCT FROM pg_temp.english_navtex(text)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'NAVTEX texts: Danish words translated', count(*) FROM u;

-- The whole English values the rows above set, for english-history.mjs: an old snapshot that holds the old value as
-- a whole field takes the new one.
CREATE TABLE IF NOT EXISTS guides.english_value (old_value text PRIMARY KEY, new_value text NOT NULL);
TRUNCATE guides.english_value;
INSERT INTO guides.english_value SELECT DISTINCT ON (old_value) old_value, new_value FROM english_msg_set ORDER BY old_value;

-- ============================================================================================================
-- The Danish texts kept for the rig's guard, then taken out of the editors that show every stored language
-- ============================================================================================================

-- The administration app's editors of sources, mailing lists (and their triggers), template parameters and dictionary
-- entries show every language a row is stored in, beyond modelLanguages (localized-descs.ts, padAndOrderDescs): a
-- Danish input under the English one. Those Danish rows are deleted below; first their values are kept here, with
-- every Danish dictionary value and the Danish mail subjects, for the rig's guard (support/guard.ts), which refuses a
-- picture that shows one of them; the publications' Danish rows, deleted below too, are kept here the same way. The
-- view guides.danish_text adds the Danish rows the copy keeps (areas, categories, messages: shown in English only, but
-- the guard's index of what Danish looks like).
CREATE TABLE IF NOT EXISTS guides.danish_saved (source text NOT NULL, key text NOT NULL, en text, da text NOT NULL,
                                                PRIMARY KEY (source, key));
INSERT INTO guides.danish_saved
SELECT 'dictionary:' || d.name, e.dict_key, en.value, da.value
  FROM public."DictionaryEntry" e
  JOIN public."Dictionary" d ON d.id = e.dictionary_id
  JOIN public."DictionaryEntryDesc" da ON da.entity_id = e.id AND da.lang = 'da'
  LEFT JOIN public."DictionaryEntryDesc" en ON en.entity_id = e.id AND en.lang = 'en'
 WHERE coalesce(da.value, '') <> ''
UNION ALL
SELECT 'SourceDesc.name', da.entity_id::text, en.name, da.name
  FROM public."SourceDesc" da LEFT JOIN public."SourceDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.name, '') <> ''
UNION ALL
SELECT 'MailingListDesc.name', da.entity_id::text, en.name, da.name
  FROM public."MailingListDesc" da LEFT JOIN public."MailingListDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.name, '') <> ''
UNION ALL
SELECT 'MailingListDesc.description', da.entity_id::text, en.description, da.description
  FROM public."MailingListDesc" da LEFT JOIN public."MailingListDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.description, '') <> ''
UNION ALL
SELECT 'MailingListTriggerDesc.subject', da.entity_id::text, en.subject, da.subject
  FROM public."MailingListTriggerDesc" da
  LEFT JOIN public."MailingListTriggerDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.subject, '') <> ''
UNION ALL
SELECT 'TemplateParamDesc.name', da.entity_id::text, en.name, da.name
  FROM public."TemplateParamDesc" da LEFT JOIN public."TemplateParamDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.name, '') <> ''
UNION ALL
SELECT DISTINCT 'ScheduledMail.subject', subject, NULL, subject
  FROM public."ScheduledMail" WHERE guides.looks_danish(subject)
-- The publications' Danish rows, deleted below ("Publications in English only"); the view read them live before.
UNION ALL
SELECT 'PublicationDesc.title', da.entity_id::text, en.title, da.title
  FROM public."PublicationDesc" da LEFT JOIN public."PublicationDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.title, '') <> ''
UNION ALL
SELECT 'PublicationSeriesDesc.name', da.entity_id::text, en.name, da.name
  FROM public."PublicationSeriesDesc" da
  LEFT JOIN public."PublicationSeriesDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.name, '') <> ''
UNION ALL
SELECT 'PublicationIssueDesc.name', da.entity_id::text, en.name, da.name
  FROM public."PublicationIssueDesc" da
  LEFT JOIN public."PublicationIssueDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.name, '') <> ''
UNION ALL
SELECT 'PublicationCategoryDesc.name', da.entity_id::text, en.name, da.name
  FROM public."PublicationCategoryDesc" da
  LEFT JOIN public."PublicationCategoryDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND coalesce(da.name, '') <> ''
ON CONFLICT (source, key) DO NOTHING;

CREATE OR REPLACE VIEW guides.danish_text AS
SELECT source, en, da FROM guides.danish_saved
UNION ALL
SELECT 'AreaDesc.name', en.name, da.name FROM public."AreaDesc" da
  LEFT JOIN public."AreaDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en' WHERE da.lang = 'da'
UNION ALL
SELECT 'CategoryDesc.name', en.name, da.name FROM public."CategoryDesc" da
  LEFT JOIN public."CategoryDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en' WHERE da.lang = 'da'
UNION ALL
SELECT 'ReferenceDesc.description', en.description, da.description FROM public."ReferenceDesc" da
  LEFT JOIN public."ReferenceDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en' WHERE da.lang = 'da'
UNION ALL
SELECT 'AttachmentDesc.caption', en.caption, da.caption FROM public."AttachmentDesc" da
  LEFT JOIN public."AttachmentDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en' WHERE da.lang = 'da'
UNION ALL
SELECT 'MessageDesc.' || v.col, v.en, v.da FROM public."MessageDesc" da
  LEFT JOIN public."MessageDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 CROSS JOIN LATERAL (VALUES ('title', en.title, da.title), ('vicinity', en.vicinity, da.vicinity),
                            ('source', en.source, da.source)) v (col, en, da)
 WHERE da.lang = 'da' AND length(v.da) BETWEEN 1 AND 300
UNION ALL
SELECT 'MessagePartDesc.subject', en.subject, da.subject FROM public."MessagePartDesc" da
  LEFT JOIN public."MessagePartDesc" en ON en.entity_id = da.entity_id AND en.lang = 'en'
 WHERE da.lang = 'da' AND length(da.subject) BETWEEN 1 AND 300;

-- The audio broadcast list's daily mail had only a Danish subject: an English one after the list's English name.
WITH u AS (
    INSERT INTO public."MailingListTriggerDesc" (id, lang, subject, entity_id)
    SELECT nextval('public."MailingListTriggerDesc_SEQ"'), 'en', 'Navigational Warnings for Voice Broadcast ${date-short}', t.id
      FROM public."MailingListTrigger" t
     WHERE t.id = 1949666
       AND NOT EXISTS (SELECT 1 FROM public."MailingListTriggerDesc" d WHERE d.entity_id = t.id AND d.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'mailing-list triggers: English subject inserted', count(*) FROM u;

WITH u AS (DELETE FROM public."SourceDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'sources: Danish rows deleted', count(*) FROM u;
WITH u AS (DELETE FROM public."MailingListDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'mailing lists: Danish rows deleted', count(*) FROM u;
WITH u AS (DELETE FROM public."MailingListTriggerDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'mailing-list triggers: Danish rows deleted', count(*) FROM u;
WITH u AS (DELETE FROM public."TemplateParamDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'template parameters: Danish rows deleted', count(*) FROM u;
WITH u AS (DELETE FROM public."DictionaryEntryDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'dictionaries: Danish rows deleted', count(*) FROM u;

-- ============================================================================================================
-- Publications in English only (after the admin manual's checks, 2026-11-12). The test data's publication series
-- declare the languages da and en, and every series, issue, legacy publication and publication category holds a
-- Danish row beside the English one. The installation configures English only (modelLanguages), so the series
-- settings drawer showed a DA box with every Danish name and pattern ('Ugentlig EfS', 'EfS uge ${week} - ${year}',
-- 'EfS-Uge-${week}-${year}.pdf') and the warning that the series declares a language the installation no longer
-- configures; an issue showed the same. The publications model keeps its languages in these rows:
--   PublicationSeries_languages   the series' declared languages (an ordered list, indexNo from 0)
--   PublicationSeriesDesc         the series' name, name pattern, file-name pattern, link and reference format
--   PublicationIssueDesc          an issue's name, file name, file and link, per language
--   PublicationDesc               the legacy publications' titles, file names and links (the editor's old references)
--   PublicationCategoryDesc       the categories' names and descriptions
--   IssueAuditEntry.detail        the names a deleted issue had, by language (two DELETED entries)
-- A row that has an English row beside it is deleted; a Danish row with no English one becomes the English row (its
-- name through the publications' rule above); each series then declares ['en'] alone. The Danish values are kept in
-- guides.danish_saved above for the rig's guard. A file both languages pointed at (a series whose issues are not
-- language-specific, such as the ice-service annex) stays on the English row; the Danish files of the other series
-- stay in the repository, unlisted. The backend reads series and issues per request; start the rig again all the same.
-- ============================================================================================================
WITH u AS (
    UPDATE public."PublicationSeriesDesc" da
       SET lang = 'en', name = pg_temp.english_publication(name),
           "nameSuggestionPattern" = pg_temp.english_publication("nameSuggestionPattern")
     WHERE da.lang = 'da'
       AND NOT EXISTS (SELECT 1 FROM public."PublicationSeriesDesc" en WHERE en.entity_id = da.entity_id AND en.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: Danish-only rows made English', count(*) FROM u;
WITH u AS (DELETE FROM public."PublicationSeriesDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: Danish rows deleted', count(*) FROM u;

-- The declared languages: Danish out, English in where a series would be left with none, then numbered from 0 again
-- (an ordered element collection; a gap in indexNo reads back as an empty entry).
WITH u AS (DELETE FROM public."PublicationSeries_languages" WHERE languages = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: Danish declared languages deleted', count(*) FROM u;
WITH u AS (
    INSERT INTO public."PublicationSeries_languages" ("PublicationSeries_id", "indexNo", languages)
    SELECT s.id, 0, 'en' FROM public."PublicationSeries" s
     WHERE NOT EXISTS (SELECT 1 FROM public."PublicationSeries_languages" l WHERE l."PublicationSeries_id" = s.id)
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: English declared where none was left', count(*) FROM u;
WITH n AS (
    SELECT "PublicationSeries_id" AS series_id, "indexNo" AS old_no,
           row_number() OVER (PARTITION BY "PublicationSeries_id" ORDER BY "indexNo") - 1 AS new_no
      FROM public."PublicationSeries_languages"),
u AS (
    UPDATE public."PublicationSeries_languages" l
       SET "indexNo" = n.new_no
      FROM n
     WHERE l."PublicationSeries_id" = n.series_id AND l."indexNo" = n.old_no AND n.old_no <> n.new_no
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication series: declared languages numbered again', count(*) FROM u;

WITH u AS (
    UPDATE public."PublicationIssueDesc" da
       SET lang = 'en', name = pg_temp.english_publication(name)
     WHERE da.lang = 'da'
       AND NOT EXISTS (SELECT 1 FROM public."PublicationIssueDesc" en WHERE en.entity_id = da.entity_id AND en.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication issues: Danish-only rows made English', count(*) FROM u;
WITH u AS (DELETE FROM public."PublicationIssueDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication issues: Danish rows deleted', count(*) FROM u;

-- One legacy publication (an NCAGS draft of 2021) has a Danish title only: it becomes the English one.
WITH u AS (
    UPDATE public."PublicationDesc" da
       SET lang = 'en', title = pg_temp.english_publication(title), "titleFormat" = pg_temp.english_publication("titleFormat")
     WHERE da.lang = 'da'
       AND NOT EXISTS (SELECT 1 FROM public."PublicationDesc" en WHERE en.entity_id = da.entity_id AND en.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publications: Danish-only rows made English', count(*) FROM u;
WITH u AS (DELETE FROM public."PublicationDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publications: Danish rows deleted', count(*) FROM u;

WITH u AS (
    UPDATE public."PublicationCategoryDesc" da
       SET lang = 'en'
     WHERE da.lang = 'da'
       AND NOT EXISTS (SELECT 1 FROM public."PublicationCategoryDesc" en WHERE en.entity_id = da.entity_id AND en.lang = 'en')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication categories: Danish-only rows made English', count(*) FROM u;
WITH u AS (DELETE FROM public."PublicationCategoryDesc" WHERE lang = 'da' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication categories: Danish rows deleted', count(*) FROM u;

-- A deleted issue's audit entry names it in every language it had ({"names": {"en": ..., "da": ...}}).
WITH u AS (
    UPDATE public."IssueAuditEntry"
       SET detail = (detail::jsonb #- '{names,da}')::text
     WHERE id IN (SELECT id FROM public."IssueAuditEntry" WHERE detail ~ '"names"\s*:\s*\{[^}]*"da"\s*:')
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'publication audit: Danish names of deleted issues taken out', count(*) FROM u;

-- The self-check of the publications: no publication row declares or holds Danish, and every series declares English
-- alone.
DO $$
DECLARE
    found text;
BEGIN
    SELECT string_agg(what || ': ' || n, ', ') INTO found
      FROM (SELECT 'PublicationSeries_languages' AS what, count(*) AS n
              FROM public."PublicationSeries_languages" WHERE languages = 'da'
            UNION ALL SELECT 'PublicationSeriesDesc', count(*) FROM public."PublicationSeriesDesc" WHERE lang = 'da'
            UNION ALL SELECT 'PublicationIssueDesc', count(*) FROM public."PublicationIssueDesc" WHERE lang = 'da'
            UNION ALL SELECT 'PublicationDesc', count(*) FROM public."PublicationDesc" WHERE lang = 'da'
            UNION ALL SELECT 'PublicationCategoryDesc', count(*) FROM public."PublicationCategoryDesc" WHERE lang = 'da'
            UNION ALL SELECT 'IssueAuditEntry.detail', count(*) FROM public."IssueAuditEntry"
                       WHERE detail ~ '"names"\s*:\s*\{[^}]*"da"\s*:'
            UNION ALL SELECT 'series not declaring English alone', count(*) FROM public."PublicationSeries" s
                       WHERE (SELECT array_agg(l.languages ORDER BY l."indexNo") FROM public."PublicationSeries_languages" l
                               WHERE l."PublicationSeries_id" = s.id) IS DISTINCT FROM ARRAY['en']::varchar[]
                          OR NOT EXISTS (SELECT 1 FROM public."PublicationSeries_languages" l
                                          WHERE l."PublicationSeries_id" = s.id AND l."indexNo" = 0)) c
     WHERE n > 0;
    IF found IS NOT NULL THEN
        RAISE EXCEPTION 'english.sql: publication rows still declare or hold Danish: %', found;
    END IF;
END $$;

-- ============================================================================================================
-- Mails: the recipients' language, and the Danish mails already sent
-- ============================================================================================================

-- The scrub's invented contacts are three in four Danish, and the staff rows Danish: every daily mailing-list trigger
-- then sends Danish mails (to MailHog) and queues Danish rows (Sysadmin > Mail Queue). Everyone reads English here.
WITH u AS (UPDATE public."Contact" SET language = 'en' WHERE language IS DISTINCT FROM 'en' AND language IS NOT NULL RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'contacts: language set to en', count(*) FROM u;
WITH u AS (UPDATE public."User" SET language = 'en' WHERE language IS DISTINCT FROM 'en' AND language IS NOT NULL RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'users: language set to en', count(*) FROM u;

WITH m AS (SELECT id FROM public."ScheduledMail" WHERE guides.looks_danish(subject)),
r AS (DELETE FROM public."ScheduledMailRecipient" WHERE mail_id IN (SELECT id FROM m) RETURNING 1),
u AS (DELETE FROM public."ScheduledMail" WHERE id IN (SELECT id FROM m) RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'mail queue: Danish mails deleted', (SELECT count(*) FROM u);

-- ============================================================================================================
-- Script resources: the Danish variants of templates (FreeMarker picks <name>_da.ftl for a Danish text), which open
-- in Danish in Sysadmin > Script Resources (the light characters' are the list's first row). The copy writes English
-- only, so nothing reads them.
-- ============================================================================================================
WITH h AS (DELETE FROM public."ScriptResourceHistory" WHERE resource_id IN
               (SELECT id FROM public."ScriptResource" WHERE path ~ '_da\.ftl$') RETURNING 1),
u AS (DELETE FROM public."ScriptResource" WHERE path ~ '_da\.ftl$' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'script resources: Danish variants deleted', (SELECT count(*) FROM u);

-- The mailing lists' templates that write Danish whatever the recipient's language (the third check, 2026-11-11):
-- - publish-navwarn.ftl and publish-navwarn-gl.ftl add a Danish half to every published warning's mail ('Dansk
--   navigationsadvarsel nr. ...' and the Danish details): the copy keeps the English half, as the base product's
--   publish-navwarn.ftl writes it;
-- - audio-broadcast.ftl, the daily voice-broadcast mail (trigger 1949666, 16 recipients), is Danish throughout: the copy
--   has it in English, word for word, with English date formats.
-- The backend keeps script resources in its entity cache: start the rig again after this file runs (data/README.md).
WITH u AS (
    UPDATE public."ScriptResource"
       SET content = regexp_replace(content,
                         '\s*<p>&nbsp;</p>\s*<div>[^<]*(<#if[^>]*>[^<]*</#if>)?\s*</div>\s*<@renderNavWarnDetails msg=message lang=''da''></@renderNavWarnDetails>',
                         ''),
           updated = now(), version = version + 1
     WHERE path IN ('templates/mailinglist/publish-navwarn.ftl', 'templates/mailinglist/publish-navwarn-gl.ftl')
       AND content ~ 'lang=''da'''
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'script resources: Danish half of the warnings'' mails taken out', count(*) FROM u;

WITH u AS (
    UPDATE public."ScriptResource" SET content = $ftl$
<#include "../messages/message-support.ftl"/>

<#setting time_zone="Europe/Copenhagen">

<html>
<head>
    <link rel="stylesheet" type="text/css" href="/css/templates/mail.css">
</head>
<body>

<h1>
    Danish navigational warnings for voice broadcast ${.now?string["EEEE d MMMM yyyy"]}
</h1>

<h2>
    This is the Joint Operations Centre with today's navigational warnings
</h2>

<#list messages as msg>
    <#assign audio=promulgation(msg, 'audio')!>
    <#if audio?? && audio.text?has_content>
    <p>
        <#if msg.areas?has_content>
            <u>
                <@areaLineage area=msg.areas[0] />
                <#assign msgDesc=descForLang(msg, language)!>
                <#if msgDesc?? && msgDesc.vicinity?has_content>
                    - ${msgDesc.vicinity}
                </#if>
            </u>
            <br>
        </#if>
        <@txtToHtml text=audio.text/>
    </p>
    </#if>
</#list>

<#if params['firingExercises']?? && params['firingExercisesDate']??>
<h2>
    Tomorrow,
    ${params['firingExercisesDate']?string["EEEE d MMMM yyyy"]},
    the following firing exercises will take place:
</h2>

    <#list params['firingExercises'] as fe>
    <p>
        At <@areaLineage area=fe.area />
        <#list fe.times as t>
            <#if !t?is_first>and again</#if>
            from ${t.fromDate?string["HHmm"]}
            to ${t.toDate?string["HHmm"]}<#if t?is_last>.</#if>
        </#list>
    </p>
    </#list>
</#if>

<p>
    That was the navigational warnings.
</p>

</body>
</html>
$ftl$,
           updated = now(), version = version + 1
     WHERE path = 'templates/mailinglist/audio-broadcast.ftl' AND content ~ 'Danske farvandsoplæsninger'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'script resources: the voice-broadcast mail made English', count(*) FROM u;

-- The message templates' audio texts (after the independent checks of the manuals, 2026-11-11): the copy's 16 NW
-- templates that write the Audio promulgation's text write it in Danish whatever the Audio type's language ('Fyret in
-- position ... er slukket.', 'Et skib er sunket ...'): Denmark broadcasts in Danish, so its audio blocks name their
-- language, lang="da", and hold the Danish sentences. The Execute templates page and every editor that runs a template
-- show that text beside the English one. The base product's templates (be/niord/bootstrap/templates/tmpl) write no
-- audio text at all, so there is no English wording to take: each audio block here is the template's own English
-- details block in the audio form (positions and dates spelled out, format="audio" and "plain", no markup), as the
-- backend's English audio texts read (the live warnings' texts above). Only the block between the audio
-- field-template's tags is replaced, each guarded by its Danish language; a self-check below stops the run when an
-- audio block in Danish is left (a template niord_test gains or changes). The backend caches script resources: start
-- the rig again after this file runs (data/README.md).
--
-- After the error hunt (2026-11-14) niord_test holds Denmark's corrected templates (its own, and the base product's
-- corrected common.ftl, markings.ftl's and work-vessel.ftl's macros),
-- so nine bodies were made again from the corrected English details: exercise.ftl (an exercise without a type is "an
-- exercise", a capital without dates), object-adrift.ftl ("have" for a plural object), obstruction-found.ftl ("have"
-- for several positions; mine.ftl's "The mines" and torpedo.ftl's "Torpedoes" come from their callers),
-- obstruction-work.ftl and salvage-operations.ftl (a capital without dates), radio-navigation.ftl (capFirst=false on
-- the type mid-sentence), reduced-depth.ftl ("Reduced water depths" without a depth, never "0 m"),
-- uncharted-obstruction.ftl and wreck.ftl (a depth without a grouping comma). The other seven templates did not change.
-- A second self-check below stops the run when a template's audio block is not exactly its body here (a template
-- missing, or one whose audio block no longer names lang="da" and was therefore left as it was).
CREATE TEMP TABLE english_audio_template (path text PRIMARY KEY, body text NOT NULL) ON COMMIT DROP;
INSERT INTO english_audio_template VALUES
  ('templates/tmpl/aton-common.ftl', $ftl$
            <#list params.positions![] as pos>
                <@line>
                    <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="long" lang="en"/>
                    <@renderPositionList geomParam=pos format="audio" lang="en"/> ${enDetails}.
                </@line>
            </#list>
        $ftl$),
  ('templates/tmpl/aton-replacement-common.ftl', $ftl$
            <#list params.positions![] as pos>
                <@line>
                    <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="long" lang="en"/>
                    <@renderPositionList geomParam=pos format="audio" lang="en"/> has been replaced with
                    <@renderAtonReplacementType atonParams=pos defaultName="${enDefaultReplacementName}" format="long" lang="en"/>
                    <#if pos.aton_light?has_content>
                        showing
                        <@lightCharacterFormat light=pos.aton_light format="verbose" lang="en"/>
                    </#if>.
                </@line>
            </#list>
        $ftl$),
  ('templates/tmpl/bridge-closed.ftl', $ftl$
        <@line>
            The bridge
            <#if params.bridge_name?has_content>
                <@quote text=params.bridge_name/>
            </#if>
            <@renderPositionList geomParam=part lang="en" format="audio"/>
            is closed for passage that requires opening of the bridge.
        </@line>
    $ftl$),
  ('templates/tmpl/buoy-established.ftl', $ftl$
        <#list params.positions as pos>
            <@line>
                <@renderAtonType atonParams=pos defaultName="A buoy" format="long" lang="en"/>
                has been established <@renderPositionList geomParam=pos format="audio" lang="en"/>.
            </@line>
        </#list>
    $ftl$),
  ('templates/tmpl/dgps.ftl', $ftl$
        <#setting locale='en'>
        <@line>
            <@renderStationName format="long" lang="en"/> DGPS station
            in position <@formatPos lat=params.station.coordinates[1] lon=params.station.coordinates[0] format='audio' />
            and frequency ${params.station.frequency?string["0.0"]} kHz
            <@renderStatus format="long" lang="en"/>
            <#if part.eventDates?has_content && part.eventDates[0].fromDate??>
                <@renderDateIntervals dateIntervals=part.eventDates format="plain" lang="en" tz="UTC"/>
            </#if>.
        </@line>
    $ftl$),
  ('templates/tmpl/exercise.ftl', $ftl$
        <@line>
            <#if part.eventDates?has_content>
                <@renderDateIntervals dateIntervals=part.eventDates format="plain" lang="en" tz="UTC" capFirst=true/>
                ${exerciseText}
            <#else>
                ${exerciseText?cap_first}
            </#if>
            will take place
            <@renderPositionList geomParam=part lang="en" format="audio"/>.
            Military vessels will be in the area listening on VHF channel 16.
            Mariners are requested to pass with caution.
        </@line>
    $ftl$),
  ('templates/tmpl/light-buoy-established.ftl', $ftl$
        <#list params.positions as pos>
            <@line>
                <@renderAtonType atonParams=pos defaultName="A light buoy" format="long" lang="en"/>
                <#if pos.aton_light?has_content>
                    showing
                    <@lightCharacterFormat light=pos.aton_light format="verbose"  lang="en"/>
                </#if>
                has been established <@renderPositionList geomParam=pos format="audio" lang="en"/>.
            </@line>
        </#list>
    $ftl$),
  ('templates/tmpl/light-destroyed.ftl', $ftl$
        <@line>
            <#list params.positions as pos>
                The light <@renderLightName lightParam=pos/> <@renderPositionList geomParam=part format="audio" lang="en"/>
                has been destroyed and makes an obstruction.
            </#list>
            The foundation of the light is <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="audio" unmarkedText="unmarked"/>
        </@line>
        <@line>
            Mariners are advised to keep well clear.
        </@line>
    $ftl$),
  ('templates/tmpl/object-adrift.ftl', $ftl$
        <@line>
            <@renderObject defaultName="an object" format="long" lang="en" capFirst=true/>
            ${objectVerb} been observed adrift <@renderPositionList geomParam=part format="audio" lang="en"/>
            <#if params.date??><@renderDate date=params.date lang="en" tz="UTC" format="plain"/></#if>.
        </@line>
        <#if params.cancelDate??>
            <@line>
               Cancel this warning <@renderDate date=params.cancelDate lang="en" tz="UTC" format="plain"/>.
            </@line>
        </#if>
    $ftl$),
  ('templates/tmpl/obstruction-found.ftl', $ftl$
            <@line>
                ${enObj[0+offset]?cap_first} ${verb} been reported
                <@renderPositionList geomParam=part lang="en" format="audio" plural=true/>.
                ${enObj[1+offset]?cap_first} may be dangerous.
            </@line>
            <@line>
                Mariners are advised to keep well clear.
            </@line>
        $ftl$),
  ('templates/tmpl/obstruction-work.ftl', $ftl$
            <@line>
                <#if part.eventDates?has_content>
                    <@renderDateIntervals dateIntervals=part.eventDates format="plain" lang="en" tz="UTC" capFirst=true/>
                    ${enDetails}
                <#else>
                    ${enDetails?cap_first}
                </#if>
                <@renderPositionList geomParam=part lang="en" format="audio"/>.
                <@renderWorkVessel vessel=params.vessel! lang="en" format="audio"/>
            </@line>
        $ftl$),
  ('templates/tmpl/radio-navigation.ftl', $ftl$
        <#list params.positions![] as pos>
            <@line>
                ${computeRadioType(pos)} on
                <@renderAtonType atonParams=pos defaultName="AtoN" format="long" lang="en" capFirst=false/>
                <@renderPositionList geomParam=pos format="audio" lang="en"/>
                <@renderStatus statusParam=pos format="long" lang="en"/>.
            </@line>
        </#list>
    $ftl$),
  ('templates/tmpl/reduced-depth.ftl', $ftl$
        <#setting locale="en">
        <@line>
            <#if waterDepth?has_content>Water depths down to ${waterDepth}m<#else>Reduced water depths</#if>
            have been observed
            <@renderPositionList geomParam=part format="audio" lang="en"/>
            <#if params.locality?has_content>in the entrance to ${params.locality}</#if>.
        </@line>
        <@line>
            Mariners are advised to keep well clear.
        </@line>
    $ftl$),
  ('templates/tmpl/salvage-operations.ftl', $ftl$
        <@line>
            <#if part.eventDates?has_content>
                <@renderDateIntervals dateIntervals=part.eventDates format="plain" lang="en" tz="UTC" capFirst=true/>
                salvage operations are carried out
            <#else>
                Salvage operations are carried out
            </#if>
            <#if params.salvage_type??>
                on <@renderListValue value=params.salvage_type defaultValue="" lang="en"/>
            </#if>
            <@renderPositionList geomParam=part lang="en" format="audio"/>.
            <@renderWorkVessel vessel=params.vessel! lang="en" format="audio"/>
        </@line>
    $ftl$),
  ('templates/tmpl/uncharted-obstruction.ftl', $ftl$
        <@line>
            An uncharted <@renderObstructionType defaultValue="obstruction" lang="en"/>
            is <@renderObstructionReport defaultValue="reported" lang="en" />
            <@renderPositionList geomParam=part format="audio" lang="en"/>.
            <#if params.obstruction_visible!false>
                <@renderObstructionType defaultValue="The obstruction" format="long" lang="en" capFirst=true/> is visible above the sea surface.
            <#else>
                The depth above <@renderObstructionType defaultValue="the obstruction" format="long" lang="en"/> is
                <#if params.obstruction_depth??>${plainNumber(params.obstruction_depth)}m.<#else>unknown.</#if>
            </#if>
            <@renderObstructionType defaultValue="The obstruction" format="long" lang="en" capFirst=true/>
            is <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="audio" unmarkedText="unmarked"/>
        </@line>
        <@line>
            Mariners are advised to keep well clear.
        </@line>
    $ftl$),
  ('templates/tmpl/wreck.ftl', $ftl$
        <@line>
            <@renderVesselType defaultName="a vessel" format="long" lang="en"/>
            has sunk <@renderPositionList geomParam=part format="audio" lang="en"/>.
            <#if params.wreck_visible!false>
                The wreck is visible above the sea surface.
            <#else>
                The depth above the wreck is <#if params.wreck_depth??>${plainNumber(params.wreck_depth)}m.<#else>unknown.</#if>
            </#if>
            The wreck is <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="audio" unmarkedText="unmarked"/>
        </@line>
        <@line>
            Mariners are advised to keep well clear.
        </@line>
    $ftl$);

WITH tag AS (SELECT '<field-template field="message.promulgation(''audio'').text" update="append">'::text AS open_tag),
block AS (
    SELECT r.id, r.content, e.body, strpos(r.content, tag.open_tag) + length(tag.open_tag) AS inner_start
      FROM public."ScriptResource" r
      JOIN english_audio_template e ON e.path = r.path
     CROSS JOIN tag
     WHERE strpos(r.content, tag.open_tag) > 0),
span AS (
    SELECT id, content, body, inner_start,
           inner_start + strpos(substr(content, inner_start), '</field-template>') - 1 AS inner_end
      FROM block),
u AS (
    UPDATE public."ScriptResource" r
       SET content = left(span.content, span.inner_start - 1) || span.body || substr(span.content, span.inner_end),
           updated = now(), version = r.version + 1
      FROM span
     WHERE r.id = span.id
       AND span.inner_end >= span.inner_start
       AND substr(span.content, span.inner_start, span.inner_end - span.inner_start) ~ 'lang="da"'
    RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'script resources: the templates'' audio texts made English', count(*) FROM u;

-- The self-check of the audio texts: no template writes an audio text in Danish any more.
DO $$
DECLARE
    danish text;
BEGIN
    SELECT string_agg(path, ', ' ORDER BY path) INTO danish
      FROM public."ScriptResource"
     WHERE content ~ 'promulgation\(''audio''\)\.text"[^>]*>([^<]|<(?!/field-template>))*lang="da"';
    IF danish IS NOT NULL THEN
        RAISE EXCEPTION 'english.sql: these templates still write the audio text in Danish: %', danish;
    END IF;
END $$;

-- The second self-check of the audio texts (2026-11-14): every template above exists and its audio block is exactly
-- its body here. The replacement is guarded by lang="da", so a template whose stored audio block had changed in any
-- other way (another language, an English block of its own) would be left silently; this stops the run instead.
DO $$
DECLARE
    open_tag constant text := '<field-template field="message.promulgation(''audio'').text" update="append">';
    wrong text;
BEGIN
    SELECT string_agg(e.path, ', ' ORDER BY e.path) INTO wrong
      FROM english_audio_template e
      LEFT JOIN public."ScriptResource" r ON r.path = e.path
     WHERE r.id IS NULL
        OR strpos(r.content, open_tag) = 0
        OR substr(r.content, strpos(r.content, open_tag) + length(open_tag),
                  strpos(substr(r.content, strpos(r.content, open_tag) + length(open_tag)), '</field-template>') - 1)
           IS DISTINCT FROM e.body;
    IF wrong IS NOT NULL THEN
        RAISE EXCEPTION 'english.sql: these templates are missing or their audio block is not the English one this file writes: %', wrong;
    END IF;
END $$;

-- The same for the mailing-list templates above: their guards match the content they replace, so a template whose
-- Danish half or Danish wording took another form would be left Danish silently.
DO $$
DECLARE
    danish text;
BEGIN
    SELECT string_agg(path, ', ' ORDER BY path) INTO danish
      FROM public."ScriptResource"
     WHERE (path IN ('templates/mailinglist/publish-navwarn.ftl', 'templates/mailinglist/publish-navwarn-gl.ftl')
            AND content ~ 'lang=''da''')
        OR (path = 'templates/mailinglist/audio-broadcast.ftl' AND content !~ 'Danish navigational warnings for voice broadcast');
    IF danish IS NOT NULL THEN
        RAISE EXCEPTION 'english.sql: these mailing-list templates still write Danish: %', danish;
    END IF;
END $$;

-- The NAVTEX texts the templates write (after the admin manual's checks, 2026-11-12): object-adrift.ftl renders its
-- NAVTEX date with lang="da", so the NAVTEX preview showed Danish month abbreviations ('12 MAJ', '3 OKT') in an English
-- text; the other templates' NAVTEX blocks are English throughout. Every lang="da" inside a NAVTEX field-template
-- becomes lang="en", the rest of the template unchanged; a self-check stops the run when one is left. The backend
-- caches script resources: start the rig again after this file runs.
DO $$
DECLARE
    pattern constant text :=
        '(promulgation\(''navtex''\)\.text"[^>]*>([^<]|<(?!/field-template>))*)lang="da"';
    changed int[];
    k int;
BEGIN
    SELECT coalesce(array_agg(id), '{}') INTO changed FROM public."ScriptResource" WHERE content ~ pattern;
    -- One lang="da" per block and pass (the match takes the last one in a block); a block holding several takes a
    -- pass each.
    LOOP
        UPDATE public."ScriptResource" SET content = regexp_replace(content, pattern, '\1lang="en"', 'g')
         WHERE content ~ pattern;
        GET DIAGNOSTICS k = ROW_COUNT;
        EXIT WHEN k = 0;
    END LOOP;
    UPDATE public."ScriptResource" SET updated = now(), version = version + 1 WHERE id = ANY (changed);
    INSERT INTO english_counts (grp, rows)
    VALUES ('script resources: the templates'' NAVTEX dates made English', cardinality(changed));
    IF EXISTS (SELECT 1 FROM public."ScriptResource" WHERE content ~ pattern) THEN
        RAISE EXCEPTION 'english.sql: a template still writes its NAVTEX text with lang="da"';
    END IF;
END $$;

-- ============================================================================================================
-- Nothing about a standard the owner's standing ruling keeps out of the guides: the inactive Baleen promulgation type's
-- name, and the dictionary key of an old menu action (no base screen reads it; the Dictionaries page lists it).
-- ============================================================================================================
WITH u AS (UPDATE public."PromulgationType" SET name = 'Baleen' WHERE "typeId" = 'baleen' AND name = 'Baleen/S-124' RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'promulgation types: the Baleen type''s name shortened', count(*) FROM u;
WITH e AS (SELECT id FROM public."DictionaryEntry" WHERE dict_key = 'menu.action.generate_s124'),
d AS (DELETE FROM public."DictionaryEntryDesc" WHERE entity_id IN (SELECT id FROM e) RETURNING 1),
u AS (DELETE FROM public."DictionaryEntry" WHERE id IN (SELECT id FROM e) RETURNING 1)
INSERT INTO english_counts (grp, rows) SELECT 'dictionaries: an old menu action''s key deleted', (SELECT count(*) FROM u);

-- One line per group: the rows this run changed (a second run prints 0 everywhere).
SELECT 'english: ' || grp || ': ' || rows FROM english_counts ORDER BY n;
SELECT 'english: live messages English throughout (niord-nw, niord-nm): '
       || (SELECT count(*) FILTER (WHERE english_ok) || ' of ' || count(*) FROM guides.english_messages('niord-nw', 'PUBLISHED'))
       || ' published NW, '
       || (SELECT count(*) FILTER (WHERE english_ok) || ' of ' || count(*) FROM guides.english_messages('niord-nm', 'PUBLISHED'))
       || ' published NM';

-- The self-check (the fourth check, 2026-11-11): every live message of every domain (every status but EXPIRED,
-- CANCELLED and DELETED) is English throughout by the rule, apart from those guides.not_english lists with a reason.
-- A live message the rule turns down stops the run, named, so that a Danish text the reading has not met is put right
-- here (a phrase rule, or a row) and not left for a picture to find.
CREATE TEMP TABLE english_live_rejected ON COMMIT DROP AS
SELECT d."domainId" AS domain, s.status, r.message_id, r.short_id
  FROM public."Domain" d
 CROSS JOIN (SELECT DISTINCT status FROM public."Message"
              WHERE status NOT IN ('EXPIRED', 'CANCELLED', 'DELETED')) s
 CROSS JOIN LATERAL guides.english_messages(d."domainId", s.status) r
 WHERE NOT r.english_ok
   AND NOT EXISTS (SELECT 1 FROM guides.not_english e WHERE e.message_id = r.message_id);
SELECT 'english: live messages the rule turns down outside guides.not_english: ' || count(*)
  FROM english_live_rejected;
DO $$
DECLARE
    names text := (SELECT string_agg(domain || ' ' || status || ' ' || message_id || coalesce(' ' || short_id, ''), ', '
                                     ORDER BY domain, status, message_id)
                     FROM english_live_rejected);
BEGIN
    IF names IS NOT NULL THEN
        RAISE EXCEPTION 'english.sql: the rule turns down live messages (put their Danish text right, or list them in guides.not_english with the reason): %', names;
    END IF;
END $$;
