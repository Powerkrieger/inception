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

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.lang.invoke.MethodHandles;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;

/**
 * Evaluates {@link AnnotationFeature#getVisibleIf()} expressions.
 * <p>
 * Parsed expressions are cached by their source text (parsing is comparatively expensive and the
 * same expression is evaluated repeatedly, e.g. once per feature editor per AJAX refresh). An
 * expression which fails to parse is treated as "always visible" - a configuration mistake in a
 * {@code visibleIf} expression must never hide a feature editor or crash the UI. Each distinct
 * invalid expression is logged as a warning only once.
 */
public final class VisibleIfEvaluator
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final ConcurrentHashMap<String, Optional<VisibleIfExpression>> CACHE = //
            new ConcurrentHashMap<>();
    private static final Set<String> ALREADY_WARNED = ConcurrentHashMap.newKeySet();

    private VisibleIfEvaluator()
    {
        // Utility class
    }

    /**
     * @param aFeature
     *            the feature whose {@code visibleIf} expression should be evaluated.
     * @param aFeatureValues
     *            resolves the current string value of any feature on the same annotation by feature
     *            name; must return {@code null} for a missing/unset feature.
     * @return {@code true} if the feature should be shown.
     */
    public static boolean isVisible(AnnotationFeature aFeature,
            Function<String, String> aFeatureValues)
    {
        return isVisible(aFeature.getVisibleIf(), aFeatureValues);
    }

    public static boolean isVisible(String aVisibleIfExpression,
            Function<String, String> aFeatureValues)
    {
        if (isBlank(aVisibleIfExpression)) {
            return true;
        }

        var parsed = CACHE.computeIfAbsent(aVisibleIfExpression, VisibleIfEvaluator::tryParse);

        if (parsed.isEmpty()) {
            return true;
        }

        try {
            return parsed.get().evaluate(aFeatureValues);
        }
        catch (Exception e) {
            // Defensive: a lookup function misbehaving must not break the annotation editor
            LOG.warn("Error evaluating visibleIf expression [{}]: {} - treating feature as "
                    + "always visible", aVisibleIfExpression, e.getMessage());
            return true;
        }
    }

    /**
     * Validates the syntax of a {@code visibleIf} expression, e.g. when it is entered in the
     * layer/feature configuration UI.
     *
     * @throws VisibleIfSyntaxException
     *             if the expression is not valid.
     */
    public static void validate(String aVisibleIfExpression) throws VisibleIfSyntaxException
    {
        if (isBlank(aVisibleIfExpression)) {
            return;
        }

        VisibleIfExpressionParser.parse(aVisibleIfExpression);
    }

    private static Optional<VisibleIfExpression> tryParse(String aExpression)
    {
        try {
            return Optional.of(VisibleIfExpressionParser.parse(aExpression));
        }
        catch (VisibleIfSyntaxException e) {
            if (ALREADY_WARNED.add(aExpression)) {
                LOG.warn("Invalid visibleIf expression [{}]: {} - treating feature as always "
                        + "visible", aExpression, e.getMessage());
            }
            return Optional.empty();
        }
    }
}
