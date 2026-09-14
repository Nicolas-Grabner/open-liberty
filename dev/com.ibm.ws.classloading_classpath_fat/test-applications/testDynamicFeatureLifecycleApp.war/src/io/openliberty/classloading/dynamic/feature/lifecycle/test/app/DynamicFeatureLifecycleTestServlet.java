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
import io.openliberty.classloading.feature.api.TestFeatureApi2;
import io.openliberty.classloading.feature.api.TestFeatureApi3;

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

    // ── Test 1: in-WAR impl classes ───────────────────────────────────────────
    // State 1: initial load, feature present; implements TestFeatureApi
    private static final String APP_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl";
    // State 2 success probe: cached TestFeatureApi — expects success even after removal
    private static final String APP_IMPL_CLASS_STATE2_REMOVED =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl_removed";
    // State 2 NCDFE probe: fresh TestFeatureApi2 — expects NoClassDefFoundError after removal
    private static final String APP_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl2";
    // State 3: fresh TestFeatureApi3 — expects success after feature re-add
    private static final String APP_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.dynamic.feature.test.app.TestFeatureApiImpl3";

    // ── Test 2: shared-library impl classes ───────────────────────────────────
    // State 1: initial load, feature present; implements TestFeatureApi
    private static final String LIB_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl";
    // State 2 success probe: cached TestFeatureApi — expects success even after removal
    private static final String LIB_IMPL_CLASS_STATE2_REMOVED =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl_removed";
    // State 2 NCDFE probe: fresh TestFeatureApi2 — expects NoClassDefFoundError after removal
    private static final String LIB_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl2";
    // State 3: fresh TestFeatureApi3 — expects success after feature re-add
    private static final String LIB_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl3";

    // ── Test 3: Bell library impl classes ─────────────────────────────────────
    // State 1: initial load, feature present; implements TestFeatureApi
    private static final String BELL_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl";
    // State 2 success probe: cached TestFeatureApi — expects success even after removal
    private static final String BELL_IMPL_CLASS_STATE2_REMOVED =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl_removed";
    // State 2 NCDFE probe: fresh TestFeatureApi2 — expects NoClassDefFoundError after removal
    private static final String BELL_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl2";
    // State 3: fresh TestFeatureApi3 — expects success after feature re-add
    private static final String BELL_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.shared.feature.lib.BellFeatureLibImpl3";

    // ── Test 4: library JAR removed from shared library fileset ───────────────
    // State 1: initial load, feature and library present; implements TestFeatureApi
    private static final String LIB4_IMPL_CLASS_STATE1 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl4_1";
    // State 2 success probe: cached TestFeatureApi — reveals whether loader was evicted
    private static final String LIB4_IMPL_CLASS_STATE2_REMOVED =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl4_1_removed";
    // State 2 NCDFE probe: fresh TestFeatureApi2 — expects CNFE/NCDFE after library removal
    private static final String LIB4_IMPL_CLASS_STATE2 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl4_2";
    // State 3: fresh TestFeatureApi3 — expects success after library restore
    private static final String LIB4_IMPL_CLASS_STATE3 =
        "io.openliberty.classloading.shared.feature.lib.SharedFeatureLibImpl4_3";

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
     * Test 1, State 2a: verifies that {@link TestFeatureApiImpl_removed} — which
     * implements the already-cached {@link TestFeatureApi} — still loads successfully
     * after the feature bundle has been dynamically removed.
     * <p>
     * Because {@code TestFeatureApi} was loaded in State 1, it remains in the WAR
     * classloader's internal cache. The JVM resolves the supertype from the cache
     * without walking the delegation chain to the (now-absent) bundle, so the load
     * succeeds. This documents the "app classloader is not recycled on feature removal"
     * invariant.
     */
    public void testAppDirectlyDependsOnFeatureApi_FeatureRemoved_CachedInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE2a - AppCL: " + cl);

        try {
            Class<?> removedClass = cl.loadClass(APP_IMPL_CLASS_STATE2_REMOVED);
            println("DYNAMIC_FEATURE_TEST STATE2a - _removed CLASS_STILL_VISIBLE (cached interface, expected): "
                + "TestFeatureApiImpl_removed loaded by " + removedClass.getClassLoader());
            if (!removedClass.getClassLoader().toString().contains("WebModule")) {
                Assert.fail("STATE2a: unexpected classloader for TestFeatureApiImpl_removed: "
                    + removedClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_TEST STATE2a - SUCCESS");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            Assert.fail("STATE2a: TestFeatureApiImpl_removed (cached interface) should have loaded but got: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Test 1, State 2b: verifies that loading {@link TestFeatureApiImpl2} — which
     * implements the never-before-seen {@link TestFeatureApi2} — produces a
     * {@link NoClassDefFoundError} after the feature bundle has been dynamically removed.
     * <p>
     * {@code TestFeatureApi2} was never referenced in State 1, so it is not in any
     * classloader's cache. When the JVM attempts to resolve it while loading
     * {@code TestFeatureApiImpl2}, it must walk the full delegation chain to the
     * (now-absent) feature bundle. That walk fails with {@code NoClassDefFoundError},
     * providing genuine evidence that the classloader correctly observes the feature
     * as absent — not a cache hit masking the removal.
     */
    public void testAppDirectlyDependsOnFeatureApi_FeatureRemoved_FreshInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_TEST STATE2b - AppCL: " + cl);

        try {
            Class<?> implClass = cl.loadClass(APP_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_TEST STATE2b - CLASS_STILL_VISIBLE (unexpected — stale loader): "
                + "TestFeatureApiImpl2 loaded by " + implClass.getClassLoader());
            Assert.fail("STATE2b: TestFeatureApiImpl2 (fresh TestFeatureApi2 interface) should have produced "
                + "NoClassDefFoundError but loaded successfully — baseline has changed. "
                + "Update this assertion if the fix is intentional.");
        } catch (NoClassDefFoundError ncdfe) {
            // Expected: the feature bundle is gone and TestFeatureApi2 was never cached.
            println("DYNAMIC_FEATURE_TEST STATE2b - NCDFE (expected — fresh interface from absent bundle): "
                + ncdfe.getMessage());
            println("DYNAMIC_FEATURE_TEST STATE2b - NCDFE");
        } catch (ClassNotFoundException cnfe) {
            // Also acceptable if Liberty evicts the app classloader on feature removal.
            println("DYNAMIC_FEATURE_TEST STATE2b - CNFE (stale loader evicted — acceptable): "
                + cnfe.getMessage());
            println("DYNAMIC_FEATURE_TEST STATE2b - CNFE");
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
            // Load the State 3 impl first — fresh TestFeatureApi3, not cached from earlier states.
            Class<?> implClass3 = cl.loadClass(APP_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi3 api3 = (TestFeatureApi3) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl3 SUCCESS: " + result3);

            // Verify all four impl classes load cleanly after the re-add.
            Class<?> implClass1 = cl.loadClass(APP_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl re-load SUCCESS");

            Class<?> removedClass = cl.loadClass(APP_IMPL_CLASS_STATE2_REMOVED);
            TestFeatureApi apiRemoved = (TestFeatureApi) removedClass.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl_removed.doWork() returned null", apiRemoved.doWork());
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl_removed re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(APP_IMPL_CLASS_STATE2);
            TestFeatureApi2 api2 = (TestFeatureApi2) implClass2.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: TestFeatureApiImpl2.doWork() returned null", api2.doWork());
            println("DYNAMIC_FEATURE_TEST STATE3 - TestFeatureApiImpl2 re-load SUCCESS");

            println("DYNAMIC_FEATURE_TEST STATE3 - SUCCESS: all impls loaded and invoked");

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
     * Test 2, State 2a: verifies that {@code SharedFeatureLibImpl_removed} — which
     * implements the already-cached {@link TestFeatureApi} — still loads successfully
     * after the feature bundle has been dynamically removed.
     * <p>
     * Because {@code TestFeatureApi} was loaded by the shared-library classloader in
     * State 1, it remains in that loader's internal cache. The load succeeds without
     * the delegation chain reaching the absent bundle, documenting that the shared-library
     * {@code AppClassLoader} is not recycled on feature removal.
     */
    public void testLibraryDependsOnFeatureApi_FeatureRemoved_CachedInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2a - WAR ClassLoader: " + cl);

        try {
            Class<?> removedClass = cl.loadClass(LIB_IMPL_CLASS_STATE2_REMOVED);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2a - _removed CLASS_STILL_VISIBLE (cached interface, expected): "
                + "SharedFeatureLibImpl_removed loaded by " + removedClass.getClassLoader());
            if (!removedClass.getClassLoader().toString().contains("testFeatureSharedLib")) {
                Assert.fail("STATE2a: unexpected classloader for SharedFeatureLibImpl_removed: "
                    + removedClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2a - SUCCESS");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            Assert.fail("STATE2a: SharedFeatureLibImpl_removed (cached interface) should have loaded but got: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Test 2, State 2b: verifies that loading {@code SharedFeatureLibImpl2} — which
     * implements the never-before-seen {@link TestFeatureApi2} — produces a
     * {@link NoClassDefFoundError} after the feature bundle has been dynamically removed.
     * <p>
     * {@code TestFeatureApi2} was never referenced in State 1, so a cold delegation
     * walk is forced through the shared-library loader to the absent bundle, producing
     * the expected {@code NoClassDefFoundError}.
     */
    public void testLibraryDependsOnFeatureApi_FeatureRemoved_FreshInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2b - WAR ClassLoader: " + cl);

        try {
            Class<?> implClass = cl.loadClass(LIB_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2b - CLASS_STILL_VISIBLE (unexpected): "
                + "SharedFeatureLibImpl2 loaded by " + implClass.getClassLoader());
            Assert.fail("STATE2b: SharedFeatureLibImpl2 (fresh TestFeatureApi2 interface) should have produced "
                + "NoClassDefFoundError but loaded successfully — baseline has changed. "
                + "Update this assertion if the fix is intentional.");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2b - NCDFE (expected — fresh interface from absent bundle): "
                + ncdfe.getMessage());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2b - NCDFE");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2b - CNFE (stale loader evicted — acceptable): "
                + cnfe.getMessage());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE2b - CNFE");
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
            // Load the State 3 library impl first — fresh TestFeatureApi3, not cached.
            Class<?> implClass3 = cl.loadClass(LIB_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi3 api3 = (TestFeatureApi3) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl3 SUCCESS: " + result3);

            // Verify all four library impls load cleanly after the re-add.
            Class<?> implClass1 = cl.loadClass(LIB_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl re-load SUCCESS");

            Class<?> removedClass = cl.loadClass(LIB_IMPL_CLASS_STATE2_REMOVED);
            TestFeatureApi apiRemoved = (TestFeatureApi) removedClass.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl_removed.doWork() returned null", apiRemoved.doWork());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl_removed re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(LIB_IMPL_CLASS_STATE2);
            TestFeatureApi2 api2 = (TestFeatureApi2) implClass2.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl2.doWork() returned null", api2.doWork());
            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SharedFeatureLibImpl2 re-load SUCCESS");

            println("DYNAMIC_FEATURE_SHAREDLIB_TEST STATE3 - SUCCESS: all impls loaded and invoked");

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
     * Test 3, State 2a: verifies that {@code BellFeatureLibImpl_removed} — which
     * implements the already-cached {@link TestFeatureApi} — still loads successfully
     * after the feature bundle has been dynamically removed.
     * <p>
     * Because {@code TestFeatureApi} was loaded by the Bell library classloader in
     * State 1, it remains in that loader's internal cache. The load succeeds without
     * the delegation chain reaching the absent bundle, documenting that the Bell library
     * {@code AppClassLoader} is not recycled on feature removal.
     */
    public void testBellLibraryDependsOnFeatureApi_FeatureRemoved_CachedInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE2a - WAR ClassLoader: " + cl);

        try {
            Class<?> removedClass = cl.loadClass(BELL_IMPL_CLASS_STATE2_REMOVED);
            println("DYNAMIC_FEATURE_BELL_TEST STATE2a - _removed CLASS_STILL_VISIBLE (cached interface, expected): "
                + "BellFeatureLibImpl_removed loaded by " + removedClass.getClassLoader());
            if (!removedClass.getClassLoader().toString().contains("testFeatureBellLib")) {
                Assert.fail("STATE2a: unexpected classloader for BellFeatureLibImpl_removed: "
                    + removedClass.getClassLoader());
            }
            println("DYNAMIC_FEATURE_BELL_TEST STATE2a - SUCCESS");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            Assert.fail("STATE2a: BellFeatureLibImpl_removed (cached interface) should have loaded but got: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Test 3, State 2b: verifies that loading {@code BellFeatureLibImpl2} — which
     * implements the never-before-seen {@link TestFeatureApi2} — produces a
     * {@link NoClassDefFoundError} after the feature bundle has been dynamically removed.
     * <p>
     * {@code TestFeatureApi2} was never referenced in State 1, so a cold delegation
     * walk is forced through the Bell library loader to the absent bundle, producing
     * the expected {@code NoClassDefFoundError}.
     */
    public void testBellLibraryDependsOnFeatureApi_FeatureRemoved_FreshInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_BELL_TEST STATE2b - WAR ClassLoader: " + cl);

        try {
            Class<?> implClass = cl.loadClass(BELL_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_BELL_TEST STATE2b - CLASS_STILL_VISIBLE (unexpected): "
                + "BellFeatureLibImpl2 loaded by " + implClass.getClassLoader());
            Assert.fail("STATE2b: BellFeatureLibImpl2 (fresh TestFeatureApi2 interface) should have produced "
                + "NoClassDefFoundError but loaded successfully — baseline has changed. "
                + "Update this assertion if the fix is intentional.");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE2b - NCDFE (expected — fresh interface from absent bundle): "
                + ncdfe.getMessage());
            println("DYNAMIC_FEATURE_BELL_TEST STATE2b - NCDFE");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_BELL_TEST STATE2b - CNFE (stale loader evicted — acceptable): "
                + cnfe.getMessage());
            println("DYNAMIC_FEATURE_BELL_TEST STATE2b - CNFE");
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
            // Load the State 3 Bell impl first — fresh TestFeatureApi3, not cached.
            Class<?> implClass3 = cl.loadClass(BELL_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi3 api3 = (TestFeatureApi3) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: BellFeatureLibImpl3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl3 SUCCESS: " + result3);

            // Verify all four Bell impls load cleanly after the re-add.
            Class<?> implClass1 = cl.loadClass(BELL_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: BellFeatureLibImpl.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl re-load SUCCESS");

            Class<?> removedClass = cl.loadClass(BELL_IMPL_CLASS_STATE2_REMOVED);
            TestFeatureApi apiRemoved = (TestFeatureApi) removedClass.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: BellFeatureLibImpl_removed.doWork() returned null", apiRemoved.doWork());
            println("DYNAMIC_FEATURE_BELL_TEST STATE3 - BellFeatureLibImpl_removed re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(BELL_IMPL_CLASS_STATE2);
            TestFeatureApi2 api2 = (TestFeatureApi2) implClass2.getDeclaredConstructor().newInstance();
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
    // Test 4 — Library JAR removed from shared library fileset
    // =========================================================================

    /**
     * Test 4, State 1: verifies that the shared-library implementation is reachable
     * and that {@code doWork()} succeeds through the library chain when both the
     * library JAR and the feature are present.
     * <p>
     * Classloader chain:
     * <pre>
     *   WAR AppClassLoader
     *     └─ Shared-library AppClassLoader  (cached in aclStore)
     *          └─ GatewayClassLoader
     *               └─ EquinoxClassLoader [test.feature.api]
     * </pre>
     * {@code SharedFeatureLibImpl4_1} is the State 1 class — fresh, not yet loaded,
     * so {@code findLoadedClass()} does not short-circuit the lookup.
     * <p>
     * Only the library impl class is loaded via the WAR classloader — the WAR's
     * {@code AppClassLoader} is intentionally <em>not</em> made the initiating
     * classloader for {@code TestFeatureApi} directly. The feature API class is
     * resolved transitively by the shared-library classloader when it resolves
     * {@code SharedFeatureLibImpl4_1}'s supertype.
     */
    public void testLibraryJarRemovedFromSharedLib_LibraryPresent() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE1 - WAR ClassLoader: " + cl);

        // Load only the State 1 library impl — do NOT call cl.loadClass(FEATURE_API_CLASS)
        // directly, to avoid making the WAR classloader the initiating CL for TestFeatureApi.
        Class<?> implClass = cl.loadClass(LIB4_IMPL_CLASS_STATE1);
        println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE1 - SharedFeatureLibImpl4_1 loaded by: "
            + implClass.getClassLoader());

        Object impl = implClass.getDeclaredConstructor().newInstance();
        TestFeatureApi api = (TestFeatureApi) impl;

        String result = api.doWork();
        Assert.assertNotNull("STATE1: doWork() returned null", result);
        Assert.assertFalse("STATE1: doWork() returned empty string", result.isEmpty());
        println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE1 - SUCCESS: " + result);
    }

    /**
     * Test 4, State 2a: eviction-detection probe using the already-cached
     * {@link TestFeatureApi} interface.
     * <p>
     * If {@code SharedLibraryImpl.delete()} evicted the library's {@code AppClassLoader}
     * from {@code aclStore}, this load will throw {@code ClassNotFoundException} (the
     * expected, correct outcome). If the loader was <em>not</em> evicted despite the
     * {@code server.xml} change, the load succeeds — a warning logged here, with the
     * 2b probe providing the hard assertion.
     */
    public void testLibraryJarRemovedFromSharedLib_LibraryRemoved_CachedInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2a - WAR ClassLoader: " + cl);

        try {
            Class<?> removedClass = cl.loadClass(LIB4_IMPL_CLASS_STATE2_REMOVED);
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2a - CLASS_STILL_VISIBLE "
                + "(library AppClassLoader NOT evicted despite server.xml change): "
                + "SharedFeatureLibImpl4_1_removed loaded by " + removedClass.getClassLoader());
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2a - WARNING: "
                + "cached interface still visible; library loader may not have been evicted.");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2a - CNFE (library loader evicted, expected): "
                + cnfe.getMessage());
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2a - CNFE");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2a - NCDFE (unexpected): "
                + ncdfe.getMessage());
            Assert.fail("STATE2a: unexpected NoClassDefFoundError for _removed class: " + ncdfe.getMessage());
        }
    }

    /**
     * Test 4, State 2b: hard assertion that {@code SharedFeatureLibImpl4_2} — which
     * implements the never-before-seen {@link TestFeatureApi2} — cannot be loaded after
     * the library JAR has been removed from the fileset.
     * <p>
     * {@code SharedLibraryImpl.delete()} should evict the library's
     * {@code AppClassLoader} from {@code aclStore}. A {@code ClassNotFoundException}
     * here confirms that eviction. If the class loads successfully, the eviction did not
     * occur and the test fails.
     */
    public void testLibraryJarRemovedFromSharedLib_LibraryRemoved_FreshInterface() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2b - WAR ClassLoader: " + cl);

        try {
            Class<?> implClass = cl.loadClass(LIB4_IMPL_CLASS_STATE2);
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2b - CLASS_STILL_VISIBLE "
                + "(library AppClassLoader NOT evicted despite server.xml change): "
                + "SharedFeatureLibImpl4_2 loaded by " + implClass.getClassLoader());
            Assert.fail("STATE2b: SharedFeatureLibImpl4_2 (fresh TestFeatureApi2) was still visible after "
                + "library JAR removal — SharedLibraryImpl.delete() did not evict the AppClassLoader. "
                + "Update this assertion if this is the confirmed baseline.");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2b - CNFE (library AppClassLoader evicted, expected): "
                + cnfe.getMessage());
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2b - CNFE");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE2b - NCDFE (unexpected): "
                + ncdfe.getMessage());
            Assert.fail("STATE2b: unexpected NoClassDefFoundError: " + ncdfe.getMessage());
        }
    }

    /**
     * Test 4, State 3: verifies that all three Test 4 library impl classes are
     * loadable after the original {@code server.xml} has been restored (library JAR
     * back in the fileset, feature present throughout).
     * <p>
     * {@code SharedFeatureLibImpl4_3} is the State 3 class (fresh, not cached). All
     * three Test 4 impl classes are then loaded to confirm the library chain is fully
     * reconstructed after the config restore.
     * <p>
     * <b>Predicted outcome:</b> {@code SUCCESS} — Liberty creates a new
     * {@code AppClassLoader} for the restored library. The feature bundle was never
     * removed, so the {@code EquinoxClassLoader} is unchanged and there is no stale
     * type-binding risk.
     * <p>
     * If {@code ClassNotFoundException} is observed, Liberty did not recreate the
     * library classloader on config restore — update the assertion and investigate.
     * {@code ClassCastException} would indicate a type-binding mismatch; this is
     * very unlikely since the feature bundle address is stable across all three states,
     * but the assertion traps it explicitly for completeness.
     */
    public void testLibraryJarRemovedFromSharedLib_LibraryRestored() throws Exception {
        ClassLoader cl = getClass().getClassLoader();
        println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - WAR ClassLoader: " + cl);

        try {
            // Load the State 3 library impl first — fresh TestFeatureApi3, not cached.
            Class<?> implClass3 = cl.loadClass(LIB4_IMPL_CLASS_STATE3);
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - SharedFeatureLibImpl4_3 loaded by: "
                + implClass3.getClassLoader());
            TestFeatureApi3 api3 = (TestFeatureApi3) implClass3.getDeclaredConstructor().newInstance();
            String result3 = api3.doWork();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl4_3.doWork() returned null", result3);
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - SharedFeatureLibImpl4_3 SUCCESS: " + result3);

            // Verify all four Test 4 impls load cleanly after library restore.
            Class<?> implClass1 = cl.loadClass(LIB4_IMPL_CLASS_STATE1);
            TestFeatureApi api1 = (TestFeatureApi) implClass1.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl4_1.doWork() returned null", api1.doWork());
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - SharedFeatureLibImpl4_1 re-load SUCCESS");

            Class<?> removedClass = cl.loadClass(LIB4_IMPL_CLASS_STATE2_REMOVED);
            TestFeatureApi apiRemoved = (TestFeatureApi) removedClass.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl4_1_removed.doWork() returned null", apiRemoved.doWork());
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - SharedFeatureLibImpl4_1_removed re-load SUCCESS");

            Class<?> implClass2 = cl.loadClass(LIB4_IMPL_CLASS_STATE2);
            TestFeatureApi2 api2 = (TestFeatureApi2) implClass2.getDeclaredConstructor().newInstance();
            Assert.assertNotNull("STATE3: SharedFeatureLibImpl4_2.doWork() returned null", api2.doWork());
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - SharedFeatureLibImpl4_2 re-load SUCCESS");

            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - SUCCESS: all impls loaded and invoked");

        } catch (ClassCastException cce) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - CLASSCAST: " + cce.getMessage());
            Assert.fail("STATE3: unexpected ClassCastException after library restore — "
                + "type-binding mismatch despite feature being present throughout the test. "
                + "Update this assertion if this is the confirmed baseline.");
        } catch (ClassNotFoundException cnfe) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - CNFE: " + cnfe.getMessage());
            Assert.fail("STATE3: ClassNotFoundException after library restore — Liberty did not "
                + "recreate the shared-library AppClassLoader on server.xml config restore. "
                + "Update this assertion if this is the confirmed baseline.");
        } catch (NoClassDefFoundError ncdfe) {
            println("DYNAMIC_FEATURE_LIB_JAR_REMOVED_TEST STATE3 - NCDFE: " + ncdfe.getMessage());
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
