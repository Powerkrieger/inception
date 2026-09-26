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
package de.tudarmstadt.ukp.inception.curation.api;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.StringArrayFS;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.jcas.cas.AnnotationBase;
import org.apache.uima.jcas.tcas.Annotation;

/**
 * Computes a fingerprint of the relations attached to a span annotation. The fingerprint is used to
 * tell apart annotations which are stacked at the same offsets and carry the same labels, but which
 * are connected to different annotations via relations - e.g. several reactions annotated on the
 * same keyword which differ only in their reactants and products.
 * <p>
 * The fingerprint only looks at the offsets and type of the annotation at the other end of each
 * relation (not at its fingerprint), so the computation is not recursive.
 * <p>
 * Annotations of applicable types which do not mark a keyword - i.e. zero-width annotations or
 * annotations covering a whole sentence - are {@link #anchor anchored} at their sentence instead of
 * at their own offsets. Annotators may place such markers anywhere within the sentence.
 */
public class RelationContextFingerprinter
    implements Serializable
{
    private static final long serialVersionUID = -2787356151209524454L;

    private static final String SENTENCE_TYPE = "de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence";

    public static final RelationContextFingerprinter NONE = new RelationContextFingerprinter(
            emptyMap());

    private final Map<String, List<RelationDecl>> relationsBySpanType;

    /**
     * @param aRelationsBySpanType
     *            for each span type which should be fingerprinted, the relation types whose
     *            annotations are taken into account.
     */
    public RelationContextFingerprinter(Map<String, List<RelationDecl>> aRelationsBySpanType)
    {
        relationsBySpanType = unmodifiableMap(new HashMap<>(aRelationsBySpanType));
    }

    public boolean isApplicable(String aSpanType)
    {
        return relationsBySpanType.containsKey(aSpanType);
    }

    /**
     * @param aSpan
     *            a span annotation.
     * @return the offsets at which the annotation is positioned for the purpose of comparing it to
     *         other annotations. For keyword-less annotations (zero-width or covering a whole
     *         sentence) of applicable types, these are the offsets of the sentence. Otherwise,
     *         these are the offsets of the annotation itself.
     */
    public Anchor anchor(AnnotationFS aSpan)
    {
        if (isApplicable(aSpan.getType().getName())) {
            var sentence = findKeywordlessSentence(aSpan);
            if (sentence != null) {
                return new Anchor(sentence.getBegin(), sentence.getEnd(), true);
            }
        }

        return new Anchor(aSpan.getBegin(), aSpan.getEnd(), false);
    }

    private static AnnotationFS findKeywordlessSentence(AnnotationFS aSpan)
    {
        var cas = aSpan.getCAS();
        var sentenceType = cas.getTypeSystem().getType(SENTENCE_TYPE);
        if (sentenceType == null) {
            return null;
        }

        // Zero-width annotation: the sentence containing it - if it sits exactly between two
        // sentences, the one it starts
        if (aSpan.getBegin() == aSpan.getEnd()) {
            var pos = aSpan.getBegin();
            AnnotationFS ending = null;
            for (var sentence : cas.<Annotation> select(sentenceType)) {
                if (sentence.getBegin() > pos) {
                    break;
                }
                if (pos < sentence.getEnd()) {
                    return sentence;
                }
                if (pos == sentence.getEnd()) {
                    ending = sentence;
                }
            }
            return ending;
        }

        // Annotation covering a whole sentence - leading/trailing whitespace and punctuation are
        // ignored since manually selected sentences often miss e.g. the final period
        var text = cas.getDocumentText();
        var span = trim(text, aSpan.getBegin(), aSpan.getEnd());
        for (var sentence : cas.<Annotation> select(sentenceType)) {
            if (sentence.getBegin() > aSpan.getEnd()) {
                break;
            }
            if (sentence.getEnd() < aSpan.getBegin()) {
                continue;
            }
            if (Arrays.equals(span, trim(text, sentence.getBegin(), sentence.getEnd()))) {
                return sentence;
            }
        }

        return null;
    }

    private static int[] trim(String aText, int aBegin, int aEnd)
    {
        var begin = aBegin;
        var end = aEnd;
        while (begin < end && isTrimmable(aText.charAt(begin))) {
            begin++;
        }
        while (end > begin && isTrimmable(aText.charAt(end - 1))) {
            end--;
        }
        return new int[] { begin, end };
    }

    private static boolean isTrimmable(char aChar)
    {
        if (Character.isWhitespace(aChar)) {
            return true;
        }

        return switch (Character.getType(aChar)) {
        case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true;
        default -> false;
        };
    }

    /**
     * @param aSpan
     *            a span annotation.
     * @return the fingerprint of the relations attached to the given annotation or {@code null} if
     *         the type of the annotation is not subject to fingerprinting. An annotation of an
     *         applicable type without any relations has an empty fingerprint.
     */
    public String fingerprint(FeatureStructure aSpan)
    {
        var entries = fingerprintEntries(aSpan);
        return entries != null ? String.join("; ", entries) : null;
    }

    /**
     * @param aSpan
     *            a span annotation.
     * @return the sorted entries (one per attached relation) making up the fingerprint of the given
     *         annotation or {@code null} if the type of the annotation is not subject to
     *         fingerprinting.
     */
    public List<String> fingerprintEntries(FeatureStructure aSpan)
    {
        if (!(aSpan instanceof AnnotationBase)) {
            return null;
        }

        var decls = relationsBySpanType.get(aSpan.getType().getName());
        if (decls == null) {
            return null;
        }

        var cas = aSpan.getCAS();
        var entries = new ArrayList<String>();
        for (var decl : decls) {
            var relType = cas.getTypeSystem().getType(decl.type());
            if (relType == null) {
                continue;
            }

            var sourceFeat = relType.getFeatureByBaseName(decl.sourceFeature());
            var targetFeat = relType.getFeatureByBaseName(decl.targetFeature());
            if (sourceFeat == null || targetFeat == null) {
                continue;
            }

            for (var rel : cas.select(relType)) {
                var source = rel.getFeatureValue(sourceFeat);
                var target = rel.getFeatureValue(targetFeat);

                if (source == aSpan) {
                    entries.add(entry(decl, rel, "->", target));
                }

                if (target == aSpan) {
                    entries.add(entry(decl, rel, "<-", source));
                }
            }
        }

        entries.sort(null);
        return entries;
    }

    private String entry(RelationDecl aDecl, FeatureStructure aRelation, String aDirection,
            FeatureStructure aOtherEnd)
    {
        var sb = new StringBuilder();
        sb.append(aDecl.type());

        for (var featName : aDecl.features()) {
            var feat = aRelation.getType().getFeatureByBaseName(featName);
            if (feat == null) {
                continue;
            }

            sb.append('|').append(featName).append('=');
            if (feat.getRange().isPrimitive()) {
                sb.append(aRelation.getFeatureValueAsString(feat));
            }
            else if (aRelation.getFeatureValue(feat) instanceof StringArrayFS array) {
                var values = array.toArray();
                Arrays.sort(values);
                sb.append(Arrays.toString(values));
            }
        }

        sb.append(' ').append(aDirection).append(' ');

        if (aOtherEnd instanceof AnnotationFS ann) {
            var anchor = anchor(ann);
            sb.append(ann.getType().getName()).append('@').append(anchor.begin()).append('-')
                    .append(anchor.end());
        }
        else {
            sb.append(String.valueOf(aOtherEnd));
        }

        return sb.toString();
    }

    /**
     * @param begin
     *            the begin offset.
     * @param end
     *            the end offset.
     * @param keywordless
     *            whether the annotation does not mark a keyword and is anchored at its sentence.
     */
    public record Anchor(int begin, int end, boolean keywordless)
        implements Serializable
    {}

    /**
     * Declares a relation type to be taken into account when fingerprinting.
     *
     * @param type
     *            the relation type name.
     * @param sourceFeature
     *            the name of the feature pointing to the relation source.
     * @param targetFeature
     *            the name of the feature pointing to the relation target.
     * @param features
     *            the label features of the relation which are included in the fingerprint.
     */
    public record RelationDecl(String type, String sourceFeature, String targetFeature,
            List<String> features)
        implements Serializable
    {}
}
