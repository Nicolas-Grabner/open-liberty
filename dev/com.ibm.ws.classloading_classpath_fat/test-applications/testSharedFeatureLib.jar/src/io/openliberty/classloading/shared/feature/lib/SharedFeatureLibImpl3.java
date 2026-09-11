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
 * Third shared-library implementation of {@link TestFeatureApi}, used exclusively
 * by the State 3 probe of Test 2 (shared-library classloader test).
 * <p>
 * Using a distinct class per lifecycle state ensures that {@code ClassLoader.loadClass()}
 * performs a genuine re-lookup rather than returning a cached result from
 * {@code findLoadedClass()} — which would hide the true classloader behaviour under test.
 * <p>
 * This class is never referenced in States 1 or 2, so loading it in State 3 forces a
 * fresh delegation chain walk after the feature has been re-added. In State 3, all three
 * impl classes ({@link SharedFeatureLibImpl}, {@link SharedFeatureLibImpl2}, and this class)
 * are loaded and expected to succeed, confirming that the full library chain is functional
 * after the feature re-add.
 */
public class SharedFeatureLibImpl3 implements TestFeatureApi {

    @Override
    public String doWork() {
        return "SharedFeatureLibImpl3.doWork() called successfully from shared library (state 3)";
    }
}
