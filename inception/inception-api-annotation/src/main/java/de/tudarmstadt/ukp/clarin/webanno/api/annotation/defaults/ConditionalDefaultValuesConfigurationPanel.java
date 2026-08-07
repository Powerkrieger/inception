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
package de.tudarmstadt.ukp.clarin.webanno.api.annotation.defaults;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.util.List;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.validation.IValidatable;
import org.apache.wicket.validation.IValidator;
import org.apache.wicket.validation.ValidationError;

import de.tudarmstadt.ukp.clarin.webanno.constraints.expression.FeatureExpressionEvaluator;
import de.tudarmstadt.ukp.clarin.webanno.constraints.expression.FeatureExpressionSyntaxException;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.inception.rendering.editorstate.FeatureState;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.schema.api.feature.ConditionalDefaultValueRule;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureEditor;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupport;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxLink;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxSubmitLink;

/**
 * Can be added to a feature support traits editor to configure conditional default value rules -
 * see {@link ConditionalDefaultValueRule}. Feature-type-agnostic: the value editor for a rule is
 * obtained from the owning feature's own {@link FeatureSupport}, exactly like
 * {@code KeyBindingsConfigurationPanel} does for key bindings, so this works for any feature type
 * whose traits expose a rule list, not just Concept/KB features.
 */
public class ConditionalDefaultValuesConfigurationPanel
    extends Panel
{
    private static final long serialVersionUID = -4739349400187449173L;

    private @SpringBean FeatureSupportRegistry featureSupportRegistry;
    private @SpringBean AnnotationSchemaService schemaService;

    private final WebMarkupContainer container;
    private final IModel<List<ConditionalDefaultValueRule>> rules;

    private final FeatureEditor editor;
    private final IModel<FeatureState> featureState;

    public ConditionalDefaultValuesConfigurationPanel(String aId, IModel<AnnotationFeature> aModel,
            IModel<List<ConditionalDefaultValueRule>> aRules)
    {
        super(aId, aModel);

        rules = aRules;

        var ruleForm = new Form<ConditionalDefaultValueRule>("ruleForm",
                CompoundPropertyModel.of(new ConditionalDefaultValueRule()));
        add(ruleForm);

        container = new WebMarkupContainer("container");
        container.setOutputMarkupPlaceholderTag(true);
        ruleForm.add(container);

        // We cannot make the condition field a required one here because then we'd get a message
        // about it not being set when saving the entire feature details form!
        container.add(new TextField<String>("condition").add(new ConditionValidator()));
        container.add(new LambdaAjaxSubmitLink<>("addRule", this::addRule));

        var feature = aModel.getObject();
        var fs = featureSupportRegistry.findExtension(feature).orElseThrow();
        featureState = Model.of(new FeatureState(VID.NONE_ID, feature, null));
        if (feature.getTagset() != null) {
            // Make sure editor is not hidden if hideUnconstraintFeature is enabled
            featureState.getObject().indicator.setAffected(true);
            featureState.getObject().indicator.rulesApplied();
            // Load tagset
            featureState.getObject().tagset = schemaService
                    .listTagsReorderable(feature.getTagset());
        }
        // We are adding only the focus component here because we do not want to display the label
        // which usually goes along with the feature editor. This assumes that there is a sensible
        // focus component... might not be the case for some multi-component editors.
        editor = fs.createEditor("value", this, null, null, featureState);
        // Some feature editors (e.g. ConceptFeatureEditor) handle their own
        // FeatureEditorValueChangedEvent to refresh themselves (e.g. description/tooltip), which
        // requires a markup id to be present - without this, selecting a value throws
        // "cannot update component that does not have setOutputMarkupId property set to true".
        editor.setOutputMarkupPlaceholderTag(true);
        editor.addFeatureUpdateBehavior();
        editor.getLabelComponent().setVisible(false);
        container.add(editor);

        container.add(createRulesList("rules", rules));
    }

    private ListView<ConditionalDefaultValueRule> createRulesList(String aId,
            IModel<List<ConditionalDefaultValueRule>> aRules)
    {
        return new ListView<ConditionalDefaultValueRule>(aId, aRules)
        {
            private static final long serialVersionUID = -1233699665083909464L;

            @Override
            protected void populateItem(ListItem<ConditionalDefaultValueRule> aItem)
            {
                var feature = ConditionalDefaultValuesConfigurationPanel.this.getModelObject();
                FeatureSupport<?> fs = featureSupportRegistry.findExtension(feature).orElseThrow();

                var rule = aItem.getModelObject();

                aItem.add(new Label("condition", rule.getCondition()));
                aItem.add(new Label("value", fs.renderFeatureValue(feature, rule.getValue())));
                aItem.add(new LambdaAjaxLink("removeRule",
                        _target -> removeRule(_target, aItem.getModelObject())));
            }
        };
    }

    public AnnotationFeature getModelObject()
    {
        return (AnnotationFeature) getDefaultModelObject();
    }

    private void addRule(AjaxRequestTarget aTarget, Form<ConditionalDefaultValueRule> aForm)
    {
        var rule = aForm.getModelObject();

        if (isBlank(rule.getCondition())) {
            error("Condition is required");
            aTarget.addChildren(getPage(), IFeedback.class);
            return;
        }

        // Copy value from the value editor over into the form model (rule) and then add it to the
        // list
        var feature = getModelObject();
        FeatureSupport<?> fs = featureSupportRegistry.findExtension(feature).orElseThrow();
        var value = fs.unwrapFeatureValue(feature, featureState.getObject().value);
        if (value == null) {
            error("Value is required");
            aTarget.addChildren(getPage(), IFeedback.class);
            return;
        }
        rule.setValue(String.valueOf(value));

        rules.getObject().add(rule);

        // Clear form and value editor
        aForm.setModelObject(new ConditionalDefaultValueRule());
        featureState.getObject().setValue(null);

        success("Rule added. Do not forget to save the feature details!");
        aTarget.addChildren(getPage(), IFeedback.class);
        aTarget.add(container);
    }

    private void removeRule(AjaxRequestTarget aTarget, ConditionalDefaultValueRule aRule)
    {
        rules.getObject().remove(aRule);
        aTarget.add(container);
    }

    private static class ConditionValidator
        implements IValidator<String>
    {
        private static final long serialVersionUID = 2308855847417697429L;

        @Override
        public void validate(IValidatable<String> aValidatable)
        {
            try {
                FeatureExpressionEvaluator.validate(aValidatable.getValue());
            }
            catch (FeatureExpressionSyntaxException e) {
                aValidatable.error(new ValidationError("Invalid condition: " + e.getMessage()));
            }
        }
    }
}
