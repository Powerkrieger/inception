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

import static de.tudarmstadt.ukp.clarin.webanno.curation.casdiff.CasDiff.doDiff;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.CHARACTERS;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.ANY_OVERLAP;
import static de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport.FEAT_REL_SOURCE;
import static de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport.FEAT_REL_TARGET;
import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.toJsonString;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toSet;
import static org.apache.uima.cas.CAS.TYPE_NAME_ANNOTATION;
import static org.apache.uima.cas.CAS.TYPE_NAME_STRING;
import static org.apache.uima.fit.factory.CasFactory.createCas;
import static org.apache.uima.fit.factory.TypeSystemDescriptionFactory.createTypeSystemDescription;
import static org.apache.uima.fit.util.FSUtil.getFeature;
import static org.apache.uima.util.CasCreationUtils.mergeTypeSystems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.resource.metadata.impl.TypeSystemDescription_impl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.tudarmstadt.ukp.clarin.webanno.curation.casdiff.RelationContextFingerprinterFactory;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.api.RelationLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.relation.curation.RelationDiffAdapterImpl;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerSupport;
import de.tudarmstadt.ukp.inception.annotation.layer.span.api.SpanLayerTraits;
import de.tudarmstadt.ukp.inception.annotation.layer.span.curation.SpanDiffAdapterImpl;
import de.tudarmstadt.ukp.inception.curation.api.DiffAdapter;

/**
 * Several reactions annotated on the same keyword (same offsets, same label) which differ only in
 * the reactants/products attached to them via relations.
 */
public class CasMergeStackedByRelationsTest
    extends CasMergeTestBase
{
    private static final String DUMMY_USER = "dummyTargetUser";

    private static final String REACTION = "webanno.custom.Reaction";
    private static final String ENTITY = "webanno.custom.Entity";
    private static final String ROLE = "webanno.custom.Role";

    // Offsets in TEXT
    private static final String TEXT = "X converts A B C D";
    private static final int KW_BEGIN = 2;
    private static final int KW_END = 10;
    private static final int A = 11;
    private static final int B = 13;
    private static final int C = 15;
    private static final int D = 17;

    private TypeSystemDescription tsd;
    private AnnotationLayer reactionLayer;
    private AnnotationLayer entityLayer;
    private AnnotationLayer roleLayer;

    @Override
    @BeforeEach
    public void setup() throws Exception
    {
        super.setup();

        var custom = new TypeSystemDescription_impl();
        custom.addType(REACTION, "", TYPE_NAME_ANNOTATION).addFeature("value", "",
                TYPE_NAME_STRING);
        custom.addType(ENTITY, "", TYPE_NAME_ANNOTATION).addFeature("value", "", TYPE_NAME_STRING);
        var roleType = custom.addType(ROLE, "", TYPE_NAME_ANNOTATION);
        roleType.addFeature(FEAT_REL_SOURCE, "", TYPE_NAME_ANNOTATION);
        roleType.addFeature(FEAT_REL_TARGET, "", TYPE_NAME_ANNOTATION);
        roleType.addFeature("role", "", TYPE_NAME_STRING);
        tsd = mergeTypeSystems(asList(createTypeSystemDescription(), custom));

        reactionLayer = new AnnotationLayer(REACTION, "Reaction", SpanLayerSupport.TYPE, project,
                false, CHARACTERS, ANY_OVERLAP);
        reactionLayer.setCrossSentence(true);
        setDistinguishStackedByRelations(reactionLayer, true);

        entityLayer = new AnnotationLayer(ENTITY, "Entity", SpanLayerSupport.TYPE, project, false,
                CHARACTERS, ANY_OVERLAP);
        entityLayer.setCrossSentence(true);

        // Relation layer without attach type - connects reactions to entities
        roleLayer = new AnnotationLayer(ROLE, "Role", RelationLayerSupport.TYPE, project, false,
                CHARACTERS, ANY_OVERLAP);
        roleLayer.setCrossSentence(true);

        var reactionValue = feature(reactionLayer, "value");
        var entityValue = feature(entityLayer, "value");
        var roleRole = feature(roleLayer, "role");

        lenient().doReturn(reactionLayer).when(schemaService).findLayer(any(Project.class),
                eq(REACTION));
        lenient().doReturn(entityLayer).when(schemaService).findLayer(any(Project.class),
                eq(ENTITY));
        lenient().doReturn(roleLayer).when(schemaService).findLayer(any(Project.class), eq(ROLE));
        lenient().doReturn(asList(reactionValue)).when(schemaService)
                .listAnnotationFeature(reactionLayer);
        lenient().doReturn(asList(entityValue)).when(schemaService)
                .listAnnotationFeature(entityLayer);
        lenient().doReturn(asList(roleRole)).when(schemaService).listAnnotationFeature(roleLayer);
        lenient().doReturn(asList(reactionLayer, entityLayer, roleLayer)).when(schemaService)
                .listAnnotationLayer(project);
    }

    @Test
    void thatStackedReactionsWithoutTraitCannotBeMerged() throws Exception
    {
        setDistinguishStackedByRelations(reactionLayer, false);

        var casByUser = new LinkedHashMap<String, CAS>();
        casByUser.put("user1", annotate(false));
        casByUser.put("user2", annotate(true));

        var result = doDiff(diffAdapters(), casByUser).toResult();

        var reactionSets = result.getPositions().stream() //
                .filter(pos -> REACTION.equals(pos.getType())) //
                .map(result::getConfigurationSet) //
                .toList();
        assertThat(reactionSets).hasSize(1);
        assertThat(reactionSets.get(0).containsStackedConfigurations()).isTrue();
    }

    @Test
    void thatStackedReactionsAreDistinguishedByRelationsAndMerged() throws Exception
    {
        var casByUser = new LinkedHashMap<String, CAS>();
        casByUser.put("user1", annotate(false));
        // Second annotator creates the same annotations in a different order
        casByUser.put("user2", annotate(true));

        var result = doDiff(diffAdapters(), casByUser).toResult();

        assertThat(result.getPositions()) //
                .as("Each reaction and each relation gets its own position") //
                .filteredOn(pos -> REACTION.equals(pos.getType())) //
                .hasSize(3);
        for (var pos : result.getPositions()) {
            var cfgSet = result.getConfigurationSet(pos);
            assertThat(cfgSet.containsStackedConfigurations()).as("%s", pos).isFalse();
            assertThat(result.isAgreement(cfgSet)).as("%s", pos).isTrue();
            assertThat(result.isComplete(cfgSet)).as("%s", pos).isTrue();
        }

        var targetCas = createCas(tsd);
        targetCas.setDocumentText(TEXT);
        sut.clearAndMergeCas(result, document, DUMMY_USER, targetCas, casByUser);

        assertThat(reactionsWithRelations(targetCas))
                .containsExactlyInAnyOrderElementsOf(expectedReactions());
        assertThat(select(targetCas, ENTITY)).hasSize(4);
        assertThat(select(targetCas, ROLE)).hasSize(6);
    }

    @Test
    void thatRemergeIntoPreservedTargetDoesNotDuplicate() throws Exception
    {
        var casByUser = new LinkedHashMap<String, CAS>();
        casByUser.put("user1", annotate(false));
        casByUser.put("user2", annotate(true));

        var result = doDiff(diffAdapters(), casByUser).toResult();

        var targetCas = createCas(tsd);
        targetCas.setDocumentText(TEXT);
        sut.clearAndMergeCas(result, document, DUMMY_USER, targetCas, casByUser);

        var remerge = new CasMerge(schemaService, null);
        remerge.setPreserveExisting(true);
        remerge.mergeCas(result, document, DUMMY_USER, targetCas, casByUser);

        assertThat(reactionsWithRelations(targetCas))
                .containsExactlyInAnyOrderElementsOf(expectedReactions());
        assertThat(select(targetCas, ENTITY)).hasSize(4);
        assertThat(select(targetCas, ROLE)).hasSize(6);
    }

    @Test
    void thatManuallyMergedRelationIsAttachedToMatchingReaction() throws Exception
    {
        var sourceCas = annotate(false);

        // Target contains all reactions, but only the second and third one have their relations
        var targetCas = createCas(tsd);
        targetCas.setDocumentText(TEXT);
        var tA = entity(targetCas, A);
        var tB = entity(targetCas, B);
        entity(targetCas, C);
        var tD = entity(targetCas, D);
        var bare = reaction(targetCas);
        role(targetCas, reaction(targetCas), "reactant", tB, "product", tD);
        role(targetCas, reaction(targetCas), "reactant", tA, "product", tD);

        // Merge the "reactant" relation of the first reaction (A -> C) from the source
        var sourceRelation = select(sourceCas, ROLE).stream() //
                .filter(rel -> getFeature(rel, FEAT_REL_TARGET, AnnotationFS.class).getBegin() == A) //
                .filter(rel -> relationsOf(getFeature(rel, FEAT_REL_SOURCE, Annotation.class))
                        .contains("product@" + C)) //
                .findFirst().get();

        new CasMerge(schemaService, null).mergeRelationAnnotation(document, DUMMY_USER, roleLayer,
                targetCas, (AnnotationFS) sourceRelation);

        assertThat(relationsOf(bare)).containsExactly("reactant@" + A);
    }

    @Test
    void thatKeywordlessReactionsAreAnchoredAtTheirSentence() throws Exception
    {
        var casByUser = new LinkedHashMap<String, CAS>();
        casByUser.put("user1", annotateKeywordless(false));
        casByUser.put("user2", annotateKeywordless(true));

        var result = doDiff(diffAdapters(), casByUser).toResult();

        assertThat(result.getPositions()) //
                .as("Markers placed differently in the same sentence share a position") //
                .filteredOn(pos -> REACTION.equals(pos.getType())) //
                .hasSize(2);
        for (var pos : result.getPositions()) {
            var cfgSet = result.getConfigurationSet(pos);
            assertThat(cfgSet.containsStackedConfigurations()).as("%s", pos).isFalse();
            assertThat(result.isAgreement(cfgSet)).as("%s", pos).isTrue();
            assertThat(result.isComplete(cfgSet)).as("%s", pos).isTrue();
        }

        var targetCas = createKeywordlessText();
        sut.clearAndMergeCas(result, document, DUMMY_USER, targetCas, casByUser);

        assertThat(reactionsWithRelations(targetCas)).containsExactlyInAnyOrder( //
                Set.of("reactant@" + kwlA(), "product@" + kwlC()), //
                Set.of("reactant@" + kwlB(), "product@" + kwlD()));
        assertThat(select(targetCas, ENTITY)).hasSize(4);
        assertThat(select(targetCas, ROLE)).hasSize(4);

        // Re-merging into the preserved target must not duplicate anything even though the
        // markers of the second annotator are at different offsets than the merged ones
        var remerge = new CasMerge(schemaService, null);
        remerge.setPreserveExisting(true);
        remerge.mergeCas(result, document, DUMMY_USER, targetCas,
                new LinkedHashMap<>(casByUser.reversed()));

        assertThat(select(targetCas, REACTION)).hasSize(2);
        assertThat(select(targetCas, ROLE)).hasSize(4);
    }

    @Test
    void thatKeywordlessReactionsInDifferentSentencesAreNotMatched() throws Exception
    {
        var user1 = createKeywordlessText();
        var user2 = createKeywordlessText();
        for (var cas : List.of(user1, user2)) {
            var a = entity(cas, kwlA());
            var c = entity(cas, kwlC());
            // user1 follows the convention (reactant sentence), user2 uses the first sentence
            var at = cas == user1 ? KWL_S2_BEGIN : 0;
            role(cas, reactionAt(cas, at, at), "reactant", a, "product", c);
        }

        var casByUser = new LinkedHashMap<String, CAS>();
        casByUser.put("user1", user1);
        casByUser.put("user2", user2);

        var result = doDiff(diffAdapters(), casByUser).toResult();

        var reactionSets = result.getPositions().stream() //
                .filter(pos -> REACTION.equals(pos.getType())) //
                .map(result::getConfigurationSet) //
                .toList();
        assertThat(reactionSets).hasSize(2);
        assertThat(reactionSets).noneMatch(result::isComplete);
    }

    @Test
    void thatKeywordReactionIsNotAnchoredAtSentence() throws Exception
    {
        var user1 = createKeywordlessText();
        var user2 = createKeywordlessText();
        for (var cas : List.of(user1, user2)) {
            var a = entity(cas, kwlA());
            var c = entity(cas, kwlC());
            // user1 uses a zero-width marker, user2 the keyword "give"
            var give = KWL_TEXT.indexOf("give");
            var reaction = cas == user1 ? reactionAt(cas, give, give)
                    : reactionAt(cas, give, give + 4);
            role(cas, reaction, "reactant", a, "product", c);
        }

        var casByUser = new LinkedHashMap<String, CAS>();
        casByUser.put("user1", user1);
        casByUser.put("user2", user2);

        var result = doDiff(diffAdapters(), casByUser).toResult();

        assertThat(result.getPositions()) //
                .filteredOn(pos -> REACTION.equals(pos.getType())) //
                .hasSize(2);
    }

    // Two sentences - the reactions have no keyword and are annotated in the second one
    private static final String KWL_TEXT = "Some intro here. A and B give C and D.";
    private static final int KWL_S1_END = KWL_TEXT.indexOf('.') + 1;
    private static final int KWL_S2_BEGIN = KWL_S1_END + 1;

    private static int kwlA()
    {
        return KWL_TEXT.indexOf("A ");
    }

    private static int kwlB()
    {
        return KWL_TEXT.indexOf("B ");
    }

    private static int kwlC()
    {
        return KWL_TEXT.indexOf("C ");
    }

    private static int kwlD()
    {
        return KWL_TEXT.indexOf("D.");
    }

    private CAS createKeywordlessText() throws Exception
    {
        var cas = createCas(tsd);
        cas.setDocumentText(KWL_TEXT);
        var sentenceType = cas.getTypeSystem().getType(Sentence._TypeName);
        cas.addFsToIndexes(cas.createAnnotation(sentenceType, 0, KWL_S1_END));
        cas.addFsToIndexes(cas.createAnnotation(sentenceType, KWL_S2_BEGIN, KWL_TEXT.length()));
        return cas;
    }

    /**
     * Two keyword-less reactions in the second sentence: A -> C and B -> D. The annotators mark
     * them differently: zero-width at sentence start vs. mid-sentence, and whole sentence without
     * vs. with the final period.
     */
    private CAS annotateKeywordless(boolean aSecondAnnotator) throws Exception
    {
        var cas = createKeywordlessText();

        var a = entity(cas, kwlA());
        var b = entity(cas, kwlB());
        var c = entity(cas, kwlC());
        var d = entity(cas, kwlD());

        var reactions = new ArrayList<Runnable>();
        reactions.add(() -> {
            var at = aSecondAnnotator ? KWL_TEXT.indexOf("give") : KWL_S2_BEGIN;
            role(cas, reactionAt(cas, at, at), "reactant", a, "product", c);
        });
        reactions.add(() -> {
            var end = aSecondAnnotator ? KWL_TEXT.length() : KWL_TEXT.length() - 1;
            role(cas, reactionAt(cas, KWL_S2_BEGIN, end), "reactant", b, "product", d);
        });
        if (aSecondAnnotator) {
            Collections.reverse(reactions);
        }
        reactions.forEach(Runnable::run);

        return cas;
    }

    /**
     * Three reactions on the same keyword: A -> C, B -> D and A -> D. The first and the last share
     * the reactant A, so also their "reactant" relations have identical offsets.
     */
    private CAS annotate(boolean aReverse) throws Exception
    {
        var cas = createCas(tsd);
        cas.setDocumentText(TEXT);

        var a = entity(cas, A);
        var b = entity(cas, B);
        var c = entity(cas, C);
        var d = entity(cas, D);

        var reactions = new ArrayList<Runnable>();
        reactions.add(() -> role(cas, reaction(cas), "reactant", a, "product", c));
        reactions.add(() -> role(cas, reaction(cas), "reactant", b, "product", d));
        reactions.add(() -> role(cas, reaction(cas), "reactant", a, "product", d));
        if (aReverse) {
            Collections.reverse(reactions);
        }
        reactions.forEach(Runnable::run);

        return cas;
    }

    private static Set<Set<String>> expectedReactions()
    {
        return Set.of( //
                Set.of("reactant@" + A, "product@" + C), //
                Set.of("reactant@" + B, "product@" + D), //
                Set.of("reactant@" + A, "product@" + D));
    }

    private static List<Set<String>> reactionsWithRelations(CAS aCas)
    {
        return select(aCas, REACTION).stream() //
                .map(CasMergeStackedByRelationsTest::relationsOf) //
                .toList();
    }

    private static Set<String> relationsOf(Annotation aReaction)
    {
        return select(aReaction.getCAS(), ROLE).stream() //
                .filter(rel -> getFeature(rel, FEAT_REL_SOURCE, Annotation.class) == aReaction) //
                .map(rel -> getFeature(rel, "role", String.class) + "@"
                        + getFeature(rel, FEAT_REL_TARGET, AnnotationFS.class).getBegin()) //
                .collect(toSet());
    }

    private static List<Annotation> select(CAS aCas, String aType)
    {
        return aCas.<Annotation> select(aCas.getTypeSystem().getType(aType)).asList();
    }

    private static Annotation entity(CAS aCas, int aBegin)
    {
        var ann = (Annotation) aCas.createAnnotation(aCas.getTypeSystem().getType(ENTITY), aBegin,
                aBegin + 1);
        ann.setFeatureValueFromString(ann.getType().getFeatureByBaseName("value"), "Chemical");
        aCas.addFsToIndexes(ann);
        return ann;
    }

    private static Annotation reactionAt(CAS aCas, int aBegin, int aEnd)
    {
        var ann = (Annotation) aCas.createAnnotation(aCas.getTypeSystem().getType(REACTION), aBegin,
                aEnd);
        ann.setFeatureValueFromString(ann.getType().getFeatureByBaseName("value"), "Conversion");
        aCas.addFsToIndexes(ann);
        return ann;
    }

    private static Annotation reaction(CAS aCas)
    {
        var ann = (Annotation) aCas.createAnnotation(aCas.getTypeSystem().getType(REACTION),
                KW_BEGIN, KW_END);
        ann.setFeatureValueFromString(ann.getType().getFeatureByBaseName("value"), "Conversion");
        aCas.addFsToIndexes(ann);
        return ann;
    }

    private static void role(CAS aCas, Annotation aReaction, Object... aRolesAndTargets)
    {
        var type = aCas.getTypeSystem().getType(ROLE);
        for (int i = 0; i < aRolesAndTargets.length; i += 2) {
            var target = (Annotation) aRolesAndTargets[i + 1];
            var rel = aCas.createAnnotation(type, target.getBegin(), target.getEnd());
            rel.setFeatureValue(type.getFeatureByBaseName(FEAT_REL_SOURCE), aReaction);
            rel.setFeatureValue(type.getFeatureByBaseName(FEAT_REL_TARGET), target);
            rel.setFeatureValueFromString(type.getFeatureByBaseName("role"),
                    (String) aRolesAndTargets[i]);
            aCas.addFsToIndexes(rel);
        }
    }

    private List<DiffAdapter> diffAdapters()
    {
        var adapters = new ArrayList<DiffAdapter>();
        adapters.add(new SpanDiffAdapterImpl(REACTION, "value"));
        adapters.add(new SpanDiffAdapterImpl(ENTITY, "value"));
        adapters.add(new RelationDiffAdapterImpl(ROLE, FEAT_REL_SOURCE, FEAT_REL_TARGET, "role"));

        var fingerprinter = RelationContextFingerprinterFactory.create(schemaService, project);
        adapters.forEach(adapter -> adapter.setRelationContextFingerprinter(fingerprinter));
        return adapters;
    }

    private AnnotationFeature feature(AnnotationLayer aLayer, String aName)
    {
        var feature = new AnnotationFeature();
        feature.setName(aName);
        feature.setEnabled(true);
        feature.setType(TYPE_NAME_STRING);
        feature.setUiName(aName);
        feature.setLayer(aLayer);
        feature.setProject(project);
        feature.setVisible(true);
        feature.setCuratable(true);
        return feature;
    }

    private static void setDistinguishStackedByRelations(AnnotationLayer aLayer, boolean aEnabled)
        throws Exception
    {
        var traits = new SpanLayerTraits();
        traits.setDistinguishStackedByRelations(aEnabled);
        aLayer.setTraits(toJsonString(traits));
    }
}
