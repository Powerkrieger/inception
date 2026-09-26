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
import static org.apache.uima.fit.util.CasUtil.selectAt;
import static org.apache.uima.fit.util.CasUtil.selectCovered;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.jcas.tcas.Annotation;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.CreateSpanAnnotationRequest;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanAdapter;
import de.tudarmstadt.ukp.inception.curation.api.RelationContextFingerprinter;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationException;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.schema.api.adapter.TypeAdapter;

class CasMergeSpan
{
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
