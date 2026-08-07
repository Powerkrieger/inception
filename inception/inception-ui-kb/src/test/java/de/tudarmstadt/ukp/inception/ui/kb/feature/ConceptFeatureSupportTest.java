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
package de.tudarmstadt.ukp.inception.ui.kb.feature;

import static org.apache.uima.cas.CAS.TYPE_NAME_ANNOTATION;
import static org.apache.uima.cas.CAS.TYPE_NAME_STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.uima.UIMAFramework;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.fit.util.FSUtil;
import org.apache.uima.util.CasCreationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.kb.ConceptFeatureTraits;
import de.tudarmstadt.ukp.inception.schema.api.feature.ConditionalDefaultValueRule;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBasePropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.rendering.editorstate.FeatureState;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;

@ExtendWith(MockitoExtension.class)
public class ConceptFeatureSupportTest
{
    private @Mock KnowledgeBaseService kbService;
    private ConceptFeatureSupport sut;

    @BeforeEach
    public void setUp()
    {
        sut = new ConceptFeatureSupport(
                new ConceptLabelCache(kbService, new KnowledgeBasePropertiesImpl()));
    }

    @Test
    public void testAccepts()
    {
        AnnotationFeature feat1 = new AnnotationFeature("Dummy feature",
                ConceptFeatureSupport.PREFIX + "someConcept");

        AnnotationFeature feat2 = new AnnotationFeature("Dummy feature", "someConcept");

        assertThat(sut.accepts(feat1)).isTrue();
        assertThat(sut.accepts(feat2)).isFalse();
    }

    @Test
    public void testWrapUnwrap() throws Exception
    {
        AnnotationFeature feat1 = new AnnotationFeature("Dummy feature",
                ConceptFeatureSupport.PREFIX + "someConcept");

        KBHandle referenceHandle = new KBHandle("id", "name");

        when(kbService.readHandle((Project) any(), anyString()))
                .thenReturn(Optional.of(new KBHandle("id", "name")));

        when(kbService.readHandle((Project) any(), anyString()))
                .thenReturn(Optional.of(new KBHandle("id", "name")));

        assertThat(sut.wrapFeatureValue(feat1, null, "id")).usingRecursiveComparison()
                .isEqualTo(referenceHandle);
        assertThat(sut.wrapFeatureValue(feat1, null, referenceHandle)).isSameAs(referenceHandle);
        assertThat(sut.wrapFeatureValue(feat1, null, null)).isNull();
        assertThatThrownBy(() -> sut.wrapFeatureValue(feat1, null, new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(sut.unwrapFeatureValue(feat1, referenceHandle)).isEqualTo("id");
        assertThat(sut.unwrapFeatureValue(feat1, "id")).isEqualTo("id");
        assertThat(sut.unwrapFeatureValue(feat1, null)).isNull();
        ;
        assertThatThrownBy(() -> sut.unwrapFeatureValue(feat1, new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- initializeAnnotation (creation-time defaults) -----------------------------------------

    @Test
    public void thatInitializeAnnotationAppliesPlainDefaultValueWhenNoRulesConfigured()
        throws Exception
    {
        var fs = createFsWithFeatures("value");
        var feature = new AnnotationFeature("value", ConceptFeatureSupport.PREFIX + "someConcept");

        var traits = new ConceptFeatureTraits();
        traits.setDefaultValue("cytoplasm");
        sut.writeTraits(feature, traits);

        sut.initializeAnnotation(feature, fs);

        assertThat(FSUtil.getFeature(fs, "value", String.class)).isEqualTo("cytoplasm");
    }

    @Test
    public void thatInitializeAnnotationFallsBackToPlainDefaultWhenSiblingNotYetSet()
        throws Exception
    {
        var fs = createFsWithFeatures("entityType", "compartmentType");
        var feature = new AnnotationFeature("compartmentType",
                ConceptFeatureSupport.PREFIX + "someConcept");

        var traits = new ConceptFeatureTraits();
        traits.setDefaultValue("cytoplasm");
        traits.setConditionalDefaultValues(
                List.of(new ConditionalDefaultValueRule("entityType == \"Gene\"", "nucleus")));
        sut.writeTraits(feature, traits);

        // entityType is not set on the FS yet - e.g. because it is processed after
        // compartmentType at creation time.
        sut.initializeAnnotation(feature, fs);

        assertThat(FSUtil.getFeature(fs, "compartmentType", String.class)).isEqualTo("cytoplasm");
    }

    @Test
    public void thatInitializeAnnotationAppliesConditionalDefaultWhenSiblingAlreadySet()
        throws Exception
    {
        var fs = createFsWithFeatures("entityType", "compartmentType");
        FSUtil.setFeature(fs, "entityType", "Gene");

        var feature = new AnnotationFeature("compartmentType",
                ConceptFeatureSupport.PREFIX + "someConcept");

        var traits = new ConceptFeatureTraits();
        traits.setDefaultValue("cytoplasm");
        traits.setConditionalDefaultValues(
                List.of(new ConditionalDefaultValueRule("entityType == \"Gene\"", "nucleus")));
        sut.writeTraits(feature, traits);

        sut.initializeAnnotation(feature, fs);

        assertThat(FSUtil.getFeature(fs, "compartmentType", String.class)).isEqualTo("nucleus");
    }

    private FeatureStructure createFsWithFeatures(String... aFeatureNames) throws Exception
    {
        var tsd = UIMAFramework.getResourceSpecifierFactory().createTypeSystemDescription();
        var type = tsd.addType("test.Span", "", TYPE_NAME_ANNOTATION);
        for (var featureName : aFeatureNames) {
            type.addFeature(featureName, "", TYPE_NAME_STRING);
        }

        var cas = CasCreationUtils.createCas(tsd, null, null);
        cas.setDocumentText("text");
        var spanType = cas.getTypeSystem().getType("test.Span");
        var fs = cas.createAnnotation(spanType, 0, 4);
        cas.addFsToIndexes(fs);
        return fs;
    }

    // -- onFeatureValueUpdated (update-time recompute) ------------------------------------------

    private AnnotationFeature compartmentTypeFeatureWithRule()
    {
        var feature = new AnnotationFeature("compartmentType",
                ConceptFeatureSupport.PREFIX + "someConcept");

        var traits = new ConceptFeatureTraits();
        traits.setDefaultValue("cytoplasm");
        traits.setConditionalDefaultValues(
                List.of(new ConditionalDefaultValueRule("entityType == \"Gene\"", "nucleus")));
        sut.writeTraits(feature, traits);

        return feature;
    }

    @Test
    public void thatOnFeatureValueUpdatedAppliesConditionalDefaultWhenSiblingChangesToMatch()
    {
        var feature = compartmentTypeFeatureWithRule();
        // Untouched: currently holds the plain fallback default (cytoplasm) that was applied at
        // creation time, when entityType was not yet set.
        var featureState = new FeatureState(VID.NONE_ID, feature, new KBHandle("cytoplasm"));

        Function<String, String> previousValues = Map.<String, String> of()::get; // entityType was
                                                                                  // unset
        Function<String, String> currentValues = Map.of("entityType", "Gene")::get;

        sut.onFeatureValueUpdated(featureState, currentValues, previousValues);

        assertThat(sut.unwrapFeatureValue(feature, featureState.getValue())).isEqualTo("nucleus");
    }

    @Test
    public void thatOnFeatureValueUpdatedRevertsToFallbackWhenSiblingNoLongerMatches()
    {
        var feature = compartmentTypeFeatureWithRule();
        // Untouched: currently holds the value that was auto-applied while entityType was Gene.
        var featureState = new FeatureState(VID.NONE_ID, feature, new KBHandle("nucleus"));

        Function<String, String> previousValues = Map.of("entityType", "Gene")::get;
        Function<String, String> currentValues = Map.of("entityType", "Protein")::get;

        sut.onFeatureValueUpdated(featureState, currentValues, previousValues);

        assertThat(sut.unwrapFeatureValue(feature, featureState.getValue())).isEqualTo("cytoplasm");
    }

    @Test
    public void thatOnFeatureValueUpdatedDoesNotOverrideManuallySetValue()
    {
        var feature = compartmentTypeFeatureWithRule();
        // Manually set to something that does not match what the default logic would have
        // produced for the previous entityType value (nucleus).
        var featureState = new FeatureState(VID.NONE_ID, feature, new KBHandle("membrane"));

        Function<String, String> previousValues = Map.of("entityType", "Gene")::get;
        Function<String, String> currentValues = Map.of("entityType", "Protein")::get;

        sut.onFeatureValueUpdated(featureState, currentValues, previousValues);

        assertThat(sut.unwrapFeatureValue(feature, featureState.getValue())).isEqualTo("membrane");
    }

    @Test
    public void thatOnFeatureValueUpdatedDoesNothingWhenNoRulesConfigured()
    {
        var feature = new AnnotationFeature("compartmentType",
                ConceptFeatureSupport.PREFIX + "someConcept");
        sut.writeTraits(feature, new ConceptFeatureTraits());

        var featureState = new FeatureState(VID.NONE_ID, feature, new KBHandle("cytoplasm"));

        Function<String, String> previousValues = Map.<String, String> of()::get;
        Function<String, String> currentValues = Map.of("entityType", "Gene")::get;

        sut.onFeatureValueUpdated(featureState, currentValues, previousValues);

        assertThat(sut.unwrapFeatureValue(feature, featureState.getValue())).isEqualTo("cytoplasm");
    }

    @Test
    public void thatOnFeatureValueUpdatedDoesNothingWhenWatchedFeatureDidNotChange()
    {
        var feature = compartmentTypeFeatureWithRule();
        var featureState = new FeatureState(VID.NONE_ID, feature, new KBHandle("membrane"));

        Function<String, String> previousValues = Map.of("entityType", "Gene")::get;
        Function<String, String> currentValues = Map.of("entityType", "Gene")::get;

        sut.onFeatureValueUpdated(featureState, currentValues, previousValues);

        assertThat(sut.unwrapFeatureValue(feature, featureState.getValue())).isEqualTo("membrane");
    }

    // -- onSiblingFeatureValueUpdated (recompute for values written straight to the CAS) --------

    /**
     * The case that motivated this hook: a recommender suggestion is accepted, so the annotation is
     * created first - applying the plain fallback default while entityType is still unset - and the
     * predicted entityType is only written afterwards, straight to the CAS. Without this the
     * compartment keeps the fallback even though the rule now matches.
     */
    @Test
    public void thatOnSiblingFeatureValueUpdatedAppliesConditionalDefaultOnCas() throws Exception
    {
        var fs = createFsWithFeatures("entityType", "compartmentType");
        FSUtil.setFeature(fs, "entityType", "Gene");
        FSUtil.setFeature(fs, "compartmentType", "cytoplasm");

        var feature = compartmentTypeFeatureWithRule();

        sut.onSiblingFeatureValueUpdated(feature, fs, //
                name -> "entityType".equals(name) ? "Gene" : null, //
                name -> null);

        assertThat(FSUtil.getFeature(fs, "compartmentType", String.class)).isEqualTo("nucleus");
    }

    @Test
    public void thatOnSiblingFeatureValueUpdatedRevertsToFallbackOnCas() throws Exception
    {
        var fs = createFsWithFeatures("entityType", "compartmentType");
        FSUtil.setFeature(fs, "entityType", "Protein");
        FSUtil.setFeature(fs, "compartmentType", "nucleus");

        var feature = compartmentTypeFeatureWithRule();

        sut.onSiblingFeatureValueUpdated(feature, fs, //
                name -> "entityType".equals(name) ? "Protein" : null, //
                name -> "entityType".equals(name) ? "Gene" : null);

        assertThat(FSUtil.getFeature(fs, "compartmentType", String.class)).isEqualTo("cytoplasm");
    }

    @Test
    public void thatOnSiblingFeatureValueUpdatedDoesNotOverrideDeliberateValueOnCas()
        throws Exception
    {
        var fs = createFsWithFeatures("entityType", "compartmentType");
        FSUtil.setFeature(fs, "entityType", "Protein");
        // Does not match what the rule would have produced for the previous entityType (nucleus),
        // so it was set deliberately and must survive.
        FSUtil.setFeature(fs, "compartmentType", "membrane");

        var feature = compartmentTypeFeatureWithRule();

        sut.onSiblingFeatureValueUpdated(feature, fs, //
                name -> "entityType".equals(name) ? "Protein" : null, //
                name -> "entityType".equals(name) ? "Gene" : null);

        assertThat(FSUtil.getFeature(fs, "compartmentType", String.class)).isEqualTo("membrane");
    }

    @Test
    public void thatOnSiblingFeatureValueUpdatedDoesNothingWhenNoRulesConfigured() throws Exception
    {
        var fs = createFsWithFeatures("entityType", "compartmentType");
        FSUtil.setFeature(fs, "entityType", "Gene");
        FSUtil.setFeature(fs, "compartmentType", "cytoplasm");

        var feature = new AnnotationFeature("compartmentType",
                ConceptFeatureSupport.PREFIX + "someConcept");
        sut.writeTraits(feature, new ConceptFeatureTraits());

        sut.onSiblingFeatureValueUpdated(feature, fs, //
                name -> "entityType".equals(name) ? "Gene" : null, //
                name -> null);

        assertThat(FSUtil.getFeature(fs, "compartmentType", String.class)).isEqualTo("cytoplasm");
    }
}
