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
package io.openliberty.classloading.dynamic.feature.sharedlib.test.app;

import javax.servlet.annotation.WebServlet;

import componenttest.app.FATServlet;

/**
 * Probe servlet for the dynamic-feature-lifecycle shared-library classloader test (Test 2).
 * <p>
 * The classloader chain under test is:
 * <pre>
 *   WAR AppClassLoader
 *     └─ Shared-library AppClassLoader  (cached in ClassLoadingServiceImpl.aclStore)
 *          └─ GatewayClassLoader
 *               └─ EquinoxClassLoader [test.feature.api]
 * </pre>
 * Each probe method captures actual Liberty runtime behaviour across one of the three
 * lifecycle states. The methods are dispatched by name via the {@code testMethod=} query
 * parameter (standard {@link FATServlet} dispatch). They are <em>not</em> annotated with
 * {@code @Test} — the single FAT {@code @Test} in {@code DynamicFeatureSharedLibTest}
 * walks all three states in sequence.
 * <p>
 * The servlet accesses the shared-library class via reflection to avoid a hard
 * compile-time dependency that would prevent the WAR from loading when the shared
 * library is not on the WAR classpath. The library is wired in via
 * {@code <classloader commonLibraryRef="testFeatureSharedLib"/>} in {@code server.xml}.
 */
@WebServlet("/DynamicFeatureSharedLibTestServlet")
public class DynamicFeatureSharedLibTestServlet extends FATServlet {

    private static final long serialVersionUID = 1L;

    private static final String FEATURE_API_CLASS =
        "io.openliberty.classloading.feature.api.TestFeatureApi";
    private static final String LIB_IMPL_CLASS =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl";

    // -------------------------------------------------------------------------
    // State 1 — Feature present at server start
    // -------------------------------------------------------------------------

    /**
     * Verifies that the shared-library implementation is reachable and that the
     * feature API can be loaded and invoked successfully through the library chain.
     * <p>
     * Expected outcome: {@code doWork()} returns a non-empty string. Classloader
     * identity is logged for architectural analysis.
     */
    public void probeState1_FeaturePresent() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - ClassLoader: " + cl);

        // Load the feature API interface — parent-first delegation reaches the
        // feature bundle through the shared-library GatewayClassLoader.
        Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - TestFeatureApi loaded by: "
            + apiClass.getClassLoader());

        // Load the shared-library implementation class.
        Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS);
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - SharedFeatureLibImpl loaded by: "
            + implClass.getClassLoader());

        // Instantiate and cast.
        Object impl = implClass.getDeclaredConstructor().newInstance();
        io.openliberty.classloading.feature.api.TestFeatureApi api =
            (io.openliberty.classloading.feature.api.TestFeatureApi) impl;

        String result = api.doWork();
        if (result == null || result.isEmpty()) {
            throw new AssertionError("doWork() returned null or empty string");
        }
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - SUCCESS: " + result);
    }

    // -------------------------------------------------------------------------
    // State 2 — Feature dynamically removed
    // -------------------------------------------------------------------------

    /**
     * Probes what happens to the shared-library classloader after the feature bundle
     * has been dynamically removed.
     * <p>
     * <b>Key question for Test 2:</b> Unlike the WAR in Test 1, the shared library has
     * its own {@code AppClassLoader} cached in {@code ClassLoadingServiceImpl.aclStore}
     * under the library's identity key. Does Liberty evict that library loader when the
     * feature is removed? Or does it stay resident (same Failure Mode A as Test 1)?
     * <p>
     * <b>Expected baseline (pre-fix):</b> The shared-library {@code AppClassLoader} is
     * not evicted — no {@code server.xml} change fires the delete notification. The
     * feature API class remains visible via the stale cached loader. This probe records
     * the observed outcome so that a future fix can be detected.
     */
    public void probeState2_FeatureRemoved() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - ClassLoader: " + cl);

        try {
            // First try to load the feature API interface.
            Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - TestFeatureApi CLASS_STILL_VISIBLE "
                + "(stale library loader retained): loaded by " + apiClass.getClassLoader());

            // Also check whether the library impl class is still reachable.
            try {
                Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS);
                println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - SharedFeatureLibImpl CLASS_STILL_VISIBLE: "
                    + "loaded by " + implClass.getClassLoader());
            } catch (ClassNotFoundException cnfeImpl) {
                println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - SharedFeatureLibImpl CNFE "
                    + "(lib class not reachable): " + cnfeImpl.getMessage());
            }

            // Lock in the observed baseline: stale library AppClassLoader retained.
            // If Liberty ever starts evicting shared-library loaders on feature removal
            // this assertion will fail, signalling the architectural fix has been made.
            if (!apiClass.getClassLoader().toString().contains("test.feature.api")) {
                throw new AssertionError(
                    "STATE2: unexpected classloader for TestFeatureApi: " + apiClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - CLASS_STILL_VISIBLE");

        } catch (ClassNotFoundException cnfe) {
            // This path would indicate Liberty has been fixed to evict stale library loaders.
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - CNFE (stale loader evicted): "
                + cnfe.getMessage());
            throw new AssertionError(
                "STATE2: TestFeatureApi was not visible after feature removal — "
                + "shared-library loader was evicted (baseline behaviour has changed). "
                + "Update this assertion if the fix is intentional.", cnfe);
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - NCDFE: " + ncdfe.getMessage());
            throw new AssertionError("STATE2: unexpected NoClassDefFoundError", ncdfe);
        }
    }

    // -------------------------------------------------------------------------
    // State 3 — Feature dynamically re-added
    // -------------------------------------------------------------------------

    /**
     * Probes whether the shared-library classloader uses the new bundle revision after
     * the feature has been re-added, and whether this causes a {@code ClassCastException}.
     * <p>
     * <b>The key Test 2 question (Failure Mode B):</b> If Liberty produces a <em>new</em>
     * bundle revision when the feature is re-added (new {@code EquinoxClassLoader} address
     * and bundle id), the stale {@code AppClassLoader} that was cached for the shared
     * library holds type bindings from the <em>old</em> revision. The cast
     * {@code (TestFeatureApi) implInstance} then fails with
     * {@code ClassCastException: TestFeatureApi is not a subtype} — this is
     * <em>Failure Mode B</em>.
     * <p>
     * <b>Possible outcomes — all handled:</b>
     * <ul>
     *   <li>{@code SUCCESS} — same bundle object reused (like Test 1, Failure Mode A silent bug)</li>
     *   <li>{@code CLASSCAST} — new bundle revision produced, stale shared-library loader cached
     *       (Failure Mode B — the bug described in the executive summary)</li>
     *   <li>{@code CNFE} — unexpected; would indicate the loader was evicted but not refreshed</li>
     * </ul>
     * <p>
     * The assertion is written to accept the <em>currently observed</em> outcome and fail
     * loudly if behaviour changes, so the test always records current baseline behaviour
     * rather than prescribing correct behaviour.
     */
    public void probeState3_FeatureReAdded() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - ClassLoader: " + cl);

        try {
            Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - TestFeatureApi loaded by: "
                + apiClass.getClassLoader());

            Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl loaded by: "
                + implClass.getClassLoader());

            Object impl = implClass.getDeclaredConstructor().newInstance();

            io.openliberty.classloading.feature.api.TestFeatureApi api =
                (io.openliberty.classloading.feature.api.TestFeatureApi) impl;

            String result = api.doWork();
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SUCCESS: cast succeeded, doWork()=" + result);

            // Baseline: cast succeeds because the same stale bundle object is still in place.
            // If this flips to CLASSCAST it means Liberty produced a new bundle revision on
            // re-add — Failure Mode B. Update the assertion deliberately in that case.
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
                // ignored — just for diagnostics
            }
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - CLASSCAST (Failure Mode B): "
                + "TestFeatureApi loaded by "
                + (apiClassAfterReAdd != null ? apiClassAfterReAdd.getClassLoader() : "<not loadable>")
                + " — " + cce.getMessage());
            // This is the bug described in the executive summary.  Record as observed baseline.
            // If the intent is that CLASSCAST IS the baseline, remove this throw and replace
            // with an assertion that cce.getMessage() contains "TestFeatureApi".
            throw new AssertionError(
                "STATE3: ClassCastException observed — Failure Mode B confirmed. "
                + "The shared-library AppClassLoader is stale and Liberty produced a new bundle revision. "
                + "Update this assertion to expect CLASSCAST if that is the intended baseline.", cce);

        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - CNFE: class not found after feature re-add: "
                + cnfe.getMessage());
            throw new AssertionError(
                "STATE3: unexpected ClassNotFoundException after feature re-add", cnfe);
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
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
