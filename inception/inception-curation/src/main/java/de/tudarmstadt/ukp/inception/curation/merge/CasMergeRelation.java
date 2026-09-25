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

import static de.tudarmstadt.ukp.inception.curation.merge.CasMerge.copyFeatures;
import static de.tudarmstadt.ukp.inception.curation.merge.CasMergeOperationResult.ResultState.CREATED;
import static de.tudarmstadt.ukp.inception.curation.merge.CasMergeOperationResult.ResultState.UPDATED;
import static de.tudarmstadt.ukp.inception.curation.merge.CasMergeSpan.selectCandidateSpansAt;
import static de.tudarmstadt.ukp.inception.support.uima.ICasUtil.getAddr;
import static java.util.Collections.emptyList;
import static org.apache.uima.fit.util.CasUtil.selectCovered;
import static org.apache.uima.fit.util.FSUtil.getFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.jcas.tcas.Annotation;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationAdapter;
import de.tudarmstadt.ukp.inception.curation.api.RelationContextFingerprinter;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationException;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.schema.api.adapter.TypeAdapter;

class CasMergeRelation
{
    static CasMergeOperationResult mergeRelationAnnotation(CasMergeContext aContext,
            SourceDocument aDocument, String aDataOwner, AnnotationLayer aAnnotationLayer,
            CAS aTargetCas, AnnotationFS aSourceFs, boolean aAllowStacking)
        throws AnnotationException
    {
        var relationAdapter = (RelationAdapter) aContext.getAdapter(aAnnotationLayer);
        if (aContext.isSilenceEvents()) {
            relationAdapter.silenceEvents();
        }

        // If an endpoint is on a layer which distinguishes stacked annotations by their relations,
        // the relation must be attached to the specific stacked endpoint annotation. Offsets alone
        // are then not sufficient to check whether an equivalent relation already exists.
        var fingerprinter = aContext.getRelationContextFingerprinter(aDocument.getProject());
        var anchored = fingerprinter
                .fingerprint(getFeature(aSourceFs, relationAdapter.getSourceFeatureName(),
                        AnnotationFS.class)) != null
                || fingerprinter.fingerprint(getFeature(aSourceFs,
                        relationAdapter.getTargetFeatureName(), AnnotationFS.class)) != null;

        if (!anchored && existsEquivalentRelation(aTargetCas, relationAdapter, aSourceFs)) {
            throw new AlreadyMergedException(
                    "The annotation already exists in the target document.");
        }

        var candidateSources = findRelationEndpointInTargetCas(aContext, aDocument, aTargetCas,
                aSourceFs, relationAdapter.getSourceFeatureName()).limit(2).toList();
        if (candidateSources.size() > 1) {
            throw new MergeConflictException(
                    "There are multiple possible sources endpoints for this relation in "
                            + "the target document. Cannot merge this annotation.");
        }

        var candidateTargets = findRelationEndpointInTargetCas(aContext, aDocument, aTargetCas,
                aSourceFs, relationAdapter.getTargetFeatureName()).limit(2).toList();
        if (candidateTargets.size() > 1) {
            throw new MergeConflictException(
                    "There are multiple possible target endpoints for this relation in the "
                            + "target document. Cannot merge this annotation.");
        }

        // check if target/source exists in the mergeview
        if (candidateSources.isEmpty() || candidateTargets.isEmpty()) {
            throw new UnfulfilledPrerequisitesException("Both the source and target annotation"
                    + " must exist in the target document. Please first merge/create them");
        }

        var originFs = candidateSources.get(0);
        var targetFs = candidateTargets.get(0);

        if (relationAdapter.getAttachFeatureName() != null) {
            var originAttachAnnotation = getFeature(originFs,
                    relationAdapter.getAttachFeatureName(), AnnotationFS.class);
            var targetAttachAnnotation = getFeature(targetFs,
                    relationAdapter.getAttachFeatureName(), AnnotationFS.class);

            if (originAttachAnnotation == null || targetAttachAnnotation == null) {
                throw new UnfulfilledPrerequisitesException(
                        "No annotation to attach to. Cannot merge this relation.");
            }
        }

        if (anchored && existsEquivalentRelationBetween(aTargetCas, relationAdapter, aSourceFs,
                originFs, targetFs)) {
            throw new AlreadyMergedException(
                    "The annotation already exists in the target document.");
        }

        var existingAnnos = selectCandidateRelationsAt(aTargetCas, relationAdapter, aSourceFs,
                originFs, targetFs);
        if (anchored) {
            existingAnnos = existingAnnos.stream() //
                    .filter(rel -> connects(relationAdapter, rel, originFs, targetFs)) //
                    .toList();
        }
        if (existingAnnos.isEmpty() || aAllowStacking) {
            var mergedRelation = relationAdapter.add(aDocument, aDataOwner, originFs, targetFs,
                    aTargetCas);
            try {
                copyFeatures(aContext, aDocument, aDataOwner, relationAdapter, mergedRelation,
                        aSourceFs);
            }
            catch (AnnotationException e) {
                // If there was an error while setting the features, then we skip the entire
                // annotation
                relationAdapter.delete(aDocument, aDataOwner, aTargetCas, VID.of(mergedRelation));
                throw e;
            }
            return new CasMergeOperationResult(CREATED, getAddr(mergedRelation));
        }
        // Modify the existing relation with this one - unless we are asked to preserve annotations
        // already present in the target (e.g. curator decisions), in which case we leave the
        // existing relation untouched.
        else if (aContext.isPreserveExisting()) {
            throw new AnnotationPreservedException(
                    "The target position is already occupied by an existing relation which is "
                            + "preserved.");
        }
        else {
            var mergeTargetFS = existingAnnos.get(0);
            copyFeatures(aContext, aDocument, aDataOwner, relationAdapter, mergeTargetFS,
                    aSourceFs);
            return new CasMergeOperationResult(UPDATED, getAddr(mergeTargetFS));
        }
    }

    private static List<Annotation> selectCandidateRelationsAt(CAS aTargetCas,
            RelationAdapter aAdapter, AnnotationFS aOriginalFs, AnnotationFS aOriginalSourceFs,
            AnnotationFS aOriginalTargetFs)
    {
        var maybeTargetType = aAdapter.getAnnotationType(aTargetCas);
        if (maybeTargetType.isEmpty()) {
            return emptyList();
        }

        return aTargetCas.<Annotation> select(maybeTargetType.get()) //
                .at(aOriginalFs.getBegin(), aOriginalFs.getEnd()) //
                .filter(fs -> aAdapter.isSamePosition(fs, aOriginalFs)) //
                .toList();
    }

    private static Stream<Annotation> findRelationEndpointInTargetCas(CasMergeContext aContext,
            SourceDocument aDocument, CAS aTargetCas, AnnotationFS aRelationFS,
            String aEndpointFeatureName)
    {
        var endpointFSClicked = getFeature(aRelationFS, aEndpointFeatureName, AnnotationFS.class);
        var endpointSpanLayer = aContext.findLayer(aDocument.getProject(),
                endpointFSClicked.getType().getName());
        var endpointSpanAdapter = aContext.getAdapter(endpointSpanLayer);

        var fingerprinter = aContext.getRelationContextFingerprinter(aDocument.getProject());
        var fingerprint = fingerprinter.fingerprintEntries(endpointFSClicked);
        if (fingerprint == null) {
            return selectCandidateSpansAt(aTargetCas, endpointSpanAdapter, endpointFSClicked);
        }

        return selectAnchoredEndpoint(aContext, fingerprinter, aTargetCas, endpointSpanAdapter,
                endpointFSClicked, fingerprint);
    }

    /**
     * Selects the target annotation to which a relation should be attached if the endpoint is on a
     * layer which distinguishes stacked annotations by their relations. Preference order:
     * <ol>
     * <li>the annotation into which the source endpoint was merged during the current merge
     * run;</li>
     * <li>among the annotations not matched to another source annotation during the current merge
     * run, the annotation whose relations are a subset of those of the source endpoint (i.e. the
     * relations are still being merged) and which has the largest number of relations.</li>
     * </ol>
     * If the choice is still ambiguous, all equally good candidates are returned so the caller can
     * report a conflict.
     */
    private static Stream<Annotation> selectAnchoredEndpoint(CasMergeContext aContext,
            RelationContextFingerprinter aFingerprinter, CAS aTargetCas, TypeAdapter aAdapter,
            AnnotationFS aEndpoint, List<String> aFingerprint)
    {
        var candidates = selectCandidateSpansAt(aTargetCas, aAdapter, aEndpoint).toList();

        var mergedAddr = aContext.getMergedSpanTarget(aEndpoint, String.join("; ", aFingerprint));
        if (mergedAddr != null) {
            var merged = candidates.stream().filter(c -> getAddr(c) == mergedAddr).findFirst();
            if (merged.isPresent()) {
                return Stream.of(merged.get());
            }
        }

        var sourceEntries = new HashSet<>(aFingerprint);
        var compatible = new ArrayList<Annotation>();
        var bestSize = -1;
        for (var candidate : candidates) {
            // Annotations claimed during this merge run belong to other source annotations
            if (aContext.isClaimed(candidate)) {
                continue;
            }

            var entries = aFingerprinter.fingerprintEntries(candidate);
            if (entries == null || !sourceEntries.containsAll(entries)) {
                continue;
            }

            if (entries.size() > bestSize) {
                compatible.clear();
                bestSize = entries.size();
            }

            if (entries.size() == bestSize) {
                compatible.add(candidate);
            }
        }

        return compatible.stream();
    }

    private static boolean connects(RelationAdapter aAdapter, FeatureStructure aRelation,
            AnnotationFS aSource, AnnotationFS aTarget)
    {
        return getFeature(aRelation, aAdapter.getSourceFeatureName(), AnnotationFS.class) == aSource
                && getFeature(aRelation, aAdapter.getTargetFeatureName(),
                        AnnotationFS.class) == aTarget;
    }

    private static boolean existsEquivalentRelationBetween(CAS aTargetCas, RelationAdapter aAdapter,
            AnnotationFS aOriginal, AnnotationFS aSource, AnnotationFS aTarget)
    {
        var targetType = aAdapter.getAnnotationType(aTargetCas);
        if (targetType.isEmpty()) {
            return false;
        }

        return aTargetCas.<Annotation> select(targetType.get()) //
                .filter(fs -> connects(aAdapter, fs, aSource, aTarget)) //
                .anyMatch(fs -> aAdapter.isEquivalentAnnotation(fs, aOriginal));
    }

    private static boolean existsEquivalentRelation(CAS aTargetCas, TypeAdapter aAdapter,
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
