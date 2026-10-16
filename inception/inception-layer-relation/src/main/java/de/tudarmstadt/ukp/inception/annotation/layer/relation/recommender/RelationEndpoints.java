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

import static org.apache.uima.fit.util.CasUtil.selectAt;

import java.util.Optional;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.text.AnnotationFS;

import de.tudarmstadt.ukp.inception.recommendation.api.model.RelationSuggestion.Endpoint;
import de.tudarmstadt.ukp.inception.recommendation.api.model.SpanSuggestion;

/**
 * Resolves an end of a suggested arc: an annotation at its offsets or, if there is none yet, a span
 * suggestion there. If the recommender said what it linked ({@link Endpoint}), only an annotation
 * or span suggestion agreeing with that counts, so that a relation predicted for the Gene on some
 * words does not end up on a Protein on the same words.
 */
final class RelationEndpoints
{
    private RelationEndpoints()
    {
        // No instances
    }

    static Optional<AnnotationFS> findAnnotation(CAS aCas, Type aType, int aBegin, int aEnd,
            Endpoint aEndpoint)
    {
        // FIXME: Without an endpoint we get the first match for the (begin, end) span. With
        // stacking, there can be more than one and we need to get the right one then which does
        // not need to be the first. We wait for #2135 for a maybe fix.
        return selectAt(aCas, aType, aBegin, aEnd).stream() //
                .filter(a -> aEndpoint == null || aEndpoint.matches(a)) //
                .findFirst();
    }

    static boolean isSuggestedAt(SpanSuggestion aSuggestion, String aSuggestionLayer, Type aType,
            int aBegin, int aEnd, Endpoint aEndpoint)
    {
        if (aSuggestion.getBegin() != aBegin || aSuggestion.getEnd() != aEnd) {
            return false;
        }

        if (aEndpoint != null) {
            return aEndpoint.matches(aSuggestionLayer, aSuggestion);
        }

        return CAS.TYPE_NAME_ANNOTATION.equals(aType.getName())
                || aType.getName().equals(aSuggestionLayer);
    }
}
