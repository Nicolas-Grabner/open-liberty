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
package io.openliberty.classloading.dynamic.feature.lifecycle.test.app;

import java.util.Iterator;
import java.util.ServiceLoader;

import javax.servlet.annotation.WebServlet;

import org.junit.Assert;

import componenttest.app.FATServlet;
import io.openliberty.classloading.feature.api.TestFeatureApi;

/**
 * Single consolidated probe servlet for all three dynamic-feature-lifecycle
 * classloader tests (Tests 1, 2 and 3).
 *
 * <p>All nine probe methods live here. They are dispatched by name via the
 * {@code testMethod=} query parameter (standard {@link FATServlet} dispatch).
 * None are annotated with {@code @Test} — each FAT test class walks its own
 * three lifecycle states in one ordered sequence via a single {@code @Test} method.
 *
 * <p>The same WAR artifact is deployed to three different servers whose
 * {@code server.xml} configurations wire up different classloader chains:
 * <ul>
 *   <li><b>dynamicFeatureLifecycleTest</b> — WAR directly depends on feature API
 *       (no shared library). Uses {@link TestFeatureApiImpl}, {@link TestFeatureApiImpl2},
 *       {@link TestFeatureApiImpl3} — in-WAR classes, one per lifecycle state.</li>
 *   <li><b>dynamicFeatureSharedLibTest</b> — WAR → shared library → feature API.
 *       Uses {@code SharedFeatureLibImpl}, {@code SharedFeatureLibImpl2},
 *       {@code SharedFeatureLibImpl3} — library classes, one per lifecycle state.</li>
 *   <li><b>dynamicFeatureBellTest</b> — Bell ({@code META-INF/services}) → shared
 *       library → feature API. Uses {@code BellFeatureLibImpl},
 *       {@code BellFeatureLibImpl2}, {@code BellFeatureLibImpl3} — Bell library
 *       classes, one per lifecycle state.</li>
 * </ul>
 *
 * <h3>Why one class per lifecycle state?</h3>
 * {@code ClassLoader.loadClass()} calls {@code findLoadedClass()} first. If the
 * class was already loaded in a prior state it is returned from the cache and
 * no delegation chain walk occurs — hiding the true classloader behaviour under
 * test. Using a distinct, previously-unloaded class per state forces a genuine
 * re-lookup each time.
 *
 * <p>Uses {@code getClass().getClassLoader()} and {@code loader.loadClass()} rather
 * than the TCCL / {@code Class.forName} — avoids the {@code ThreadContextClassLoader}
 * wrapper and static-initialiser side-effects.
 */
@WebServlet("/DynamicFeatureLifecycleTestServlet")
public class DynamicFeatureLifecycleTestServlet extends FATServlet {

    private static final long serialVersionUID = 1L;

    // ── Feature API (defined by the OSGi bundle, loaded via parent delegation) ──
    private static final String FEATURE_API_CLASS =
        "io.openliberty.classloading.feature.api.TestFeatureApi";

    // ── Test 1: in-WAR impl classes (one per state) ──────────────────────────
    private static final String APP_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl";
    private static final String APP_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl2";
    private static final String APP_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl3";

    // ── Test 2: shared-library impl classes (one per state) ──────────────────
    private static final String LIB_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl";
    private static final String LIB_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl2";
    private static final String LIB_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl3";

    // ── Test 3: Bell library impl classes (one per state) ────────────────────
    private static final String BELL_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl";
    private static final String BELL_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl2";
    private static final String BELL_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl3";

    // =========================================================================
    // Test 1 — Application directly depends on feature API (no shared library)
    // =========================================================================

    /**
     * Test 1, State 1: verifies that the in-WAR feature API implementation is
     * reachable and that {@code doWork()} succeeds when the feature is present.
     * <p>
     * {@link TestFeatureApiImpl} (the State 1 class) is newly loaded here — it has
     * not been touched before this probe, so {@code findLoadedClass()} will not
     * short-circuit the lookup.
     */
    public void testAppDirectlyDependsOnFeatureApi_FeaturePresent() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE1 - AppCL: " + cl);

        // Load the State 1 in-WAR impl — first load, guaranteed fresh lookup.
        Class<?> implClass = cl.loadClass(APP_IMPL_CLASS_STATE1);
        println("DYNAMIC_FEATURE_TEST STATE1 - TestFeatureApiImpl loaded by: "
            + implClass.getClassLoader());

        Object impl = implClass.getDeclaredConstructor().newInstance();
        TestFeatureApi api = (TestFeatureApi) impl;

        String result = api.doWork();
        Assert.assertNotNull("STATE1: doWork() returned null", result);
        Assert.assertFalse("STATE1: doWork() returned empty string", result.isEmpty());
        println("DYNAMIC_FEATURE_TEST STATE1 - SUCCESS: " + result);
    }

    /**
     * Test 1, State 2: probes what happens to the WAR classloader after the feature
     * bundle has been dynamically removed.
     * <p>
     * {@link TestFeatureApiImpl2} is used here so that {@code findLoadedClass()} does
     * not return a result cached from State 1. This forces a genuine re-lookup through
     * the WAR's {@code AppClassLoader} delegation chain.
     * <p>
     * <b>Expected baseline (pre-fix):</b> The WAR's {@code AppClassLoader} is not
     * evicted from {@code ClassLoadingServiceImpl.aclStore} — no {@code server.xml}
     * change fires the delete notification. {@code TestFeatureApiImpl2} can still be
     * found because the stale cached {@code EquinoxClassLoader} for the removed bundle
     * remains reachable. {@code CLASS_STILL_VISIBLE} is the observed outcome.
     * <p>
     * If this assertion ever flips to {@code CNFE} it means Liberty has started
     * proactively invalidating app classloaders on feature removal — the intended fix.
     */
    public void testAppDirectlyDependsOnFeatureApi_FeatureRemoved() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE2 - AppCL: " + cl);

        try {
            // Load the State 2 in-WAR impl — fresh class, not cached from State 1.
            Class<?> implClass = cl.loadClass(APP_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_TEST STATE2 - CLASS_STILL_VISIBLE (stale loader retained): "
                + "TestFeatureApiImpl2 loaded by " + implClass.getClassLoader());

            // Lock in the observed baseline: stale AppClassLoader retained in aclStore.
            // Update this assertion intentionally if Liberty is fixed to evict it.
            if (!implClass.getClassLoader().toString().contains("WebModule")) {
                Assert.fail("STATE2: unexpected classloader for TestFeatureApiImpl2: "
                    + implClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_TEST STATE2 - CLASS_STILL_VISIBLE");

        } catch (ClassNotFoundException cnfe) {
            // This path means Liberty has been fixed to evict stale AppClassLoaders.
            println("DYNAMIC_FEATURE_TEST STATE2 - CNFE (stale loader evicted — baseline has changed): "
                + cnfe.getMessage());
            Assert.fail("STATE2: TestFeatureApiImpl2 was not visible after feature removal — "
                + "baseline behaviour has changed. Update this assertion if the fix is intentional.");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_TEST STATE2 - NCDFE: " + ncdfe.getMessage());
            Assert.fail("STATE2: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    /**
     * Test 1, State 3: verifies that all three in-WAR impl classes are loadable after
     * the feature has been re-added.
     * <p>
     * {@link TestFeatureApiImpl3} is the State 3 class (fresh, not cached). All three
     * impl classes are then loaded to confirm the WAR classloader is fully functional
     * after the feature re-add.
     * <p>
     * <b>Expected baseline (pre-fix):</b> All three loads succeed because the same
     * OSGi bundle object was reused on re-add (same {@code EquinoxClassLoader} instance,
     * same bundle id — Failure Mode A). The cast succeeds for the wrong reason: the
     * stale never-invalidated loader is still in place.
     */
    public void testAppDirectlyDependsOnFeatureApi_FeatureReAdded() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE3 - AppCL: " + cl);

        try {
            // Load the State 3 impl first — fresh, not cached from earlier states.
            Class<?> implClass3 = cl.loadClass(APP_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi api3 = (TestFeatureApi) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl3 SUCCESS: " + result3);

            // Also verify all three impl classes load cleanly after the re-add.
            Class<?> implClass1 = cl.loadClass(APP_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(APP_IMPL_CLASS_STATE2);
            TestFeatureApi api2 = (TestFeatureApi) implClass2.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl2.doWork() returned null", api2.doWork());
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl2 re-load SUCCESS");

            println("DYNAMIC_FEATURE_TEST STATE3 - SUCCESS: all three impls loaded and invoked");

        } catch (ClassCastException cce) {
            println("DYNAMIC_FEATURE_TEST STATE3 - CLASSCAST (Failure Mode B): " + cce.getMessage());
            Assert.fail("STATE3: unexpected ClassCastException — baseline behaviour has changed. "
                + "Update this assertion if the change is intentional.");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_TEST STATE3 - CNFE: " + cnfe.getMessage());
            Assert.fail("STATE3: unexpected ClassNotFoundException after feature re-add: "
                + cnfe.getMessage());
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
            Assert.fail("STATE3: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    // =========================================================================
    // Test 2 — Application → shared library → feature API
    // =========================================================================

    /**
     * Test 2, State 1: verifies that the shared-library implementation is reachable
     * and that {@code doWork()} succeeds through the library chain when the feature
     * is present.
     * <p>
     * Only the library impl class is loaded via the WAR classloader — the WAR's
     * {@code AppClassLoader} is intentionally <em>not</em> made the initiating
     * classloader for {@code TestFeatureApi} directly. The feature API class is
     * loaded transitively by the shared-library classloader when it resolves
     * {@code SharedFeatureLibImpl}'s supertype. This keeps the WAR loader out of
     * the feature API delegation path and isolates the test to the library loader.
     */
    public void testLibraryDependsOnFeatureApi_FeaturePresent() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - WAR ClassLoader: " + cl);

        // Load only the State 1 library impl — do NOT call cl.loadClass(FEATURE_API_CLASS)
        // directly, to avoid making the WAR classloader the initiating CL for TestFeatureApi.
        Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS_STATE1);
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - SharedFeatureLibImpl loaded by: "
            + implClass.getClassLoader());

        Object impl = implClass.getDeclaredConstructor().newInstance();
        TestFeatureApi api = (TestFeatureApi) impl;

        String result = api.doWork();
        Assert.assertNotNull("STATE1: doWork() returned null", result);
        Assert.assertFalse("STATE1: doWork() returned empty string", result.isEmpty());
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE1 - SUCCESS: " + result);
    }

    /**
     * Test 2, State 2: probes what happens to the shared-library classloader after
     * the feature bundle has been dynamically removed.
     * <p>
     * {@code SharedFeatureLibImpl2} is used here — a fresh class, not cached from
     * State 1 — so {@code findLoadedClass()} does not short-circuit the lookup.
     * This forces a genuine re-lookup through the library's {@code AppClassLoader}
     * delegation chain.
     * <p>
     * <b>Expected baseline (pre-fix):</b> The shared-library {@code AppClassLoader}
     * is not evicted from {@code aclStore}. {@code SharedFeatureLibImpl2} is still
     * visible because the stale library loader (and its still-reachable
     * {@code EquinoxClassLoader} for the removed bundle) remain in memory.
     */
    public void testLibraryDependsOnFeatureApi_FeatureRemoved() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - WAR ClassLoader: " + cl);

        try {
            // Load the State 2 library impl — fresh class, not cached from State 1.
            Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - CLASS_STILL_VISIBLE "
                + "(stale library loader retained): SharedFeatureLibImpl2 loaded by "
                + implClass.getClassLoader());

            // Lock in the observed baseline: stale library AppClassLoader retained.
            // Update this assertion intentionally if Liberty is fixed to evict it.
            if (!implClass.getClassLoader().toString().contains("testFeatureSharedLib")) {
                Assert.fail("STATE2: unexpected classloader for SharedFeatureLibImpl2: "
                    + implClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - CLASS_STILL_VISIBLE");

        } catch (ClassNotFoundException cnfe) {
            // This path means Liberty has been fixed to evict stale library loaders.
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - CNFE (stale loader evicted — baseline has changed): "
                + cnfe.getMessage());
            Assert.fail("STATE2: SharedFeatureLibImpl2 was not visible after feature removal — "
                + "baseline behaviour has changed. Update this assertion if the fix is intentional.");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2 - NCDFE: " + ncdfe.getMessage());
            Assert.fail("STATE2: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    /**
     * Test 2, State 3: verifies that all three shared-library impl classes are loadable
     * after the feature has been re-added.
     * <p>
     * {@code SharedFeatureLibImpl3} is the State 3 class (fresh, not cached). All three
     * library impl classes are then loaded to confirm the full library chain is functional
     * after the feature re-add.
     * <p>
     * <b>Failure Mode B watch:</b> If Liberty produces a new bundle revision on re-add
     * while the stale library {@code AppClassLoader} is still cached in {@code aclStore},
     * the cast will fail with {@code ClassCastException}. This is the architectural bug
     * described in the background document. The assertion is locked in to the currently
     * observed {@code SUCCESS} baseline and will fail loudly if behaviour changes.
     */
    public void testLibraryDependsOnFeatureApi_FeatureReAdded() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - WAR ClassLoader: " + cl);

        try {
            // Load the State 3 library impl first — fresh, not cached from earlier states.
            Class<?> implClass3 = cl.loadClass(LIB_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi api3 = (TestFeatureApi) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl3 SUCCESS: " + result3);

            // Verify all three library impls load cleanly after the re-add.
            Class<?> implClass1 = cl.loadClass(LIB_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(LIB_IMPL_CLASS_STATE2);
            TestFeatureApi api2 = (TestFeatureApi) implClass2.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl2.doWork() returned null", api2.doWork());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl2 re-load SUCCESS");

            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SUCCESS: all three impls loaded and invoked");

        } catch (ClassCastException cce) {
            // Failure Mode B: new bundle revision produced on re-add; stale library loader
            // still cached in aclStore — type mismatch detected. This is the bug.
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - CLASSCAST (Failure Mode B): "
                + cce.getMessage());
            Assert.fail("STATE3: ClassCastException observed — Failure Mode B confirmed. "
                + "The shared-library AppClassLoader is stale and Liberty produced a new bundle revision. "
                + "Update this assertion to expect CLASSCAST if that is the intended baseline.");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - CNFE: " + cnfe.getMessage());
            Assert.fail("STATE3: unexpected ClassNotFoundException after feature re-add: "
                + cnfe.getMessage());
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
            Assert.fail("STATE3: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    // =========================================================================
    // Test 3 — Bell (META-INF/services) → shared library → feature API
    // =========================================================================

    /**
     * Test 3, State 1: verifies that Bell discovered {@code BellFeatureLibImpl} from
     * {@code META-INF/services/} and that the service can be found via
     * {@link ServiceLoader} and invoked successfully.
     * <p>
     * Only the Bell library impl class is loaded via the WAR classloader — the WAR's
     * {@code AppClassLoader} is intentionally not made the initiating classloader for
     * {@code TestFeatureApi} directly. The feature API class is loaded transitively
     * by the Bell library classloader when it resolves {@code BellFeatureLibImpl}'s
     * supertype, keeping the test focused on the Bell library loader.
     */
    public void testBellLibraryDependsOnFeatureApi_FeaturePresent() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - WAR ClassLoader: " + cl);

        // Load only the State 1 Bell impl — do NOT call cl.loadClass(FEATURE_API_CLASS)
        // directly, to avoid making the WAR classloader the initiating CL for TestFeatureApi.
        Class<?> implClass = cl.loadClass(BELL_IMPL_CLASS_STATE1);
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - BellFeatureLibImpl loaded by: "
            + implClass.getClassLoader());

        Object impl = implClass.getDeclaredConstructor().newInstance();
        TestFeatureApi api = (TestFeatureApi) impl;

        // Also probe via ServiceLoader to confirm Bell registered the service correctly.
        Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
        @SuppressWarnings("unchecked")
        ServiceLoader<TestFeatureApi> loader =
            (ServiceLoader<TestFeatureApi>)
            ServiceLoader.load((Class<TestFeatureApi>) apiClass, cl);

        Iterator<TestFeatureApi> it = loader.iterator();
        if (!it.hasNext()) {
            Assert.fail("STATE1: ServiceLoader found no providers for TestFeatureApi — "
                + "META-INF/services/ entry missing or Bell did not register the service");
        }
        TestFeatureApi svc = it.next();
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - ServiceLoader provider: "
            + svc.getClass().getName() + " loaded by " + svc.getClass().getClassLoader());

        String result = api.doWork();
        Assert.assertNotNull("STATE1: doWork() returned null", result);
        Assert.assertFalse("STATE1: doWork() returned empty string", result.isEmpty());
        println("DYNAMIC_FEATURE_BELL_TEST STATE1 - SUCCESS: " + result);
    }

    /**
     * Test 3, State 2: probes what happens to the Bell library classloader and the
     * Bell-registered service after the feature bundle has been dynamically removed.
     * <p>
     * {@code BellFeatureLibImpl2} is used here — a fresh class, not cached from
     * State 1 — so {@code findLoadedClass()} does not short-circuit the lookup.
     * <p>
     * <b>Expected baseline (pre-fix):</b> The Bell library {@code AppClassLoader} is
     * not evicted from {@code aclStore}. {@code BellFeatureLibImpl2} is still visible
     * via the stale cached library loader. Whether Bell itself unregisters the OSGi
     * service is an open question — the probe checks class visibility (the strongest
     * signal available from a WAR context) and logs the outcome for correlation with
     * Bell trace messages.
     */
    public void testBellLibraryDependsOnFeatureApi_FeatureRemoved() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE2 - WAR ClassLoader: " + cl);

        try {
            // Load the State 2 Bell impl — fresh class, not cached from State 1.
            Class<?> implClass = cl.loadClass(BELL_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - CLASS_STILL_VISIBLE "
                + "(stale Bell library loader retained): BellFeatureLibImpl2 loaded by "
                + implClass.getClassLoader());

            // Lock in the observed baseline: stale Bell library AppClassLoader retained.
            // Update this assertion intentionally if Liberty is fixed to evict it.
            if (!implClass.getClassLoader().toString().contains("testFeatureBellLib")) {
                Assert.fail("STATE2: unexpected classloader for BellFeatureLibImpl2: "
                    + implClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - CLASS_STILL_VISIBLE");

        } catch (ClassNotFoundException cnfe) {
            // This path means Liberty has been fixed to evict stale Bell library loaders.
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - CNFE (stale loader evicted — baseline has changed): "
                + cnfe.getMessage());
            Assert.fail("STATE2: BellFeatureLibImpl2 was not visible after feature removal — "
                + "baseline behaviour has changed. Update this assertion if the fix is intentional.");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE2 - NCDFE: " + ncdfe.getMessage());
            Assert.fail("STATE2: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    /**
     * Test 3, State 3: verifies that all three Bell library impl classes are loadable
     * after the feature has been re-added, and that the Bell-registered service is
     * discoverable via {@link ServiceLoader}.
     * <p>
     * {@code BellFeatureLibImpl3} is the State 3 class (fresh, not cached). All three
     * Bell library impl classes are then loaded to confirm the full Bell library chain
     * is functional after the feature re-add.
     * <p>
     * The {@link ServiceLoader} probe is repeated here to observe whether Bell
     * re-registered the service after the feature re-add. This is informational —
     * the outcome is logged so it can be correlated with Bell trace in
     * {@code messages.log}.
     */
    public void testBellLibraryDependsOnFeatureApi_FeatureReAdded() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE3 - WAR ClassLoader: " + cl);

        try {
            // Load the State 3 Bell impl first — fresh, not cached from earlier states.
            Class<?> implClass3 = cl.loadClass(BELL_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi api3 = (TestFeatureApi) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: BellFeatureLibImpl3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl3 SUCCESS: " + result3);

            // Verify all three Bell impls load cleanly after the re-add.
            Class<?> implClass1 = cl.loadClass(BELL_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: BellFeatureLibImpl.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(BELL_IMPL_CLASS_STATE2);
            TestFeatureApi api2 = (TestFeatureApi) implClass2.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: BellFeatureLibImpl2.doWork() returned null", api2.doWork());
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl2 re-load SUCCESS");

            // Probe ServiceLoader to observe whether Bell re-registered the service after re-add.
            Class<?> apiClass = cl.loadClass(FEATURE_API_CLASS);
            @SuppressWarnings("unchecked")
            ServiceLoader<TestFeatureApi> loader =
                (ServiceLoader<TestFeatureApi>)
                ServiceLoader.load((Class<TestFeatureApi>) apiClass, cl);
            Iterator<TestFeatureApi> it = loader.iterator();
            if (it.hasNext()) {
                TestFeatureApi svc = it.next();
                println("DYNAMIC_FEATURE_BELL_TEST STATE3 - ServiceLoader provider after re-add: "
                    + svc.getClass().getName() + " loaded by " + svc.getClass().getClassLoader());
            } else {
                println("DYNAMIC_FEATURE_BELL_TEST STATE3 - ServiceLoader found no providers after re-add "
                    + "(Bell may not have re-registered the service)");
            }

            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - SUCCESS: all three impls loaded and invoked");

        } catch (ClassCastException cce) {
            // Failure Mode B: new bundle revision produced on re-add; stale Bell library
            // loader still cached in aclStore — type mismatch detected.
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - CLASSCAST (Failure Mode B): "
                + cce.getMessage());
            Assert.fail("STATE3: ClassCastException observed — Failure Mode B confirmed via Bell path. "
                + "The Bell library AppClassLoader is stale and Liberty produced a new bundle revision. "
                + "Update this assertion to expect CLASSCAST if that is the intended baseline.");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - CNFE: " + cnfe.getMessage());
            Assert.fail("STATE3: unexpected ClassNotFoundException after feature re-add: "
                + cnfe.getMessage());
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
            Assert.fail("STATE3: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static void println(String message) {
        System.out.println(message);
    }
}
