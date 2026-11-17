/**
 * Inline stylesheet for the mail-details HTML preview.
 *
 * The backend mail body links `/css/templates/mail.css` + `common.css` via
 * absolute-path `<link>` tags. Inside the empty-sandbox preview iframe (opaque
 * origin) those links cannot resolve, so the body would render unstyled. These
 * rules mirror the backend stylesheets (`css/templates/mail.css` +
 * `common.css`) for the classes the mail body actually emits, and are injected
 * into the frame as a `<style>` block.
 *
 * The literals (colors, mm/px sizes, page-break rules) are intentionally NOT
 * re-tokenised: the iframe has no access to the app's CSS custom properties, so
 * the styling must be self-contained.
 */
export const MAIL_PREVIEW_CSS = `
body {
  font-size: 12px;
  font-family: Helvetica;
  margin: 0;
  padding: 0;
}
a {
  color: #000000;
}
h1 {
  font-size: 24px;
}
h2 {
  font-size: 18px;
}
h3 {
  color: #002d47;
  font-size: 14px;
}

.avoid-break-before {
  page-break-before: avoid !important;
}
.avoid-break-after {
  page-break-after: avoid !important;
}
.avoid-break-before-after {
  page-break-before: avoid !important;
  page-break-after: avoid !important;
}

table.message-table {
  table-layout: fixed;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
}
table.message-table tr,
table.message-table tr td {
  page-break-inside: avoid;
}
tr.table-header-row {
  page-break-after: avoid;
}
td.table-header {
  border-bottom: 0.05em solid #a32638;
}
td.table-header h4 {
  color: #a32638;
  font-size: 16px;
}
td.table-image {
  vertical-align: top;
  padding: 10px;
  border-bottom: 0.05em dotted gray;
}
td.table-item {
  vertical-align: top;
  width: 100%;
  padding: 10px;
  border-bottom: 0.05em dotted gray;
}

table.field-table {
  page-break-before: avoid !important;
  page-break-inside: auto;
}
.field-name {
  white-space: nowrap;
  vertical-align: top;
  padding-right: 10px;
  text-align: left;
  color: gray;
}
.field-value {
  vertical-align: top;
  width: 100%;
}

/* Tables in the message rich-text description field. */
.message-description table {
  page-break-inside: auto;
  border-collapse: collapse;
  border: 0.05em solid #999;
}
.message-description table td,
.message-description table th {
  vertical-align: top;
  border: 0.05em solid #999;
  padding: 3px;
}
.message-description ol,
.message-description ul {
  padding-left: 4mm;
  margin-top: 0;
  margin-bottom: 0;
}
.message-description ol li,
.message-description ul li {
  margin-bottom: 1mm;
}
.message-description p {
  margin: 0 0 1mm 0;
}
.message-description ol li p:last-child,
.message-description ul li p:last-child {
  padding: 0;
  margin: 0;
}

table.positions {
  border: 0;
  margin-bottom: 1mm;
}
table.positions td,
table.positions th {
  color: #333;
  border: 0;
  padding: 0 3mm 0 0;
  text-align: left;
}
table.positions td:nth-child(1) {
  text-align: right;
  color: darkgray;
}
ul.positions li {
  margin-bottom: 0;
}
table.position-table td.pos-col {
  padding-left: 1mm;
  padding-right: 1mm;
  text-align: right;
  white-space: nowrap;
}
table.position-table td.pos-index {
  min-width: 4mm;
  text-align: right;
  white-space: nowrap;
}

img.lang-flag {
  height: 2mm;
  opacity: 0.3;
}
.lang-header-first {
  background-color: #999;
  color: white;
  padding: 1mm 2mm;
  margin: 2mm 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}
.lang-header {
  background-color: white;
  color: #999;
  border: 1px solid #999;
  padding: 1mm 2mm;
  margin: 2mm 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

.attachment {
  text-align: center;
}

.label-message-id {
  font-size: 75%;
  background-color: #999;
  display: inline-block;
  margin-bottom: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  padding: 0.2em 0.6em 0.4em;
  font-weight: bold;
  line-height: 1;
  color: #fff;
  text-align: center;
  white-space: nowrap;
  vertical-align: baseline;
  border-radius: 0.25em;
}
.label-status {
  font-size: 75%;
  background-color: #fff;
  display: inline-block;
  margin-bottom: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  padding: 0.2em 0.6em 0.4em;
  font-weight: bold;
  line-height: 1;
  color: #aaa;
  text-align: center;
  white-space: nowrap;
  vertical-align: baseline;
  border-radius: 0.25em;
  border: 1px solid #aaa;
}

.original-information {
  font-size: 200%;
  line-height: 20px;
  margin-bottom: -3px;
}
`;
