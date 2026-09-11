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
 * Third Bell library implementation of {@link TestFeatureApi}, used exclusively
 * by the State 3 probe of Test 3 (Bell classloader test).
 * <p>
 * A distinct class is used per lifecycle state so that each probe forces
 * {@code ClassLoader.loadClass()} to perform a genuine re-lookup rather than
 * returning a cached result from {@code findLoadedClass()}.
 * <p>
 * This class is listed as a third entry in
 * {@code META-INF/services/io.openliberty.classloading.feature.api.TestFeatureApi}
 * inside the Bell library JAR so that Bell and {@code ServiceLoader} can discover it
 * independently of {@link BellFeatureLibImpl} and {@link BellFeatureLibImpl2}.
 * In State 3, all three Bell impls are loaded and expected to succeed, confirming
 * that the Bell library chain is functional after the feature re-add.
 */
public class BellFeatureLibImpl3 implements TestFeatureApi {

    @Override
    public String doWork() {
        return "BellFeatureLibImpl3.doWork() called successfully from Bell library (state 3)";
    }
}
