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

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.uima.cas.text.AnnotationPredicates.overlapping;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.fit.util.FSUtil;
import org.apache.uima.jcas.cas.AnnotationBase;
import org.apache.uima.jcas.tcas.Annotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.rendering.pipeline.RenderStep;
import de.tudarmstadt.ukp.inception.rendering.request.RenderRequest;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VDocument;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.ui.kb.feature.ConceptLabelCache.Key;

/**
 * Pre-render step that walks the visible layers, collects every concept-feature value about to be
 * rendered, and warms {@link ConceptLabelCache} via a single bulk load per (project, repositoryId)
 * group. Runs immediately before {@link RenderStep#RENDER_STRUCTURE} so the per-annotation render
 * loop downstream finds each label already cached.
 *
 * <p>
 * Self-contained on purpose: no changes to the {@code FeatureSupport} SPI. If the warming misses
 * (e.g. because of a logic drift vs. the actual renderer), the per-annotation
 * {@code labelCache.get(...)} calls degrade gracefully to single-key loads — no correctness impact,
 * only the perf benefit is reduced.
 */
@Order(KbLabelCachePrewarmStep.RENDER_PREWARM_KB_LABELS)
public class KbLabelCachePrewarmStep
    implements RenderStep
{
    public static final String ID = "KbLabelCachePrewarmer";

    /**
     * Ordered just before {@link RenderStep#RENDER_STRUCTURE} so the cache is warm when
     * {@code PreRendererImpl} kicks off the per-annotation render loop.
     */
    public static final int RENDER_PREWARM_KB_LABELS = RenderStep.RENDER_STRUCTURE - 1;

    private static final Logger LOG = LoggerFactory.getLogger(KbLabelCachePrewarmStep.class);

    private final ConceptLabelCache labelCache;
    private final ConceptFeatureSupport conceptFeatureSupport;
    private final MultiValueConceptFeatureSupport multiValueConceptFeatureSupport;
    private final AnnotationSchemaService schemaService;

    public KbLabelCachePrewarmStep(ConceptLabelCache aLabelCache,
            ConceptFeatureSupport aConceptFeatureSupport,
            MultiValueConceptFeatureSupport aMultiValueConceptFeatureSupport,
            AnnotationSchemaService aSchemaService)
    {
        labelCache = aLabelCache;
        conceptFeatureSupport = aConceptFeatureSupport;
        multiValueConceptFeatureSupport = aMultiValueConceptFeatureSupport;
        schemaService = aSchemaService;
    }

    @Override
    public String getId()
    {
        return ID;
    }

    @Override
    public void render(VDocument aVdoc, RenderRequest aRequest)
    {
        var cas = aRequest.getCas();
        if (cas == null) {
            return;
        }

        var documentText = cas.getDocumentText();
        if (documentText == null || documentText.isEmpty()) {
            return;
        }

        var windowBegin = Math.max(0, aRequest.getWindowBeginOffset());
        var windowEnd = Math.min(documentText.length(), aRequest.getWindowEndOffset());

        var keys = new LinkedHashSet<Key>();
        var fallbacks = new HashMap<AnnotationFeature, List<String>>();

        for (var layer : aRequest.getVisibleLayers()) {
            collectKeysForLayer(cas, layer, windowBegin, windowEnd, aRequest, keys, fallbacks);
        }

        if (keys.isEmpty()) {
            return;
        }

        var start = System.currentTimeMillis();
        var unknown = unknownKeys(labelCache.getAll(keys));
        // Identifiers the primary knowledge base doesn't know are looked up in the feature's
        // fallback knowledge bases - also in bulk, one round per fallback
        for (var round = 0; !unknown.isEmpty(); round++) {
            var next = new LinkedHashSet<Key>();
            for (var key : unknown) {
                var featureFallbacks = fallbacks.getOrDefault(key.getAnnotationFeature(),
                        List.of());
                if (round < featureFallbacks.size()) {
                    next.add(Key.of(key.getAnnotationFeature(), featureFallbacks.get(round),
                            key.getLabel()));
                }
            }
            if (next.isEmpty()) {
                break;
            }
            unknown = unknownKeys(labelCache.getAll(next));
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("Prewarmed {} concept-label keys in {}ms", keys.size(),
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * Unknown identifiers come back as identifier-only handles (see {@link ConceptLabelCache}).
     */
    private static List<Key> unknownKeys(Map<Key, KBHandle> aHandles)
    {
        return aHandles.entrySet().stream() //
                .filter(e -> e.getValue().getName() == null) //
                .map(Map.Entry::getKey) //
                .toList();
    }

    private void collectKeysForLayer(org.apache.uima.cas.CAS aCas, AnnotationLayer aLayer,
            int aWindowBegin, int aWindowEnd, RenderRequest aRequest, Set<Key> aOut,
            Map<AnnotationFeature, List<String>> aFallbacks)
    {
        // Resolve repositoryId once per feature — readTraits deserializes JSON, so doing it per
        // FS would scale with the document size.
        var featureInfos = schemaService.listSupportedFeatures(aLayer).stream() //
                .filter(f -> !aRequest.getHiddenFeatures().contains(f.getId())) //
                .map(this::toFeatureInfo) //
                .filter(java.util.Objects::nonNull) //
                .toList();
        if (featureInfos.isEmpty()) {
            return;
        }

        for (var info : featureInfos) {
            if (!info.fallbackRepositoryIds().isEmpty()) {
                aFallbacks.put(info.feature(), info.fallbackRepositoryIds());
            }
        }

        var type = aCas.getTypeSystem().getType(aLayer.getName());
        if (type == null) {
            return;
        }

        // Document-level layers are AnnotationBase subtypes without offsets - they are always
        // in the window, so the overlap check applies only to proper Annotations.
        for (var fs : aCas.<AnnotationBase> select(type)) {
            if (fs instanceof Annotation ann && !overlapping(ann, aWindowBegin, aWindowEnd)) {
                continue;
            }
            for (var info : featureInfos) {
                extractKeys(info, fs, aOut);
            }
        }
    }

    private FeatureInfo toFeatureInfo(AnnotationFeature aFeature)
    {
        if (conceptFeatureSupport.accepts(aFeature)) {
            var traits = conceptFeatureSupport.readTraits(aFeature);
            return new FeatureInfo(aFeature, traits.getRepositoryId(),
                    fallbacksOf(traits.getRepositoryIdsInOrder()), false);
        }
        if (multiValueConceptFeatureSupport.accepts(aFeature)) {
            var traits = multiValueConceptFeatureSupport.readTraits(aFeature);
            return new FeatureInfo(aFeature, traits.getRepositoryId(),
                    fallbacksOf(traits.getRepositoryIdsInOrder()), true);
        }
        return null;
    }

    private void extractKeys(FeatureInfo aInfo, FeatureStructure aFs, Set<Key> aOut)
    {
        var feature = aInfo.feature();
        if (!aInfo.isMulti()) {
            var id = FSUtil.getFeature(aFs, feature.getName(), String.class);
            if (isNotBlank(id)) {
                aOut.add(Key.of(feature, aInfo.repositoryId(), id));
            }
            return;
        }

        @SuppressWarnings("unchecked")
        var values = (List<String>) FSUtil.getFeature(aFs, feature.getName(), List.class);
        if (values == null || values.isEmpty()) {
            return;
        }
        for (var id : values) {
            if (isNotBlank(id)) {
                aOut.add(Key.of(feature, aInfo.repositoryId(), id));
            }
        }
    }

    private static List<String> fallbacksOf(List<String> aRepositoryIdsInOrder)
    {
        return aRepositoryIdsInOrder.size() > 1
                ? aRepositoryIdsInOrder.subList(1, aRepositoryIdsInOrder.size())
                : List.of();
    }

    private record FeatureInfo(AnnotationFeature feature, String repositoryId,
            List<String> fallbackRepositoryIds, boolean isMulti)
    {}
}
