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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Map;

import org.junit.jupiter.api.Test;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;

class VisibleIfEvaluatorTest
{
    @Test
    void thatNullOrBlankExpressionIsAlwaysVisible()
    {
        assertThat(VisibleIfEvaluator.isVisible((String) null, name -> null)).isTrue();
        assertThat(VisibleIfEvaluator.isVisible("", name -> null)).isTrue();
        assertThat(VisibleIfEvaluator.isVisible("   ", name -> null)).isTrue();
    }

    @Test
    void thatInvalidExpressionFallsBackToAlwaysVisibleWithoutThrowing()
    {
        assertThatCode(
                () -> assertThat(VisibleIfEvaluator.isVisible("entityType ==", name -> "PERSON"))
                        .isTrue()).doesNotThrowAnyException();
    }

    @Test
    void thatValidExpressionIsEvaluatedAgainstFeature()
    {
        var feature = new AnnotationFeature("gender", "uima.cas.String");
        feature.setVisibleIf("entityType == \"PERSON\"");

        assertThat(VisibleIfEvaluator.isVisible(feature, Map.of("entityType", "PERSON")::get))
                .isTrue();
        assertThat(VisibleIfEvaluator.isVisible(feature, Map.of("entityType", "EVENT")::get))
                .isFalse();
    }

    @Test
    void thatValidatePassesForValidExpressions()
    {
        assertThatCode(() -> VisibleIfEvaluator.validate("entityType == \"PERSON\""))
                .doesNotThrowAnyException();
        assertThatCode(() -> VisibleIfEvaluator.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> VisibleIfEvaluator.validate("")).doesNotThrowAnyException();
    }

    @Test
    void thatValidateThrowsForInvalidExpressions()
    {
        assertThatExceptionOfType(VisibleIfSyntaxException.class)
                .isThrownBy(() -> VisibleIfEvaluator.validate("entityType ="));
    }
}
