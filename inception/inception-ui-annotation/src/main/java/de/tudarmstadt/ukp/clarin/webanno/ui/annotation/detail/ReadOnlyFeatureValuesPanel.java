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
package de.tudarmstadt.ukp.clarin.webanno.ui.annotation.detail;

import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static de.tudarmstadt.ukp.inception.support.uima.ICasUtil.selectFsByAddr;
import static java.util.Collections.emptyList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.startsWithAny;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.jcas.cas.StringArray;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.ExternalLink;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.GenericPanel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationFeature;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.schema.api.feature.FeatureSupportRegistry;

/**
 * Shows the feature values of the selected annotation as plain text and links. Used instead of the
 * feature editors when the active editor is a read-only viewer: disabled editors would neither let
 * the user select their text nor follow their links.
 */
public class ReadOnlyFeatureValuesPanel
    extends GenericPanel<AnnotatorState>
{
    private static final long serialVersionUID = -3380284420263405186L;

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private @SpringBean FeatureSupportRegistry featureSupportRegistry;

    private final AnnotationDetailEditorPanel owner;

    public ReadOnlyFeatureValuesPanel(String aId, IModel<AnnotatorState> aModel,
            AnnotationDetailEditorPanel aOwner)
    {
        super(aId, aModel);

        owner = aOwner;

        setOutputMarkupPlaceholderTag(true);

        var rows = LoadableDetachableModel.of(this::loadRows);

        var noFeaturesNotice = new WebMarkupContainer("noFeaturesNotice");
        noFeaturesNotice.add(visibleWhen(() -> rows.getObject().isEmpty()));
        add(noFeaturesNotice);

        add(new ListView<FeatureValueRow>("features", rows)
        {
            private static final long serialVersionUID = 5250465340441390373L;

            @Override
            protected void populateItem(ListItem<FeatureValueRow> aItem)
            {
                var row = aItem.getModelObject();

                aItem.add(new Label("name", row.name()));

                var value = new Label("value", row.label());
                value.add(visibleWhen(() -> row.label() != null));
                aItem.add(value);

                var noValue = new WebMarkupContainer("noValue");
                noValue.add(visibleWhen(() -> row.label() == null && row.identifiers().isEmpty()));
                aItem.add(noValue);

                aItem.add(new ListView<String>("identifiers", row.identifiers())
                {
                    private static final long serialVersionUID = -6512318364718001254L;

                    @Override
                    protected void populateItem(ListItem<String> aIdItem)
                    {
                        var identifier = aIdItem.getModelObject();

                        var link = new ExternalLink("link", identifier, identifier);
                        link.add(visibleWhen(() -> isUrl(identifier)));
                        aIdItem.add(link);

                        var text = new Label("text", identifier);
                        text.add(visibleWhen(() -> !isUrl(identifier)));
                        aIdItem.add(text);
                    }
                });
            }
        });
    }

    private static boolean isUrl(String aValue)
    {
        return startsWithAny(aValue, "http://", "https://");
    }

    private List<FeatureValueRow> loadRows()
    {
        var state = getModelObject();
        if (state == null || state.getSelection().getAnnotation().isNotSet()) {
            return emptyList();
        }

        FeatureStructure fs;
        try {
            fs = selectFsByAddr(owner.activeEditorCas(),
                    state.getSelection().getAnnotation().getId());
        }
        catch (Exception e) {
            LOG.error("Unable to load the selected annotation", e);
            return emptyList();
        }

        var rows = new ArrayList<FeatureValueRow>();
        for (var featureState : state.getFeatureStates()) {
            rows.add(toRow(featureState.feature, fs));
        }
        return rows;
    }

    private FeatureValueRow toRow(AnnotationFeature aFeature, FeatureStructure aFs)
    {
        String label = null;
        try {
            label = featureSupportRegistry.findExtension(aFeature)
                    .map(support -> support.renderFeatureValue(aFeature, aFs)) //
                    .orElse(null);
        }
        catch (Exception e) {
            // E.g. a knowledge base that cannot be reached - fall back to the stored value
            LOG.debug("Unable to render value of feature {}", aFeature, e);
        }

        // Show the stored values next to the rendered label if they differ from it, e.g. the IRI
        // of a concept - that is usually what the user wants to copy or follow
        var identifiers = new ArrayList<String>();
        for (var value : storedValues(aFeature, aFs)) {
            if (!isBlank(value) && !Objects.equals(value, label)) {
                identifiers.add(value);
            }
        }

        if (isBlank(label)) {
            label = null;
        }

        return new FeatureValueRow(aFeature.getUiName(), label, identifiers);
    }

    private static List<String> storedValues(AnnotationFeature aFeature, FeatureStructure aFs)
    {
        var feature = aFs.getType().getFeatureByBaseName(aFeature.getName());
        if (feature == null) {
            return emptyList();
        }

        if (feature.getRange().isPrimitive()) {
            var value = aFs.getFeatureValueAsString(feature);
            return value != null ? List.of(value) : emptyList();
        }

        if (CAS.TYPE_NAME_STRING_ARRAY.equals(feature.getRange().getName())
                && aFs.getFeatureValue(feature) instanceof StringArray array) {
            var values = new ArrayList<String>();
            for (var value : array.toArray()) {
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        }

        // Link features and other complex values have no single stored value worth showing -
        // their rendered label already describes them
        return emptyList();
    }

    record FeatureValueRow(String name, String label, List<String> identifiers)
        implements Serializable
    {}
}
