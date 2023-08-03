/*
 * (c) Copyright 2023 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.delegate.processors;

import com.palantir.delegate.processors.ImmutableAnnotatedTypeMethod.ImplementationBuildStage;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import org.immutables.value.Value;

@Value.Immutable
@ImmutablesStyle
public interface AnnotatedTypeMethod {
    /** Top level implementation method. This may also be an interface method. */
    ExecutableElement implementation();

    /**
     * Overridden interface methods. If the annotated type is an interface or a default interface method
     * is not overridden, this will include the {@link #implementation()} method.
     */
    List<ExecutableElement> overridden();

    static ImplementationBuildStage builder() {
        return ImmutableAnnotatedTypeMethod.builder();
    }
}
