package org.examples.stream;

import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorActivityManager;
import com.intellij.openapi.editor.EditorGutter;
import com.intellij.openapi.editor.actionSystem.DocCommandGroupId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtilBase;
import org.jetbrains.annotations.NotNull;

public class ShowStreamExampleInfoAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        DataContext dataContext = event.getDataContext();
        presentation.setEnabled(false);

        Project project = CommonDataKeys.PROJECT.getData(dataContext);
        if (project == null) return;

        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiElement element = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
        if (editor == null && element == null) return;

        if (LookupManager.getInstance(project).getActiveLookup() == null) {
            if (event.getData(EditorGutter.KEY) != null) return;
            if (editor == null) return;
            PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (file == null && element == null) return;
        }
        presentation.setEnabled(true);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        DataContext dataContext = e.getDataContext();
        final Project project = CommonDataKeys.PROJECT.getData(dataContext);
        final Editor editor = CommonDataKeys.EDITOR.getData(dataContext);

        if (project != null && editor != null) {
            actionPerformedImpl(project, editor);
        }
    }

    private void actionPerformedImpl(@NotNull final Project project,@NotNull final Editor editor) {
        final PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
        if (psiFile == null) return;

        CommandProcessor.getInstance().executeCommand(project, () -> {
            final Runnable action = () -> {
                if (!EditorActivityManager.getInstance().isVisible(editor)) return;
                DocumentationManager documentationManager = new DocumentationManager(project);
                JBPopup hint = documentationManager.getDocInfoHint();
                documentationManager.showJavaDocInfo(editor, psiFile, hint != null || LookupManager.getActiveLookup(editor) == null);
            };
            action.run();
        }, getCommandName(), DocCommandGroupId.noneGroupId(editor.getDocument()), editor.getDocument());
    }

    private @NlsContexts.Command String getCommandName() {
        String text = getTemplatePresentation().getText();
        return text == null ? "" : text;
    }
}
