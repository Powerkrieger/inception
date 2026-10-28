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
package de.tudarmstadt.ukp.inception.kb;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel;
import de.tudarmstadt.ukp.inception.schema.api.feature.RecommendableFeatureTrait;

/**
 * Traits for knowledge-base-related features.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class ConceptFeatureTraits_ImplBase
    implements Serializable, RecommendableFeatureTrait
{
    private static final long serialVersionUID = 3351053348043107018L;

    private String repositoryId;
    private @JsonInclude(NON_EMPTY) List<String> fallbackRepositoryIds = new ArrayList<>();
    private String scope;
    private ConceptFeatureValueType allowedValueType;
    private boolean retainSuggestionInfo = false;
    private @JsonInclude(NON_EMPTY) List<PermissionLevel> rolesSeeingSuggestionInfo = new ArrayList<>();

    public ConceptFeatureTraits_ImplBase()
    {
        // Nothing to do
    }

    public String getRepositoryId()
    {
        return repositoryId;
    }

    public void setRepositoryId(String aKnowledgeBaseId)
    {
        repositoryId = aKnowledgeBaseId;
    }

    /**
     * Further knowledge bases searched alongside the {@link #getRepositoryId() primary one}, in
     * order. Their candidates are always ranked below those of the primary knowledge base. Only
     * meaningful if a primary knowledge base is set - without one, all knowledge bases are searched
     * anyway.
     */
    public List<String> getFallbackRepositoryIds()
    {
        return fallbackRepositoryIds;
    }

    public void setFallbackRepositoryIds(List<String> aRepositoryIds)
    {
        if (aRepositoryIds == null) {
            fallbackRepositoryIds = new ArrayList<>();
        }
        else {
            fallbackRepositoryIds = aRepositoryIds;
        }
    }

    /**
     * @return the primary knowledge base followed by the fallbacks, without duplicates; empty if no
     *         primary knowledge base is set (i.e. any knowledge base may be used).
     */
    @JsonIgnore
    public List<String> getRepositoryIdsInOrder()
    {
        if (repositoryId == null) {
            return List.of();
        }

        var ids = new LinkedHashSet<String>();
        ids.add(repositoryId);
        ids.addAll(fallbackRepositoryIds);
        return List.copyOf(ids);
    }

    public String getScope()
    {
        return scope;
    }

    public void setScope(String aScope)
    {
        scope = aScope;
    }

    public ConceptFeatureValueType getAllowedValueType()
    {
        return allowedValueType != null ? allowedValueType : ConceptFeatureValueType.ANY_OBJECT;
    }

    public void setAllowedValueType(ConceptFeatureValueType aAllowedType)
    {
        allowedValueType = aAllowedType;
    }

    @Override
    public boolean isRetainSuggestionInfo()
    {
        return retainSuggestionInfo;
    }

    @Override
    public void setRetainSuggestionInfo(boolean aRetainSuggestionInfo)
    {
        retainSuggestionInfo = aRetainSuggestionInfo;
    }

    @Override
    public void setRolesSeeingSuggestionInfo(List<PermissionLevel> aRolesSeeingSuggestionInfo)
    {
        if (aRolesSeeingSuggestionInfo == null) {
            rolesSeeingSuggestionInfo = new ArrayList<>();
        }
        else {
            rolesSeeingSuggestionInfo = aRolesSeeingSuggestionInfo;
        }
    }

    @Override
    public List<PermissionLevel> getRolesSeeingSuggestionInfo()
    {
        return rolesSeeingSuggestionInfo;
    }
}
