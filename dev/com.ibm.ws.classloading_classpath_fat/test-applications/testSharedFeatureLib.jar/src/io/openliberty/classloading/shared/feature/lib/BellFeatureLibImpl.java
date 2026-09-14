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
 * First Bell library implementation of {@link TestFeatureApi}, used exclusively
 * by the State 1 probe of Test 3 (Bell classloader test).
 * <p>
 * This class is listed as the first entry in
 * {@code META-INF/services/io.openliberty.classloading.feature.api.TestFeatureApi}
 * inside the Bell library JAR so that Bell discovers and registers it as an OSGi
 * service at server startup.
 * <p>
 * A distinct class is used per lifecycle state so that each probe forces
 * {@code ClassLoader.loadClass()} to perform a genuine re-lookup rather than
 * returning a cached result from {@code findLoadedClass()}.
 */
public class BellFeatureLibImpl implements TestFeatureApi {

    @Override
    public String doWork() {
        return "BellFeatureLibImpl.doWork() called successfully from Bell library (state 1)";
    }
}
