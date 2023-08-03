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

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public interface DelegateProcessorStrategy {

    /**
     * Returns the annotations that are supported by this annotation processor.
     */
    Set<String> supportedAnnotations();

    /**
     * Returns the simple name of the generated class.
     */
    String generatedTypeName(String annotatedTypeName);

    /**
     * Returns the type used for the delegate field in the generted class.
     */
    default TypeName delegateType(DelegateTypeArguments arguments) {
        return Types.delegateType(arguments);
    }

    /**
     * Returns code executed prior to delegation.
     */
    default Optional<CodeBlock> before(DelegateMethodArguments arguments) {
        return Optional.empty();
    }

    /**
     * Returns code executed after successful delegation. Given a {@link LocalVariable} reference to the return type,
     * empty if the method does not return.
     */
    default Optional<CodeBlock> onSuccess(DelegateMethodArguments arguments, Optional<LocalVariable> result) {
        return Optional.empty();
    }

    /**
     * Returns code executed in a {@code catch (Throwable throwable)} block on failure.
     * The returned {@link CodeBlock} is not responsible for rethrowing the throwable.
     */
    default Optional<CodeBlock> onFailure(DelegateMethodArguments arguments, LocalVariable throwable) {
        return Optional.empty();
    }

    /**
     * Returns code executed in a {@code finally} block on success or failure.
     * In this context, "always" refers to the result of delegation - if {@link #before(DelegateMethodArguments)}
     * throws, this block will not be invoked.
     */
    default Optional<CodeBlock> alwaysAfter(DelegateMethodArguments arguments) {
        return Optional.empty();
    }

    /**
     * Returns additional fields that will be added to the generated class.
     */
    default List<FieldSpec> additionalFields(AdditionalFieldsArguments arguments) {
        return List.of();
    }

    /**
     * This method will be called immediately before the {@link TypeSpec.Builder} for the generated type is built, to
     * allow processor to make any custom modifications.
     */
    default void customize(CustomizeArguments arguments, TypeSpec.Builder generatedType) {}

    @Value.Immutable
    @ImmutablesStyle
    interface DelegateTypeArguments {
        ProcessorContext context();

        AnnotatedType type();

        static ImmutableDelegateTypeArguments.ContextBuildStage builder() {
            return ImmutableDelegateTypeArguments.builder();
        }
    }

    @Value.Immutable
    @ImmutablesStyle
    interface DelegateMethodArguments {
        ProcessorContext context();

        AnnotatedType type();

        AnnotatedTypeMethod method();

        FieldSpec delegate();

        static ImmutableDelegateMethodArguments.ContextBuildStage builder() {
            return ImmutableDelegateMethodArguments.builder();
        }
    }

    @Value.Immutable
    @ImmutablesStyle
    interface AdditionalFieldsArguments {
        ProcessorContext context();

        AnnotatedType type();

        static ImmutableAdditionalFieldsArguments.ContextBuildStage builder() {
            return ImmutableAdditionalFieldsArguments.builder();
        }
    }

    @Value.Immutable
    @ImmutablesStyle
    interface CustomizeArguments {
        ProcessorContext context();

        AnnotatedType type();

        TypeName generatedTypeName();

        TypeName delegateTypeName();

        static ImmutableCustomizeArguments.ContextBuildStage builder() {
            return ImmutableCustomizeArguments.builder();
        }
    }
}
