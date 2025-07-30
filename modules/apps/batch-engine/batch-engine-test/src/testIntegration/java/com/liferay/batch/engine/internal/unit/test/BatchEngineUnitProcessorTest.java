package com.liferay.batch.engine.internal.unit.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.batch.engine.unit.BatchEngineUnitProcessor;
import com.liferay.batch.engine.unit.BatchEngineUnitReader;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.audit.AuditMessage;
import com.liferay.portal.kernel.audit.AuditRouter;
import com.liferay.portal.kernel.model.ModelListener;
import com.liferay.portal.kernel.model.Role;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.model.role.RoleConstants;
import com.liferay.portal.kernel.service.RoleLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.UserLocalService;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.test.util.UserTestUtil;
import com.liferay.portal.kernel.util.ProxyUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.kernel.zip.ZipWriter;
import com.liferay.portal.kernel.zip.ZipWriterFactory;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

@RunWith(Arquillian.class)
public class BatchEngineUnitProcessorTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new LiferayIntegrationTestRule(),
			PermissionCheckerMethodTestRule.INSTANCE);

	@Before
	public void setUp() {
		_bundle = FrameworkUtil.getBundle(BatchEngineUnitProcessorTest.class);

		_bundleContext = _bundle.getBundleContext();
	}

	private BundleContext _bundleContext;


	@Test
	public void testBatchProcessingWithBundleRestart() throws Exception {
		User defaultOmniAdminUser = _userLocalService.getUser(TestPropsValues.getUserId());
		int originalStatus = defaultOmniAdminUser.getStatus();

		User newOmniAdminUser = UserTestUtil.addUser();

		try {
			Role role = _roleLocalService.getRole(
				newOmniAdminUser.getCompanyId(), RoleConstants.ADMINISTRATOR);

			_userLocalService.addRoleUser(role.getRoleId(), newOmniAdminUser);

			Queue<AuditMessage> auditMessages = new LinkedList<>();

			AuditRouter originalAuditRouter = ReflectionTestUtil.getFieldValue(
				_objectDefinitionModelListener, "_auditRouter");

			AuditRouter spyingAuditRouter = (AuditRouter) ProxyUtil.newProxyInstance(
				AuditRouter.class.getClassLoader(),
				new Class<?>[] {AuditRouter.class},
				(proxy, method, arguments) -> {
					if (arguments[0] instanceof AuditMessage) {
						auditMessages.add((AuditMessage) arguments[0]);
					}
					return method.invoke(originalAuditRouter, arguments);
				});

			ReflectionTestUtil.setFieldValue(
				_objectDefinitionModelListener, "_auditRouter", spyingAuditRouter);

			_userLocalService.updateStatus(
				defaultOmniAdminUser.getUserId(),
				WorkflowConstants.STATUS_INACTIVE,
				new ServiceContext());

			Assert.assertFalse(
				_userLocalService.getUser(defaultOmniAdminUser.getUserId()).isActive());
			Assert.assertTrue(newOmniAdminUser.isActive());

			// Step 0: Uninstall existing "batch11" bundle if it's already deployed
			for (Bundle bundle : _bundleContext.getBundles()) {
				if ("batch11".equals(bundle.getSymbolicName()) &&
					(bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.RESOLVED)) {

					_stopAndUninstallBundle(bundle);
				}
			}

			// --- Step 1: Install and process custom test bundle ---
			Bundle testBundle = _bundleContext.installBundle(
				RandomTestUtil.randomString(), _toInputStream("batch11"));

			testBundle.start();

			// --- Step 2: Stop and uninstall the bundle ---
			_stopAndUninstallBundle(testBundle);

			// --- Step 3: Reinstall and start the bundle again ---
			Bundle reinstalledBundle = _bundleContext.installBundle(
				RandomTestUtil.randomString(), _toInputStream("batch11"));
			_startBundle(reinstalledBundle);

			// Wait briefly to allow startup hooks to finish
			Thread.sleep(1000);

			// --- Step 4: Audit assertions ---
			AuditMessage auditMessage = auditMessages.stream()
				.filter(msg -> msg.getClassName().contains("ObjectDefinition"))
				.findFirst()
				.orElse(null);

			Assert.assertNotNull("Expected ObjectDefinition audit message", auditMessage);
			Assert.assertNotEquals(
				"Audit message user should not be the default user",
				defaultOmniAdminUser.getUserId(), auditMessage.getUserId());

			// Cleanup
			reinstalledBundle.stop();
			reinstalledBundle.uninstall();
		}
		finally {
			_userLocalService.updateStatus(
				defaultOmniAdminUser.getUserId(), originalStatus, new ServiceContext());

			if (newOmniAdminUser != null) {
				_userLocalService.deleteUser(newOmniAdminUser);
			}
		}
	}

	private void _stopAndUninstallBundle(Bundle bundle)
		throws BundleException, InterruptedException {
		if ((bundle.getState() == Bundle.ACTIVE) || (bundle.getState() == Bundle.STARTING)) {
			bundle.stop();
		}
		bundle.uninstall();
	}

	private void _startBundle(Bundle bundle) throws BundleException {
		long timeout = 5000;
		long pollInterval = 100;

		bundle.start();

		long startTime = System.currentTimeMillis();

		while (bundle.getState() != Bundle.ACTIVE) {
			if ((System.currentTimeMillis() - startTime) > timeout) {
				throw new RuntimeException(
					"Timeout waiting for bundle " + bundle.getSymbolicName() + " to become ACTIVE");
			}

			try {
				Thread.sleep(pollInterval);
			}
			catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();

				throw new RuntimeException(
					"Interrupted while waiting for bundle to start",
					interruptedException);
			}
		}
	}

	@Inject
	private ZipWriterFactory _zipWriterFactory;

	private InputStream _toInputStream(String dirName) throws Exception {
		ZipWriter zipWriter = _zipWriterFactory.getZipWriter();

		String basePath = StringBundler.concat(
			"com/liferay/batch/engine/internal/test/dependencies/", dirName,
			StringPool.SLASH);

		Enumeration<URL> enumeration = _bundle.findEntries(basePath, "*", true);

		if (enumeration != null) {
			while (enumeration.hasMoreElements()) {
				URL url = enumeration.nextElement();

				String urlPath = url.getPath();

				if (urlPath.endsWith(StringPool.SLASH)) {
					continue;
				}

				String zipPath = urlPath.substring(basePath.length());

				if (zipPath.startsWith(StringPool.SLASH)) {
					zipPath = zipPath.substring(1);
				}

				try (InputStream inputStream = url.openStream()) {
					zipWriter.addEntry(zipPath, inputStream);
				}
			}
		}

		return new FileInputStream(zipWriter.getFile());
	}

	private void _startBundles(List<Bundle> bundles) throws BundleException {
		long timeout = 5000;
		long pollInterval = 100;

		for (Bundle bundle : bundles) {
			bundle.start();

			long startTime = System.currentTimeMillis();

			while (bundle.getState() != Bundle.ACTIVE) {
				if ((System.currentTimeMillis() - startTime) > timeout) {
					throw new RuntimeException(
						"Timeout waiting for bundle " + bundle.getSymbolicName() + " to become ACTIVE");
				}

				try {
					Thread.sleep(pollInterval);
				}
				catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();

					throw new RuntimeException(
						"Interrupted while waiting for bundle to start",
						interruptedException);
				}
			}
		}
	}

	@Inject
	private UserLocalService _userLocalService;

	@Inject
	private RoleLocalService _roleLocalService;

	@Inject(
		filter = "component.name=com.liferay.object.internal.model.listener.ObjectDefinitionModelListener"
	)
	private ModelListener<ObjectDefinition> _objectDefinitionModelListener;

	@Inject
	private BatchEngineUnitProcessor _batchEngineUnitProcessor;

	@Inject
	private BatchEngineUnitReader _batchEngineUnitReader;

	private Bundle _bundle;

}
