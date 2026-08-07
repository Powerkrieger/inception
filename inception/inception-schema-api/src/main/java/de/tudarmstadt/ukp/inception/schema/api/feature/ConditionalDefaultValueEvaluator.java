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
package de.tudarmstadt.ukp.inception.schema.api.feature;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.List;
import java.util.function.Function;

import de.tudarmstadt.ukp.clarin.webanno.constraints.expression.FeatureExpressionEvaluator;

/**
 * Evaluates a list of {@link ConditionalDefaultValueRule}s to determine which default value a
 * {@link FeatureSupport} should apply. Shared across feature types so that any
 * {@code FeatureSupport} implementation which supports a plain default value (Concept, String,
 * Number, Boolean, ...) can add conditional defaults on top of it without reimplementing the rule
 * evaluation itself.
 */
public final class ConditionalDefaultValueEvaluator
{
    /**
     * A rule whose condition is blank, unparsable or fails to evaluate must not match: applying a
     * default value is a write into the annotation, so the safe direction on error is to leave the
     * value alone and fall through to the plain default. This is the opposite of the fallback used
     * for feature visibility.
     */
    private static final boolean FALLBACK_NO_MATCH = false;

    private ConditionalDefaultValueEvaluator()
    {
        // Utility class
    }

    /**
     * @param aFallbackDefault
     *            the plain, unconditional default value to fall back to if no rule matches; may be
     *            {@code null}/blank.
     * @param aRules
     *            rules evaluated in order; the first one whose condition matches wins.
     * @param aFeatureValues
     *            resolves the current string value of any feature on the same annotation by feature
     *            name; must return {@code null} for a missing/unset feature.
     * @return the value of the first matching rule, or {@code aFallbackDefault} if none match.
     */
    public static String computeDefaultValue(String aFallbackDefault,
            List<ConditionalDefaultValueRule> aRules, Function<String, String> aFeatureValues)
    {
        for (var rule : aRules) {
            if (isNotBlank(rule.getCondition()) && isNotBlank(rule.getValue())
                    && FeatureExpressionEvaluator.evaluate(rule.getCondition(), aFeatureValues,
                            FALLBACK_NO_MATCH)) {
                return rule.getValue();
            }
        }
        return aFallbackDefault;
    }
}
