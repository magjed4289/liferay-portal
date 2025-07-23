/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.headless.builder.internal.deployer.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.portal.kernel.dao.db.DBManagerUtil;
import com.liferay.portal.kernel.dao.db.DBType;
import com.liferay.portal.kernel.db.partition.DBPartition;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.json.JSONUtil;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.AssumeTestRule;
import com.liferay.portal.kernel.test.util.HTTPTestUtil;
import com.liferay.portal.kernel.util.Http;
import com.liferay.portal.test.rule.FeatureFlag;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.util.PropsValues;

import java.util.Objects;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;

/**
 * @author Magdalena Jedraszak
 */
@FeatureFlag("LPS-178642")
@RunWith(Arquillian.class)
public class APIPropertyObjectDefinitionDeployerImplTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new AssumeTestRule("assume"), new LiferayIntegrationTestRule());

	public static void assume() {
		DBType dbType = DBManagerUtil.getDBType();

		Assume.assumeTrue(
			(dbType == DBType.MYSQL) || (dbType == DBType.POSTGRESQL));

		Assume.assumeTrue(DBPartition.isPartitionEnabled());
	}

	@Test
	public void testAPIBuilderObjectDefinitionsCreatedAndPublished()
		throws Exception {

		JSONObject jsonObject = HTTPTestUtil.invokeToJSONObject(
			JSONUtil.put(
				"domain", "able.com"
			).put(
				"portalInstanceId", "able.com"
			).put(
				"virtualHost", "www.able.com"
			).toString(),
			"headless-portal-instances/v1.0/portal-instances",
			Http.Method.POST);

		long companyId = jsonObject.getLong("companyId");

		Bundle bundle = null;

		try {
			bundle = _stopBundle();
		}
		finally {
			if (bundle != null) {
				_startHeadlessBuilderImplBundle(bundle);
			}
		}

		try {
			HTTPTestUtil.customize(
			).withBaseURL(
				"http://www.able.com:8080"
			).withCredentials(
				"test@able.com", PropsValues.DEFAULT_ADMIN_PASSWORD
			).apply(
				() -> {
					JSONObject apiPropertyJSONObject =
						HTTPTestUtil.invokeToJSONObject(
							null,
							"object-admin/v1.0/object-definitions" +
								"/by-external-reference-code/L_API_PROPERTY",
							Http.Method.GET);

					Assert.assertEquals(
						"true", apiPropertyJSONObject.getString("active"));
				}
			);
		}
		finally {
			if (companyId != 0) {
				_companyLocalService.deleteCompany(companyId);
			}
		}
	}

	private void _startHeadlessBuilderImplBundle(Bundle bundle)
		throws BundleException {

		bundle.start();

		long timeout = 5000;
		long pollInterval = 100;
		long startTime = System.currentTimeMillis();

		while (bundle.getState() != Bundle.ACTIVE) {
			if ((System.currentTimeMillis() - startTime) > timeout) {
				throw new RuntimeException(
					"Timeout waiting for bundle to become ACTIVE");
			}

			try {
				Thread.sleep(pollInterval);
			}
			catch (InterruptedException interruptedException) {
				Thread.currentThread(
				).interrupt();

				throw new RuntimeException(
					"Interrupted while waiting for bundle to start",
					interruptedException);
			}
		}
	}

	private Bundle _stopBundle() throws Exception {
		Bundle bundle = FrameworkUtil.getBundle(
			APIPropertyObjectDefinitionDeployerImplTest.class);

		BundleContext bundleContext = bundle.getBundleContext();

		for (Bundle curBundle : bundleContext.getBundles()) {
			if (Objects.equals(
					curBundle.getSymbolicName(),
					"com.liferay.headless.builder.impl") &&
				(curBundle.getState() == Bundle.ACTIVE)) {

				curBundle.stop();

				return curBundle;
			}
		}

		return null;
	}

	@Inject
	private CompanyLocalService _companyLocalService;

}