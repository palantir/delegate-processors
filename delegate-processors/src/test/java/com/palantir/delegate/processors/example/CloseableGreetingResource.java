/*
 * (c) Copyright 2021 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.delegate.processors.example;

import com.palantir.delegate.processors.processor.Delegate;
import java.io.Closeable;

@Delegate
public final class CloseableGreetingResource implements GreetingService, Closeable {
    @Override
    public String greet() {
        return "Hello, World!";
    }

    @Override
    public void close() {
        // nop
    }
}
