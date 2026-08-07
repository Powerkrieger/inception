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
package de.tudarmstadt.ukp.inception.schema.api.feature;

import java.io.Serializable;

/**
 * A single rule of the form "when {@code condition} evaluates to true (given the values of other
 * features on the same annotation), use {@code value} as the default value instead of the plain,
 * unconditional default value". Feature-type-agnostic: {@code value} is always the CAS-storable
 * string representation of the value (e.g. a KB identifier for a Concept feature, or the value
 * itself for a plain string feature), the same representation
 * {@link FeatureSupport#unwrapFeatureValue} produces.
 * <p>
 * {@code condition} is a
 * {@link de.tudarmstadt.ukp.clarin.webanno.constraints.expression.FeatureExpression}, e.g.
 * {@code entityType == "Gene"} - the same small expression language that also backs
 * {@link de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature#getVisibleIf()}.
 *
 * @see ConditionalDefaultValueEvaluator
 */
public class ConditionalDefaultValueRule
    implements Serializable
{
    private static final long serialVersionUID = -3766283358803156173L;

    private String condition;
    private String value;

    public ConditionalDefaultValueRule()
    {
        // Nothing to do
    }

    public ConditionalDefaultValueRule(String aCondition, String aValue)
    {
        condition = aCondition;
        value = aValue;
    }

    public String getCondition()
    {
        return condition;
    }

    public void setCondition(String aCondition)
    {
        condition = aCondition;
    }

    public String getValue()
    {
        return value;
    }

    public void setValue(String aValue)
    {
        value = aValue;
    }
}
