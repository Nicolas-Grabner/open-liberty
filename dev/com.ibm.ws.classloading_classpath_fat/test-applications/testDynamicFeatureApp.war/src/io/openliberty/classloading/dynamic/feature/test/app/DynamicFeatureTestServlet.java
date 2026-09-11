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
package io.openliberty.classloading.dynamic.feature.test.app;

import javax.servlet.annotation.WebServlet;

import componenttest.app.FATServlet;

/**
 * Probe servlet for the dynamic-feature-lifecycle classloader test.
 * <p>
 * Each probe method captures actual Liberty runtime behaviour across one of the
 * three lifecycle states. The methods are dispatched by name via the
 * {@code testMethod=} query parameter (standard {@link FATServlet} dispatch).
 * They are <em>not</em> annotated with {@code @Test} — the single FAT
 * {@code @Test} method in {@code DynamicFeatureLifecycleTest} walks all three
 * states in sequence.
 * <p>
 * Classloader identity is logged in every probe so that architectural analysis
 * of stale-loader scenarios can be done from the server output.
 * <p>
 * Uses {@code getClass().getClassLoader()} (the WAR's {@code AppClassLoader} directly)
 * and {@code loader.loadClass(name)} — consistent with Tests 2 and 3. This avoids the
 * {@code ThreadContextClassLoader} wrapper above the {@code AppClassLoader} and the
 * static-initializer side-effects of {@code Class.forName}.
 */
@WebServlet("/DynamicFeatureTestServlet")
public class DynamicFeatureTestServlet extends FATServlet {

    private static final long serialVersionUID = 1L;

    private static final String FEATURE_API_CLASS = "io.openliberty.classloading.feature.api.TestFeatureApi";

    // -------------------------------------------------------------------------
    // State 1 — Feature present at server start
    // -------------------------------------------------------------------------

    /**
     * Verifies that the feature API is accessible and that an in-WAR
     * implementation can be cast and invoked successfully.
     */
    public void probeState1_FeaturePresent() throws Exception {
        ClassLoader appCL = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE1 - AppCL: " + appCL);

        // Load the API interface via the app classloader (parent-first delegation
        // will find it in the feature bundle).
        Class<?> apiClass = appCL.loadClass(FEATURE_API_CLASS);
        println("DYNAMIC_FEATURE_TEST STATE1 - TestFeatureApi loaded by: " + apiClass.getClassLoader());

        // Instantiate the in-WAR implementation and cast to the feature interface.
        Object impl = new TestFeatureApiImpl();
        io.openliberty.classloading.feature.api.TestFeatureApi api =
            (io.openliberty.classloading.feature.api.TestFeatureApi) impl;

        String result = api.doWork();
        if (result == null || result.isEmpty()) {
            throw new AssertionError("doWork() returned null or empty string");
        }
        println("DYNAMIC_FEATURE_TEST STATE1 - SUCCESS: " + result);
    }

    // -------------------------------------------------------------------------
    // State 2 — Feature dynamically removed
    // -------------------------------------------------------------------------

    /**
     * Probes what happens to the app classloader after the feature bundle has been
     * dynamically removed.
     * <p>
     * <b>Observed baseline behaviour (Test 1, direct app → feature dependency):</b>
     * The {@code AppClassLoader} is cached in {@code ClassLoadingServiceImpl.aclStore}
     * and is never evicted when only a feature is removed (no {@code server.xml} change
     * fires a delete notification). As a result, the cached {@code EquinoxClassLoader}
     * for the removed bundle remains reachable and {@code TestFeatureApi} is still
     * visible — {@code CLASS_STILL_VISIBLE} is the expected outcome for this scenario.
     * <p>
     * If this assertion ever changes to {@code CNFE} it means Liberty has started
     * proactively invalidating app classloaders on feature removal — which would be
     * the architecturally correct fix described in the background document.
     */
    public void probeState2_FeatureRemoved() throws Exception {
        ClassLoader appCL = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE2 - AppCL: " + appCL);

        try {
            Class<?> apiClass = appCL.loadClass(FEATURE_API_CLASS);
            // Baseline: stale AppClassLoader retained in aclStore — class is still visible.
            println("DYNAMIC_FEATURE_TEST STATE2 - CLASS_STILL_VISIBLE (stale loader retained): loaded by "
                + apiClass.getClassLoader());
            // Lock in the observed baseline. If Liberty ever starts evicting the cached
            // AppClassLoader on feature removal this assertion will fail, signalling the
            // architectural fix has been made and State 2 behaviour has changed.
            if (!apiClass.getClassLoader().toString().contains("test.feature.api")) {
                throw new AssertionError("STATE2: unexpected classloader for TestFeatureApi: "
                    + apiClass.getClassLoader());
            }
        } catch (ClassNotFoundException cnfe) {
            // This path means Liberty has been fixed to proactively evict stale loaders.
            println("DYNAMIC_FEATURE_TEST STATE2 - CNFE (stale loader evicted — baseline has changed): "
                + cnfe.getMessage());
            throw new AssertionError("STATE2: TestFeatureApi was not visible after feature removal — "
                + "baseline behaviour has changed (stale loader no longer retained). "
                + "Update this assertion if the fix is intentional.", cnfe);
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_TEST STATE2 - NCDFE: " + ncdfe.getMessage());
            throw new AssertionError("STATE2: unexpected NoClassDefFoundError", ncdfe);
        }
    }

    // -------------------------------------------------------------------------
    // State 3 — Feature dynamically re-added
    // -------------------------------------------------------------------------

    /**
     * Probes whether the app classloader uses the new bundle revision after the
     * feature has been re-added.
     * <p>
     * <b>Observed baseline behaviour (Test 1, direct app → feature dependency):</b>
     * Because the stale {@code AppClassLoader} was never evicted in State 2, the same
     * {@code EquinoxClassLoader} instance (same address, same bundle revision id) is
     * present in State 3. The re-add re-wires the same OSGi bundle object rather than
     * producing a new revision, so the cast succeeds — but only because the bug from
     * State 2 "helped". The bundle id is identical across all three states.
     * <p>
     * If Liberty is ever fixed to properly evict and reload the app classloader this
     * probe may transition through {@code CLASSCAST} (new bundle revision, stale cached
     * loader) before reaching {@code SUCCESS} — update the assertion at that point.
     */
    public void probeState3_FeatureReAdded() throws Exception {
        ClassLoader appCL = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE3 - AppCL: " + appCL);

        try {
            Class<?> apiClass = appCL.loadClass(FEATURE_API_CLASS);
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApi loaded by: " + apiClass.getClassLoader());

            // Attempt to cast in-WAR impl to the (potentially new-revision) interface.
            Object impl = new TestFeatureApiImpl();
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl classloader: "
                + impl.getClass().getClassLoader());

            io.openliberty.classloading.feature.api.TestFeatureApi api =
                (io.openliberty.classloading.feature.api.TestFeatureApi) impl;

            String result = api.doWork();
            println("DYNAMIC_FEATURE_TEST STATE3 - SUCCESS: class loaded and cast succeeded, doWork()=" + result);

            // Lock in observed baseline: cast succeeds because the same stale bundle
            // instance (never evicted in State 2) is still in place after re-add.
            if (result == null || result.isEmpty()) {
                throw new AssertionError("STATE3: doWork() returned null or empty");
            }

        } catch (ClassCastException cce) {
            // This path means Liberty produced a new bundle revision on re-add while the
            // old AppClassLoader was still cached in aclStore (Failure Mode B).
            Class<?> apiClassDiag;
            try {
                apiClassDiag = appCL.loadClass(FEATURE_API_CLASS);
            } catch (ClassNotFoundException cnfe2) {
                apiClassDiag = null;
            }
            println("DYNAMIC_FEATURE_TEST STATE3 - CLASSCAST: TestFeatureApi loaded by "
                + (apiClassDiag != null ? apiClassDiag.getClassLoader() : "<not loadable>")
                + " — " + cce.getMessage());
            throw new AssertionError("STATE3: unexpected ClassCastException — baseline behaviour "
                + "has changed. Update this assertion if the change is intentional.", cce);

        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_TEST STATE3 - CNFE: class not found even after feature re-add: "
                + cnfe.getMessage());
            throw new AssertionError("STATE3: unexpected ClassNotFoundException after feature re-add", cnfe);
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
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
