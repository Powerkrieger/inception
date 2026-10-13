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
package de.tudarmstadt.ukp.inception.recommendation.api.model;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.text.AnnotationFS;

public class RelationSuggestion
    extends ArcSuggestion_ImplBase<RelationPosition>
    implements Serializable
{
    private static final long serialVersionUID = -1904645143661843249L;

    /**
     * What the recommender linked at an end of the relation: the layer and the non-empty string
     * features of the span it predicted there. With several annotations or span suggestions at the
     * same offsets (e.g. a Gene and a Protein on the same words), this says which one the relation
     * belongs to.
     */
    public record Endpoint(String layer, Map<String, String> features)
        implements Serializable
    {
        public Endpoint
        {
            features = Map.copyOf(features);
        }

        public static Endpoint of(AnnotationFS aSpan)
        {
            var features = new HashMap<String, String>();
            for (var feature : aSpan.getType().getFeatures()) {
                if (!CAS.TYPE_NAME_STRING.equals(feature.getRange().getName())) {
                    continue;
                }
                var value = aSpan.getStringValue(feature);
                if (isNotEmpty(value)) {
                    features.put(feature.getShortName(), value);
                }
            }
            return new Endpoint(aSpan.getType().getName(), features);
        }

        /**
         * @return whether the span suggestion is the one predicted here: same layer and the label
         *         the prediction has for the suggested feature.
         */
        public boolean matches(String aLayer, SpanSuggestion aSuggestion)
        {
            return layer.equals(aLayer) && aSuggestion.getLabel() != null
                    && aSuggestion.getLabel().equals(features.get(aSuggestion.getFeature()));
        }

        /**
         * @return whether the annotation may be the one predicted here: same layer and no feature
         *         set to a different value (it may lack values the prediction has).
         */
        public boolean matches(AnnotationFS aAnnotation)
        {
            if (!layer.equals(aAnnotation.getType().getName())) {
                return false;
            }
            for (var entry : features.entrySet()) {
                var feature = aAnnotation.getType().getFeatureByBaseName(entry.getKey());
                if (feature == null) {
                    continue;
                }
                var value = aAnnotation.getStringValue(feature);
                if (isNotEmpty(value) && !value.equals(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
    }

    // null if the recommender did not say (e.g. suggestions restored from older versions)
    private final Endpoint sourceEndpoint;
    private final Endpoint targetEndpoint;

    public RelationSuggestion(Builder<? extends Builder<?>> aBuilder)
    {
        super(aBuilder);

        sourceEndpoint = aBuilder.sourceEndpoint;
        targetEndpoint = aBuilder.targetEndpoint;
    }

    public Endpoint getSourceEndpoint()
    {
        return sourceEndpoint;
    }

    public Endpoint getTargetEndpoint()
    {
        return targetEndpoint;
    }

    @Override
    public Builder toBuilder()
    {
        return builder() //
                .withId(id) //
                .withGeneration(generation) //
                .withAge(getAge()) //
                .withRecommenderId(recommenderId) //
                .withRecommenderName(recommenderName) //
                .withLayerId(layerId) //
                .withFeature(feature) //
                .withDocument(documentId) //
                .withLabel(label) //
                .withUiLabel(uiLabel) //
                .withScore(score) //
                .withScoreExplanation(scoreExplanation) //
                .withCorrection(correction) //
                .withCorrectionExplanation(correctionExplanation) //
                .withPosition(position) //
                .withSourceEndpoint(sourceEndpoint) //
                .withTargetEndpoint(targetEndpoint) //
                .withAutoAcceptMode(getAutoAcceptMode()) //
                .withHidingFlags(getHidingFlags());
    }

    public static Builder<Builder<?>> builder()
    {
        return new Builder<>();
    }

    public static class Builder<T extends Builder<?>>
        extends ArcSuggestion_ImplBase.Builder<T, RelationPosition>
    {
        private Endpoint sourceEndpoint;
        private Endpoint targetEndpoint;

        public T withSourceEndpoint(Endpoint aEndpoint)
        {
            sourceEndpoint = aEndpoint;
            return (T) this;
        }

        public T withTargetEndpoint(Endpoint aEndpoint)
        {
            targetEndpoint = aEndpoint;
            return (T) this;
        }

        @Override
        public RelationSuggestion build()
        {
            return new RelationSuggestion(this);
        }
    }
}
