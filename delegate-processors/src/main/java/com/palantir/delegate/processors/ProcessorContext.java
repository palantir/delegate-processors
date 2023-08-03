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

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.immutables.value.Value;

@Value.Immutable
@ImmutablesStyle
public interface ProcessorContext {

    Messager messager();

    Filer filer();

    Elements elements();

    Types types();

    static ProcessorContext create(ProcessingEnvironment processingEnv) {
        return ImmutableProcessorContext.builder()
                .messager(processingEnv.getMessager())
                .filer(processingEnv.getFiler())
                .elements(processingEnv.getElementUtils())
                .types(processingEnv.getTypeUtils())
                .build();
    }
}
