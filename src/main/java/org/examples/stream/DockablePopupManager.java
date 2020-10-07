// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.examples.stream;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.wm.*;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.ui.content.*;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.Activatable;
import com.intellij.util.ui.update.UiNotifyConnector;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.concurrent.CompletableFuture;

public abstract class DockablePopupManager<T extends JComponent & Disposable> {
  protected ToolWindow myToolWindow;
  private Runnable myAutoUpdateRequest;
  @NotNull protected final Project myProject;

  public DockablePopupManager(@NotNull Project project) {
    myProject = project;
  }

  @NonNls
  protected abstract String getShowInToolWindowProperty();

  @NonNls
  protected abstract String getAutoUpdateEnabledProperty();

  protected abstract boolean getAutoUpdateDefault();

  @Nls
  protected abstract String getAutoUpdateTitle();

  @Nls
  protected abstract String getRestorePopupDescription();

  @Nls
  protected abstract String getAutoUpdateDescription();

  protected abstract T createComponent();

  protected abstract void doUpdateComponent(@NotNull CompletableFuture<PsiElement> elementFuture,
                                   PsiElement originalElement,
                                   T component);

  protected abstract void doUpdateComponent(@NotNull PsiElement element, PsiElement originalElement, T component);

  protected abstract void doUpdateComponent(Editor editor, PsiFile psiFile, boolean requestFocus);

  protected abstract void doUpdateComponent(@NotNull PsiElement element);

  protected abstract @NlsContexts.TabTitle String getTitle(PsiElement element);

  protected abstract String getToolWindowId();

  public void createToolWindow(@NotNull PsiElement element, PsiElement originalElement) {
    doCreateToolWindow(element, null, originalElement);
  }

  private void doCreateToolWindow(@Nullable PsiElement element,
                                  @Nullable CompletableFuture<PsiElement> elementFuture,
                                  PsiElement originalElement) {
    assert myToolWindow == null;

    T component = createComponent();

    ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(myProject);
    ToolWindow toolWindow = toolWindowManager.getToolWindow(getToolWindowId());
    if (toolWindow == null) {
      toolWindow = toolWindowManager
        .registerToolWindow(RegisterToolWindowTask.closable(getToolWindowId(), AllIcons.Toolwindows.Documentation, ToolWindowAnchor.RIGHT));
    }
    else {
      toolWindow.setAvailable(true);
    }
    myToolWindow = toolWindow;

    toolWindow.setToHideOnEmptyContent(false);

    setToolWindowDefaultState(myToolWindow);

    ContentManager contentManager = toolWindow.getContentManager();
    String displayName = element != null ? getTitle(element) : "";
    contentManager.addContent(ContentFactory.SERVICE.getInstance().createContent(component, displayName, false));
    contentManager.addContentManagerListener(new ContentManagerListener() {
      @Override
      public void contentRemoved(@NotNull ContentManagerEvent event) {
        restorePopupBehavior();
      }
    });

    installComponentActions(toolWindow, component);

    new UiNotifyConnector(component, new Activatable() {
      @Override
      public void showNotify() {
        restartAutoUpdate(PropertiesComponent.getInstance().getBoolean(getAutoUpdateEnabledProperty(), getAutoUpdateDefault()));
      }

      @Override
      public void hideNotify() {
        restartAutoUpdate(false);
      }
    });

    myToolWindow.show(null);
    PropertiesComponent.getInstance().setValue(getShowInToolWindowProperty(), Boolean.TRUE.toString());
    restartAutoUpdate(PropertiesComponent.getInstance().getBoolean(getAutoUpdateEnabledProperty(), true));
    if (element != null) {
      doUpdateComponent(element, originalElement, component);
    }
    else {
      //noinspection ConstantConditions
      doUpdateComponent(elementFuture, originalElement, component);
    }
  }

  protected abstract void installComponentActions(@NotNull ToolWindow toolWindow, T component);

  protected abstract void setToolWindowDefaultState(@NotNull ToolWindow toolWindow);

  protected AnAction[] createActions() {
    ToggleAction toggleAutoUpdateAction = new ToggleAction(getAutoUpdateTitle(), getAutoUpdateDescription(),
                                           AllIcons.General.AutoscrollFromSource) {
      @Override
      public boolean isSelected(@NotNull AnActionEvent e) {
        return PropertiesComponent.getInstance().getBoolean(getAutoUpdateEnabledProperty(),
                                                            getAutoUpdateDefault());
      }

      @Override
      public void setSelected(@NotNull AnActionEvent e, boolean state) {
        PropertiesComponent.getInstance().setValue(getAutoUpdateEnabledProperty(), state, getAutoUpdateDefault());
        restartAutoUpdate(state);
      }
    };
    return new AnAction[]{createRestorePopupAction(), toggleAutoUpdateAction};
  }

  @NotNull
  protected AnAction createRestorePopupAction() {
    return new DumbAwareAction(CodeInsightBundle.messagePointer("action.AnActionButton.text.open.as.popup"), this::getRestorePopupDescription, null) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        restorePopupBehavior();
      }
    };
  }

  void restartAutoUpdate(final boolean state) {
    boolean enabled = state && myToolWindow != null;
    if (enabled) {
      if (myAutoUpdateRequest == null) {
        myAutoUpdateRequest = () -> updateComponent(false);

        UIUtil.invokeLaterIfNeeded(() -> IdeEventQueue.getInstance().addIdleListener(myAutoUpdateRequest, 500));
      }
    }
    else {
      if (myAutoUpdateRequest != null) {
        IdeEventQueue.getInstance().removeIdleListener(myAutoUpdateRequest);
        myAutoUpdateRequest = null;
      }
    }
  }

  protected void updateComponent(boolean requestFocus) {
    if (myProject.isDisposed()) {
      return;
    }

    DataManager.getInstance()
      .getDataContextFromFocusAsync()
      .onSuccess(dataContext -> {
        if (!myProject.isOpen()) return;
        updateComponentInner(dataContext, requestFocus);
      });
  }

  private void updateComponentInner(@NotNull DataContext dataContext, boolean requestFocus) {
    if (CommonDataKeys.PROJECT.getData(dataContext) != myProject) {
      return;
    }

    final Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
    if (editor == null) {
      PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
      if (element != null) {
        doUpdateComponent(element);
      }
      return;
    }

    PsiDocumentManager.getInstance(myProject).performLaterWhenAllCommitted(() -> {
      if (editor.isDisposed()) {
        return;
      }

      PsiFile file = PsiUtilBase.getPsiFileInEditor(editor, myProject);
      Editor injectedEditor = InjectedLanguageUtil.getEditorForInjectedLanguageNoCommit(editor, file);
      PsiFile injectedFile = PsiUtilBase.getPsiFileInEditor(injectedEditor, myProject);
      if (injectedFile != null) {
        doUpdateComponent(injectedEditor, injectedFile, requestFocus);
      }
      else if (file != null) {
        doUpdateComponent(editor, file, requestFocus);
      }
    });
  }

  public abstract void restorePopupBehavior();
}
