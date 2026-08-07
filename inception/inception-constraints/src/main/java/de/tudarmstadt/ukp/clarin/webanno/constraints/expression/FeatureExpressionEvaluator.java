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

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.lang.invoke.MethodHandles;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates {@link FeatureExpression}s against the current feature values of a single annotation.
 * Use-case-agnostic: this backs both feature visibility ({@code visibleIf}) and conditional default
 * value rules, so nothing here is phrased in terms of visibility.
 * <p>
 * Parsed expressions are cached by their source text (parsing is comparatively expensive and the
 * same expression is evaluated repeatedly, e.g. once per feature editor per AJAX refresh). Each
 * distinct invalid expression is logged as a warning only once.
 * <p>
 * There is deliberately no default failure behavior: a blank, unparsable or failing expression
 * yields the {@code aFallback} the caller passes. The safe fallback depends entirely on what the
 * expression controls - for visibility it is {@code true} (a broken expression must never hide a
 * feature editor), for a conditional default value rule it is {@code false} (a broken expression
 * must never silently write a value into an annotation).
 */
public final class FeatureExpressionEvaluator
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final ConcurrentHashMap<String, Optional<FeatureExpression>> CACHE = //
            new ConcurrentHashMap<>();
    private static final Set<String> ALREADY_WARNED = ConcurrentHashMap.newKeySet();

    private FeatureExpressionEvaluator()
    {
        // Utility class
    }

    /**
     * @param aExpression
     *            the expression to evaluate; may be {@code null}/blank.
     * @param aFeatureValues
     *            resolves the current string value of any feature on the same annotation by feature
     *            name; must return {@code null} for a missing/unset feature.
     * @param aFallback
     *            returned if the expression is blank, cannot be parsed, or throws while being
     *            evaluated.
     * @return the result of the expression, or {@code aFallback}.
     */
    public static boolean evaluate(String aExpression, Function<String, String> aFeatureValues,
            boolean aFallback)
    {
        if (isBlank(aExpression)) {
            return aFallback;
        }

        var parsed = CACHE.computeIfAbsent(aExpression, FeatureExpressionEvaluator::tryParse);

        if (parsed.isEmpty()) {
            return aFallback;
        }

        try {
            return parsed.get().evaluate(aFeatureValues);
        }
        catch (Exception e) {
            // Defensive: a lookup function misbehaving must not break the annotation editor
            LOG.warn("Error evaluating feature expression [{}]: {} - falling back to [{}]",
                    aExpression, e.getMessage(), aFallback);
            return aFallback;
        }
    }

    /**
     * Validates the syntax of an expression, e.g. when it is entered in a configuration UI.
     *
     * @param aExpression
     *            the expression to validate; a blank expression is considered valid.
     * @throws FeatureExpressionSyntaxException
     *             if the expression is not valid.
     */
    public static void validate(String aExpression) throws FeatureExpressionSyntaxException
    {
        if (isBlank(aExpression)) {
            return;
        }

        FeatureExpressionParser.parse(aExpression);
    }

    private static Optional<FeatureExpression> tryParse(String aExpression)
    {
        try {
            return Optional.of(FeatureExpressionParser.parse(aExpression));
        }
        catch (FeatureExpressionSyntaxException e) {
            if (ALREADY_WARNED.add(aExpression)) {
                LOG.warn("Invalid feature expression [{}]: {}", aExpression, e.getMessage());
            }
            return Optional.empty();
        }
    }
}
