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
package de.tudarmstadt.ukp.clarin.webanno.ui.annotation.detail;

import static de.tudarmstadt.ukp.clarin.webanno.ui.annotation.detail.FeatureEditorListPanel.filterVisibleFeatureStates;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.inception.rendering.editorstate.FeatureState;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;

/**
 * Tests the {@code visibleIf} filtering used to decide, for the currently selected annotation,
 * which feature editors are rendered in the sidebar - mirroring the scenario from the feature
 * request: {@code entityType}, {@code gender} and {@code occupation} on the same annotation, with
 * {@code gender}/{@code occupation} only shown when {@code entityType == "PERSON"}.
 */
class FeatureEditorListPanelVisibilityTest
{
    private AnnotationFeature entityTypeFeature;
    private AnnotationFeature genderFeature;
    private AnnotationFeature occupationFeature;
    private AnnotationFeature timeFeature;

    private List<FeatureState> featureStates(String aEntityType)
    {
        entityTypeFeature = new AnnotationFeature("entityType", "uima.cas.String");

        genderFeature = new AnnotationFeature("gender", "uima.cas.String");
        genderFeature.setVisibleIf("entityType == \"PERSON\"");

        occupationFeature = new AnnotationFeature("occupation", "uima.cas.String");
        occupationFeature.setVisibleIf("entityType == \"PERSON\"");

        timeFeature = new AnnotationFeature("time", "uima.cas.String");

        return List.of( //
                new FeatureState(VID.NONE_ID, entityTypeFeature, aEntityType), //
                new FeatureState(VID.NONE_ID, genderFeature, "FEMALE"), //
                new FeatureState(VID.NONE_ID, occupationFeature, "ENGINEER"), //
                new FeatureState(VID.NONE_ID, timeFeature, "2026"));
    }

    private List<String> visibleNames(List<FeatureState> aStates)
    {
        return filterVisibleFeatureStates(aStates).stream() //
                .map(fs -> fs.getFeature().getName()) //
                .toList();
    }

    @Test
    void thatDependentFeaturesAreShownWhenControllingFeatureMatches()
    {
        var states = featureStates("PERSON");

        assertThat(visibleNames(states)).containsExactly("entityType", "gender", "occupation",
                "time");
    }

    @Test
    void thatDependentFeaturesAreHiddenWhenControllingFeatureDoesNotMatch()
    {
        var states = featureStates("EVENT");

        assertThat(visibleNames(states)).containsExactly("entityType", "time");
    }

    @Test
    void thatChangingControllingFeatureImmediatelyChangesVisibility()
    {
        // Simulates the AJAX round-trip: entityType starts as EVENT (gender/occupation hidden)...
        var states = featureStates("EVENT");
        assertThat(visibleNames(states)).containsExactly("entityType", "time");

        // ...the user edits the entityType feature editor in place (same FeatureState objects,
        // as AnnotatorState.getFeatureStates() is mutated in place, not replaced)...
        var entityTypeState = states.get(0);
        entityTypeState.setValue("PERSON");

        // ...and re-filtering (as happens on the next AJAX refresh) immediately shows them.
        assertThat(visibleNames(states)).containsExactly("entityType", "gender", "occupation",
                "time");
    }

    @Test
    void thatHidingAFeatureDoesNotDeleteItsStoredValue()
    {
        var states = featureStates("EVENT");

        // gender/occupation are hidden ...
        assertThat(visibleNames(states)).doesNotContain("gender", "occupation");

        // ... but their FeatureState still carries the previously entered value untouched.
        var genderState = states.stream() //
                .filter(fs -> "gender".equals(fs.getFeature().getName())) //
                .findFirst().orElseThrow();
        assertThat(genderState.getValue()).isEqualTo("FEMALE");

        // Switching the controlling feature back makes it visible again with the same value.
        states.get(0).setValue("PERSON");
        assertThat(visibleNames(states)).contains("gender", "occupation");
        assertThat(genderState.getValue()).isEqualTo("FEMALE");
    }

    @Test
    void thatMissingControllingFeatureHidesDependentFeatures()
    {
        var states = featureStates(null);

        assertThat(visibleNames(states)).containsExactly("entityType", "time");
    }

    @Test
    void thatFeaturesWithoutVisibleIfAreAlwaysShown()
    {
        var states = featureStates("EVENT");

        assertThat(visibleNames(states)).contains("entityType", "time");
    }
}
