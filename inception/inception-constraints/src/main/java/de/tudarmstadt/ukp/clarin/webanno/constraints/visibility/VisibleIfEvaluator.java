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

import java.util.function.Function;

import de.tudarmstadt.ukp.clarin.webanno.constraints.expression.FeatureExpressionEvaluator;
import de.tudarmstadt.ukp.clarin.webanno.constraints.expression.FeatureExpressionSyntaxException;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;

/**
 * Applies the shared {@link FeatureExpressionEvaluator} to the one thing it is used for here:
 * deciding whether a feature editor is shown, based on {@link AnnotationFeature#getVisibleIf()}.
 * <p>
 * This exists to pin down the failure policy for the visibility use case: an expression which is
 * blank, fails to parse or fails to evaluate is treated as <b>visible</b>. A configuration mistake
 * in a {@code visibleIf} expression must never hide a feature editor or crash the UI. Other users
 * of the expression language need the opposite fallback and must therefore not route through here.
 */
public final class VisibleIfEvaluator
{
    /** A broken or missing visibility expression must never hide a feature editor. */
    private static final boolean FALLBACK_VISIBLE = true;

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
        return FeatureExpressionEvaluator.evaluate(aVisibleIfExpression, aFeatureValues,
                FALLBACK_VISIBLE);
    }

    /**
     * Validates the syntax of a {@code visibleIf} expression, e.g. when it is entered in the
     * layer/feature configuration UI.
     *
     * @param aVisibleIfExpression
     *            the expression to validate; a blank expression is considered valid.
     * @throws FeatureExpressionSyntaxException
     *             if the expression is not valid.
     */
    public static void validate(String aVisibleIfExpression) throws FeatureExpressionSyntaxException
    {
        FeatureExpressionEvaluator.validate(aVisibleIfExpression);
    }
}
