/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.headless.builder.test.util;

import com.liferay.batch.engine.unit.BatchEngineUnitProcessor;
import com.liferay.batch.engine.unit.BatchEngineUnitReader;
import com.liferay.headless.builder.test.BaseTestCase;

import java.io.File;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * @author Alejandro Tardín
 */
public class HeadlessBuilderUtil {

	public static void deploy(
		BatchEngineUnitProcessor batchEngineUnitProcessor,
		BatchEngineUnitReader batchEngineUnitReader) {

		// TODO Delete the bundle deployment when the FF LPS-178642 is removed

		Bundle testBundle = FrameworkUtil.getBundle(BaseTestCase.class);

		BundleContext bundleContext = testBundle.getBundleContext();

		for (Bundle bundle : bundleContext.getBundles()) {
			if (Objects.equals(
					bundle.getSymbolicName(),
					"com.liferay.headless.builder.impl")) {

				_setUpProcessedFile(bundle, "00.list.type.definition");
				_setUpProcessedFile(bundle, "01.object.definition");

				CompletableFuture<Void> completableFuture =
					batchEngineUnitProcessor.processBatchEngineUnits(
						batchEngineUnitReader.getBatchEngineUnits(bundle));

				completableFuture.join();
			}
		}
	}

	private static void _setUpProcessedFile(
		Bundle bundle, String processedFileName) {

		File processedFile = bundle.getDataFile(
			".com.liferay.headless.builder.internal.batch." +
				processedFileName + ".batch.engine.data.json.0.processed");

		if ((processedFile != null) && processedFile.exists()) {
			processedFile.delete();
		}
	}

}