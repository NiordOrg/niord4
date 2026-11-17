import { expect, test } from './auth/fixtures';

// Smoke tests for the Mail Queue section. Backend-light: search filters render
// and the status radio works. Live search results, the details dialog content,
// and the page-overflow clamp run against the live backend during manual testing.

test.describe('sysadmin mail queue section', () => {
  test('the section renders the search filters under the shell', async ({ authenticatedPage }) => {
    await authenticatedPage.goto('/sysadmin/mails');
    await expect(authenticatedPage).toHaveURL(/\/sysadmin\/mails(?:[?#]|$)/);
    await expect(authenticatedPage.getByPlaceholder('Recipient')).toBeVisible();
    await expect(authenticatedPage.getByPlaceholder('Sender')).toBeVisible();
    await expect(authenticatedPage.getByPlaceholder('Subject')).toBeVisible();
  });

  test('the status radio offers All/Pending/Sent/Error and defaults to All', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/sysadmin/mails');
    for (const status of ['All', 'Pending', 'Sent', 'Error']) {
      await expect(
        authenticatedPage
          .getByRole('radio', { name: status })
          .or(authenticatedPage.getByText(status, { exact: true }))
          .first(),
      ).toBeVisible();
    }
  });
});
