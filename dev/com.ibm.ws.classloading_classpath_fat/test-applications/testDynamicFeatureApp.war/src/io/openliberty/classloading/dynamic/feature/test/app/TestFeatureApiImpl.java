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

import io.openliberty.classloading.feature.api.TestFeatureApi;

/**
 * In-WAR implementation of {@link TestFeatureApi}.
 * Lives in the WAR classloader; delegates to the feature bundle for the interface type.
 * The cast {@code (TestFeatureApi) new TestFeatureApiImpl()} must succeed in State 1
 * because both sides resolve the interface from the same (current) bundle revision.
 * In State 3 the same cast is attempted so the test can observe whether the JVM
 * detects a stale-loader mismatch.
 */
public class TestFeatureApiImpl implements TestFeatureApi {

    @Override
    public String doWork() {
        return "TestFeatureApiImpl.doWork() called successfully";
    }
}
