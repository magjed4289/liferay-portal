/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

import {mergeTests} from '@playwright/test';

import {apiHelpersTest} from '../../fixtures/apiHelpersTest';
import {applicationsMenuPageTest} from '../../fixtures/applicationsMenuPageTest';
import {dataApiHelpersTest} from '../../fixtures/dataApiHelpersTest';
import {featureFlagsTest} from '../../fixtures/featureFlagsTest';
import {loginTest} from '../../fixtures/loginTest';
import getRandomString from '../../utils/getRandomString';
import {stagingConfigurationPageTest} from './fixtures/stagingConfigurationPageTest';

export const test = mergeTests(
	applicationsMenuPageTest,
	apiHelpersTest,
	loginTest(),
	stagingConfigurationPageTest
);

const testWithPrivatePages = mergeTests(
	test,
	dataApiHelpersTest,
	featureFlagsTest({
		'LPD-38869': {enabled: true},
		'LPD-39304': {enabled: true},
	})
);

test('Check if local staging can be enabled', async ({
	apiHelpers,
	applicationsMenuPage,
	stagingConfigurationPage,
}) => {
	const siteName: string = getRandomString();

	await applicationsMenuPage.goToSites();

	const site = await apiHelpers.headlessSite.createSite({
		name: siteName,
	});

	await stagingConfigurationPage.gotoStagingConfiguration(
		site.friendlyUrlPath
	);

	await stagingConfigurationPage.enableLocalStaging({});
});

[
	{name: 'Blank Site', templateKey: 'blank-site-initializer'},
	{
		name: 'Masterclass',
		templateKey: 'com.liferay.site.initializer.masterclass',
	},
	{name: 'Minium', templateKey: 'minium-initializer'},
	{name: 'Minium Full', templateKey: 'minium-full-initializer'},
	{name: 'Speedwell', templateKey: 'speedwell-initializer'},
	{
		name: 'Team Extranet',
		templateKey: 'com.liferay.site.initializer.team.extranet',
	},
	{
		name: 'Teaser Showcase',
		templateKey: 'com.liferay.site.initializer.teaser.showcase',
	},
	{name: 'Welcome', templateKey: 'com.liferay.site.initializer.welcome'},
].forEach(({name, templateKey}) => {
	testWithPrivatePages(
		`Check if local staging can be enabled for ${name} site template`,
		async ({
			apiHelpers,
			applicationsMenuPage,
			stagingConfigurationPage,
		}) => {
			const randomString: string = getRandomString();
			const siteName: string = `Test ${name} Site ${randomString}`;

			await applicationsMenuPage.goToSites();

			const site = await apiHelpers.headlessSite.createSite({
				externalReferenceCode: randomString,
				name: siteName,
				templateKey,
				templateType: 'site-initializer',
			});

			let response;
			const maxRetries = 3;
			const retryInterval = 6000;
			let siteFound = false;

			await test.step('Wait for site to be available', async () => {
				for (let attempt = 1; attempt <= maxRetries; attempt++) {
					response = await apiHelpers.headlessSite.getSiteByERC(
						site.externalReferenceCode
					);

					if (response.status !== 'NOT_FOUND') {
						console.log(`Site found on attempt ${attempt}`);
						apiHelpers.data.push({id: site.id, type: 'site'});
						siteFound = true;

						return;
					}

					if (attempt < maxRetries) {
						await new Promise((resolve) =>
							setTimeout(resolve, retryInterval)
						);
					}
				}
			});

			test.skip(
				!siteFound,
				`Skipping test: Site was NOT_FOUND after ${maxRetries} attempts`
			);

			await stagingConfigurationPage.gotoStagingConfiguration(
				site.friendlyUrlPath
			);
			await stagingConfigurationPage.enableLocalStaging({
				versioning: true,
			});
		}
	);
});
