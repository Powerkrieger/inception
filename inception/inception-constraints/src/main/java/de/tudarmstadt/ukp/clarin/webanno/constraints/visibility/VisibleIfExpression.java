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
package de.tudarmstadt.ukp.clarin.webanno.constraints.visibility;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Parsed abstract syntax tree for a {@code visibleIf} feature-visibility expression. An expression
 * is evaluated against a lookup function that resolves the current value of any feature on the same
 * annotation by name; a feature that is missing or has no value resolves to {@code null}.
 */
public sealed interface VisibleIfExpression
{
    boolean evaluate(Function<String, String> aFeatureValues);

    record And(VisibleIfExpression left, VisibleIfExpression right)
        implements VisibleIfExpression
    {
        @Override
        public boolean evaluate(Function<String, String> aFeatureValues)
        {
            return left.evaluate(aFeatureValues) && right.evaluate(aFeatureValues);
        }
    }

    record Or(VisibleIfExpression left, VisibleIfExpression right)
        implements VisibleIfExpression
    {
        @Override
        public boolean evaluate(Function<String, String> aFeatureValues)
        {
            return left.evaluate(aFeatureValues) || right.evaluate(aFeatureValues);
        }
    }

    record Not(VisibleIfExpression inner)
        implements VisibleIfExpression
    {
        @Override
        public boolean evaluate(Function<String, String> aFeatureValues)
        {
            return !inner.evaluate(aFeatureValues);
        }
    }

    record Equals(String featureName, String value)
        implements VisibleIfExpression
    {
        @Override
        public boolean evaluate(Function<String, String> aFeatureValues)
        {
            return Objects.equals(aFeatureValues.apply(featureName), value);
        }
    }

    /**
     * {@code feature in ["A", "B"]}. A missing/{@code null} feature value never matches.
     */
    record In(String featureName, List<String> values)
        implements VisibleIfExpression
    {
        @Override
        public boolean evaluate(Function<String, String> aFeatureValues)
        {
            var value = aFeatureValues.apply(featureName);
            return value != null && values.contains(value);
        }
    }
}
