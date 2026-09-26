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
package de.tudarmstadt.ukp.inception.curation.merge;

import static de.tudarmstadt.ukp.clarin.webanno.model.LinkMode.NONE;
import static de.tudarmstadt.ukp.inception.curation.merge.CasMerge.copyFeatures;
import static de.tudarmstadt.ukp.inception.curation.merge.CasMergeOperationResult.ResultState.CREATED;
import static de.tudarmstadt.ukp.inception.curation.merge.CasMergeOperationResult.ResultState.UPDATED;
import static de.tudarmstadt.ukp.inception.support.uima.ICasUtil.getAddr;
import static java.util.Comparator.comparingInt;
import static org.apache.uima.fit.util.CasUtil.selectAt;
import static org.apache.uima.fit.util.CasUtil.selectCovered;
import static org.apache.uima.fit.util.FSUtil.getFeature;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.jcas.tcas.Annotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationAdapter;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.CreateSpanAnnotationRequest;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanAdapter;
import de.tudarmstadt.ukp.inception.curation.api.RelationContextFingerprinter;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationException;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.schema.api.adapter.TypeAdapter;

class CasMergeSpan
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static CasMergeOperationResult mergeSpanAnnotation(CasMergeContext aContext,
            SourceDocument aDocument, String aDataOwner, AnnotationLayer aAnnotationLayer,
            CAS aTargetCas, AnnotationFS aSourceFs, boolean aAllowStacking)
        throws AnnotationException
    {
        var adapter = (SpanAdapter) aContext.getAdapter(aAnnotationLayer);
        if (aContext.isSilenceEvents()) {
            adapter.silenceEvents();
        }

        // On layers which distinguish stacked annotations by their relations, several source
        // annotations with the same label may legitimately be merged at the same position. They are
        // told apart by their relations and each target annotation is only matched once per merge.
        var fingerprinter = aContext.getRelationContextFingerprinter(aDocument.getProject());
        var fingerprint = fingerprinter.fingerprint(aSourceFs);

        if (fingerprint != null) {
            var equivalent = findEquivalentAnchoredSpan(aContext, fingerprinter, aTargetCas,
                    adapter, aSourceFs, fingerprint);
            if (equivalent.isPresent()) {
                aContext.claim(aSourceFs, fingerprint, equivalent.get());
                throw new AlreadyMergedException(
                        "The annotation already exists in the target document.");
            }

            // An annotation which only lacks some of the relations of the source annotation (e.g.
            // because only the annotation itself was merged before) is completed instead of adding
            // another annotation next to it
            if (aContext.isMergeAttachedRelations()) {
                var partial = findPartialAnchoredSpan(aContext, fingerprinter, aTargetCas, adapter,
                        aSourceFs);
                if (partial.isPresent()) {
                    aContext.claim(aSourceFs, fingerprint, partial.get());
                    mergeAttachedRelations(aContext, aDocument, aDataOwner, aTargetCas, aSourceFs);
                    return new CasMergeOperationResult(UPDATED, getAddr(partial.get()));
                }
            }
        }
        else if (existsEquivalentSpan(aTargetCas, adapter, aSourceFs)) {
            throw new AlreadyMergedException(
                    "The annotation already exists in the target document.");
        }

        // a) if stacking allowed add this new annotation to the merge view
        var targetType = adapter.getAnnotationType(aTargetCas).get();
        var existingAnnos = selectAt(aTargetCas, targetType, aSourceFs.getBegin(),
                aSourceFs.getEnd());
        if (existingAnnos.isEmpty() || aAllowStacking) {
            // Create the annotation via the adapter - this also takes care of attaching to an
            // annotation if necessary
            var mergedSpan = adapter.handle(CreateSpanAnnotationRequest.builder() //
                    .withDocument(aDocument, aDataOwner, aTargetCas) //
                    .withRange(aSourceFs.getBegin(), aSourceFs.getEnd()) //
                    .build());

            var mergedSpanAddr = -1;
            try {
                copyFeatures(aContext, aDocument, aDataOwner, adapter, mergedSpan, aSourceFs);
                mergedSpanAddr = getAddr(mergedSpan);
                if (fingerprint != null) {
                    aContext.claim(aSourceFs, fingerprint, mergedSpan);
                }
            }
            catch (AnnotationException e) {
                // If there was an error while setting the features, then we skip the entire
                // annotation
                adapter.delete(aDocument, aDataOwner, aTargetCas, VID.of(mergedSpan));
                throw e;
            }

            if (fingerprint != null && aContext.isMergeAttachedRelations()) {
                mergeAttachedRelations(aContext, aDocument, aDataOwner, aTargetCas, aSourceFs);
            }

            return new CasMergeOperationResult(CREATED, mergedSpanAddr);
        }
        // b) if stacking is not allowed, modify the existing annotation with this one - unless we
        // are asked to preserve annotations already present in the target (e.g. curator decisions),
        // in which case we leave the existing annotation untouched.
        else if (aContext.isPreserveExisting()) {
            throw new AnnotationPreservedException(
                    "The target position is already occupied by an existing annotation which is "
                            + "preserved.");
        }
        else {
            var annoToUpdate = existingAnnos.get(0);
            copyFeatures(aContext, aDocument, aDataOwner, adapter, annoToUpdate, aSourceFs);
            if (fingerprint != null) {
                aContext.claim(aSourceFs, fingerprint, annoToUpdate);
                if (aContext.isMergeAttachedRelations()) {
                    mergeAttachedRelations(aContext, aDocument, aDataOwner, aTargetCas, aSourceFs);
                }
            }
            var mergedSpanAddr = getAddr(annoToUpdate);
            return new CasMergeOperationResult(UPDATED, mergedSpanAddr);
        }
    }

    static Stream<Annotation> selectCandidateSpansAt(CAS aTargetCas, TypeAdapter aAdapter,
            AnnotationFS aOriginal)
    {
        var targetType = aAdapter.getAnnotationType(aTargetCas);
        if (targetType.isEmpty()) {
            return Stream.empty();
        }

        return aTargetCas.<Annotation> select(targetType.get()) //
                .at(aOriginal.getBegin(), aOriginal.getEnd()) //
                .sorted((a, b) -> aAdapter.countNonEqualFeatures(a, b,
                        (fs, f) -> f.getLinkMode() == NONE));
    }

    private static Optional<Annotation> findEquivalentAnchoredSpan(CasMergeContext aContext,
            RelationContextFingerprinter aFingerprinter, CAS aTargetCas, TypeAdapter aAdapter,
            AnnotationFS aOriginal, String aFingerprint)
    {
        var targetType = aAdapter.getAnnotationType(aTargetCas);
        if (targetType.isEmpty()) {
            return Optional.empty();
        }

        return selectCandidateSpansAtAnchor(aTargetCas, aFingerprinter, aAdapter, aOriginal) //
                .filter(fs -> !aContext.isClaimed(fs)) //
                .filter(fs -> isEquivalentIgnoringPosition(aAdapter, fs, aOriginal, Set.of())) //
                .filter(fs -> aFingerprint.equals(aFingerprinter.fingerprint(fs))) //
                .findFirst();
    }

    /**
     * Finds a target annotation with the same labels as the given source annotation whose relations
     * are a proper subset of those of the source annotation. If there are several, the one with the
     * most relations is used.
     */
    private static Optional<Annotation> findPartialAnchoredSpan(CasMergeContext aContext,
            RelationContextFingerprinter aFingerprinter, CAS aTargetCas, TypeAdapter aAdapter,
            AnnotationFS aOriginal)
    {
        var sourceEntries = new HashSet<>(aFingerprinter.fingerprintEntries(aOriginal));
        return selectCandidateSpansAtAnchor(aTargetCas, aFingerprinter, aAdapter, aOriginal) //
                .filter(fs -> !aContext.isClaimed(fs)) //
                .filter(fs -> isEquivalentIgnoringPosition(aAdapter, fs, aOriginal, Set.of())) //
                .filter(fs -> {
                    var entries = aFingerprinter.fingerprintEntries(fs);
                    return entries.size() < sourceEntries.size()
                            && sourceEntries.containsAll(entries);
                }) //
                .max(comparingInt(fs -> aFingerprinter.fingerprintEntries(fs).size()));
    }

    /**
     * Merges the relations attached to the given source annotation - which must already have been
     * merged (and claimed) - and the other endpoints of these relations if they do not exist in the
     * target yet. Endpoints on layers which distinguish stacked annotations by their relations are
     * not merged here since they would be merged without their own relations. Existing annotations
     * in the target are not overwritten.
     */
    private static void mergeAttachedRelations(CasMergeContext aContext, SourceDocument aDocument,
            String aDataOwner, CAS aTargetCas, AnnotationFS aSourceFs)
    {
        var project = aDocument.getProject();
        var fingerprinter = aContext.getRelationContextFingerprinter(project);

        var preserveExisting = aContext.isPreserveExisting();
        var mergeAttachedRelations = aContext.isMergeAttachedRelations();
        aContext.setPreserveExisting(true);
        aContext.setMergeAttachedRelations(false);
        try {
            for (var relation : fingerprinter.attachedRelations(aSourceFs)) {
                var relationLayer = aContext.findLayer(project, relation.getType().getName());
                var relationAdapter = (RelationAdapter) aContext.getAdapter(relationLayer);

                try {
                    for (var endpointFeature : List.of(relationAdapter.getSourceFeatureName(),
                            relationAdapter.getTargetFeatureName())) {
                        var endpoint = getFeature(relation, endpointFeature, AnnotationFS.class);
                        if (endpoint == null || endpoint == aSourceFs
                                || fingerprinter.fingerprint(endpoint) != null) {
                            continue;
                        }

                        var endpointLayer = aContext.findLayer(project,
                                endpoint.getType().getName());
                        try {
                            mergeSpanAnnotation(aContext, aDocument, aDataOwner, endpointLayer,
                                    aTargetCas, endpoint, endpointLayer.isAllowStacking());
                        }
                        catch (AlreadyMergedException e) {
                            // Endpoint already exists in the target (or a preserved annotation
                            // occupies its position) - nothing to do
                        }
                    }

                    CasMergeRelation.mergeRelationAnnotation(aContext, aDocument, aDataOwner,
                            relationLayer, aTargetCas, (AnnotationFS) relation,
                            relationLayer.isAllowStacking());
                }
                catch (AlreadyMergedException e) {
                    // Relation already exists in the target - nothing to do
                }
                catch (AnnotationException e) {
                    LOG.warn("Unable to merge relation {} attached to {}: {}", relation, aSourceFs,
                            e.getMessage());
                }
            }
        }
        finally {
            aContext.setPreserveExisting(preserveExisting);
            aContext.setMergeAttachedRelations(mergeAttachedRelations);
        }
    }

    /**
     * Like {@link #selectCandidateSpansAt} but for keyword-less annotations (zero-width or covering
     * a whole sentence), all keyword-less annotations anchored at the same sentence are candidates,
     * regardless of their exact offsets.
     */
    static Stream<Annotation> selectCandidateSpansAtAnchor(CAS aTargetCas,
            RelationContextFingerprinter aFingerprinter, TypeAdapter aAdapter,
            AnnotationFS aOriginal)
    {
        var anchor = aFingerprinter.anchor(aOriginal);
        if (!anchor.keywordless()) {
            return selectCandidateSpansAt(aTargetCas, aAdapter, aOriginal);
        }

        var targetType = aAdapter.getAnnotationType(aTargetCas);
        if (targetType.isEmpty()) {
            return Stream.empty();
        }

        return aTargetCas.<Annotation> select(targetType.get()) //
                .coveredBy(anchor.begin(), anchor.end()) //
                .filter(fs -> anchor.equals(aFingerprinter.anchor(fs)));
    }

    /**
     * Compares the feature values of two annotations of the same layer but not their positions.
     * Used where the position has already been matched by other means (e.g. for keyword-less
     * annotations whose offsets may differ).
     */
    static boolean isEquivalentIgnoringPosition(TypeAdapter aAdapter, FeatureStructure aFS1,
            FeatureStructure aFS2, Set<String> aIgnoredFeatures)
    {
        for (var feature : aAdapter.listFeatures()) {
            if (aIgnoredFeatures.contains(feature.getName())) {
                continue;
            }

            if (!aAdapter.isFeatureValueEqual(feature, aFS1, aFS2)) {
                return false;
            }
        }

        return true;
    }

    private static boolean existsEquivalentSpan(CAS aTargetCas, TypeAdapter aAdapter,
            AnnotationFS aOriginal)
    {
        var targetType = aAdapter.getAnnotationType(aTargetCas);
        if (targetType.isEmpty()) {
            return false;
        }

        return selectCovered(aTargetCas, targetType.get(), aOriginal.getBegin(), aOriginal.getEnd())
                .stream() //
                .anyMatch(fs -> aAdapter.isEquivalentAnnotation(fs, aOriginal));
    }
}
