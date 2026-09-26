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
package de.tudarmstadt.ukp.clarin.webanno.curation.casdiff;

import static de.tudarmstadt.ukp.clarin.webanno.model.LinkMode.NONE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationAdapter;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerTraits;
import de.tudarmstadt.ukp.inception.curation.api.RelationContextFingerprinter;
import de.tudarmstadt.ukp.inception.curation.api.RelationContextFingerprinter.RelationDecl;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;

/**
 * Builds a {@link RelationContextFingerprinter} for all span layers of a project which have
 * {@link SpanLayerTraits#isDistinguishStackedByRelations()} enabled.
 */
public class RelationContextFingerprinterFactory
{
    private RelationContextFingerprinterFactory()
    {
        // No instances
    }

    public static RelationContextFingerprinter create(AnnotationSchemaService aSchemaService,
            Project aProject)
    {
        var layers = aSchemaService.listAnnotationLayer(aProject);

        var spanLayers = layers.stream() //
                .filter(AnnotationLayer::isEnabled) //
                .filter(l -> SpanLayerSupport.TYPE.equals(l.getType())) //
                .filter(l -> aSchemaService.getAdapter(l).getTraits(SpanLayerTraits.class)
                        .map(SpanLayerTraits::isDistinguishStackedByRelations) //
                        .orElse(false)) //
                .toList();

        if (spanLayers.isEmpty()) {
            return RelationContextFingerprinter.NONE;
        }

        var relationLayers = layers.stream() //
                .filter(AnnotationLayer::isEnabled) //
                .filter(l -> RelationLayerSupport.TYPE.equals(l.getType())) //
                .toList();

        var relationsBySpanType = new HashMap<String, List<RelationDecl>>();
        for (var spanLayer : spanLayers) {
            var decls = new ArrayList<RelationDecl>();
            for (var relLayer : relationLayers) {
                // Relation layers without an attach type can connect any annotations
                if (relLayer.getAttachType() != null
                        && !relLayer.getAttachType().equals(spanLayer)) {
                    continue;
                }

                var adapter = (RelationAdapter) aSchemaService.getAdapter(relLayer);
                var features = aSchemaService.listSupportedFeatures(relLayer).stream() //
                        .filter(AnnotationFeature::isEnabled) //
                        .filter(AnnotationFeature::isCuratable) //
                        .filter(f -> NONE.equals(f.getLinkMode())) //
                        .map(AnnotationFeature::getName) //
                        .sorted() //
                        .toList();

                decls.add(new RelationDecl(relLayer.getName(), adapter.getSourceFeatureName(),
                        adapter.getTargetFeatureName(), features));
            }
            relationsBySpanType.put(spanLayer.getName(), decls);
        }

        return new RelationContextFingerprinter(relationsBySpanType);
    }
}
