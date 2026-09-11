/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.classloading.classpath.fat;

import static io.openliberty.classloading.classpath.fat.FATSuite.DYNAMIC_FEATURE_BELL_TEST_SERVER;
import static io.openliberty.classloading.classpath.fat.FATSuite.TEST_DYNAMIC_FEATURE_BELL_APP;
import static io.openliberty.classloading.classpath.fat.FATSuite.TEST_DYNAMIC_FEATURE_BELL_JAR;
import static io.openliberty.classloading.classpath.fat.FATSuite.TEST_DYNAMIC_FEATURE_BELL_WAR;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.ShrinkHelper;
import com.ibm.websphere.simplicity.ShrinkHelper.DeployOptions;

import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;

/**
 * FAT test for the dynamic feature lifecycle classloader baseline (Test 3).
 * <p>
 * Tests the three lifecycle states when a {@code <bell>} element wires a shared
 * library's {@code META-INF/services/} provider through the OSGi service registry.
 * The classloader chain under test is:
 * <pre>
 *   WAR AppClassLoader
 *     └─ Shared-library AppClassLoader  (cached in ClassLoadingServiceImpl.aclStore)
 *          └─ GatewayClassLoader
 *               └─ EquinoxClassLoader [test.feature.api]
 *
 *   Bell (bells-1.0)
 *     reads META-INF/services/io.openliberty.classloading.feature.api.TestFeatureApi
 *       from testFeatureBellLib.jar
 *     → registers SharedFeatureLibImpl as OSGi service at server start
 * </pre>
 *
 * <ol>
 *   <li><b>State 1</b> — Feature present at server start: Bell discovers the provider,
 *       registers it into the OSGi service registry, and the WAR can invoke
 *       {@code doWork()} successfully.</li>
 *   <li><b>State 2</b> — Feature dynamically removed: observes whether Bell unregisters
 *       the service, and whether the shared-library {@code AppClassLoader} is evicted
 *       from {@code aclStore} or retained (stale loader — Failure Mode A).</li>
 *   <li><b>State 3</b> — Feature dynamically re-added: observes whether Bell
 *       re-registers the service, and whether the cast succeeds (same bundle revision,
 *       Failure Mode A) or fails with {@code ClassCastException} (new bundle revision,
 *       Failure Mode B — the bug described in the executive summary).</li>
 * </ol>
 *
 * <p>The test records <em>actual current</em> Liberty behaviour so findings can
 * drive architectural discussions. Assertions are locked in to the observed baseline.
 *
 * <p><b>Why no {@code @TestServlet} / no {@code extends FATServletClient}:</b>
 * The three lifecycle states must run inside a <em>single</em> {@code @Test} method
 * that walks State 1 → 2 → 3 in one ordered sequence. {@code @TestServlet} synthesises
 * independent JUnit tests with no FAT-class code between them, so there is no hook to
 * inject {@code changeFeatures()} and wait for {@code CWWKF0008I}.
 */
@RunWith(FATRunner.class)
public class DynamicFeatureBellTest {

    @Server(DYNAMIC_FEATURE_BELL_TEST_SERVER)
    public static LibertyServer server;

    private static final String SERVLET_PATH =
        TEST_DYNAMIC_FEATURE_BELL_APP + "/DynamicFeatureBellTestServlet";

    @BeforeClass
    public static void setupTestServer() throws Exception {
        // Feature / bundle installs must happen before startServer().
        server.installSystemFeature("testFeatureApi-1.0");
        assertTrue("testFeatureApi-1.0.mf should have been copied to lib/features",
                   server.fileExistsInLibertyInstallRoot("lib/features/testFeatureApi-1.0.mf"));

        server.installSystemBundle("test.feature.api");
        assertTrue("test.feature.api.jar should have been copied to lib",
                   server.fileExistsInLibertyInstallRoot("lib/test.feature.api.jar"));

        ShrinkHelper.exportAppToServer(server, TEST_DYNAMIC_FEATURE_BELL_WAR, DeployOptions.SERVER_ONLY);

        // The Bell library JAR contains SharedFeatureLibImpl and a META-INF/services/ entry
        // for TestFeatureApi, which Bell reads to register the provider as an OSGi service.
        // Deployed to sharedLibs/ to match <fileset dir="${server.config.dir}/sharedLibs"> in server.xml.
        ShrinkHelper.exportToServer(server, "sharedLibs", TEST_DYNAMIC_FEATURE_BELL_JAR, DeployOptions.SERVER_ONLY);

        server.startServer();
    }

    /**
     * Walks State 1 → State 2 → State 3 in a single ordered sequence.
     * <p>
     * Each state is probed via an HTTP call to a named servlet method. After each
     * feature-change, the test waits for {@code CWWKF0008I} (feature update complete)
     * before proceeding to the next probe.
     *
     * <h3>Expected baseline summary (pre-fix)</h3>
     * <pre>
     * State 1  Bell discovers SharedFeatureLibImpl via META-INF/services/
     *          → OSGi service registered at startup
     *          Shared-library AppClassLoader → GatewayClassLoader → EquinoxClassLoader@X [bundle id=N]
     *          — ServiceLoader discovers provider, doWork() succeeds ✓
     *
     * State 2  Shared-library AppClassLoader (cached in aclStore, never evicted)
     *          — feature API class still visible via stale loader (CLASS_STILL_VISIBLE)
     *          — Bell service may or may not be unregistered from OSGi (observable via trace)
     *
     * State 3  If same bundle object reused on re-add (id=N unchanged) → SUCCESS (Failure Mode A)
     *          If new bundle revision produced (id changes) → CLASSCAST (Failure Mode B — the bug)
     *          Bell may or may not re-register the service after re-add (observable via trace)
     * </pre>
     *
     * <p><b>Key observation to capture:</b>
     * The {@code EquinoxClassLoader} address and bundle id logged in States 1 and 3 reveal
     * whether Liberty produces a new bundle revision on re-add. If the id is the same across
     * all three states, the test observes Failure Mode A (silent stale loader). If the id
     * changes between State 1 and State 3, the test observes Failure Mode B
     * ({@code ClassCastException}). The Bell-specific question — whether the OSGi service
     * is unregistered and re-registered across the lifecycle — is answered by correlating
     * the servlet log output with Bell trace messages.
     */
    @Test
    public void testDynamicFeatureBellLifecycle() throws Exception {
        // ── STATE 1: Feature present ─────────────────────────────────────────
        server.setMarkToEndOfLog();
        FATServletClient.runTest(server, SERVLET_PATH, "probeState1_FeaturePresent");
        assertNotNull("State 1 SUCCESS marker not found in log",
                      server.waitForStringInLogUsingMark("DYNAMIC_FEATURE_BELL_TEST STATE1 - SUCCESS"));

        // ── STATE 2: Remove feature dynamically ──────────────────────────────
        server.setMarkToEndOfLog();
        server.changeFeatures(Arrays.asList("servlet-4.0", "componenttest-1.0", "bells-1.0"));
        assertNotNull("Feature update (removal) did not complete — CWWKF0008I not found",
                      server.waitForStringInLogUsingMark("CWWKF0008I"));

        server.setMarkToEndOfLog();
        FATServletClient.runTest(server, SERVLET_PATH, "probeState2_FeatureRemoved");
        assertNotNull("State 2 marker not found in log",
                      server.waitForStringInLogUsingMark("DYNAMIC_FEATURE_BELL_TEST STATE2 -"));

        // ── STATE 3: Re-add feature dynamically ──────────────────────────────
        server.setMarkToEndOfLog();
        server.changeFeatures(Arrays.asList("servlet-4.0", "componenttest-1.0", "bells-1.0", "testFeatureApi-1.0"));
        assertNotNull("Feature update (re-add) did not complete — CWWKF0008I not found",
                      server.waitForStringInLogUsingMark("CWWKF0008I"));

        server.setMarkToEndOfLog();
        FATServletClient.runTest(server, SERVLET_PATH, "probeState3_FeatureReAdded");
        assertNotNull("State 3 marker not found in log",
                      server.waitForStringInLogUsingMark("DYNAMIC_FEATURE_BELL_TEST STATE3 -"));
    }

    @AfterClass
    public static void stopServer() throws Exception {
        try {
            // CWWKL0041W — classloader no longer valid; expected when the feature bundle
            // is removed while a stale AppClassLoader still holds a reference to it.
            server.stopServer("CWWKL0041W");
        } finally {
            server.uninstallSystemFeature("testFeatureApi-1.0");
            assertFalse("testFeatureApi-1.0.mf was not cleaned up",
                        server.fileExistsInLibertyInstallRoot("lib/features/testFeatureApi-1.0.mf"));
            server.uninstallSystemBundle("test.feature.api");
            assertFalse("test.feature.api.jar was not cleaned up",
                        server.fileExistsInLibertyInstallRoot("lib/test.feature.api.jar"));
        }
    }
}
