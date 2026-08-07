/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.constraints.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureExpressionEvaluatorTest
{
    private static final boolean EITHER_FALLBACK = true;

    @Test
    void thatValidExpressionIsEvaluatedRegardlessOfFallback()
    {
        var values = Map.of("entityType", "Gene");

        for (var fallback : new boolean[] { true, false }) {
            assertThat(FeatureExpressionEvaluator.evaluate("entityType == \"Gene\"", values::get,
                    fallback)).isTrue();
            assertThat(FeatureExpressionEvaluator.evaluate("entityType == \"Protein\"", values::get,
                    fallback)).isFalse();
        }
    }

    /**
     * The whole point of the explicit fallback: the same broken expression must resolve differently
     * depending on what it controls. Visibility fails open (never hide an editor), a conditional
     * default value rule fails closed (never write a value off a broken condition).
     */
    @ParameterizedTest
    @ValueSource(strings = { "entityType ==", "(entityType == \"Gene\"", "123abc == \"x\"",
            "entityType == \"unterminated" })
    void thatUnparsableExpressionYieldsTheCallersFallback(String aExpression)
    {
        assertThat(FeatureExpressionEvaluator.evaluate(aExpression, name -> "Gene", true)).isTrue();
        assertThat(FeatureExpressionEvaluator.evaluate(aExpression, name -> "Gene", false))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void thatBlankExpressionYieldsTheCallersFallback(String aExpression)
    {
        assertThat(FeatureExpressionEvaluator.evaluate(aExpression, name -> null, true)).isTrue();
        assertThat(FeatureExpressionEvaluator.evaluate(aExpression, name -> null, false)).isFalse();
    }

    @Test
    void thatNullExpressionYieldsTheCallersFallback()
    {
        assertThat(FeatureExpressionEvaluator.evaluate(null, name -> null, true)).isTrue();
        assertThat(FeatureExpressionEvaluator.evaluate(null, name -> null, false)).isFalse();
    }

    @Test
    void thatMisbehavingLookupYieldsTheCallersFallbackWithoutThrowing()
    {
        var expression = "entityType == \"Gene\"";
        assertThatCode(() -> {
            assertThat(FeatureExpressionEvaluator.evaluate(expression, name -> {
                throw new IllegalStateException("lookup exploded");
            }, true)).isTrue();
            assertThat(FeatureExpressionEvaluator.evaluate(expression, name -> {
                throw new IllegalStateException("lookup exploded");
            }, false)).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    void thatValidateAcceptsValidAndBlankExpressions()
    {
        assertThatCode(() -> FeatureExpressionEvaluator.validate("entityType == \"Gene\""))
                .doesNotThrowAnyException();
        assertThatCode(() -> FeatureExpressionEvaluator.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> FeatureExpressionEvaluator.validate("")).doesNotThrowAnyException();
    }

    @Test
    void thatValidateThrowsForInvalidExpressions()
    {
        assertThatExceptionOfType(FeatureExpressionSyntaxException.class)
                .isThrownBy(() -> FeatureExpressionEvaluator.validate("entityType ="));
    }

    @Test
    void thatMissingFeatureValueDoesNotMatch()
    {
        assertThat(FeatureExpressionEvaluator.evaluate("entityType == \"Gene\"", name -> null,
                EITHER_FALLBACK)).isFalse();
    }
}
