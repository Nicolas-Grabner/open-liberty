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
package io.openliberty.classloading.shared.feature.lib;

import io.openliberty.classloading.feature.api.TestFeatureApi;

/**
 * Second shared-library implementation of {@link TestFeatureApi}, used exclusively
 * by the State 2 probe of Test 2 (shared-library classloader test).
 * <p>
 * Using a distinct class per lifecycle state ensures that {@code ClassLoader.loadClass()}
 * performs a genuine re-lookup rather than returning a cached result from
 * {@code findLoadedClass()} — which would hide the true classloader behaviour under test.
 * <p>
 * This class is never referenced in State 1, so the shared-library {@code AppClassLoader}
 * has not loaded it before the feature is removed. Loading it in State 2 therefore
 * forces a fresh delegation chain walk: library loader → GatewayClassLoader →
 * EquinoxClassLoader (or failure, if the bundle has been evicted).
 */
public class SharedFeatureLibImpl2 implements TestFeatureApi {

    @Override
    public String doWork() {
        return "SharedFeatureLibImpl2.doWork() called successfully from shared library (state 2)";
    }
}
