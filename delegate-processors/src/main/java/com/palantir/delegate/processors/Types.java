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

package com.palantir.delegate.processors;

import com.google.common.collect.Iterables;
import com.palantir.delegate.processors.DelegateProcessorStrategy.DelegateTypeArguments;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import java.util.List;
import javax.lang.model.type.TypeMirror;

final class Types {

    // Given:
    // 1: class Resource implements Iface1, Iface2
    // 2: interface Iface2 extends Iface1
    // We can simplify interfaces from [Iface1, Iface2] to [Iface2], however we should retain
    // interface order in the 'implements' tree to avoid changing jersey (or similar reflective
    // framework) behavior.
    static TypeName delegateType(DelegateTypeArguments arguments) {
        List<TypeMirror> interfaces = arguments.type().interfaces();
        if (interfaces.size() == 1) {
            return TypeName.get(Iterables.getOnlyElement(interfaces));
        }

        for (TypeMirror interfaceMirror : interfaces) {
            boolean allMatch = true;
            for (TypeMirror toCheck : interfaces) {
                if (!arguments.context().types().isAssignable(interfaceMirror, toCheck)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return TypeName.get(interfaceMirror);
            }
        }

        return TypeVariableName.get(
                "DELEGATE",
                arguments.type().interfaces().stream().map(TypeName::get).toArray(TypeName[]::new));
    }

    private Types() {}
}
