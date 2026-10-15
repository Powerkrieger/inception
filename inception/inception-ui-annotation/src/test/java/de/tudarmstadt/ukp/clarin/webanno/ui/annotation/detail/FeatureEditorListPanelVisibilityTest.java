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
import static de.tudarmstadt.ukp.clarin.webanno.ui.annotation.detail.FeatureEditorListPanel.isStaleFeatureState;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.danekja.java.util.function.serializable.SerializableSupplier;
import org.junit.jupiter.api.Test;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.inception.rendering.editorstate.FeatureState;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupport;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureType;

/**
 * Tests the {@code visibleIf} filtering used to decide, for the currently selected annotation,
 * which feature editors are rendered in the sidebar - mirroring the scenario from the feature
 * request: {@code entityType}, {@code gender} and {@code occupation} on the same annotation, with
 * {@code gender}/{@code occupation} only shown when {@code entityType == "PERSON"}.
 */
class FeatureEditorListPanelVisibilityTest
{
    /**
     * None of the features in this test are backed by a real {@link FeatureSupport} (they are plain
     * {@code uima.cas.String} features not wired to any registered support), so the lookup falls
     * back to plain {@code String.valueOf} - this stub models that "no support registered" case
     * without pulling in a mocking framework this test module doesn't otherwise depend on.
     */
    private static final FeatureSupportRegistry NO_FEATURE_SUPPORT_REGISTERED = //
            new FeatureSupportRegistry()
            {
                @Override
                public <T> Optional<FeatureSupport<T>> findExtension(AnnotationFeature aKey)
                {
                    return Optional.empty();
                }

                @Override
                public List<FeatureType> getAllTypes(AnnotationLayer aLayer)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<FeatureType> getUserSelectableTypes(AnnotationLayer aLayer)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public FeatureType getFeatureType(AnnotationFeature aFeature)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public <T> T readTraits(AnnotationFeature aFeature,
                        SerializableSupplier<T> aIfMissing)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isAccessible(AnnotationFeature aFeature)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean isSupported(AnnotationFeature aFeature)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<FeatureSupport<?>> getExtensions()
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<FeatureSupport<?>> getExtensions(AnnotationFeature aContext)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public <X extends FeatureSupport<?>> Optional<X> getExtension(String aId)
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public <X extends FeatureSupport<?>> Optional<X> findGenericExtension(
                        AnnotationFeature aKey)
                {
                    throw new UnsupportedOperationException();
                }
            };

    private AnnotationFeature entityTypeFeature;
    private AnnotationFeature genderFeature;
    private AnnotationFeature occupationFeature;
    private AnnotationFeature timeFeature;

    /** Dependent features left empty, as on a freshly created annotation. */
    private List<FeatureState> featureStates(String aEntityType)
    {
        return featureStates(aEntityType, null, null);
    }

    private List<FeatureState> featureStates(String aEntityType, String aGender, String aOccupation)
    {
        entityTypeFeature = new AnnotationFeature("entityType", "uima.cas.String");

        genderFeature = new AnnotationFeature("gender", "uima.cas.String");
        genderFeature.setVisibleIf("entityType == \"PERSON\"");

        occupationFeature = new AnnotationFeature("occupation", "uima.cas.String");
        occupationFeature.setVisibleIf("entityType == \"PERSON\"");

        timeFeature = new AnnotationFeature("time", "uima.cas.String");

        return List.of( //
                new FeatureState(VID.NONE_ID, entityTypeFeature, aEntityType), //
                new FeatureState(VID.NONE_ID, genderFeature, aGender), //
                new FeatureState(VID.NONE_ID, occupationFeature, aOccupation), //
                new FeatureState(VID.NONE_ID, timeFeature, "2026"));
    }

    private List<String> visibleNames(List<FeatureState> aStates)
    {
        return filterVisibleFeatureStates(aStates, NO_FEATURE_SUPPORT_REGISTERED).stream() //
                .map(fs -> fs.getFeature().getName()) //
                .toList();
    }

    private List<String> staleNames(List<FeatureState> aStates)
    {
        return aStates.stream() //
                .filter(fs -> isStaleFeatureState(fs, aStates, NO_FEATURE_SUPPORT_REGISTERED)) //
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
    void thatHiddenFeatureWithValueIsShownAsStale()
    {
        // gender was filled in while entityType was PERSON, then entityType was changed
        var states = featureStates("EVENT", "FEMALE", null);

        // gender stays visible because it still holds a value, occupation (empty) is hidden
        assertThat(visibleNames(states)).containsExactly("entityType", "gender", "time");
        assertThat(staleNames(states)).containsExactly("gender");
        assertThat(states.get(1).getValue()).isEqualTo("FEMALE");
    }

    @Test
    void thatClearingAStaleFeatureHidesIt()
    {
        var states = featureStates("EVENT", "FEMALE", null);
        assertThat(staleNames(states)).containsExactly("gender");

        states.get(1).setValue(null);

        assertThat(visibleNames(states)).containsExactly("entityType", "time");
        assertThat(staleNames(states)).isEmpty();
    }

    @Test
    void thatBlankValueDoesNotMakeAFeatureStale()
    {
        var states = featureStates("EVENT", "  ", "");

        assertThat(visibleNames(states)).containsExactly("entityType", "time");
        assertThat(staleNames(states)).isEmpty();
    }

    @Test
    void thatFeatureVisibleByItsExpressionIsNotStale()
    {
        var states = featureStates("PERSON", "FEMALE", "ENGINEER");

        assertThat(staleNames(states)).isEmpty();
    }

    @Test
    void thatSwitchingControllingFeatureBackClearsStaleness()
    {
        var states = featureStates("EVENT", "FEMALE", "ENGINEER");
        assertThat(staleNames(states)).containsExactly("gender", "occupation");

        states.get(0).setValue("PERSON");

        assertThat(staleNames(states)).isEmpty();
        assertThat(states.get(1).getValue()).isEqualTo("FEMALE");
    }

    @Test
    void thatConceptFeatureWithValueIsShownAsStale()
    {
        var identifierFeature = new AnnotationFeature("gene_identifier", "kb:<ANY>");
        identifierFeature.setVisibleIf("entityType == \"Gene\"");

        var states = List.of( //
                new FeatureState(VID.NONE_ID,
                        new AnnotationFeature("entityType", "uima.cas.String"), "Protein"), //
                new FeatureState(VID.NONE_ID, identifierFeature, "ncbigene:7157"));

        assertThat(visibleNames(states)).containsExactly("entityType", "gene_identifier");
        assertThat(staleNames(states)).containsExactly("gene_identifier");
    }

    @Test
    void thatPrimitiveFeatureIsNeverStale()
    {
        // The CAS cannot distinguish an unset boolean from false, so primitive features are
        // never kept visible on account of their value
        var adultFeature = new AnnotationFeature("adult", "uima.cas.Boolean");
        adultFeature.setVisibleIf("entityType == \"PERSON\"");

        var states = List.of( //
                new FeatureState(VID.NONE_ID,
                        new AnnotationFeature("entityType", "uima.cas.String"), "EVENT"), //
                new FeatureState(VID.NONE_ID, adultFeature, true));

        assertThat(visibleNames(states)).containsExactly("entityType");
        assertThat(staleNames(states)).isEmpty();
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
