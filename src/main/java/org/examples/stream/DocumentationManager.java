// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.examples.stream;

import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.completion.CompletionUtil;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.ParameterInfoController;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.actions.BaseNavigateToSourceAction;
import com.intellij.ide.actions.WindowAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.gotoByName.ChooseByNameBase;
import com.intellij.ide.util.gotoByName.QuickSearchComponent;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageDocumentation;
import com.intellij.lang.documentation.CompositeDocumentationProvider;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.libraries.LibraryUtil;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.psi.*;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.reference.SoftReference;
import com.intellij.ui.*;
import com.intellij.ui.content.Content;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.ui.popup.PopupUpdateProcessor;
import com.intellij.util.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.*;
import org.jetbrains.concurrency.CancellablePromise;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class DocumentationManager extends DockablePopupManager<DocumentationComponent> {
  public static final String NEW_JAVADOC_LOCATION_AND_SIZE = "javadoc.popup.new";
  private static final String NO_EXAMPLE_FOUND = "No example found.";

  private static final Logger LOG = Logger.getInstance(DocumentationManager.class);
  private static final String SHOW_DOCUMENTATION_IN_TOOL_WINDOW = "ShowDocumentationInToolWindow";
  private static final String DOCUMENTATION_AUTO_UPDATE_ENABLED = "DocumentationAutoUpdateEnabled";

  private static final Class<?>[] ACTION_CLASSES_TO_IGNORE = {
    HintManagerImpl.ActionToIgnore.class,
    ScrollingUtil.ScrollingAction.class,
    SwingActionDelegate.class,
    BaseNavigateToSourceAction.class,
    WindowAction.class
  };
  private static final String[] ACTION_IDS_TO_IGNORE = {
    IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN,
    IdeActions.ACTION_EDITOR_MOVE_CARET_UP,
    IdeActions.ACTION_EDITOR_MOVE_CARET_PAGE_DOWN,
    IdeActions.ACTION_EDITOR_MOVE_CARET_PAGE_UP,
    IdeActions.ACTION_EDITOR_ESCAPE
  };
  private static final String[] ACTION_PLACES_TO_IGNORE = {
    ActionPlaces.JAVADOC_INPLACE_SETTINGS,
    ActionPlaces.JAVADOC_TOOLBAR
  };

  private Editor myEditor;
  private final Alarm myUpdateDocAlarm;
  private WeakReference<JBPopup> myDocInfoHintRef;//null, but maybe not always
  private WeakReference<Component> myFocusedBeforePopup;
  public static final Key<SmartPsiElementPointer<?>> ORIGINAL_ELEMENT_KEY = Key.create("Original element");

  private boolean myCloseOnSneeze;
  private @Nls String myPrecalculatedDocumentation;

  private AnAction myRestorePopupAction;

  private ToolWindow myDefaultDocToolWindow;
  private final Map<String, ToolWindow> myLangToolWindows = new HashMap<>();

  @Override
  protected String getToolWindowId() {
    return ToolWindowId.DOCUMENTATION;
  }

  @Override
  protected DocumentationComponent createComponent() {
    return new DocumentationComponent(this);
  }

  @Override
  public String getRestorePopupDescription() {
    return "Restore popup view mode";
  }

  @Override
  public String getAutoUpdateDescription() {
    return "Refresh documentation on selection change auto...";
  }

  @Override
  public String getAutoUpdateTitle() {
    return "Auto-update from Source";
  }

  @Override
  protected boolean getAutoUpdateDefault() {
    return true;
  }

  @NotNull
  @Override
  protected AnAction createRestorePopupAction() {
    myRestorePopupAction = super.createRestorePopupAction();
    return myRestorePopupAction;
  }

  @Override
  public void restorePopupBehavior() {
    ToolWindow defaultToolWindow = myDefaultDocToolWindow;
    if (defaultToolWindow == null && myLangToolWindows.isEmpty()) {
      return;
    }

    myToolWindow = null;
    myDefaultDocToolWindow = null;
    PropertiesComponent.getInstance().setValue(getShowInToolWindowProperty(), Boolean.FALSE.toString());

    if (defaultToolWindow != null) {
      defaultToolWindow.remove();
      Disposer.dispose(defaultToolWindow.getContentManager());
    }
    myLangToolWindows.clear();

    restartAutoUpdate(false);
    Component previouslyFocused = SoftReference.dereference(myFocusedBeforePopup);
    if (previouslyFocused != null && previouslyFocused.isShowing()) {
      UIUtil.runWhenFocused(previouslyFocused, () -> updateComponent(true));
      IdeFocusManager.getInstance(myProject).requestFocus(previouslyFocused, true);
    }
  }

  public void registerQuickDocShortcutSet(JComponent component, AnAction restorePopupAction) {
    ShortcutSet quickDocShortcut = ActionManager.getInstance().getAction(IdeActions.ACTION_QUICK_JAVADOC).getShortcutSet();
    restorePopupAction.registerCustomShortcutSet(quickDocShortcut, component);
  }

  @Override
  public void createToolWindow(@NotNull PsiElement element, PsiElement originalElement) {
    doCreateToolWindow(element, originalElement);
    if (myToolWindow != null) {
      myToolWindow.getComponent().putClientProperty(ChooseByNameBase.TEMPORARILY_FOCUSABLE_COMPONENT_KEY, Boolean.TRUE);
    }
  }

  private void doCreateDefaultToolWindow(@NotNull PsiElement element, PsiElement originalElement) {
    myToolWindow = null;
    super.createToolWindow(element, originalElement);
    myDefaultDocToolWindow = myToolWindow;
    if (myRestorePopupAction != null) {
      registerQuickDocShortcutSet(myToolWindow.getComponent(), myRestorePopupAction);
      myRestorePopupAction = null;
    }
  }

    private void doCreateToolWindow(@NotNull PsiElement element, PsiElement originalElement) {
    Language language = element.getLanguage();
    assert myLangToolWindows.get(language.getID()) == null;

    doCreateDefaultToolWindow(element, originalElement);
  }

  @Override
  protected void installComponentActions(@NotNull ToolWindow toolWindow, DocumentationComponent component) {
    toolWindow.setTitleActions(component.getActions());
    DefaultActionGroup group = new DefaultActionGroup(createActions());
    group.add(component.getFontSizeAction());
    ((ToolWindowEx)toolWindow).setAdditionalGearActions(group);
    component.removeCornerMenu();
  }

  @Override
  protected void setToolWindowDefaultState(@NotNull ToolWindow toolWindow) {
    Rectangle rectangle = WindowManager.getInstance().getIdeFrame(myProject).suggestChildFrameBounds();
    toolWindow.setDefaultState(ToolWindowAnchor.RIGHT, ToolWindowType.DOCKED, new Rectangle(rectangle.width / 4, rectangle.height));
    toolWindow.setType(ToolWindowType.DOCKED, null);
    toolWindow.setSplitMode(true, null);
    toolWindow.setAutoHide(false);
  }

  public DocumentationManager(@NotNull Project project) {
    super(project);
    AnActionListener actionListener = new AnActionListener() {
      @Override
      public void beforeActionPerformed(@NotNull AnAction action, @NotNull DataContext dataContext, @NotNull AnActionEvent event) {
        if (getDocInfoHint() != null &&
            LookupManager.getActiveLookup(myEditor) == null && // let the lookup manage all the actions
            !Conditions.instanceOf(ACTION_CLASSES_TO_IGNORE).value(action) &&
            !ArrayUtil.contains(event.getPlace(), ACTION_PLACES_TO_IGNORE) &&
            !ContainerUtil.exists(ACTION_IDS_TO_IGNORE, id -> ActionManager.getInstance().getAction(id) == action)) {
          closeDocHint();
        }
      }

      @Override
      public void beforeEditorTyping(char c, @NotNull DataContext dataContext) {
        JBPopup hint = getDocInfoHint();
        if (hint != null && LookupManager.getActiveLookup(myEditor) == null) {
          hint.cancel();
        }
      }
    };
    ApplicationManager.getApplication().getMessageBus().connect(project).subscribe(AnActionListener.TOPIC, actionListener);
    myUpdateDocAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, myProject);
  }

  private void closeDocHint() {
    JBPopup hint = getDocInfoHint();
    if (hint == null) {
      return;
    }
    myCloseOnSneeze = false;
    hint.cancel();
    Component toFocus = SoftReference.dereference(myFocusedBeforePopup);
    hint.cancel();
    if (toFocus != null) {
      IdeFocusManager.getInstance(myProject).requestFocus(toFocus, true);
    }
  }

  protected void showJavaDocInfo(@NotNull PsiElement element,
                                 PsiElement original) {
    if (!element.isValid()) {
      return;
    }

    PopupUpdateProcessor updateProcessor = new PopupUpdateProcessor(element.getProject()) {
      @Override
      public void updatePopup(Object lookupItemObject) {
        if (lookupItemObject instanceof PsiElement) {
          doShowJavaDocInfo((PsiElement)lookupItemObject, false, this, original, null);
        }
      }
    };

    doShowJavaDocInfo(element, false, updateProcessor, original, null);
  }

  private CompletableFuture<PsiElement> asCompletableFuture(CancellablePromise<PsiElement> elementPromise) {
    if (elementPromise.isDone()) {
      try {
        return CompletableFuture.completedFuture(elementPromise.get());
      } catch (Throwable e) {
        return CompletableFuture.failedFuture(e);
      }
    } else {
      final CompletableFuture<PsiElement> future = new CompletableFuture<>();
      elementPromise.onSuccess(future::complete);
      elementPromise.onError(future::completeExceptionally);
      return future.whenComplete((psiElement, throwable) -> elementPromise.cancel(false));
    }
  }

  protected void showJavaDocInfo(Editor editor,
                                 @Nullable PsiFile file,
                                 boolean requestFocus) {
    myEditor = editor;
    Project project = getProject(file);
    PsiDocumentManager.getInstance(project).commitAllDocuments();

    if (file != null && !file.isValid()) {
      file = null; // commit could invalidate the file
    }
    PsiFile finalFile = file;

    PsiElement originalElement = getContextElement(editor, file);

    CancellablePromise<PsiElement> elementPromise =
      ReadAction.nonBlocking(() -> findTargetElementFromContext(editor, finalFile, originalElement)).coalesceBy(this)
        .submit(AppExecutorUtil.getAppExecutorService());
    CompletableFuture<PsiElement> elementFuture = asCompletableFuture(elementPromise);

    PopupUpdateProcessor updateProcessor = new PopupUpdateProcessor(project) {
      @Override
      public void updatePopup(Object lookupIteObject) {
        if (lookupIteObject == null) {
          doShowJavaDocInfo(elementFuture, false, this, originalElement, NO_EXAMPLE_FOUND);
          return;
        }
        if (lookupIteObject instanceof PsiElement) {
          doShowJavaDocInfo((PsiElement)lookupIteObject, false, this, originalElement, null);
          return;
        }

        DocumentationProvider documentationProvider = getProviderFromElement(finalFile);

        PsiElement element = documentationProvider.getDocumentationElementForLookupItem(
          PsiManager.getInstance(myProject),
          lookupIteObject,
          originalElement
        );

        if (element == null) {
          doShowJavaDocInfo(elementFuture, false, this, originalElement, NO_EXAMPLE_FOUND);
          return;
        }

        if (myEditor != null) {
          PsiFile file = element.getContainingFile();
          if (file != null) {
            Editor editor = myEditor;
            showJavaDocInfo(myEditor, file, false);
            myEditor = editor;
          }
        }
        else {
          doShowJavaDocInfo(element, false, this, originalElement, null);
        }
      }
    };

    doShowJavaDocInfo(elementFuture, requestFocus, updateProcessor, originalElement, null);
  }

  public PsiElement findTargetElement(Editor editor, PsiFile file) {
    return findTargetElement(editor, file, getContextElement(editor, file));
  }

  private static PsiElement getContextElement(Editor editor, PsiFile file) {
    return file != null ? file.findElementAt(editor.getCaretModel().getOffset()) : null;
  }

  protected void doShowJavaDocInfo(@NotNull PsiElement element,
                                   boolean requestFocus,
                                   @NotNull PopupUpdateProcessor updateProcessor,
                                   PsiElement originalElement,
                                   @Nullable @Nls String documentation) {
    if (!myProject.isOpen()) return;

    ReadAction.run(() -> {
      assertSameProject(element);
      storeOriginalElement(myProject, originalElement, element);
    });

    JBPopup prevHint = getDocInfoHint();

    ToolWindow newToolWindow = myDefaultDocToolWindow;

    myPrecalculatedDocumentation = documentation;
    if (newToolWindow == null && PropertiesComponent.getInstance().isTrueValue(SHOW_DOCUMENTATION_IN_TOOL_WINDOW)) {
      createToolWindow(element, originalElement);
    }
    else if (newToolWindow != null) {
      myToolWindow = newToolWindow;
      Content content= myToolWindow.getContentManager().getSelectedContent();
      if (content != null) {
        DocumentationComponent component = (DocumentationComponent)content.getComponent();
        boolean sameElement = element.getManager().areElementsEquivalent(component.getElement(), element);
        if (sameElement) {
          JComponent preferredFocusableComponent = content.getPreferredFocusableComponent();
          // focus toolWindow on the second actionPerformed
          boolean focus = requestFocus || CommandProcessor.getInstance().getCurrentCommand() != null;
          if (preferredFocusableComponent != null && focus) {
            IdeFocusManager.getInstance(myProject).requestFocus(preferredFocusableComponent, true);
          }
        }
        if (!sameElement || !component.isUpToDate()) {
          cancelAndFetchDocInfo(component, new MyCollector(element, originalElement, null, false));
        }
      }

      if (!myToolWindow.isVisible()) {
        myToolWindow.show(null);
      }
    }
    else if (prevHint != null && prevHint.isVisible() && prevHint instanceof AbstractPopup) {
      DocumentationComponent component = (DocumentationComponent)((AbstractPopup)prevHint).getComponent();
      cancelAndFetchDocInfo(component, new MyCollector(element, originalElement, null, false));
    }
    else {
      showInPopup(element, requestFocus, updateProcessor, originalElement);
    }
  }

  protected void doShowJavaDocInfo(@NotNull CompletableFuture<PsiElement> elementFuture,
                                   boolean requestFocus,
                                   @NotNull PopupUpdateProcessor updateProcessor,
                                   PsiElement originalElement,
                                   @Nullable String documentation) {
    if (!myProject.isOpen()) return;

    PsiElement targetElement = null;
    try {
      //try to get target element if possible (in case when element can be resolved fast)
      targetElement = elementFuture.get(50, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException | ExecutionException | TimeoutException e) {
      LOG.debug("Failed to calculate targetElement in 50ms", e);
    }

    if (targetElement != null) {
      doShowJavaDocInfo(targetElement, requestFocus, updateProcessor, originalElement,
                        documentation);
    }
    else {
      elementFuture.thenAccept(element -> {
        if (element != null) {
          AppUIUtil.invokeOnEdt(() -> doShowJavaDocInfo(element, requestFocus, updateProcessor, originalElement, documentation));
        }
      });
    }
  }

  private void showInPopup(@NotNull PsiElement element,
                           boolean requestFocus,
                           PopupUpdateProcessor updateProcessor,
                           PsiElement originalElement) {
    Component focusedComponent = WindowManagerEx.getInstanceEx().getFocusedComponent(myProject);
    myFocusedBeforePopup = new WeakReference<>(focusedComponent);

    DocumentationComponent component = new DocumentationComponent(this, true);

    boolean hasLookup = LookupManager.getActiveLookup(myEditor) != null;
    AbstractPopup hint = (AbstractPopup)JBPopupFactory
      .getInstance().createComponentPopupBuilder(component, component)
      .setProject(myProject)
      .addListener(updateProcessor)
      .addUserData(updateProcessor)
      .setResizable(true)
      .setMovable(true)
      .setFocusable(true)
      .setRequestFocus(requestFocus)
      .setCancelOnClickOutside(!hasLookup) // otherwise selecting lookup items by mouse would close the doc
      .setModalContext(false)
      .setCancelCallback(() -> {
        if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
          return false;
        }
        myCloseOnSneeze = false;

        findQuickSearchComponent().ifPresent(QuickSearchComponent::unregisterHint);

        Disposer.dispose(component);
        myEditor = null;
        return Boolean.TRUE;
      })
      .setKeyEventHandler(e -> {
        if (myCloseOnSneeze) {
          closeDocHint();
        }
        if (AbstractPopup.isCloseRequest(e) && getDocInfoHint() != null) {
          closeDocHint();
          return true;
        }
        return false;
      })
      .createPopup();

    component.setHint(hint);
    component.setToolWindowCallback(() -> {
      createToolWindow(element, originalElement);
      myToolWindow.setAutoHide(false);
      hint.cancel();
    });

    if (DimensionService.getInstance().getSize(NEW_JAVADOC_LOCATION_AND_SIZE, myProject) != null) {
      hint.setDimensionServiceKey(NEW_JAVADOC_LOCATION_AND_SIZE);
    }

    if (myEditor == null) {
      // subsequent invocation of javadoc popup from completion will have myEditor == null because of cancel invoked,
      // so reevaluate the editor for proper popup placement
      Lookup lookup = LookupManager.getInstance(myProject).getActiveLookup();
      myEditor = lookup != null ? lookup.getEditor() : null;
    }
    cancelAndFetchDocInfo(component, new MyCollector(element, originalElement, null, false));

    myDocInfoHintRef = new WeakReference<>(hint);

    findQuickSearchComponent().ifPresent(quickSearch -> quickSearch.registerHint(hint));

    IdeEventQueue.getInstance().addDispatcher(e -> {
      if (e.getID() == MouseEvent.MOUSE_PRESSED && e.getSource() == hint.getPopupWindow()) {
        myCloseOnSneeze = false;
      }
      return false;
    }, component);
  }

  public static void storeOriginalElement(Project project, PsiElement originalElement, PsiElement element) {
    if (element == null) return;
    try {
      element.putUserData(
        ORIGINAL_ELEMENT_KEY,
        SmartPointerManager.getInstance(project).createSmartPsiElementPointer(originalElement)
      );
    }
    catch (RuntimeException ex) {
      // PsiPackage does not allow putUserData
    }
  }

  @Nullable
  private PsiElement findTargetElementFromContext(@NotNull Editor editor, @Nullable PsiFile file, @Nullable PsiElement originalElement) {
    PsiElement list = ParameterInfoController.findArgumentList(file, editor.getCaretModel().getOffset(), -1);
    PsiElement expressionList = null;
    if (list != null) {
      LookupEx lookup = LookupManager.getInstance(myProject).getActiveLookup();
      if (lookup != null) {
        expressionList = null; // take completion variants for documentation then
      }
      else {
        expressionList = list;
      }
    }
    PsiElement element = assertSameProject(findTargetElement(editor, file));
    if (element == null && expressionList != null) {
      element = expressionList;
    }
    if (element == null && file == null) return null; //file == null for text field editor

    if (element == null) { // look if we are within a javadoc comment
      element = assertSameProject(originalElement);
      if (element == null) return null;

      PsiComment comment = PsiTreeUtil.getParentOfType(element, PsiComment.class);
      if (comment == null) return null;

      element = comment instanceof PsiDocCommentBase ? ((PsiDocCommentBase)comment).getOwner() : comment.getParent();
    }
    return element;
  }

  @Nullable
  public PsiElement findTargetElement(@NotNull Editor editor, @Nullable PsiFile file, PsiElement contextElement) {
    return findTargetElement(editor, editor.getCaretModel().getOffset(), file, contextElement);
  }

  @Nullable
  public PsiElement findTargetElement(Editor editor, int offset, @Nullable PsiFile file, PsiElement contextElement) {
    try {
      return findTargetElementUnsafe(editor, offset, file, contextElement);
    }
    catch (IndexNotReadyException ex) {
      LOG.warn("Index not ready");
      LOG.debug(ex);
      return null;
    }
  }

  /**
   * in case index is not ready will throw IndexNotReadyException
   */
  @Nullable
  private PsiElement findTargetElementUnsafe(Editor editor, int offset, @Nullable PsiFile file, PsiElement contextElement) {
    if (LookupManager.getInstance(myProject).getActiveLookup() != null) {
      return assertSameProject(getElementFromLookup(editor, file));
    }

    TargetElementUtil util = TargetElementUtil.getInstance();
    PsiElement element = null;
    if (file != null) {
      DocumentationProvider documentationProvider = getProviderFromElement(file);
      element = assertSameProject(documentationProvider.getCustomDocumentationElement(editor, file, contextElement, offset));
    }

    if (element == null) {
      TargetElementUtil targetElementUtil = TargetElementUtil.getInstance();
      element = assertSameProject(util.findTargetElement(editor, targetElementUtil.getAllAccepted(), offset));

      // Allow context doc over xml tag content
      if (element != null || contextElement != null) {
        PsiElement adjusted = assertSameProject(util.adjustElement(editor, targetElementUtil.getAllAccepted(), element, contextElement));
        if (adjusted != null) {
          element = adjusted;
        }
      }
    }

    if (element == null) {
      PsiReference ref = TargetElementUtil.findReference(editor, offset);
      if (ref != null) {
        element = assertSameProject(util.adjustReference(ref));
        if (ref instanceof PsiPolyVariantReference) {
          element = assertSameProject(ref.getElement());
        }
      }
    }

    storeOriginalElement(myProject, contextElement, element);

    return element;
  }

  @Nullable
  public PsiElement getElementFromLookup(Editor editor, @Nullable PsiFile file) {
    Lookup activeLookup = LookupManager.getInstance(myProject).getActiveLookup();

    if (activeLookup != null) {
      LookupElement item = activeLookup.getCurrentItem();
      if (item != null) {
        int offset = editor.getCaretModel().getOffset();
        if (offset > 0 && offset == editor.getDocument().getTextLength()) offset--;
        PsiReference ref = TargetElementUtil.findReference(editor, offset);
        PsiElement contextElement = file == null ? null : ObjectUtils.coalesce(file.findElementAt(offset), file);
        PsiElement targetElement = ref != null ? ref.getElement() : contextElement;
        if (targetElement != null) {
          PsiUtilCore.ensureValid(targetElement);
        }

        DocumentationProvider documentationProvider = getProviderFromElement(file);
        PsiManager psiManager = PsiManager.getInstance(myProject);
        PsiElement fromProvider = targetElement == null ? null :
                                  documentationProvider.getDocumentationElementForLookupItem(psiManager, item.getObject(), targetElement);
        return fromProvider != null ? fromProvider : CompletionUtil.getTargetElement(item);
      }
    }
    return null;
  }

  @Nullable
  public JBPopup getDocInfoHint() {
    if (myDocInfoHintRef == null) return null;
    JBPopup hint = myDocInfoHintRef.get();
    if (hint == null || !hint.isVisible() && !ApplicationManager.getApplication().isUnitTestMode()) {
      if (hint != null) {
        // hint's window might've been hidden by AWT without notifying us
        // dispose to remove the popup from IDE hierarchy and avoid leaking components
        hint.cancel();
      }
      myDocInfoHintRef = null;
      return null;
    }
    return hint;
  }

  private void cancelAndFetchDocInfo(@NotNull DocumentationComponent component, @NotNull DocumentationCollector provider) {
    myUpdateDocAlarm.cancelAllRequests();
    doFetchDocInfo(component, provider);
  }

  void updateToolWindowTabName(@NotNull PsiElement element) {
    if (myToolWindow != null) {
        Content content = myToolWindow.getContentManager().getSelectedContent();
        if (content != null) content.setDisplayName(getTitle(element));
    }
  }

  private void doFetchDocInfo(@NotNull DocumentationComponent component,
                                        @NotNull DocumentationCollector collector) {
    if (myPrecalculatedDocumentation != null) {
      LOG.debug("Setting precalculated documentation:\n", myPrecalculatedDocumentation);
      // if precalculated documentation is provided, we also expect precalculated target element to be provided
      // so we're not waiting for its calculation here
      PsiElement element = collector.getElement(false);
      if (element == null) {
        LOG.debug("Element for precalculated documentation is not available anymore");
        component.setText(NO_EXAMPLE_FOUND, null);
        return;
      }
      component.setData(element, myPrecalculatedDocumentation, collector.ref);
      myPrecalculatedDocumentation = null;
      return;
    }

    boolean wasEmpty = component.isEmpty();
    if (wasEmpty) {
      component.setText("Fetching Documentation...", null);
    }

    ModalityState modality = ModalityState.defaultModalityState();

    myUpdateDocAlarm.addRequest(() -> {
      if (myProject.isDisposed()) return;
      LOG.debug("Started fetching documentation...");

      PsiElement element = collector.getElement(true);
      if (element == null || !ReadAction.compute(element::isValid)) {
        LOG.debug("Element for which documentation was requested is not available anymore");
        GuiUtils.invokeLaterIfNeeded(() -> component.setText(NO_EXAMPLE_FOUND, null), ModalityState.any());
        return;
      }

      component.startWait();

      Throwable fail = null;
      String text = null;
      try {
        text = collector.getDocumentation();
      }
      catch (Throwable e) {
        LOG.info(e);
        fail = e;
      }

      if (fail != null) {
        Throwable finalFail = fail;
        GuiUtils.invokeLaterIfNeeded(() -> {
          String message = finalFail instanceof IndexNotReadyException
                           ? "Documentation is not available until indices are built."
                           : "Cannot fetch remote documentation: internal error";
          component.setText(message, null);
        }, ModalityState.any());
        return;
      }

      LOG.debug("Documentation fetched successfully:\n", text);

      String finalText = text;
      PsiDocumentManager.getInstance(myProject).performLaterWhenAllCommitted(() -> {
        if (!element.isValid()) {
          LOG.debug("Element for which documentation was requested is not valid");
          return;
        }
        String currentText = component.getText();
        if (finalText == null) {
          component.setText(NO_EXAMPLE_FOUND, element);
        }
        else if (finalText.isEmpty()) {
          component.setText(currentText, element);
        }
        else {
          component.setData(element, finalText, collector.ref);
        }
      }, modality);
    }, 10);
  }

  @NotNull
  public static DocumentationProvider getProviderFromElement(PsiElement element) {
    return getProviderFromElement(element, null);
  }

  @NotNull
  public static DocumentationProvider getProviderFromElement(@Nullable PsiElement element, @Nullable PsiElement originalElement) {
    if (element != null && !element.isValid()) {
      element = null;
    }
    if (originalElement != null && !originalElement.isValid()) {
      originalElement = null;
    }

    if (originalElement == null) {
      originalElement = getOriginalElement(element);
    }

    PsiFile containingFile =
      originalElement != null ? originalElement.getContainingFile() : element != null ? element.getContainingFile() : null;
    Set<DocumentationProvider> result = new LinkedHashSet<>();

    Language containingFileLanguage = containingFile != null ? containingFile.getLanguage() : null;
    DocumentationProvider originalProvider =
      containingFile != null ? LanguageDocumentation.INSTANCE.forLanguage(containingFileLanguage) : null;

    Language elementLanguage = element != null ? element.getLanguage() : null;
    DocumentationProvider elementProvider =
      element == null || elementLanguage.is(containingFileLanguage) ? null : LanguageDocumentation.INSTANCE.forLanguage(elementLanguage);

    ContainerUtil.addIfNotNull(result, elementProvider);
    ContainerUtil.addIfNotNull(result, originalProvider);

    if (containingFile != null) {
      Language baseLanguage = containingFile.getViewProvider().getBaseLanguage();
      if (!baseLanguage.is(containingFileLanguage)) {
        ContainerUtil.addIfNotNull(result, LanguageDocumentation.INSTANCE.forLanguage(baseLanguage));
      }
    }
    else if (element instanceof PsiDirectory) {
      Set<Language> set = new HashSet<>();

      for (PsiFile file : ((PsiDirectory)element).getFiles()) {
        Language baseLanguage = file.getViewProvider().getBaseLanguage();
        if (!set.contains(baseLanguage)) {
          set.add(baseLanguage);
          ContainerUtil.addIfNotNull(result, LanguageDocumentation.INSTANCE.forLanguage(baseLanguage));
        }
      }
    }
    return CompositeDocumentationProvider.wrapProviders(result);
  }

  @Nullable
  public static PsiElement getOriginalElement(PsiElement element) {
    SmartPsiElementPointer originalElementPointer = element != null ? element.getUserData(ORIGINAL_ELEMENT_KEY) : null;
    return originalElementPointer != null ? originalElementPointer.getElement() : null;
  }

  private @Nullable Pair<@NotNull PsiElement, @Nullable String> getTarget(@Nullable PsiElement context, @Nullable String url) {
    if (context != null && url != null && url.startsWith(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)) {
      PsiManager manager = PsiManager.getInstance(getProject(context));
      String refText = url.substring(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL.length());
      int separatorPos = refText.lastIndexOf(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL_REF_SEPARATOR);
      String ref = null;
      if (separatorPos >= 0) {
        ref = refText.substring(separatorPos + DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL_REF_SEPARATOR.length());
        refText = refText.substring(0, separatorPos);
      }
      DocumentationProvider provider = getProviderFromElement(context);
      PsiElement targetElement = provider.getDocumentationElementForLink(manager, refText, context);
      if (targetElement == null) {
        for (DocumentationProvider documentationProvider : DocumentationProvider.EP_NAME.getExtensionList()) {
          targetElement = documentationProvider.getDocumentationElementForLink(manager, refText, context);
          if (targetElement != null) {
            break;
          }
        }
      }
      if (targetElement == null) {
        for (Language language : Language.getRegisteredLanguages()) {
          DocumentationProvider documentationProvider = LanguageDocumentation.INSTANCE.forLanguage(language);
          if (documentationProvider != null) {
            targetElement = documentationProvider.getDocumentationElementForLink(manager, refText, context);
            if (targetElement != null) {
              break;
            }
          }
        }
      }
      if (targetElement != null) {
        return Pair.create(targetElement, ref);
      }
    }
    return null;
  }

  public void navigateByLink(@NotNull DocumentationComponent component, @Nullable PsiElement context, @NotNull String url) {
    myPrecalculatedDocumentation = null;
    component.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    PsiElement psiElement = context;
    if (psiElement == null) {
      psiElement = component.getElement();
      if (psiElement == null) {
        return;
      }
    }
    if (url.equals("external_doc")) {
      return;
    }
    if (url.startsWith("open")) {
      PsiFile containingFile = psiElement.getContainingFile();
      OrderEntry libraryEntry = null;
      if (containingFile != null) {
        VirtualFile virtualFile = containingFile.getVirtualFile();
        libraryEntry = LibraryUtil.findLibraryEntry(virtualFile, myProject);
      }
      else if (psiElement instanceof PsiDirectoryContainer) {
        PsiDirectory[] directories = ((PsiDirectoryContainer)psiElement).getDirectories();
        for (PsiDirectory directory : directories) {
          VirtualFile virtualFile = directory.getVirtualFile();
          libraryEntry = LibraryUtil.findLibraryEntry(virtualFile, myProject);
          if (libraryEntry != null) {
            break;
          }
        }
      }
      if (libraryEntry != null) {
        ProjectSettingsService.getInstance(myProject).openLibraryOrSdkSettings(libraryEntry);
      }
    }
    else if (url.startsWith(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)) {
      Pair<@NotNull PsiElement, @Nullable String> target = getTarget(psiElement, url);
      if (target != null) {
        cancelAndFetchDocInfo(component, new MyCollector(target.first, null, target.second, false));
      }
    } else {
      cancelAndFetchDocInfo(component, new DocumentationCollector(psiElement, null) {
        @Override
        public String getDocumentation() {
          return "Couldn't resolve URL <i>" + url + "</i> <p>Configuring paths to API docs in <a href=\"open://Project Settings\">project settings</a> might help";
        }
      });
    }

    component.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
  }

  public Project getProject(@Nullable PsiElement element) {
    assertSameProject(element);
    return myProject;
  }

  private PsiElement assertSameProject(@Nullable PsiElement element) {
    if (element != null && element.isValid() && myProject != element.getProject()) {
      throw new AssertionError(myProject + "!=" + element.getProject() + "; element=" + element);
    }
    return element;
  }

  @Override
  public String getShowInToolWindowProperty() {
    return SHOW_DOCUMENTATION_IN_TOOL_WINDOW;
  }

  @Override
  public String getAutoUpdateEnabledProperty() {
    return DOCUMENTATION_AUTO_UPDATE_ENABLED;
  }

  @Override
  protected void doUpdateComponent(@NotNull CompletableFuture<PsiElement> elementFuture,
                                   PsiElement originalElement,
                                   DocumentationComponent component) {
    cancelAndFetchDocInfo(component, new MyCollector(elementFuture, originalElement, null, false));
  }

  @Override
  protected void doUpdateComponent(@NotNull PsiElement element,
                                   PsiElement originalElement,
                                   DocumentationComponent component) {
    cancelAndFetchDocInfo(component, new MyCollector(element, originalElement, null, false));
  }

  @Override
  protected void doUpdateComponent(Editor editor, PsiFile psiFile, boolean requestFocus) {
    showJavaDocInfo(editor, psiFile, requestFocus);
  }

  @Override
  protected void doUpdateComponent(@NotNull PsiElement element) {
    showJavaDocInfo(element, element);
  }

  @Override
  protected String getTitle(PsiElement element) {
    String title = SymbolPresentationUtil.getSymbolPresentableText(element);
    return title != null ? title : element.getText();
  }

  protected Editor getEditor() {
    return myEditor;
  }

  private abstract static class DocumentationCollector {
    private final CompletableFuture<PsiElement> myElementFuture;
    final String ref;

    DocumentationCollector(PsiElement element,
                           String ref) {
      this(CompletableFuture.completedFuture(element), ref);
    }

    DocumentationCollector(@NotNull CompletableFuture<PsiElement> elementFuture,
                           String ref) {
      myElementFuture = elementFuture;
      this.ref = ref;
    }

    @Nullable
    public PsiElement getElement(boolean wait) {
      try {
        return wait ? myElementFuture.get() : myElementFuture.getNow(null);
      }
      catch (Exception e) {
        LOG.debug("Cannot get target element", e);
        return null;
      }
    }

    @Nullable
    abstract String getDocumentation() throws Exception;
  }

  private static class MyCollector extends DocumentationCollector {
    final PsiElement originalElement;
    //todo think do i need this, show doc when mouse point on method, probably delete cause pf show doc info standard window
    final boolean onHover;

    MyCollector(@NotNull PsiElement element,
                PsiElement originalElement,
                String ref,
                boolean onHover) {
      this(CompletableFuture.completedFuture(element), originalElement, ref, onHover);
    }

    MyCollector(@NotNull CompletableFuture<PsiElement> elementSupplier,
                PsiElement originalElement,
                String ref,
                boolean onHover) {
      super(elementSupplier, ref);
      this.originalElement = originalElement;
      this.onHover = onHover;
    }

    @Override
    @Nullable
    public String getDocumentation() {
      PsiElement element = getElement(true);
      if (element == null) {
        return null;
      }
      DocumentationProvider provider = ReadAction.compute(() -> getProviderFromElement(element, originalElement));
      LOG.debug("Using provider ", provider);

      return ReadAction.nonBlocking(() -> {
        if (!element.isValid()) return null;
        SmartPsiElementPointer<?> originalPointer = element.getUserData(ORIGINAL_ELEMENT_KEY);
        PsiElement originalPsi = originalPointer != null ? originalPointer.getElement() : null;
        if (element instanceof PsiReference) {
          return onHover ? provider.generateHoverDoc(element, originalPsi) : provider.generateDoc(element, originalPsi);
        }
        final PsiClass psiClass = ((PsiMember) element).getContainingClass();
        if (nonNull(psiClass)) {
          final int parametersCount = ((PsiMethod) element).getParameterList().getParametersCount();
          final String fullMethodName = getFullMethodName(psiClass, element, parametersCount);
          final String filePath = CodeExamples.classToFileMap.get(fullMethodName);
          if (isNull(filePath)) return null;
          return FileUtil.loadTextAndClose(DocumentationManager.class.getResourceAsStream(filePath));
        }
        return null;
      }).executeSynchronously();
    }

    private String getFullMethodName(PsiClass psiClass, PsiElement psiElement, int parametersCount) {
      final String methodName = ((PsiMember) psiElement).getName();
      return psiClass.getQualifiedName() + "." + methodName + parametersCount;
    }
  }

  private Optional<QuickSearchComponent> findQuickSearchComponent() {
    Component c = SoftReference.dereference(myFocusedBeforePopup);
    while (c != null) {
      if (c instanceof QuickSearchComponent) {
        return Optional.of((QuickSearchComponent)c);
      }
      c = c.getParent();
    }
    return Optional.empty();
  }
}
