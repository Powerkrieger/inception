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
package de.tudarmstadt.ukp.inception.annotation.layer.relation.recommender;

import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.CHARACTERS;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.ANY_OVERLAP;
import static de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport.FEAT_REL_SOURCE;
import static de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport.FEAT_REL_TARGET;
import static de.tudarmstadt.ukp.inception.recommendation.api.model.LearningRecordChangeLocation.MAIN_EDITOR;
import static de.tudarmstadt.ukp.inception.recommendation.api.model.LearningRecordUserAction.ACCEPTED;
import static de.tudarmstadt.ukp.inception.support.uima.ICasUtil.selectFsByAddr;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.apache.uima.fit.factory.TypeSystemDescriptionFactory.createTypeSystemDescription;
import static org.apache.uima.fit.util.CasUtil.getType;
import static org.apache.uima.fit.util.CasUtil.select;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.fit.factory.CasFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.tudarmstadt.ukp.clarin.webanno.constraints.ConstraintsService;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.RelationAdapterImpl;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.SpanAdapterImpl;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.recommender.SpanSuggestionSupport;
import de.tudarmstadt.ukp.inception.recommendation.api.LearningRecordService;
import de.tudarmstadt.ukp.inception.recommendation.api.RecommendationService;
import de.tudarmstadt.ukp.inception.recommendation.api.model.AnnotationSuggestion;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Predictions;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.model.RelationPosition;
import de.tudarmstadt.ukp.inception.recommendation.api.model.RelationSuggestion;
import de.tudarmstadt.ukp.inception.recommendation.api.model.RelationSuggestion.Endpoint;
import de.tudarmstadt.ukp.inception.recommendation.api.model.SpanSuggestion;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupport;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.inception.schema.api.layer.LayerSupportRegistryImpl;

/**
 * Accepting a relation suggestion whose ends are only suggested yet accepts the span suggestions
 * there that agree with the endpoint - onto one new annotation, even when another annotation of the
 * same layer is stacked at those offsets (a Gene where a Protein is already annotated).
 */
@ExtendWith(MockitoExtension.class)
class RelationSuggestionEndpointAcceptanceTest
{
    private static final String ENTITY = "custom.Entity";
    private static final String ROLE = "custom.Role";

    private @Mock ConstraintsService constraintsService;
    private @Mock LearningRecordService learningRecordService;
    private @Mock AnnotationSchemaService schemaService;
    private @Mock RecommendationService recommendationService;
    private @Mock FeatureSupportRegistry featureSupportRegistry;
    private @Mock(name = "primitiveFeatureSupport") FeatureSupport<Void> featureSupport;

    private CAS cas;
    private SourceDocument document;
    private AnnotationLayer entityLayer;
    private AnnotationFeature valueFeature;
    private AnnotationFeature identifierFeature;
    private AnnotationLayer roleLayer;
    private AnnotationFeature roleFeature;
    private Recommender nerRecommender;
    private Recommender linkerRecommender;
    private Recommender roleRecommender;
    private RelationAdapterImpl roleAdapter;
    private Predictions predictions;
    private RelationSuggestionSupport sut;

    @BeforeEach
    void setup() throws Exception
    {
        var tsd = createTypeSystemDescription();
        var entityType = tsd.addType(ENTITY, "", CAS.TYPE_NAME_ANNOTATION);
        entityType.addFeature("value", "", CAS.TYPE_NAME_STRING);
        entityType.addFeature("identifier", "", CAS.TYPE_NAME_STRING);
        var roleType = tsd.addType(ROLE, "", CAS.TYPE_NAME_ANNOTATION);
        roleType.addFeature(FEAT_REL_SOURCE, "", CAS.TYPE_NAME_ANNOTATION);
        roleType.addFeature(FEAT_REL_TARGET, "", CAS.TYPE_NAME_ANNOTATION);
        roleType.addFeature("role", "", CAS.TYPE_NAME_STRING);
        cas = CasFactory.createCas(tsd);
        cas.setDocumentText("IL-4 induces PPAR .");

        var project = new Project();
        project.setId(1L);
        document = new SourceDocument();
        document.setId(1L);
        document.setProject(project);

        entityLayer = new AnnotationLayer(ENTITY, "Entity", SpanLayerSupport.TYPE, project, true,
                CHARACTERS, ANY_OVERLAP);
        entityLayer.setId(1L);
        valueFeature = new AnnotationFeature(1L, entityLayer, "value", CAS.TYPE_NAME_STRING);
        identifierFeature = new AnnotationFeature(2L, entityLayer, "identifier",
                CAS.TYPE_NAME_STRING);

        roleLayer = new AnnotationLayer(ROLE, "Role", RelationLayerSupport.TYPE, project, true,
                CHARACTERS, ANY_OVERLAP);
        roleLayer.setId(2L);
        roleFeature = new AnnotationFeature(3L, roleLayer, "role", CAS.TYPE_NAME_STRING);

        nerRecommender = recommender(10L, entityLayer, valueFeature);
        linkerRecommender = recommender(11L, entityLayer, identifierFeature);
        roleRecommender = recommender(12L, roleLayer, roleFeature);

        lenient().when(featureSupport.accepts(any())).thenReturn(true);
        lenient().doAnswer(inv -> {
            AnnotationFeature feat = inv.getArgument(1);
            var fs = selectFsByAddr(inv.getArgument(0), (int) inv.getArgument(2));
            fs.setStringValue(fs.getType().getFeatureByBaseName(feat.getName()),
                    (String) inv.getArgument(3));
            return null;
        }).when(featureSupport).pushFeatureValue(any(), any(), anyInt(), any());
        lenient().when(featureSupport.getFeatureValue(any(), any())).thenAnswer(inv -> {
            AnnotationFeature feat = inv.getArgument(0);
            org.apache.uima.cas.FeatureStructure fs = inv.getArgument(1);
            return fs.getStringValue(fs.getType().getFeatureByBaseName(feat.getName()));
        });
        @SuppressWarnings({ "unchecked", "rawtypes" })
        var asWild = (Optional) Optional.of(featureSupport);
        lenient().when(featureSupportRegistry.findExtension(any(AnnotationFeature.class)))
                .thenReturn(asWild);

        // Null event publishers silence the adapters: no learning records, no events
        var layerSupportRegistry = new LayerSupportRegistryImpl(emptyList());
        var entityAdapter = new SpanAdapterImpl(layerSupportRegistry, featureSupportRegistry, null,
                entityLayer, () -> asList(valueFeature, identifierFeature), emptyList(),
                constraintsService);
        roleAdapter = new RelationAdapterImpl(layerSupportRegistry, featureSupportRegistry, null,
                roleLayer, FEAT_REL_TARGET, FEAT_REL_SOURCE, () -> asList(roleFeature), emptyList(),
                constraintsService);

        lenient().when(schemaService.getLayer(1L)).thenReturn(entityLayer);
        lenient().when(schemaService.getAdapter(entityLayer)).thenReturn(entityAdapter);
        lenient().when(schemaService.getFeature(eq("value"), any())).thenReturn(valueFeature);
        lenient().when(schemaService.getFeature(eq("identifier"), any()))
                .thenReturn(identifierFeature);

        // Accepting a span suggestion through the service goes to the span layer's own support
        var spanSupport = new SpanSuggestionSupport(null, learningRecordService, null,
                schemaService, featureSupportRegistry, () -> false);
        lenient().when(recommendationService.acceptSuggestion(anyString(), any(), anyString(),
                any(), any(), any(), any())).thenAnswer(inv -> {
                    SpanSuggestion suggestion = inv.getArgument(5);
                    var feature = suggestion.getFeature().equals("value") ? valueFeature
                            : identifierFeature;
                    return spanSupport
                            .acceptSuggestion(inv.getArgument(0), inv.getArgument(1),
                                    inv.getArgument(2), inv.getArgument(3), entityAdapter, feature,
                                    inv.getArgument(4), suggestion, inv.getArgument(6), ACCEPTED)
                            .orElse(null);
                });
        lenient().when(recommendationService.getRecommender(anyLong())).thenReturn(null);

        predictions = new Predictions(new User("user"), "user", project);

        sut = new RelationSuggestionSupport(recommendationService, learningRecordService, null,
                schemaService, featureSupportRegistry);
    }

    @Test
    void thatEndpointIsCreatedWithTypeAndIdentifier() throws Exception
    {
        var il4 = entity(0, 4, "Protein", "uniprot:P05112");
        suggestGene(13, 17, "ncbigene:5468");

        accept(il4, 13, 17);

        var gene = entitiesAt(13, 17);
        assertThat(gene).hasSize(1);
        assertThat(value(gene.get(0))).isEqualTo("Gene");
        assertThat(identifier(gene.get(0))).isEqualTo("ncbigene:5468");
        assertThat(roleTargets()).containsExactly(gene.get(0));
    }

    @Test
    void thatEndpointDoesNotWriteIntoAnotherTypeStackedThere() throws Exception
    {
        var il4 = entity(0, 4, "Protein", "uniprot:P05112");
        // The annotator already has a Protein on "PPAR"; the role is meant for the Gene there
        var protein = entity(13, 17, "Protein", null);
        suggestGene(13, 17, "ncbigene:5468");

        accept(il4, 13, 17);

        assertThat(value(protein)).isEqualTo("Protein");
        assertThat(identifier(protein)).isNull();

        var gene = entitiesAt(13, 17).stream() //
                .filter(e -> e != protein) //
                .toList();
        assertThat(gene).hasSize(1);
        assertThat(value(gene.get(0))).isEqualTo("Gene");
        assertThat(identifier(gene.get(0))).isEqualTo("ncbigene:5468");
        assertThat(roleTargets()).containsExactly(gene.get(0));
    }

    private void suggestGene(int aBegin, int aEnd, String aIdentifier)
    {
        // The identifier scores higher, so the old code accepted it first - onto the Protein
        predictions.putSuggestions(2, 0, 0, List.<AnnotationSuggestion> of( //
                spanSuggestion(nerRecommender, valueFeature, aBegin, aEnd, "Gene", 0.90), //
                spanSuggestion(linkerRecommender, identifierFeature, aBegin, aEnd, aIdentifier,
                        0.95)));
    }

    private void accept(AnnotationFS aSource, int aTargetBegin, int aTargetEnd) throws Exception
    {
        var suggestion = RelationSuggestion.builder() //
                .withRecommender(roleRecommender) //
                .withDocument(document) //
                .withLayer(roleLayer) //
                .withFeature(roleFeature) //
                .withPosition(new RelationPosition(aSource.getBegin(), aSource.getEnd(),
                        aTargetBegin, aTargetEnd)) //
                .withLabel("STIMULATING") //
                .withUiLabel("STIMULATING") //
                .withScore(0.9) //
                .withSourceEndpoint(Endpoint.of(aSource)) //
                .withTargetEndpoint(new Endpoint(ENTITY,
                        Map.of("value", "Gene", "identifier", "ncbigene:5468"))) //
                .build();

        sut.acceptSuggestion("user", document, "user", cas, roleAdapter, roleFeature, predictions,
                suggestion, MAIN_EDITOR, ACCEPTED);
    }

    private Recommender recommender(long aId, AnnotationLayer aLayer, AnnotationFeature aFeature)
    {
        var recommender = new Recommender("rec-" + aId, aLayer);
        recommender.setId(aId);
        recommender.setFeature(aFeature);
        return recommender;
    }

    private SpanSuggestion spanSuggestion(Recommender aRecommender, AnnotationFeature aFeature,
            int aBegin, int aEnd, String aLabel, double aScore)
    {
        return SpanSuggestion.builder() //
                .withRecommender(aRecommender) //
                .withDocument(document) //
                .withLayer(entityLayer) //
                .withFeature(aFeature) //
                .withPosition(aBegin, aEnd) //
                .withCoveredText(cas.getDocumentText().substring(aBegin, aEnd)) //
                .withLabel(aLabel) //
                .withUiLabel(aLabel) //
                .withScore(aScore) //
                .build();
    }

    private AnnotationFS entity(int aBegin, int aEnd, String aValue, String aIdentifier)
    {
        var type = getType(cas, ENTITY);
        var fs = cas.createAnnotation(type, aBegin, aEnd);
        fs.setStringValue(type.getFeatureByBaseName("value"), aValue);
        fs.setStringValue(type.getFeatureByBaseName("identifier"), aIdentifier);
        cas.addFsToIndexes(fs);
        return fs;
    }

    private List<AnnotationFS> entitiesAt(int aBegin, int aEnd)
    {
        return select(cas, getType(cas, ENTITY)).stream() //
                .filter(e -> e.getBegin() == aBegin && e.getEnd() == aEnd) //
                .toList();
    }

    private List<AnnotationFS> roleTargets()
    {
        var type = getType(cas, ROLE);
        return select(cas, type).stream() //
                .map(r -> (AnnotationFS) r
                        .getFeatureValue(type.getFeatureByBaseName(FEAT_REL_TARGET))) //
                .toList();
    }

    private static String value(AnnotationFS aEntity)
    {
        return aEntity.getStringValue(aEntity.getType().getFeatureByBaseName("value"));
    }

    private static String identifier(AnnotationFS aEntity)
    {
        return aEntity.getStringValue(aEntity.getType().getFeatureByBaseName("identifier"));
    }
}
