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

import java.io.IOException;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.inception.support.json.JSONUtil;

/**
 * Reads curation-relevant feature traits without binding to a specific feature support's typed
 * traits class. The trait is written by {@code StringFeatureTraits}; it is read here generically
 * from the raw traits JSON so the curation modules do not need to depend on the string-feature
 * module.
 */
public final class CurationFeatureTraits
{
    private static final String KEEP_ALL_VALUES_ON_MERGE = "keepAllValuesOnMerge";

    private CurationFeatureTraits()
    {
        // Utility class
    }

    /**
     * @param aFeature
     *            a feature.
     * @return whether curation keeps all annotators' values for this feature (so a difference on it
     *         does not block the merge and every annotator's value is kept). A missing or malformed
     *         traits value is treated as {@code false}.
     */
    public static boolean keepAllValuesOnMerge(AnnotationFeature aFeature)
    {
        var traits = aFeature.getTraits();
        if (traits == null || traits.isBlank()) {
            return false;
        }

        try {
            return JSONUtil.fromJsonString(traits).path(KEEP_ALL_VALUES_ON_MERGE).asBoolean(false);
        }
        catch (IOException e) {
            return false;
        }
    }
}
