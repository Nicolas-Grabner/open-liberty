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
package io.openliberty.classloading.dynamic.feature.bell.test.app;

import java.util.Iterator;
import java.util.ServiceLoader;

import javax.servlet.annotation.WebServlet;

import componenttest.app.FATServlet;

/**
 * Probe servlet for the dynamic-feature-lifecycle Bell classloader test (Test 3).
 * <p>
 * The classloader chain under test is:
 * <pre>
 *   WAR AppClassLoader
 *     └─ Shared-library AppClassLoader  (commonLibraryRef="testFeatureBellLib")
 *          └─ GatewayClassLoader
 *               └─ EquinoxClassLoader [test.feature.api]
 *
 *   Bell (bells-1.0 feature)
 *     reads META-INF/services/io.openliberty.classloading.feature.api.TestFeatureApi
 *       in testFeatureBellLib.jar
 *     → registers SharedFeatureLibImpl as an OSGi service at server start
 * </pre>
 * Each probe method captures actual Liberty runtime behaviour across one of the three
 * lifecycle states. The methods are dispatched by name via the {@code testMethod=} query
 * parameter (standard {@link FATServlet} dispatch). They are <em>not</em> annotated with
 * {@code @Test} — the single FAT {@code @Test} in {@code DynamicFeatureBellTest} walks
 * all three states in sequence.
 * <p>
 * The service is discovered via {@link ServiceLoader} using the shared library's
 * {@link ClassLoader} — this mirrors the path Bell uses internally to find providers
 * from {@code META-INF/services/}. The WAR holds the library as a common library
 * ({@code <classloader commonLibraryRef="testFeatureBellLib"/>}), so
 * {@code getClass().getClassLoader()} reaches the library's {@code AppClassLoader}.
 * <p>
 * Uses {@code getClass().getClassLoader()} and {@code loader.loadClass()} rather than
 * the TCCL / {@code Class.forName} — consistent with Tests 1, 2.
 */
@WebServlet("/DynamicFeatureBellTestServlet")
public class DynamicFeatureBellTestServlet extends FATServlet {

    private static final long serialVersionUID = 1L;

    private static final String FEATURE_API_CLASS =
        "io.openliberty.classloading.feature.api.TestFeatureApi";
    private static final String LIB_IMPL_CLASS =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl";

    // -------------------------------------------------------------------------
    // State 1 — Feature present at server start
    // -------------------------------------------------------------------------

    /**
     * Verifies that Bell discovered {@code SharedFeatureLibImpl} from
     * {@code META-INF/services/} inside the shared library, and that the service
     * implementation can be loaded and invoked via {@link ServiceLoader}.
     * <p>
     * Expected outcome: {@code doWork()} returns a non-empty string. Classloader
     * identity (WAR loader, library loader, EquinoxClassLoader) is logged for
     * architectural analysis.
     */
    public void probeState1_FeaturePresent() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - WAR ClassLoader: " + cl);

        // Load the feature API interface — reaches the feature bundle via parent
        // delegation through the shared-library GatewayClassLoader.
        Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - TestFeatureApi loaded by: "
            + apiClass.getClassLoader());

        // Load the shared-library implementation via the library classloader.
        Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS);
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - SharedFeatureLibImpl loaded by: "
            + implClass.getClassLoader());

        // Use ServiceLoader with the library's classloader to simulate the path Bell
        // uses when discovering META-INF/services/ entries.  Bell registers discovered
        // providers into the OSGi service registry; ServiceLoader probes the same
        // classloader to confirm the provider is discoverable.
        @SuppressWarnings("unchecked")
        ServiceLoader<io.openliberty.classloading.feature.api.TestFeatureApi> loader =
            (ServiceLoader<io.openliberty.classloading.feature.api.TestFeatureApi>)
            ServiceLoader.load(
                (Class<io.openliberty.classloading.feature.api.TestFeatureApi>) apiClass,
                cl);

        Iterator<io.openliberty.classloading.feature.api.TestFeatureApi> it = loader.iterator();
        if (!it.hasNext()) {
            throw new AssertionError(
                "STATE1: ServiceLoader found no providers for TestFeatureApi — "
                + "META-INF/services/ entry missing or Bell did not register the service");
        }

        io.openliberty.classloading.feature.api.TestFeatureApi svc = it.next();
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - ServiceLoader provider: "
            + svc.getClass().getName() + " loaded by " + svc.getClass().getClassLoader());

        String result = svc.doWork();
        if (result == null || result.isEmpty()) {
            throw new AssertionError("STATE1: doWork() returned null or empty string");
        }
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - SUCCESS: " + result);
    }

    // -------------------------------------------------------------------------
    // State 2 — Feature dynamically removed
    // -------------------------------------------------------------------------

    /**
     * Probes what happens to the Bell-registered service and the shared-library
     * classloader after the feature bundle has been dynamically removed.
     * <p>
     * <b>Key questions for Test 3:</b>
     * <ol>
     *   <li>Does Bell unregister the OSGi service when the feature bundle is removed?
     *       (Probed indirectly: can the API interface still be loaded via the library
     *       loader?)</li>
     *   <li>Is the shared-library {@code AppClassLoader} evicted from
     *       {@code ClassLoadingServiceImpl.aclStore}, or retained (stale loader)?</li>
     * </ol>
     * <p>
     * <b>Expected baseline (pre-fix):</b> The shared-library {@code AppClassLoader}
     * is not evicted — no {@code server.xml} change fires the delete notification.
     * The feature API class and the library impl class remain visible via the stale
     * cached loader (same Failure Mode A as Tests 1 and 2).
     * <p>
     * Whether Bell itself unregisters the OSGi service when the feature is removed
     * is an open question. The probe checks class visibility (the strongest signal
     * available from a WAR context) and logs the outcome so it can be correlated
     * with Bell trace messages in {@code messages.log}.
     */
    public void probeState2_FeatureRemoved() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE2 - WAR ClassLoader: " + cl);

        try {
            // Try to load the feature API interface.
            Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - TestFeatureApi CLASS_STILL_VISIBLE "
                + "(stale library loader retained): loaded by " + apiClass.getClassLoader());

            // Also check the library impl class.
            try {
                Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS);
                println("DYNAMIC_FEATURE_BELL_TEST STATE2 - SharedFeatureLibImpl CLASS_STILL_VISIBLE: "
                    + "loaded by " + implClass.getClassLoader());
            } catch (ClassNotFoundException cnfeImpl) {
                println("DYNAMIC_FEATURE_BELL_TEST STATE2 - SharedFeatureLibImpl CNFE: "
                    + cnfeImpl.getMessage());
            }

            // Lock in the observed baseline: stale library AppClassLoader retained.
            // If Liberty ever starts evicting shared-library loaders on feature removal,
            // this assertion will fail, signalling that the architectural fix has landed.
            if (!apiClass.getClassLoader().toString().contains("test.feature.api")) {
                throw new AssertionError(
                    "STATE2: unexpected classloader for TestFeatureApi: "
                    + apiClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - CLASS_STILL_VISIBLE");

        } catch (ClassNotFoundException cnfe) {
            // This path means Liberty has been fixed to proactively evict stale library loaders.
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - CNFE (stale loader evicted — baseline has changed): "
                + cnfe.getMessage());
            throw new AssertionError(
                "STATE2: TestFeatureApi was not visible after feature removal — "
                + "shared-library loader was evicted (baseline behaviour has changed). "
                + "Update this assertion if the fix is intentional.", cnfe);
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - NCDFE: " + ncdfe.getMessage());
            throw new AssertionError("STATE2: unexpected NoClassDefFoundError", ncdfe);
        }
    }

    // -------------------------------------------------------------------------
    // State 3 — Feature dynamically re-added
    // -------------------------------------------------------------------------

    /**
     * Probes whether the Bell-registered service is usable after the feature has
     * been re-added, and whether the cast succeeds or fails.
     * <p>
     * <b>Possible outcomes — all handled:</b>
     * <ul>
     *   <li>{@code SUCCESS} — same bundle object reused on re-add (Failure Mode A
     *       silent bug, same as Tests 1 and 2)</li>
     *   <li>{@code CLASSCAST} — new bundle revision produced on re-add; stale
     *       shared-library loader cached in {@code aclStore} — type mismatch between
     *       the old revision's {@code TestFeatureApi} (held by the library loader)
     *       and the new revision's {@code TestFeatureApi} (visible through the updated
     *       WAR gateway). This is Failure Mode B.</li>
     *   <li>{@code CNFE} — unexpected; would indicate the loader was evicted but not
     *       refreshed after re-add.</li>
     * </ul>
     * <p>
     * <b>Bell-specific question:</b> Does Bell re-register the service on feature
     * re-add? If it does, does the newly registered service instance hold type bindings
     * from the old bundle revision (stale library loader) or the new one? Log
     * {@code ServiceLoader} discovery to help answer this from the Bell trace.
     */
    public void probeState3_FeatureReAdded() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE3 - WAR ClassLoader: " + cl);

        try {
            Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - TestFeatureApi loaded by: "
                + apiClass.getClassLoader());

            Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS);
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - SharedFeatureLibImpl loaded by: "
                + implClass.getClassLoader());

            Object impl = implClass.getDeclaredConstructor().newInstance();

            io.openliberty.classloading.feature.api.TestFeatureApi api =
                (io.openliberty.classloading.feature.api.TestFeatureApi) impl;

            // Probe ServiceLoader to observe whether Bell re-registered the service
            // after the feature re-add. This is informational — log regardless of the
            // doWork() outcome so the Bell trace can be correlated with this output.
            @SuppressWarnings("unchecked")
            ServiceLoader<io.openliberty.classloading.feature.api.TestFeatureApi> loader =
                (ServiceLoader<io.openliberty.classloading.feature.api.TestFeatureApi>)
                ServiceLoader.load(
                    (Class<io.openliberty.classloading.feature.api.TestFeatureApi>) apiClass,
                    cl);

            Iterator<io.openliberty.classloading.feature.api.TestFeatureApi> it = loader.iterator();
            if (it.hasNext()) {
                io.openliberty.classloading.feature.api.TestFeatureApi svc = it.next();
                println("DYNAMIC_FEATURE_BELL_TEST STATE3 - ServiceLoader provider after re-add: "
                    + svc.getClass().getName() + " loaded by " + svc.getClass().getClassLoader());
            } else {
                println("DYNAMIC_FEATURE_BELL_TEST STATE3 - ServiceLoader found no providers after re-add "
                    + "(Bell may not have re-registered the service)");
            }

            String result = api.doWork();
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - SUCCESS: cast succeeded, doWork()=" + result);

            // Baseline: cast succeeds because the same stale bundle object is still in
            // place (Failure Mode A). If this flips to CLASSCAST it means Liberty
            // produced a new bundle revision on re-add — Failure Mode B. Update the
            // assertion deliberately in that case.
            if (result == null || result.isEmpty()) {
                throw new AssertionError("STATE3: doWork() returned null or empty");
            }

        } catch (ClassCastException cce) {
            // Failure Mode B: new bundle revision produced on re-add; stale library loader
            // still cached in aclStore — type mismatch detected.
            Class<?> apiClassAfterReAdd = null;
            try {
                apiClassAfterReAdd = cl.loadClass(FEATURE_API_CLASS);
            } catch (ClassNotFoundException ignored) {
                // ignored — diagnostic only
            }
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - CLASSCAST (Failure Mode B): "
                + "TestFeatureApi loaded by "
                + (apiClassAfterReAdd != null ? apiClassAfterReAdd.getClassLoader() : "<not loadable>")
                + " — " + cce.getMessage());
            // This is the bug described in the executive summary.  Record as observed baseline.
            // If CLASSCAST is the intended baseline, replace this throw with an assertion
            // that cce.getMessage() contains "TestFeatureApi".
            throw new AssertionError(
                "STATE3: ClassCastException observed — Failure Mode B confirmed via Bell path. "
                + "The shared-library AppClassLoader is stale and Liberty produced a new bundle revision. "
                + "Update this assertion to expect CLASSCAST if that is the intended baseline.", cce);

        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - CNFE: class not found after feature re-add: "
                + cnfe.getMessage());
            throw new AssertionError(
                "STATE3: unexpected ClassNotFoundException after feature re-add", cnfe);
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
            throw new AssertionError("STATE3: unexpected NoClassDefFoundError", ncdfe);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static void println(String message) {
        System.out.println(message);
    }
}
