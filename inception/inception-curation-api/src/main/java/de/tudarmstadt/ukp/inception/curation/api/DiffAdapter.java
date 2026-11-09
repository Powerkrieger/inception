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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.uima.cas.CAS;
import org.apache.uima.jcas.cas.AnnotationBase;

import de.tudarmstadt.ukp.inception.annotation.feature.link.LinkFeatureDiffMode;
import de.tudarmstadt.ukp.inception.annotation.feature.link.LinkFeatureMultiplicityMode;

public interface DiffAdapter
{
    /**
     * @return UIMA type name to which the adapter applies.
     */
    String getType();

    Collection<? extends Position> generateSubPositions(AnnotationBase aFs);

    LinkFeatureDecl getLinkFeature(String aFeature);

    /**
     * @return names of features that the adapter should compare. Link features are not included
     *         here.
     */
    Set<String> getFeatures();

    /**
     * @return names of link features that the adapter should compare. Non-link features are not
     *         included here.
     */
    Set<String> getLinkFeatures();

    Position getPosition(AnnotationBase aFS);

    List<? extends AnnotationBase> selectAnnotationsInWindow(CAS aCas, int aWindowBegin,
            int aWindowEnd);

    boolean isIncludeInDiff(String aFeature);

    void addLinkFeature(String aName, String aRoleFeature, String aTargetFeature,
            LinkFeatureMultiplicityMode aCompareBehavior, LinkFeatureDiffMode aDiffMode);

    /**
     * @param aFingerprinter
     *            fingerprinter used to distinguish stacked span annotations by their attached
     *            relations when generating positions.
     */
    default void setRelationContextFingerprinter(RelationContextFingerprinter aFingerprinter)
    {
        // Ignored by default
    }

    /**
     * Records the {@code visibleIf} expression of each feature (by name) so that curation can skip
     * conditionally-hidden features when comparing feature structures. Features absent from the map
     * (or mapped to a blank expression) are treated as always visible.
     *
     * @param aVisibleIfByFeature
     *            feature name to its {@code visibleIf} expression.
     */
    default void setFeatureVisibility(Map<String, String> aVisibleIfByFeature)
    {
        // Optional - implementations that do not track visibility treat all features as visible.
    }

    /**
     * @param aFeature
     *            a feature name.
     * @return the {@code visibleIf} expression of the feature, or {@code null} if the feature has
     *         no visibility condition (always visible).
     */
    default String getVisibleIf(String aFeature)
    {
        return null;
    }

    /**
     * Records the names of features that must not count towards curation agreement (e.g. free-text
     * comment/note features kept from all annotators). A difference on such a feature does not
     * prevent two annotations from being considered equal.
     *
     * @param aFeatures
     *            feature names to exclude from agreement.
     */
    default void setNonAgreementFeatures(Set<String> aFeatures)
    {
        // Optional - implementations that do not track this treat all features as
        // agreement-bearing.
    }

    /**
     * @return names of features excluded from curation agreement. Never {@code null}.
     */
    default Set<String> getNonAgreementFeatures()
    {
        return java.util.Collections.emptySet();
    }
}
