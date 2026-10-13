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

import static java.util.Collections.emptySet;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Type;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.inception.recommendation.api.RecommendationService;
import de.tudarmstadt.ukp.inception.recommendation.api.SuggestionRenderer;
import de.tudarmstadt.ukp.inception.recommendation.api.model.AnnotationSuggestion;
import de.tudarmstadt.ukp.inception.recommendation.api.model.ArcSuggestion_ImplBase;
import de.tudarmstadt.ukp.inception.recommendation.api.model.RelationSuggestion;
import de.tudarmstadt.ukp.inception.recommendation.api.model.RelationSuggestion.Endpoint;
import de.tudarmstadt.ukp.inception.recommendation.api.model.SpanSuggestion;
import de.tudarmstadt.ukp.inception.recommendation.api.model.SuggestionDocumentGroup;
import de.tudarmstadt.ukp.inception.rendering.request.RenderRequest;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VArc;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VDocument;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupport;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;

public abstract class ArcSuggestionRenderer_ImplBase<T extends ArcSuggestion_ImplBase<?>>
    implements SuggestionRenderer
{
    private final RecommendationService recommendationService;
    private final AnnotationSchemaService annotationService;
    private final FeatureSupportRegistry fsRegistry;

    public ArcSuggestionRenderer_ImplBase(RecommendationService aRecommendationService,
            AnnotationSchemaService aAnnotationService, FeatureSupportRegistry aFsRegistry)
    {
        recommendationService = aRecommendationService;
        annotationService = aAnnotationService;
        fsRegistry = aFsRegistry;
    }

    @Override
    public void render(VDocument aVDoc, RenderRequest aRequest,
            SuggestionDocumentGroup<? extends AnnotationSuggestion> aSuggestions,
            AnnotationLayer aLayer)
    {
        var cas = aRequest.getCas();

        // TODO #176 use the document Id once it it available in the CAS
        var groupedPredictions = (SuggestionDocumentGroup<T>) aSuggestions;

        // No recommendations to render for this layer
        if (groupedPredictions.isEmpty()) {
            return;
        }

        recommendationService.calculateSuggestionVisibility(
                aRequest.getSessionOwner().getUsername(), aRequest.getSourceDocument(), cas,
                aRequest.getAnnotationUser().getUsername(), aLayer, groupedPredictions,
                aRequest.getWindowBeginOffset(), aRequest.getWindowEndOffset());

        var pref = recommendationService.getPreferences(aRequest.getAnnotationUser(),
                aLayer.getProject());

        // Bulk-load all the features of this layer to avoid having to do repeated DB accesses later
        var features = annotationService.listSupportedFeatures(aLayer).stream()
                .collect(toMap(AnnotationFeature::getName, identity()));

        var rankerCache = new HashMap<Long, Boolean>();

        // The span suggestions shown, which suggested arcs may attach to (span layers render first)
        var spanSuggestions = recommendationService
                .getPredictions(aRequest.getSessionOwner(), aLayer.getProject()).values().stream() //
                .flatMap(p -> p
                        .getSuggestionsByDocument(aRequest.getSourceDocument(),
                                aRequest.getWindowBeginOffset(), aRequest.getWindowEndOffset())
                        .stream()) //
                .filter(SpanSuggestion.class::isInstance) //
                .map(SpanSuggestion.class::cast) //
                .filter(s -> aVDoc.getSpan(s.getVID()) != null) //
                .toList();

        for (var group : groupedPredictions) {
            for (var suggestion : group.bestSuggestions(pref)) {
                // Skip rendering AnnotationObjects that should not be rendered
                if (!pref.isShowAllPredictions() && !suggestion.isVisible()) {
                    continue;
                }

                var feature = features.get(suggestion.getFeature());

                var hiddenValues = aRequest.getHiddenFeatureValues().getOrDefault(feature.getId(),
                        emptySet());
                if (hiddenValues.contains(suggestion.getLabel())) {
                    // If the feature value is hidden, we do not render the suggestion
                    continue;
                }

                var position = suggestion.getPosition();
                int sourceBegin = position.getSourceBegin();
                int sourceEnd = position.getSourceEnd();
                int targetBegin = position.getTargetBegin();
                int targetEnd = position.getTargetEnd();

                // Retrieve the UI display label for the given feature value
                var sourceType = getSourceType(cas, aLayer, feature);
                var targetType = getTargetType(cas, aLayer, feature);

                var relation = suggestion instanceof RelationSuggestion r ? r : null;
                var source = findEndpoint(aVDoc, cas, spanSuggestions, sourceType, sourceBegin,
                        sourceEnd, relation != null ? relation.getSourceEndpoint() : null);
                var target = findEndpoint(aVDoc, cas, spanSuggestions, targetType, targetBegin,
                        targetEnd, relation != null ? relation.getTargetEndpoint() : null);

                if (source.isEmpty() || target.isEmpty()) {
                    continue;
                }

                FeatureSupport<?> featureSupport = fsRegistry.findExtension(feature).orElseThrow();
                var annotation = featureSupport.renderFeatureValue(feature, suggestion.getLabel());

                Map<String, String> featureValues = annotation != null
                        ? Map.of(suggestion.getFeature(), annotation)
                        : Map.of();

                var isRanker = rankerCache.computeIfAbsent(suggestion.getRecommenderId(), id -> {
                    var recommender = recommendationService.getRecommender(id);
                    if (recommender != null) {
                        var factory = recommendationService.getRecommenderFactory(recommender);
                        return factory.map(f -> f.isRanker(recommender)).orElse(false);
                    }
                    return false;
                });

                var arc = renderArc(aLayer, suggestion, source.get(), target.get(), featureValues);
                arc.setScore(suggestion.getScore());
                arc.setHideScore(isRanker);

                aVDoc.add(arc);
            }
        }
    }

    private Optional<VID> findEndpoint(VDocument aVDoc, CAS aCas,
            List<SpanSuggestion> aSpanSuggestions, Type aType, int aBegin, int aEnd,
            Endpoint aEndpoint)
    {
        var annotation = RelationEndpoints.findAnnotation(aCas, aType, aBegin, aEnd, aEndpoint);
        if (annotation.isPresent()) {
            return annotation.map(VID::of);
        }

        // Not annotated yet: draw the arc to the span suggestion there. It disappears with that
        // suggestion when it is rejected or hidden, and accepting the arc accepts it first.
        return aSpanSuggestions.stream() //
                .filter(s -> RelationEndpoints.isSuggestedAt(s,
                        aVDoc.getSpan(s.getVID()).getLayer().getName(), aType, aBegin, aEnd,
                        aEndpoint)) //
                .map(SpanSuggestion::getVID) //
                .findFirst();
    }

    protected abstract VArc renderArc(AnnotationLayer aLayer, T suggestion, VID source, VID target,
            Map<String, String> featureAnnotation);

    protected abstract Type getSourceType(CAS aCas, AnnotationLayer aLayer,
            AnnotationFeature aFeature);

    protected abstract Type getTargetType(CAS aCas, AnnotationLayer aLayer,
            AnnotationFeature aFeature);
}
