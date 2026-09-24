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
package de.tudarmstadt.ukp.clarin.webanno.ui.curation.component;

import static de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocumentState.FINISHED;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocumentState.IN_PROGRESS;
import static de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel.MANAGER;
import static de.tudarmstadt.ukp.inception.support.json.JSONUtil.toInterpretableJsonString;
import static de.tudarmstadt.ukp.inception.support.lambda.LambdaBehavior.visibleWhen;
import static de.tudarmstadt.ukp.inception.support.uima.ICasUtil.selectFsByAddr;
import static java.util.Collections.emptyList;
import static org.apache.wicket.markup.head.JavaScriptHeaderItem.forReference;

import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.commons.lang3.Validate;
import org.apache.wicket.ajax.AbstractDefaultAjaxBehavior;
import org.apache.wicket.ajax.AjaxEventBehavior;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.ajax.markup.html.modal.ModalDialog;
import org.apache.wicket.feedback.IFeedback;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnLoadHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wicketstuff.jquery.ui.settings.JQueryUILibrarySettings;

import de.agilecoders.wicket.core.markup.html.bootstrap.image.Icon;
import de.agilecoders.wicket.extensions.markup.html.bootstrap.icon.FontAwesome7IconType;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.comment.AnnotatorCommentDialogPanel;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.exception.NotEditableException;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.page.AnnotationPageBase;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.paging.NoPagingStrategy;
import de.tudarmstadt.ukp.clarin.webanno.brat.annotation.BratRequestUtils;
import de.tudarmstadt.ukp.clarin.webanno.brat.message.GetCollectionInformationResponse;
import de.tudarmstadt.ukp.clarin.webanno.brat.render.BratSerializer;
import de.tudarmstadt.ukp.clarin.webanno.brat.resource.BratCurationResourceReference;
import de.tudarmstadt.ukp.clarin.webanno.brat.schema.BratSchemaGenerator;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationSet;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.ui.curation.component.model.AnnotatorSegmentState;
import de.tudarmstadt.ukp.clarin.webanno.ui.curation.component.render.CurationRenderer;
import de.tudarmstadt.ukp.inception.bootstrap.BootstrapModalDialog;
import de.tudarmstadt.ukp.inception.diam.editor.DiamAjaxBehavior;
import de.tudarmstadt.ukp.inception.diam.editor.DiamRequest;
import de.tudarmstadt.ukp.inception.diam.editor.actions.EditorAjaxRequestHandlerBase;
import de.tudarmstadt.ukp.inception.diam.editor.actions.LazyDetailsHandler;
import de.tudarmstadt.ukp.inception.diam.editor.lazydetails.LazyDetailsLookupService;
import de.tudarmstadt.ukp.inception.diam.model.ajax.AjaxResponse;
import de.tudarmstadt.ukp.inception.diam.model.ajax.DefaultAjaxResponse;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.editor.state.AnnotatorStateImpl;
import de.tudarmstadt.ukp.inception.project.api.ProjectService;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationActionHandler;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotationException;
import de.tudarmstadt.ukp.inception.rendering.editorstate.AnnotatorState;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DiamContext;
import de.tudarmstadt.ukp.inception.rendering.editorstate.DocumentEditorManager;
import de.tudarmstadt.ukp.inception.rendering.request.RenderRequest;
import de.tudarmstadt.ukp.inception.rendering.selection.Selection;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VRange;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.inception.support.json.JSONUtil;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaAjaxLink;
import de.tudarmstadt.ukp.inception.support.lambda.LambdaStringResourceBehavior;
import de.tudarmstadt.ukp.inception.support.wicket.SymbolLabel;
import de.tudarmstadt.ukp.inception.support.wicket.WicketUtil;

public abstract class BratSuggestionVisualizer
    extends Panel
    implements DiamContext
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final long serialVersionUID = 6653508018500736430L;

    private @SpringBean ProjectService projectService;
    private @SpringBean UserDao userService;
    private @SpringBean DocumentService documentService;
    private @SpringBean LazyDetailsLookupService lazyDetailsLookupService;
    private @SpringBean BratSchemaGenerator bratSchemaGenerator;
    private @SpringBean CurationRenderer curationRenderer;
    private @SpringBean BratSerializer bratSerializer;
    private @SpringBean AnnotationSchemaService schemaService;

    private final WebMarkupContainer vis;
    private final ModalDialog modalDialog;
    private final BootstrapModalDialog confirmationDialog;
    private final AbstractDefaultAjaxBehavior controller;
    private final LambdaStringResourceBehavior collProvider;
    private final LambdaStringResourceBehavior docProvider;

    private final int position;

    private final DocumentEditorManager manager;

    private InspectionContext inspectionContext;

    public BratSuggestionVisualizer(String aId, DocumentEditorManager aManager,
            IModel<AnnotatorSegmentState> aModel, int aPosition)
    {
        super(aId, aModel);

        Validate.notNull(aManager, "Document editor manager must be provided");

        manager = aManager;
        position = aPosition;

        vis = new WebMarkupContainer("vis");
        vis.setOutputMarkupId(true);

        // Provides collection-level information like type definitions, styles, etc.
        collProvider = new LambdaStringResourceBehavior(this::getCollectionData);

        // Provides the actual document contents
        docProvider = new LambdaStringResourceBehavior(this::getDocumentData);

        modalDialog = new BootstrapModalDialog("modalDialog");
        queue(modalDialog);

        add(vis);
        add(collProvider, docProvider);

        add(new Label("username", getModel().map(this::maybeAnonymizeUsername)));

        confirmationDialog = new BootstrapModalDialog("stateChangeConfirmationDialog");
        confirmationDialog.trapFocus();
        add(confirmationDialog);

        var annDoc = LoadableDetachableModel.of(this::getAnnotationDocument);

        var stateToggle = new LambdaAjaxLink("stateToggle", _target -> {
            var dialogContent = new StateChangeConfirmationDialogPanel(
                    BootstrapModalDialog.CONTENT_ID, getModel().map(this::maybeAnonymizeUsername));
            dialogContent.setConfirmAction(this::actionToggleAnnotationDocumentState);
            confirmationDialog.open(dialogContent, _target);
        });
        stateToggle.setOutputMarkupId(true);
        stateToggle.add(new SymbolLabel("state", annDoc.map(AnnotationDocument::getState)));
        add(stateToggle);

        var commentSymbol = new Icon("commentSymbol", FontAwesome7IconType.comment_s);
        commentSymbol.add(visibleWhen(
                annDoc.map(AnnotationDocument::getAnnotatorComment).map(StringUtils::isNotBlank)));
        commentSymbol.add(
                AjaxEventBehavior.onEvent("click", _t -> actionShowAnnotatorComment(_t, annDoc)));
        queue(commentSymbol);

        controller = new DiamAjaxBehavior(this) //
                .setGlobalHandlersEnabled(false) //
                .addPriorityHandler(new CurationLazyDetailsHandler()) //
                .addPriorityHandler(new CurationActionAjaxRequestHandler());
        add(controller);
    }

    private void actionShowAnnotatorComment(AjaxRequestTarget aTarget,
            LoadableDetachableModel<AnnotationDocument> aAnnDoc)
    {
        modalDialog.open(new AnnotatorCommentDialogPanel(ModalDialog.CONTENT_ID, aAnnDoc), aTarget);
    }

    private void actionToggleAnnotationDocumentState(AjaxRequestTarget aTarget)
    {
        var username = getModelObject().getUser().getUsername();
        var doc = getModelObject().getAnnotatorState().getDocument();
        var annDoc = documentService.getAnnotationDocument(doc, AnnotationSet.forUser(username));
        var annDocState = annDoc.getState();

        switch (annDocState) {
        case IN_PROGRESS:
            documentService.setAnnotationDocumentState(annDoc, FINISHED);
            break;
        case FINISHED:
            documentService.setAnnotationDocumentState(annDoc, IN_PROGRESS);
            break;
        default:
            error("Can only change document state for documents that are finished or in progress, "
                    + "but document is in state [" + annDocState + "]");
            aTarget.addChildren(getPage(), IFeedback.class);
            break;
        }

        ((AnnotationPageBase) getPage()).actionLoadDocument(aTarget);
    }

    private AnnotationDocument getAnnotationDocument()
    {
        var username = getModelObject().getUser().getUsername();
        var doc = getModelObject().getAnnotatorState().getDocument();
        return documentService.getAnnotationDocument(doc, AnnotationSet.forUser(username));
    }

    private String maybeAnonymizeUsername(AnnotatorSegmentState aSegment)
    {
        Project project = aSegment.getAnnotatorState().getProject();
        if (project.isAnonymousCuration()
                && !projectService.hasRole(userService.getCurrentUser(), project, MANAGER)) {
            return "Anonymized annotator " + (position + 1);
        }

        return aSegment.getUser().getUiName();
    }

    public void setModel(IModel<AnnotatorSegmentState> aModel)
    {
        setDefaultModel(aModel);
    }

    public void setModelObject(AnnotatorSegmentState aModel)
    {
        setDefaultModelObject(aModel);
    }

    @SuppressWarnings("unchecked")
    public IModel<AnnotatorSegmentState> getModel()
    {
        return (IModel<AnnotatorSegmentState>) getDefaultModel();
    }

    @Override
    public IModel<AnnotatorState> getStateModel()
    {
        return getModel().map(AnnotatorSegmentState::getAnnotatorState);
    }

    public AnnotatorSegmentState getModelObject()
    {
        return (AnnotatorSegmentState) getDefaultModelObject();
    }

    @Override
    public CAS getEditorCas() throws IOException
    {
        var segment = getModelObject();
        return documentService.readAnnotationCas(segment.getAnnotatorState().getDocument(),
                AnnotationSet.forUser(segment.getUser().getUsername()));
    }

    @Override
    public DocumentEditorManager getDocumentEditorManager()
    {
        return manager;
    }

    @Override
    public AnnotationActionHandler getActionHandler()
    {
        throw new UnsupportedOperationException(
                "This editor is a passive viewer and has no action handler.");
    }

    @Override
    public void actionShowSelectedDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
            int aBegin, int aEnd, List<VRange> aAdditionalPingRanges)
    {
        // Selection of annotations is not supported
    }

    @Override
    public void actionRefreshDocument(AjaxRequestTarget aTarget)
    {
        render(aTarget);
    }

    @Override
    public void renderHead(IHeaderResponse aResponse)
    {
        aResponse.render(forReference(JQueryUILibrarySettings.get().getJavaScriptReference()));
        aResponse.render(JavaScriptHeaderItem.forReference(BratCurationResourceReference.get()));

        // BRAT call to load the BRAT JSON from our collProvider and docProvider.
        String script = "BratCuration('" + vis.getMarkupId() + "', '" + controller.getCallbackUrl()
                + "', '" + collProvider.getCallbackUrl() + "', '" + docProvider.getCallbackUrl()
                + "')";
        aResponse.render(OnLoadHeaderItem.forScript("\n" + script));
    }

    public String getDocumentData()
    {
        try {
            var state = getModelObject().getAnnotatorState();
            // FIXME: This is a minimalist render request containing only a view pieces of
            // information that the serializer needs. In particular, it does not contain the CAS
            // because even if we loaded it again now, its FS addresses could have changed over
            // what they were when the VDocument has first been created. Optimally, we wouldn't
            // need access to the request here at all and the important information would be
            // contained in the VDocument already.
            var request = RenderRequest.builder() //
                    .withState(state) //
                    .withSessionOwner(userService.getCurrentUser()) //
                    .withWindow(state.getWindowBeginOffset(), state.getWindowEndOffset()) //
                    .withClipArcs(true) //
                    .withClipSpans(true) //
                    .withLongArcs(true) //
                    .build();
            var response = bratSerializer.render(getModelObject().getVDocument(), request);

            return JSONUtil.toInterpretableJsonString(response);
        }
        catch (Exception e) {
            handleError("Unable to render annotatations", e);
            return "{}";
        }
    }

    private String getCollectionData()
    {
        try {
            var aState = getModelObject().getAnnotatorState();
            var info = new GetCollectionInformationResponse();
            info.setEntityTypes(bratSchemaGenerator.buildEntityTypes(aState.getProject(),
                    aState.getAnnotationLayers()));
            return JSONUtil.toInterpretableJsonString(info);
        }
        catch (IOException e) {
            handleError("Unablet to render collection information", e);
            return "{}";
        }
    }

    private void handleError(String aMessage, Exception e)
    {
        var requestCycle = RequestCycle.get();
        requestCycle.find(AjaxRequestTarget.class)
                .ifPresent(target -> target.addChildren(getPage(), IFeedback.class));

        if (e instanceof AnnotationException) {
            // These are common exceptions happening as part of the user interaction. We do
            // not really need to log their stack trace to the log.
            error(aMessage + ": " + e.getMessage());
            // If debug is enabled, we'll also write the error to the log just in case.
            if (LOG.isDebugEnabled()) {
                LOG.error("{}: {}", aMessage, e.getMessage(), e);
            }
            return;
        }

        LOG.error("{}", aMessage, e);
        error(aMessage);
    }

    private String bratRenderCommand(String aJson)
    {
        return WicketUtil.wrapInTryCatch("Wicket.$('" + vis.getMarkupId()
                + "').dispatcher.post('renderData', [" + aJson + "]);");
    }

    public void render(AjaxRequestTarget aTarget)
    {
        LOG.debug("[{}][{}] render", getMarkupId(), vis.getMarkupId());

        // Controls whether rendering should happen within the AJAX request or after the AJAX
        // request. Doing it within the request has the benefit of the browser only having to
        // recalculate the layout once at the end of the AJAX request (at least theoretically)
        // while deferring the rendering causes the AJAX request to complete faster, but then
        // the browser needs to recalculate its layout twice - once of any Wicket components
        // being re-rendered and once for the brat view to re-render.
        final boolean deferredRendering = false;

        var js = new StringBuilder();

        if (deferredRendering) {
            js.append("setTimeout(function() {");
        }

        js.append(bratRenderCommand(getDocumentData()));

        if (deferredRendering) {
            js.append("}, 0);");
        }

        aTarget.appendJavaScript(js);
    }

    protected abstract void onClientEvent(AjaxRequestTarget aTarget) throws Exception;

    /**
     * Show the given annotation of this annotator in the annotation detail panel without merging
     * it. The detail panel follows the active editor context, so this activates a read-only context
     * bound to this annotator's annotations.
     *
     * @param aTarget
     *            the AJAX target
     * @param aVid
     *            the VID of the annotation in this annotator's CAS
     * @throws IOException
     *             if there was an I/O-level problem
     * @throws AnnotationException
     *             if there was an annotation-level problem
     */
    public void actionInspect(AjaxRequestTarget aTarget, VID aVid)
        throws IOException, AnnotationException
    {
        if (inspectionContext == null) {
            inspectionContext = new InspectionContext();
        }

        inspectionContext.actionSelect(aTarget, aVid);
    }

    static boolean isInspectionContext(DiamContext aContext)
    {
        return aContext instanceof InspectionContext;
    }

    private final class CurationLazyDetailsHandler
        extends EditorAjaxRequestHandlerBase
        implements Serializable
    {
        private static final long serialVersionUID = -5651753631846550817L;

        @Override
        public String getCommand()
        {
            return LazyDetailsHandler.COMMAND;
        }

        @Override
        public AjaxResponse handle(DiamRequest aRequest, AjaxRequestTarget aTarget)
        {
            try {
                final var request = getRequest().getPostParameters();
                final var paramId = BratRequestUtils.getVidFromRequest(request);

                var context = aRequest.getContext();
                var state = context.getViewState();
                var result = lazyDetailsLookupService.lookupLazyDetails(request, paramId,
                        context::getEditorCas, state.getDocument(), getModelObject().getUser(),
                        state.getWindowBeginOffset(), state.getWindowEndOffset());
                attachResponse(aTarget, aRequest, toInterpretableJsonString(result));

                return new DefaultAjaxResponse(LazyDetailsHandler.COMMAND);
            }
            catch (Exception e) {
                return handleError("Unable to load lazy details", e);
            }
        }
    }

    private final class CurationActionAjaxRequestHandler
        extends EditorAjaxRequestHandlerBase
        implements Serializable
    {
        private static final long serialVersionUID = 8053988681869772378L;

        @Override
        public AjaxResponse handle(DiamRequest aRequest, AjaxRequestTarget aTarget)
        {
            try {
                onClientEvent(aTarget);
                return new DefaultAjaxResponse(getAction(aRequest));
            }
            catch (Exception e) {
                return handleError("Unable to merge", e);
            }
        }

        @Override
        public String getCommand()
        {
            return "clientEvent";
        }

        @Override
        public boolean accepts(DiamRequest aRequest)
        {
            return true;
        }
    }

    /**
     * Read-only editor context through which the annotation detail panel shows an annotation of
     * this annotator. It carries its own {@link AnnotatorState} so that selecting an annotation
     * here does not touch the curator's selection, and it rejects all mutations - annotations are
     * accepted by clicking them, which merges them into the curated document.
     */
    final class InspectionContext
        implements DiamContext, AnnotationActionHandler, Serializable
    {
        private static final long serialVersionUID = 2311425400236521472L;

        private final IModel<AnnotatorState> stateModel = new Model<>();

        private AnnotatorState curatorState()
        {
            return BratSuggestionVisualizer.this.getModelObject().getAnnotatorState();
        }

        private Optional<DiamContext> curatorContext()
        {
            var curatorState = curatorState();
            return manager.findEditorFor(curatorState.getDocument(), curatorState.getDataOwner());
        }

        /**
         * Mirror the curator's view configuration (layers, preferences, constraints) so that the
         * detail panel shows the same features the curator would see after merging.
         */
        private AnnotatorState newState()
        {
            var curatorState = curatorState();

            var state = new AnnotatorStateImpl();
            state.setUser(BratSuggestionVisualizer.this.getModelObject().getUser());
            state.setPagingStrategy(new NoPagingStrategy());
            state.setProject(curatorState.getProject());
            state.setDocument(curatorState.getDocument(), emptyList());
            state.setAllAnnotationLayers(curatorState.getAllAnnotationLayers());
            state.setAnnotationLayers(curatorState.getAnnotationLayers());
            state.setPreferences(curatorState.getPreferences());
            state.setConstraints(curatorState.getConstraints());
            return state;
        }

        @Override
        public IModel<AnnotatorState> getStateModel()
        {
            return stateModel;
        }

        @Override
        public CAS getEditorCas() throws IOException
        {
            return BratSuggestionVisualizer.this.getEditorCas();
        }

        @Override
        public AnnotationActionHandler getActionHandler()
        {
            return this;
        }

        @Override
        public boolean isEditor()
        {
            return false;
        }

        @Override
        public DocumentEditorManager getDocumentEditorManager()
        {
            return manager;
        }

        @Override
        public void ensureIsEditable() throws AnnotationException
        {
            throw new NotEditableException("The annotations of annotators cannot be edited. "
                    + "Click the annotation to merge it into the curated document.");
        }

        @Override
        public void actionSelect(AjaxRequestTarget aTarget, VID aVid)
            throws IOException, AnnotationException
        {
            // Slot arcs point to their host annotation - that is the one we show
            var vid = new VID(aVid.getId());
            if (!(selectFsByAddr(getEditorCas(), vid.getId()) instanceof AnnotationFS annoFs)) {
                return;
            }

            // Start from a fresh state on every selection so that we pick up any changes to the
            // curator's layer configuration since the last inspection
            stateModel.setObject(newState());
            stateModel.getObject().setSelection(
                    schemaService.findAdapter(getProject(), annoFs).select(vid, annoFs));

            actionLoadSelectedAnnotationDetails(aTarget);
        }

        @Override
        public void actionLoadSelectedAnnotationDetails(AjaxRequestTarget aTarget)
            throws AnnotationException
        {
            // If this context was already active, the detail panel has picked up the new selection
            // from the selection change event. Otherwise, activating it makes the panel load it.
            activate(aTarget);

            // Pages with a single fixed editor context (e.g. the legacy curation page) ignore the
            // activation, so the detail panel would never show the annotation.
            if (manager.getActiveContext().filter(context -> context == this).isEmpty()) {
                throw new AnnotationException(
                        "Inspecting annotations is not supported on this page.");
            }
        }

        @Override
        public void actionSelectAndJump(AjaxRequestTarget aTarget, VID aVid)
            throws IOException, AnnotationException
        {
            actionSelect(aTarget, aVid);

            if (selectFsByAddr(getEditorCas(), aVid.getId()) instanceof AnnotationFS annoFs) {
                actionJump(aTarget, annoFs.getBegin(), annoFs.getEnd());
            }
        }

        @Override
        public void actionJump(AjaxRequestTarget aTarget, int aBegin, int aEnd)
            throws IOException, AnnotationException
        {
            // The annotator panes follow the curator's viewport, so scrolling happens there
            var curatorContext = curatorContext();
            if (curatorContext.isPresent()) {
                curatorContext.get().getActionHandler().actionJump(aTarget, aBegin, aEnd);
            }
        }

        @Override
        public void actionShowSelectedDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
                int aBegin, int aEnd)
            throws IOException, AnnotationException
        {
            actionShowSelectedDocument(aTarget, aDocument, aBegin, aEnd, null);
        }

        @Override
        public void actionShowSelectedDocument(AjaxRequestTarget aTarget, SourceDocument aDocument,
                int aBegin, int aEnd, List<VRange> aAdditionalPingRanges)
            throws IOException, AnnotationException
        {
            var curatorContext = curatorContext();
            if (curatorContext.isPresent()) {
                curatorContext.get().actionShowSelectedDocument(aTarget, aDocument, aBegin, aEnd,
                        aAdditionalPingRanges);
            }
        }

        @Override
        public void actionRefreshDocument(AjaxRequestTarget aTarget)
        {
            curatorContext().ifPresent(context -> context.actionRefreshDocument(aTarget));
        }

        @Override
        public void actionClear(AjaxRequestTarget aTarget)
        {
            stateModel.getObject().setSelection(Selection.unselected());
        }

        @Override
        public void actionDelete(AjaxRequestTarget aTarget) throws AnnotationException
        {
            ensureIsEditable();
        }

        @Override
        public void actionReverse(AjaxRequestTarget aTarget) throws AnnotationException
        {
            ensureIsEditable();
        }

        @Override
        public void actionFillSlot(AjaxRequestTarget aTarget, int aSlotFillerBegin,
                int aSlotFillerEnd)
            throws AnnotationException
        {
            ensureIsEditable();
        }

        @Override
        public void actionFillSlot(AjaxRequestTarget aTarget, VID aExistingSlotFillerId)
            throws AnnotationException
        {
            ensureIsEditable();
        }

        @Override
        public void writeEditorCas() throws AnnotationException
        {
            ensureIsEditable();
        }

        @Override
        public void writeEditorCas(CAS aCas) throws AnnotationException
        {
            ensureIsEditable();
        }
    }
}
