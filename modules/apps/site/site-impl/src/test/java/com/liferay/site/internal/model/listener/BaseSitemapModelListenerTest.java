/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.site.internal.model.listener;

import com.liferay.portal.kernel.model.Layout;
import com.liferay.portal.kernel.test.GCUtil;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.transaction.Propagation;
import com.liferay.portal.kernel.transaction.TransactionAttribute;
import com.liferay.portal.kernel.transaction.TransactionCallbackUtil;
import com.liferay.portal.kernel.transaction.TransactionStatus;
import com.liferay.portal.kernel.util.ProxyUtil;
import com.liferay.portal.test.rule.LiferayUnitTestRule;
import com.liferay.site.constants.SitemapConstants;
import com.liferay.site.manager.SitemapManager;

import java.lang.ref.WeakReference;

import java.util.Objects;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

/**
 * @author Magdalena Jedraszak
 */
public class BaseSitemapModelListenerTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testOnAfterCreateDedupesWithinSameTransaction() {
		long companyId = RandomTestUtil.randomLong();
		long groupId = RandomTestUtil.randomLong();

		LayoutModelListener layoutModelListener = _createLayoutModelListener();

		_fireCreated(Propagation.REQUIRED, true);

		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId));
		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId));
		layoutModelListener.onAfterRemove(_createLayout(companyId, groupId));

		Mockito.verifyNoInteractions(_sitemapManager);

		_fireCommitted(Propagation.REQUIRED, true);

		Mockito.verify(
			_sitemapManager, Mockito.times(1)
		).scheduleRegenerateSitemap(
			SitemapConstants.ASSET_TYPE_KEY_PAGES, companyId, groupId, null
		);
	}

	@Test
	public void testOnAfterCreateDiscardsCommitCallbackOnRollback() {
		long companyId = RandomTestUtil.randomLong();
		long groupId = RandomTestUtil.randomLong();

		LayoutModelListener layoutModelListener = _createLayoutModelListener();

		_fireCreated(Propagation.REQUIRED, true);

		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId));

		_fireRollbacked(Propagation.REQUIRED, true);

		Mockito.verifyNoInteractions(_sitemapManager);
	}

	@Test
	public void testOnAfterCreateDoesNotDedupeAcrossDifferentGroups() {
		long companyId = RandomTestUtil.randomLong();
		long groupId1 = RandomTestUtil.randomLong();
		long groupId2 = RandomTestUtil.randomLong();

		LayoutModelListener layoutModelListener = _createLayoutModelListener();

		_fireCreated(Propagation.REQUIRED, true);

		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId1));
		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId2));

		_fireCommitted(Propagation.REQUIRED, true);

		Mockito.verify(
			_sitemapManager, Mockito.times(1)
		).scheduleRegenerateSitemap(
			SitemapConstants.ASSET_TYPE_KEY_PAGES, companyId, groupId1, null
		);

		Mockito.verify(
			_sitemapManager, Mockito.times(1)
		).scheduleRegenerateSitemap(
			SitemapConstants.ASSET_TYPE_KEY_PAGES, companyId, groupId2, null
		);
	}

	@Test
	public void testOnAfterCreateDoesNotRetainModel() throws Exception {
		long companyId = RandomTestUtil.randomLong();
		long groupId = RandomTestUtil.randomLong();

		LayoutModelListener layoutModelListener = _createLayoutModelListener();

		Layout layout = _createLayout(companyId, groupId);

		WeakReference<Layout> weakReference = new WeakReference<>(layout);

		_fireCreated(Propagation.REQUIRED, true);

		layoutModelListener.onAfterCreate(layout);

		layout = null;

		GCUtil.gc(true);

		Assert.assertNull(weakReference.get());

		_fireCommitted(Propagation.REQUIRED, true);

		Mockito.verify(
			_sitemapManager, Mockito.times(1)
		).scheduleRegenerateSitemap(
			SitemapConstants.ASSET_TYPE_KEY_PAGES, companyId, groupId, null
		);
	}

	@Test
	public void testOnAfterCreateSchedulesAgainInSubsequentTransaction() {
		long companyId = RandomTestUtil.randomLong();
		long groupId = RandomTestUtil.randomLong();

		LayoutModelListener layoutModelListener = _createLayoutModelListener();

		_fireCreated(Propagation.REQUIRED, true);

		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId));

		_fireCommitted(Propagation.REQUIRED, true);

		_fireCreated(Propagation.REQUIRED, true);

		layoutModelListener.onAfterCreate(_createLayout(companyId, groupId));

		_fireCommitted(Propagation.REQUIRED, true);

		Mockito.verify(
			_sitemapManager, Mockito.times(2)
		).scheduleRegenerateSitemap(
			SitemapConstants.ASSET_TYPE_KEY_PAGES, companyId, groupId, null
		);
	}

	private Layout _createLayout(long companyId, long groupId) {
		return (Layout)ProxyUtil.newProxyInstance(
			Layout.class.getClassLoader(), new Class<?>[] {Layout.class},
			(proxy, method, methodArgs) -> {
				String methodName = method.getName();

				if (Objects.equals(methodName, "getCompanyId")) {
					return companyId;
				}

				if (Objects.equals(methodName, "getGroupId")) {
					return groupId;
				}

				return null;
			});
	}

	private LayoutModelListener _createLayoutModelListener() {
		LayoutModelListener layoutModelListener = new LayoutModelListener();

		_sitemapManager = Mockito.mock(SitemapManager.class);

		ReflectionTestUtil.setFieldValue(
			layoutModelListener, "sitemapManager", _sitemapManager);

		return layoutModelListener;
	}

	private TransactionAttribute _createTransactionAttribute(
		Propagation propagation) {

		TransactionAttribute.Builder builder =
			new TransactionAttribute.Builder();

		builder.setPropagation(propagation);

		return builder.build();
	}

	private TransactionStatus _createTransactionStatus(boolean newTransaction) {
		return new TransactionStatus() {

			@Override
			public boolean isCompleted() {
				return false;
			}

			@Override
			public boolean isNewTransaction() {
				return newTransaction;
			}

			@Override
			public boolean isRollbackOnly() {
				return false;
			}

			@Override
			public void suppressLifecycleListenerThrowable(
				Throwable throwable) {
			}

		};
	}

	private void _fireCommitted(
		Propagation propagation, boolean newTransaction) {

		TransactionCallbackUtil.TRANSACTION_LIFECYCLE_LISTENER.committed(
			_createTransactionAttribute(propagation),
			_createTransactionStatus(newTransaction));
	}

	private void _fireCreated(Propagation propagation, boolean newTransaction) {
		TransactionCallbackUtil.TRANSACTION_LIFECYCLE_LISTENER.created(
			_createTransactionAttribute(propagation),
			_createTransactionStatus(newTransaction));
	}

	private void _fireRollbacked(
		Propagation propagation, boolean newTransaction) {

		TransactionCallbackUtil.TRANSACTION_LIFECYCLE_LISTENER.rollbacked(
			_createTransactionAttribute(propagation),
			_createTransactionStatus(newTransaction), null);
	}

	private SitemapManager _sitemapManager;

}