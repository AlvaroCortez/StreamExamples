// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.examples.stream;

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.actions.WindowAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.actionSystem.impl.MenuItemPresentationFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsUtil;
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.options.FontSize;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.DimensionService;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.reference.SoftReference;
import com.intellij.ui.*;
import com.intellij.ui.components.JBLayeredPane;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.ui.popup.PopupPositionManager;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.*;
import com.intellij.util.ui.*;
import com.intellij.util.ui.accessibility.ScreenReader;
import org.jetbrains.annotations.*;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.plaf.TextUI;
import javax.swing.text.*;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DocumentationComponent extends JPanel implements Disposable, DataProvider, WidthBasedLayout {
  private static final Logger LOG = Logger.getInstance(DocumentationComponent.class);
  //todo check what it is and where using
  private static final String DOCUMENTATION_TOPIC_ID = "reference.toolWindows.Documentation";

  public static final ColorKey COLOR_KEY = EditorColors.DOCUMENTATION_COLOR;
  public static final Color SECTION_COLOR = Gray.get(0x90);

  private static final Highlighter.HighlightPainter LINK_HIGHLIGHTER = new LinkHighlighter();

  private static final int PREFERRED_HEIGHT_MAX_EM = 10;
  private static final JBDimension MAX_DEFAULT = new JBDimension(650, 500);
  private static final JBDimension MIN_DEFAULT = new JBDimension(300, 36);

  private static final Pattern EXTERNAL_LINK_PATTERN = Pattern.compile("(<a\\s*href=[\"']http[^>]*>)([^>]*)(</a>)");
  private static final @NonNls String EXTERNAL_LINK_REPLACEMENT = "$1$2<icon src='AllIcons.Ide.External_link_arrow'>$3";

  private DocumentationManager myManager;
  private SmartPsiElementPointer<PsiElement> myElement;
  private long myModificationCount;

  private static final String CODE_EXAMPLE_FONT_SIZE_PROPERTY = "code.example.font.size";

  private final ActionToolbarImpl myToolBar;
  private volatile boolean myIsEmpty;
  private boolean mySizeTrackerRegistered;
  private boolean myIgnoreFontSizeSliderChange;

  private Reference<Component> myReferenceComponent;

  private Runnable myToolWindowCallback;
  private final ActionButton myCorner;

  private final MyScrollPane myScrollPane;
  private final JEditorPane myEditorPane;
  private @Nls String myText; // myEditorPane.getText() surprisingly crashes.., let's cache the text
  private @Nls String myDecoratedText; // myEditorPane.getText() surprisingly crashes.., let's cache the text
  private final JComponent myControlPanel;
  private int myHighlightedLink = -1;
  private Object myHighlightingTag;
  private final boolean myStoreSize;
  private boolean myManuallyResized;

  private AbstractPopup myHint;

  private final Map<KeyStroke, ActionListener> myKeyboardActions = new HashMap<>();

  public DocumentationComponent(DocumentationManager manager) {
    this(manager, true);
  }

  public DocumentationComponent(DocumentationManager manager, boolean storeSize) {
    myManager = manager;
    myIsEmpty = true;
    myStoreSize = storeSize;

    myEditorPane = new JEditorPane() {
      {
        enableEvents(AWTEvent.KEY_EVENT_MASK);
      }

      @Override
      protected void processKeyEvent(KeyEvent e) {
        KeyStroke keyStroke = KeyStroke.getKeyStrokeForEvent(e);
        ActionListener listener = myKeyboardActions.get(keyStroke);
        if (listener != null) {
          listener.actionPerformed(new ActionEvent(DocumentationComponent.this, 0, ""));
          e.consume();
          return;
        }
        super.processKeyEvent(e);
      }

      Point initialClick;

      @Override
      protected void processMouseEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_PRESSED && myHint != null) {
          initialClick = null;
          StyledDocument document = (StyledDocument)getDocument();
          int x = e.getX();
          int y = e.getY();
          if (!hasTextAt(document, x, y) &&
              !hasTextAt(document, x + 3, y) &&
              !hasTextAt(document, x - 3, y) &&
              !hasTextAt(document, x, y + 3) &&
              !hasTextAt(document, x, y - 3)) {
            initialClick = e.getPoint();
          }
        }
        super.processMouseEvent(e);
      }

      private boolean hasTextAt(StyledDocument document, int x, int y) {
        Element element = document.getCharacterElement(viewToModel2D(new Point(x, y)));
        try {
          String text = document.getText(element.getStartOffset(), element.getEndOffset() - element.getStartOffset());
          if (StringUtil.isEmpty(text.trim())) {
            return false;
          }
        }
        catch (BadLocationException ignored) {
          return false;
        }
        return true;
      }

      @Override
      protected void processMouseMotionEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_DRAGGED && myHint != null && initialClick != null) {
          Point location = myHint.getLocationOnScreen();
          myHint.setLocation(new Point(location.x + e.getX() - initialClick.x, location.y + e.getY() - initialClick.y));
          e.consume();
          return;
        }
        super.processMouseMotionEvent(e);
      }

      @Override
      protected void paintComponent(Graphics g) {
        GraphicsUtil.setupAntialiasing(g);
        super.paintComponent(g);
      }

      @Override
      public void setDocument(Document doc) {
        super.setDocument(doc);
        doc.putProperty("IgnoreCharsetDirective", Boolean.TRUE);
      }
    };
    DataProvider helpDataProvider = dataId -> PlatformDataKeys.HELP_ID.is(dataId) ? DOCUMENTATION_TOPIC_ID : null;
    myEditorPane.putClientProperty(DataManager.CLIENT_PROPERTY_DATA_PROVIDER, helpDataProvider);
    myText = "";
    myDecoratedText = "";
    myEditorPane.setEditable(false);
    if (ScreenReader.isActive()) {
      // Note: Making the caret visible is merely for convenience
      myEditorPane.getCaret().setVisible(true);
    }
    else {
      myEditorPane.putClientProperty("caretWidth", 0); // do not reserve space for caret (making content one pixel narrower than component)
      UIUtil.doNotScrollToCaret(myEditorPane);
    }
    myEditorPane.setBackground(EditorColorsUtil.getGlobalOrDefaultColor(COLOR_KEY));
    HTMLEditorKit editorKit = new JBHtmlEditorKit(true, true) {
      @Override
      public ViewFactory getViewFactory() {
        JBHtmlFactory factory = new JBHtmlFactory();
        factory.setAdditionalIconResolver(src -> {
          ModuleType<?> id = ModuleTypeManager.getInstance().findByID(src);
          return id == null ? null : id.getIcon();
        });
        return factory;
      }
    };
    prepareCSS(editorKit);
    myEditorPane.setEditorKit(editorKit);
    myEditorPane.setBorder(JBUI.Borders.empty());
    myScrollPane = new MyScrollPane();
    myScrollPane.putClientProperty(DataManager.CLIENT_PROPERTY_DATA_PROVIDER, helpDataProvider);

    FocusListener focusAdapter = new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        Component previouslyFocused = WindowManagerEx.getInstanceEx().getFocusedComponent(manager.getProject(getElement()));

        if (previouslyFocused != myEditorPane) {
          if (myHint != null && !myHint.isDisposed()) myHint.cancel();
        }
      }
    };
    myEditorPane.addFocusListener(focusAdapter);

    Disposer.register(this, () -> myEditorPane.removeFocusListener(focusAdapter));

    setLayout(new BorderLayout());

    setOpaque(true);
    myScrollPane.setBorder(JBUI.Borders.empty());

    DefaultActionGroup actions = new DefaultActionGroup();

    ActionPopupMenu contextMenu = ((ActionManagerImpl)ActionManager.getInstance()).createActionPopupMenu(
      ActionPlaces.JAVADOC_TOOLBAR, actions, new MenuItemPresentationFactory(true));
    PopupHandler popupHandler = new PopupHandler() {
      @Override
      public void invokePopup(Component comp, int x, int y) {
        contextMenu.getComponent().show(comp, x, y);
      }
    };
    myEditorPane.addMouseListener(popupHandler);
    Disposer.register(this, () -> myEditorPane.removeMouseListener(popupHandler));

    new NextLinkAction().registerCustomShortcutSet(CustomShortcutSet.fromString("TAB"), this);
    new PreviousLinkAction().registerCustomShortcutSet(CustomShortcutSet.fromString("shift TAB"), this);
    new ActivateLinkAction().registerCustomShortcutSet(CustomShortcutSet.fromString("ENTER"), this);

    DefaultActionGroup toolbarActions = new DefaultActionGroup();
    toolbarActions.add(actions);
    toolbarActions.addAction(new ShowAsToolWindowAction()).setAsSecondary(true);
    toolbarActions.addAction(new FontSizeSettingsAction(true)).setAsSecondary(true);
    toolbarActions.addAction(new RestoreDefaultSizeAction()).setAsSecondary(true);
    myToolBar = new ActionToolbarImpl(ActionPlaces.JAVADOC_TOOLBAR, toolbarActions, true) {
      Point initialClick;

      @Override
      protected void processMouseEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_PRESSED && myHint != null) {
          initialClick = e.getPoint();
        }
        super.processMouseEvent(e);
      }

      @Override
      protected void processMouseMotionEvent(MouseEvent e) {
        if (e.getID() == MouseEvent.MOUSE_DRAGGED && myHint != null && initialClick != null) {
          Point location = myHint.getLocationOnScreen();
          myHint.setLocation(new Point(location.x + e.getX() - initialClick.x, location.y + e.getY() - initialClick.y));
          e.consume();
          return;
        }
        super.processMouseMotionEvent(e);
      }
    };
    myToolBar.setSecondaryActionsIcon(AllIcons.Actions.More, true);

    JLayeredPane layeredPane = new JBLayeredPane() {
      @Override
      public void doLayout() {
        Rectangle r = getBounds();
        for (Component component : getComponents()) {
          if (component instanceof JScrollPane) {
            component.setBounds(0, 0, r.width, r.height);
          }
          else {
            Dimension d = component.getPreferredSize();
            component.setBounds(r.width - d.width - 2, r.height - d.height - 7, d.width, d.height);
          }
        }
      }

      @Override
      public Dimension getPreferredSize() {
        Dimension size = myScrollPane.getPreferredSize();
        if (myHint == null && myManager != null && myManager.myToolWindow == null) {
          int em = myEditorPane.getFont().getSize();
          int prefHeightMax = PREFERRED_HEIGHT_MAX_EM * em;
          return new Dimension(size.width, Math.min(prefHeightMax, size.height));
        }
        return size;
      }
    };
    layeredPane.add(myScrollPane);
    layeredPane.setLayer(myScrollPane, 0);

    DefaultActionGroup gearActions = new MyGearActionGroup();
    ShowAsToolWindowAction showAsToolwindowAction = new ShowAsToolWindowAction();
    gearActions.add(showAsToolwindowAction);
    gearActions.add(new FontSizeSettingsAction(false));
    gearActions.add(new RestoreDefaultSizeAction());
    gearActions.addSeparator();
    gearActions.addAll(actions);
    Presentation presentation = new Presentation();
    presentation.setIcon(AllIcons.Actions.More);
    presentation.putClientProperty(ActionButton.HIDE_DROPDOWN_ICON, Boolean.TRUE);
    myCorner = new ActionButton(gearActions, presentation, ActionPlaces.UNKNOWN, new Dimension(20, 20)) {
      @Override
      protected DataContext getDataContext() {
        return DataManager.getInstance().getDataContext(myCorner);
      }
    };
    myCorner.setNoIconsInPopup(true);
    showAsToolwindowAction.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts("QuickJavaDoc"), myCorner);
    layeredPane.add(myCorner);
    layeredPane.setLayer(myCorner, JLayeredPane.POPUP_LAYER);
    add(layeredPane, BorderLayout.CENTER);

    myControlPanel = myToolBar.getComponent();
    myControlPanel.setBorder(IdeBorderFactory.createBorder(UIUtil.getTooltipSeparatorColor(), SideBorder.BOTTOM));

    HyperlinkListener hyperlinkListener = e -> {
      HyperlinkEvent.EventType type = e.getEventType();
      if (type == HyperlinkEvent.EventType.ACTIVATED) {
        manager.navigateByLink(DocumentationComponent.this, null, e.getDescription());
      }
    };
    myEditorPane.addHyperlinkListener(hyperlinkListener);
    Disposer.register(this, () -> myEditorPane.removeHyperlinkListener(hyperlinkListener));

    if (myHint != null) {
      Disposer.register(myHint, this);
    }
    else if (myManager.myToolWindow != null) {
      Disposer.register(myManager.myToolWindow.getContentManager(), this);
    }

    registerActions();

    updateControlState();
  }

  @Override
  public void setBackground(Color color) {
    super.setBackground(color);
    if (myEditorPane != null) myEditorPane.setBackground(color);
    if (myControlPanel != null) myControlPanel.setBackground(color);
  }

  public List<AnAction> getActions() {
    return myToolBar.getActions().stream().filter((action -> !(action instanceof Separator))).collect(Collectors.toList());
  }

  public AnAction getFontSizeAction() {
    return new FontSizeSettingsAction(false);
  }

  public void removeCornerMenu() {
    myCorner.setVisible(false);
  }

  public void setToolWindowCallback(Runnable callback) {
    myToolWindowCallback = callback;
  }

  @Override
  public boolean requestFocusInWindow() {
    // With a screen reader active, set the focus directly to the editor because
    // it makes it easier for users to read/navigate the documentation contents.
    if (ScreenReader.isActive()) {
      return myEditorPane.requestFocusInWindow();
    }
    else {
      return myScrollPane.requestFocusInWindow();
    }
  }

  @Override
  public void requestFocus() {
    // With a screen reader active, set the focus directly to the editor because
    // it makes it easier for users to read/navigate the documentation contents.
    IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown(() -> {
      if (ScreenReader.isActive()) {
        IdeFocusManager.getGlobalInstance().requestFocus(myEditorPane, true);
      }
      else {
        IdeFocusManager.getGlobalInstance().requestFocus(myScrollPane, true);
      }
    });
  }

  private static void prepareCSS(@NotNull HTMLEditorKit editorKit) {
    Color borderColor = UIUtil.getTooltipSeparatorColor();
    int leftPadding = 8;
    int definitionTopPadding = 4;
    String editorFontName = StringUtil.escapeQuotes(EditorColorsManager.getInstance().getGlobalScheme().getEditorFontName());
    editorKit.getStyleSheet().addRule("tt {font-family:\"" + editorFontName + "\"; font-size: 96%;}");
    editorKit.getStyleSheet().addRule("code {font-family:\"" + editorFontName + "\"; font-size: 96%;}");
    editorKit.getStyleSheet().addRule("pre {font-family:\"" + editorFontName + "\"; font-size: 96%;}");
    editorKit.getStyleSheet().addRule(".pre {font-family:\"" + editorFontName + "\"; font-size: 96%;}");
    editorKit.getStyleSheet().addRule("html { padding-bottom: 8px; }");
    editorKit.getStyleSheet().addRule("h1, h2, h3, h4, h5, h6 { margin-top: 0; padding-top: 1px; }");
    editorKit.getStyleSheet().addRule("a { color: #" + ColorUtil.toHex(getLinkColor()) + "; text-decoration: none;}");
    editorKit.getStyleSheet().addRule(".definition { padding: " + definitionTopPadding + "px 17px 1px " + leftPadding +
                                      "px; border-bottom: thin solid #" + ColorUtil.toHex(borderColor) + "; }");
    editorKit.getStyleSheet().addRule(".definition-only { padding: " + definitionTopPadding + "px 17px 0 " + leftPadding + "px; }");
    editorKit.getStyleSheet().addRule(".definition-only pre { margin-bottom: 0 }");
    editorKit.getStyleSheet().addRule(".content { padding: 5px 16px 0 " + leftPadding + "px; max-width: 100% }");
    editorKit.getStyleSheet().addRule(".content-only { padding: 8px 16px 0 " + leftPadding + "px; max-width: 100% }");
    editorKit.getStyleSheet().addRule(".bottom { padding: 3px 16px 0 " + leftPadding + "px; }");
    editorKit.getStyleSheet().addRule(".bottom-no-content { padding: 5px 16px 0 " + leftPadding + "px; }");
    editorKit.getStyleSheet().addRule("p { padding: 1px 0 2px 0; }");
    editorKit.getStyleSheet().addRule("ol { padding: 0 16px 0 0; }");
    editorKit.getStyleSheet().addRule("ul { padding: 0 16px 0 0; }");
    editorKit.getStyleSheet().addRule("li { padding: 1px 0 2px 0; }");
    editorKit.getStyleSheet().addRule(".grayed { color: #909090; display: inline;}");
    editorKit.getStyleSheet().addRule(".centered { text-align: center}");

    // sections table
    editorKit.getStyleSheet().addRule(".sections { padding: 0 16px 0 " + leftPadding + "px; border-spacing: 0; }");
    editorKit.getStyleSheet().addRule("tr { margin: 0 0 0 0; padding: 0 0 0 0; }");
    editorKit.getStyleSheet().addRule("table p { padding-bottom: 0}");
    editorKit.getStyleSheet().addRule("td { margin: 4px 0 0 0; padding: 0 0 0 0; }");
    editorKit.getStyleSheet().addRule("th { text-align: left; }");
    editorKit.getStyleSheet().addRule(".section { color: " + ColorUtil.toHtmlColor(SECTION_COLOR) + "; padding-right: 4px; white-space:nowrap;}");
  }

  private static Color getLinkColor() {
    return JBUI.CurrentTheme.Link.linkColor();
  }

  @Override
  public Object getData(@NotNull @NonNls String dataId) {
    if (com.intellij.codeInsight.documentation.DocumentationManager.SELECTED_QUICK_DOC_TEXT.getName().equals(dataId)) {
      // Javadocs often contain &nbsp; symbols (non-breakable white space). We don't want to copy them as is and replace
      // with raw white spaces. See IDEA-86633 for more details.
      String selectedText = myEditorPane.getSelectedText();
      return selectedText == null ? null : selectedText.replace((char)160, ' ');
    }

    return null;
  }

  @NotNull
  public static FontSize getQuickDocFontSize() {
    FontSize v3 = readCodeExamplesFontSizeFromSettings();
    if (v3 != null) {
      return v3;
    }
    return FontSize.SMALL;
  }

  @Nullable
  private static FontSize readCodeExamplesFontSizeFromSettings() {
    String strValue = PropertiesComponent.getInstance().getValue(CODE_EXAMPLE_FONT_SIZE_PROPERTY);
    if (strValue != null) {
      try {
        return FontSize.valueOf(strValue);
      }
      catch (IllegalArgumentException ignored) {}
    }
    return null;
  }

  public static void setQuickDocFontSize(@NotNull FontSize fontSize) {
    PropertiesComponent.getInstance().setValue(CODE_EXAMPLE_FONT_SIZE_PROPERTY, fontSize.toString());
  }

  public boolean isEmpty() {
    return myIsEmpty;
  }

  public void startWait() {
    myIsEmpty = true;
  }

  public void setHint(JBPopup hint) {
    myHint = (AbstractPopup)hint;
  }

  @Nullable
  public PsiElement getElement() {
    return myElement != null ? myElement.getElement() : null;
  }

  private void setElement(SmartPsiElementPointer<PsiElement> element) {
    myElement = element;
    myModificationCount = getCurrentModificationCount();
  }

  public boolean isUpToDate() {
    return getElement() != null && myModificationCount == getCurrentModificationCount();
  }

  private long getCurrentModificationCount() {
    return myElement != null ? PsiModificationTracker.SERVICE.getInstance(myElement.getProject()).getModificationCount() : -1;
  }

  public void setText(@NotNull @Nls String text, @Nullable PsiElement element) {
    setData(element, text, null);
  }

  public void setData(@Nullable PsiElement element,
                      @NotNull @Nls String text,
                      @Nullable String ref) {
    SmartPsiElementPointer<PsiElement> pointer = null;
    if (element != null && element.isValid()) {
      pointer = SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
    }
    setDataInternal(pointer, text, new Rectangle(0, 0), ref);
  }

  private void setDataInternal(@Nullable SmartPsiElementPointer<PsiElement> element,
                               @NotNull @Nls String text,
                               @NotNull Rectangle viewRect,
                               @Nullable String ref) {
    myIsEmpty = false;
    if (myManager == null) return;

    myText = text;
    setElement(element);
    if (element != null && element.getElement() != null) {
      myManager.updateToolWindowTabName(element.getElement());
    }
    myDecoratedText = decorate(text);

    showHint(viewRect, ref);
  }

  protected void showHint(@NotNull Rectangle viewRect, @Nullable String ref) {
    String refToUse;
    Rectangle viewRectToUse;
    if (DocumentationManagerProtocol.KEEP_SCROLLING_POSITION_REF.equals(ref)) {
      refToUse = null;
      viewRectToUse = myScrollPane.getViewport().getViewRect();
    }
    else {
      refToUse = ref;
      viewRectToUse = viewRect;
    }

    updateControlState();

    highlightLink(myHighlightedLink);

    myEditorPane.setText(myDecoratedText);
    applyFontProps();

    showHint();

    SwingUtilities.invokeLater(() -> {
      myEditorPane.scrollRectToVisible(viewRectToUse); // if ref is defined but is not found in document, this provides a default location
      if (refToUse != null) {
        UIUtil.scrollToReference(myEditorPane, refToUse);
      }
      else if (ScreenReader.isActive()) {
        myEditorPane.setCaretPosition(0);
      }
    });
  }

  protected void showHint() {
    if (myHint == null) return;

    setHintSize();

    DataContext dataContext = getDataContext();
    PopupPositionManager.positionPopupInBestPosition(myHint, myManager.getEditor(), dataContext,
                                                     PopupPositionManager.Position.RIGHT, PopupPositionManager.Position.LEFT);

    Window window = myHint.getPopupWindow();
    if (window != null) window.setFocusableWindowState(true);

    registerSizeTracker();
  }

  private DataContext getDataContext() {
    Component referenceComponent;
    if (myReferenceComponent == null) {
      referenceComponent = IdeFocusManager.getInstance(myManager.myProject).getFocusOwner();
      myReferenceComponent = new WeakReference<>(referenceComponent);
    }
    else {
      referenceComponent = SoftReference.dereference(myReferenceComponent);
      if (referenceComponent == null || ! referenceComponent.isShowing()) referenceComponent = myHint.getComponent();
    }
    return DataManager.getInstance().getDataContext(referenceComponent);
  }

  private void setHintSize() {
    Dimension hintSize;
    if (!myManuallyResized && myHint.getDimensionServiceKey() == null) {
      hintSize = getOptimalSize();
    }
    else {
      if (myManuallyResized) {
        hintSize = myHint.getSize();
        JBInsets.removeFrom(hintSize, myHint.getContent().getInsets());
      }
      else {
        hintSize = DimensionService.getInstance().getSize(com.intellij.codeInsight.documentation.DocumentationManager.NEW_JAVADOC_LOCATION_AND_SIZE, myManager.myProject);
      }
      if (hintSize == null) {
        hintSize = new Dimension(MIN_DEFAULT);
      }
      else {
        hintSize.width = Math.max(hintSize.width, MIN_DEFAULT.width);
        hintSize.height = Math.max(hintSize.height, MIN_DEFAULT.height);
      }
    }
    myHint.setSize(hintSize);
  }

  public Dimension getOptimalSize() {
    int width = getPreferredWidth();
    int height = getPreferredHeight(width);
    return new Dimension(width, height);
  }

  @Override
  public int getPreferredWidth() {
    int minWidth = JBUIScale.scale(300);
    int maxWidth = getPopupAnchor() != null ? JBUIScale.scale(435) : MAX_DEFAULT.width;

    int width = definitionPreferredWidth();
    if (width < 0) { // no definition found
      width = myEditorPane.getPreferredSize().width;
    }
    else {
      width = Math.max(width, myEditorPane.getMinimumSize().width);
    }
    Insets insets = getInsets();
    return MathUtil.clamp(width, minWidth, maxWidth) + insets.left + insets.right;
  }

  @Override
  public int getPreferredHeight(int width) {
    myEditorPane.setBounds(0, 0, width, MAX_DEFAULT.height);
    myEditorPane.setText(myDecoratedText);
    Dimension preferredSize = myEditorPane.getPreferredSize();

    int height = preferredSize.height;
    JScrollBar scrollBar = myScrollPane.getHorizontalScrollBar();
    int reservedForScrollBar = width < preferredSize.width && scrollBar.isOpaque() ? scrollBar.getPreferredSize().height : 0;
    Insets insets = getInsets();
    return MathUtil.clamp(height, MIN_DEFAULT.height, MAX_DEFAULT.height) + insets.top + insets.bottom + reservedForScrollBar;
  }

  private Component getPopupAnchor() {
    LookupEx lookup = myManager == null ? null : LookupManager.getActiveLookup(myManager.getEditor());

    if (lookup != null && lookup.getCurrentItem() != null && lookup.getComponent().isShowing()) {
      return lookup.getComponent();
    }
    Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    JBPopup popup = PopupUtil.getPopupContainerFor(focusOwner);
    if (popup != null && popup != myHint && !popup.isDisposed()) {
      return popup.getContent();
    }
    return null;
  }

  private void registerSizeTracker() {
    AbstractPopup hint = myHint;
    if (hint == null || mySizeTrackerRegistered) return;
    mySizeTrackerRegistered = true;
    hint.addResizeListener(this::onManualResizing, this);
    ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(AnActionListener.TOPIC, new AnActionListener() {
      @Override
      public void afterActionPerformed(@NotNull AnAction action, @NotNull DataContext dataContext, @NotNull AnActionEvent event) {
        if (action instanceof WindowAction) onManualResizing();
      }
    });
  }

  private void onManualResizing() {
    myManuallyResized = true;
    if (myStoreSize && myHint != null) {
      myHint.setDimensionServiceKey(com.intellij.codeInsight.documentation.DocumentationManager.NEW_JAVADOC_LOCATION_AND_SIZE);
      myHint.storeDimensionSize();
    }
  }

  private int definitionPreferredWidth() {
    TextUI ui = myEditorPane.getUI();
    View view = ui.getRootView(myEditorPane);
    View definition = findDefinition(view);
    return definition != null ? (int)definition.getPreferredSpan(View.X_AXIS) : -1;
  }

  private static View findDefinition(View view) {
    if ("definition".equals(view.getElement().getAttributes().getAttribute(HTML.Attribute.CLASS))) {
      return view;
    }
    for (int i = 0; i < view.getViewCount(); i++) {
      View definition = findDefinition(view.getView(i));
      if (definition != null) return definition;
    }
    return null;
  }

  @Contract(pure = true)
  private String decorate(String text) {
    text = StringUtil.replaceIgnoreCase(text, "</html>", "");
    text = StringUtil.replaceIgnoreCase(text, "</body>", "");
    text = StringUtil.replaceIgnoreCase(text, DocumentationMarkup.SECTIONS_START + DocumentationMarkup.SECTIONS_END, "");
    text = StringUtil.replaceIgnoreCase(text, DocumentationMarkup.SECTIONS_START + "<p>" + DocumentationMarkup.SECTIONS_END, "");
    boolean hasContent = text.contains(DocumentationMarkup.CONTENT_START);
    if (!hasContent) {
      if (!text.contains(DocumentationMarkup.DEFINITION_START)) {
        int bodyStart = findContentStart(text);
        if (bodyStart > 0) {
          text = text.substring(0, bodyStart) +
                 DocumentationMarkup.CONTENT_START +
                 text.substring(bodyStart) +
                 DocumentationMarkup.CONTENT_END;
        }
        else {
          text = DocumentationMarkup.CONTENT_START + text + DocumentationMarkup.CONTENT_END;
        }
        hasContent = true;
      } else if (!text.contains(DocumentationMarkup.SECTIONS_START)){
        text = StringUtil.replaceIgnoreCase(text, DocumentationMarkup.DEFINITION_START, "<div class='definition-only'><pre>");
      }
    }
    if (!text.contains(DocumentationMarkup.DEFINITION_START)) {
      text = text.replace("class='content'", "class='content-only'");
    }
    String location = getLocationText();
    if (location != null) {
      text = text + getBottom(hasContent) + location + "</div>";
    }
    text = text.replaceAll("<p>\\s*(<(?:[uo]l|h\\d|p))", "$1");
    text = addExternalLinksIcon(text);
    return text;
  }

  private static int findContentStart(String text) {
    int index = StringUtil.indexOfIgnoreCase(text, "<body>", 0);
    if (index >= 0) return index + 6;
    index = StringUtil.indexOfIgnoreCase(text, "</head>", 0);
    if (index >= 0) return index + 7;
    index = StringUtil.indexOfIgnoreCase(text, "</style>", 0);
    if (index >= 0) return index + 8;
    index = StringUtil.indexOfIgnoreCase(text, "<html>", 0);
    if (index >= 0) return index + 6;
    return -1;
  }

  @NotNull
  private static String getBottom(boolean hasContent) {
    return "<div class='" + (hasContent ? "bottom" : "bottom-no-content") + "'>";
  }

  @Contract(pure = true)
  public static String addExternalLinksIcon(String text) {
    return EXTERNAL_LINK_PATTERN.matcher(text).replaceAll(EXTERNAL_LINK_REPLACEMENT);
  }

  private @NonNls String getLocationText() {
    PsiElement element = getElement();
    if (element != null) {
      PsiFile file = element.getContainingFile();
      VirtualFile virtualFile = file == null ? null : file.getVirtualFile();

      if (virtualFile == null) return null;

      SearchScope scope = element.getUseScope();
      if (scope instanceof LocalSearchScope) {
        return null;
      }

      ProjectFileIndex fileIndex = ProjectRootManager.getInstance(element.getProject()).getFileIndex();
      Module module = fileIndex.getModuleForFile(virtualFile);

      if (module != null && !ModuleType.isInternal(module)) {
        if (ModuleManager.getInstance(element.getProject()).getModules().length == 1) return null;
        return "<icon src='" + ModuleType.get(module).getId() + "'>&nbsp;" + module.getName().replace("<", "&lt;");
      }
      else {
        List<OrderEntry> entries = fileIndex.getOrderEntriesForFile(virtualFile);
        for (OrderEntry order : entries) {
          if (order instanceof LibraryOrderEntry || order instanceof JdkOrderEntry) {
            return "<icon src='AllIcons.Nodes.PpLibFolder" + "'>&nbsp;" + order.getPresentableName().replace("<", "&lt;");
          }
        }
      }
    }

    return null;
  }

  private void applyFontProps() {
    Document document = myEditorPane.getDocument();
    if (!(document instanceof StyledDocument)) {
      return;
    }
    String fontName = myEditorPane.getFont().getFontName();

    // changing font will change the doc's CSS as myEditorPane has JEditorPane.HONOR_DISPLAY_PROPERTIES via UIUtil.getHTMLEditorKit
    myEditorPane.setFont(UIUtil.getFontWithFallback(fontName, Font.PLAIN, JBUIScale.scale(getQuickDocFontSize().getSize())));
  }

  private void updateControlState() {
      remove(myControlPanel);
      if (myManager.myToolWindow != null) return;
      myCorner.setVisible(true);
  }

  private static class MyGearActionGroup extends DefaultActionGroup implements HintManagerImpl.ActionToIgnore {
    MyGearActionGroup(AnAction @NotNull ... actions) {
      super(actions);
      setPopup(true);
    }
  }

  private void registerActions() {
    // With screen readers, we want the default keyboard behavior inside
    // the document text editor, i.e. the caret moves with cursor keys, etc.
    if (!ScreenReader.isActive()) {
      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getVerticalScrollBar();
        int value = scrollBar.getValue() - scrollBar.getUnitIncrement(-1);
        value = Math.max(value, 0);
        scrollBar.setValue(value);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getVerticalScrollBar();
        int value = scrollBar.getValue() + scrollBar.getUnitIncrement(+1);
        value = Math.min(value, scrollBar.getMaximum());
        scrollBar.setValue(value);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getHorizontalScrollBar();
        int value = scrollBar.getValue() - scrollBar.getUnitIncrement(-1);
        value = Math.max(value, 0);
        scrollBar.setValue(value);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getHorizontalScrollBar();
        int value = scrollBar.getValue() + scrollBar.getUnitIncrement(+1);
        value = Math.min(value, scrollBar.getMaximum());
        scrollBar.setValue(value);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getVerticalScrollBar();
        int value = scrollBar.getValue() - scrollBar.getBlockIncrement(-1);
        value = Math.max(value, 0);
        scrollBar.setValue(value);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getVerticalScrollBar();
        int value = scrollBar.getValue() + scrollBar.getBlockIncrement(+1);
        value = Math.min(value, scrollBar.getMaximum());
        scrollBar.setValue(value);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getHorizontalScrollBar();
        scrollBar.setValue(0);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), e -> {
        JScrollBar scrollBar = myScrollPane.getHorizontalScrollBar();
        scrollBar.setValue(scrollBar.getMaximum());
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, InputEvent.CTRL_MASK), e -> {
        JScrollBar scrollBar = myScrollPane.getVerticalScrollBar();
        scrollBar.setValue(0);
      });

      myKeyboardActions.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, InputEvent.CTRL_MASK), e -> {
        JScrollBar scrollBar = myScrollPane.getVerticalScrollBar();
        scrollBar.setValue(scrollBar.getMaximum());
      });
    }
  }

  public @Nls String getText() {
    return myText;
  }

  @Override
  public void dispose() {
    myEditorPane.getCaret().setVisible(false); // Caret, if blinking, has to be deactivated.
    myKeyboardActions.clear();
    myElement = null;
    myManager = null;
    myHint = null;
  }

  private int getLinkCount() {
    HTMLDocument document = (HTMLDocument)myEditorPane.getDocument();
    int linkCount = 0;
    for (HTMLDocument.Iterator it = document.getIterator(HTML.Tag.A); it.isValid(); it.next()) {
      if (it.getAttributes().isDefined(HTML.Attribute.HREF)) linkCount++;
    }
    return linkCount;
  }

  @Nullable
  private HTMLDocument.Iterator getLink(int n) {
    if (n >= 0) {
      HTMLDocument document = (HTMLDocument)myEditorPane.getDocument();
      int linkCount = 0;
      for (HTMLDocument.Iterator it = document.getIterator(HTML.Tag.A); it.isValid(); it.next()) {
        if (it.getAttributes().isDefined(HTML.Attribute.HREF) && linkCount++ == n) return it;
      }
    }
    return null;
  }

  private void highlightLink(int n) {
    myHighlightedLink = n;
    Highlighter highlighter = myEditorPane.getHighlighter();
    HTMLDocument.Iterator link = getLink(n);
    if (link != null) {
      int startOffset = link.getStartOffset();
      int endOffset = link.getEndOffset();
      try {
        if (myHighlightingTag == null) {
          myHighlightingTag = highlighter.addHighlight(startOffset, endOffset, LINK_HIGHLIGHTER);
        }
        else {
          highlighter.changeHighlight(myHighlightingTag, startOffset, endOffset);
        }
        myEditorPane.setCaretPosition(startOffset);
        if (!ScreenReader.isActive()) {
          // scrolling to target location explicitly, as we've disabled auto-scrolling to caret
          myEditorPane.scrollRectToVisible(myEditorPane.modelToView(startOffset));
        }
      }
      catch (BadLocationException e) {
        LOG.warn("Error highlighting link", e);
      }
    }
    else if (myHighlightingTag != null) {
      highlighter.removeHighlight(myHighlightingTag);
      myHighlightingTag = null;
    }
  }

  private void activateLink(int n) {
    HTMLDocument.Iterator link = getLink(n);
    if (link != null) {
      String href = (String)link.getAttributes().getAttribute(HTML.Attribute.HREF);
      myManager.navigateByLink(this, null, href);
    }
  }

  private class FontSizeSettingsAction extends AnAction implements HintManagerImpl.ActionToIgnore {
    private final boolean myOnToolbar;

    FontSizeSettingsAction(boolean onToolbar) {
      super("Adjust Font Size...");
      myOnToolbar = onToolbar;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      if (myManager == null || myOnToolbar && myManager.myToolWindow != null) {
        e.getPresentation().setEnabledAndVisible(false);
      }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      DocFontSizePopup.show(() -> {
        if (myIgnoreFontSizeSliderChange) {
          return;
        }
        applyFontProps();
        // resize popup according to new font size, if user didn't set popup size manually
        if (!myManuallyResized && myHint != null && myHint.getDimensionServiceKey() == null) showHint();
      }, DocumentationComponent.this);
    }
  }

  private class PreviousLinkAction extends AnAction implements HintManagerImpl.ActionToIgnore {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      int linkCount = getLinkCount();
      if (linkCount <= 0) return;
      highlightLink(myHighlightedLink < 0 ? (linkCount - 1) : (myHighlightedLink + linkCount - 1) % linkCount);
    }
  }

  private class NextLinkAction extends AnAction implements HintManagerImpl.ActionToIgnore {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      int linkCount = getLinkCount();
      if (linkCount <= 0) return;
      highlightLink((myHighlightedLink + 1) % linkCount);
    }
  }

  private class ActivateLinkAction extends AnAction implements HintManagerImpl.ActionToIgnore {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      activateLink(myHighlightedLink);
    }
  }

  private static class LinkHighlighter implements Highlighter.HighlightPainter {
    private static final Stroke STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{1}, 0);

    @Override
    public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
      try {
        Rectangle target = c.getUI().getRootView(c).modelToView(p0, Position.Bias.Forward, p1, Position.Bias.Backward, bounds).getBounds();
        Graphics2D g2d = (Graphics2D)g.create();
        try {
          g2d.setStroke(STROKE);
          g2d.setColor(c.getSelectionColor());
          g2d.drawRect(target.x, target.y, target.width - 1, target.height - 1);
        }
        finally {
          g2d.dispose();
        }
      }
      catch (Exception e) {
        LOG.warn("Error painting link highlight", e);
      }
    }
  }

  private class MyScrollPane extends JBScrollPane {
    MyScrollPane() {
      super(myEditorPane, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);
      setLayout(new Layout() {
        @Override
        public void layoutContainer(Container parent) {
          super.layoutContainer(parent);
          if (!myCorner.isVisible()) return;
          if (vsb != null) {
            Rectangle bounds = vsb.getBounds();
            vsb.setBounds(bounds.x, bounds.y, bounds.width, bounds.height - myCorner.getPreferredSize().height - 3);
          }
          if (hsb != null) {
            Rectangle bounds = hsb.getBounds();
            int vsbOffset = vsb != null ? vsb.getBounds().width : 0;
            hsb.setBounds(bounds.x, bounds.y, bounds.width - myCorner.getPreferredSize().width - 3 + vsbOffset, bounds.height);
          }
        }
      });
    }

    @Override
    public Border getViewportBorder() {
      return null;
    }

    @Override
    protected void processMouseWheelEvent(MouseWheelEvent e) {
      if (!EditorSettingsExternalizable.getInstance().isWheelFontChangeEnabled() || !EditorUtil.isChangeFontSize(e)) {
        super.processMouseWheelEvent(e);
        return;
      }

      int rotation = e.getWheelRotation();
      if (rotation == 0) return;
      int change = Math.abs(rotation);
      boolean increase = rotation <= 0;
      FontSize newFontSize = getQuickDocFontSize();
      for (; change > 0; change--) {
        if (increase) {
          newFontSize = newFontSize.larger();
        }
        else {
          newFontSize = newFontSize.smaller();
        }
      }

      if (newFontSize == getQuickDocFontSize()) {
        return;
      }

      setQuickDocFontSize(newFontSize);
      applyFontProps();
      myIgnoreFontSizeSliderChange = true;
      try {
        DocFontSizePopup.update();
      }
      finally {
        myIgnoreFontSizeSliderChange = false;
      }
    }
  }

  private class ShowAsToolWindowAction extends AnAction implements HintManagerImpl.ActionToIgnore {
    ShowAsToolWindowAction() {
      super(() -> "Open as Tool Window");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Presentation presentation = e.getPresentation();
      if (myManager == null) {
        presentation.setEnabledAndVisible(false);
      }
      else {
        presentation.setIcon(ToolWindowManager.getInstance(myManager.myProject).getLocationIcon(ToolWindowId.DOCUMENTATION, EmptyIcon.ICON_16));
        presentation.setEnabledAndVisible(myToolWindowCallback != null);
      }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      myToolWindowCallback.run();
    }
  }

  private class RestoreDefaultSizeAction extends AnAction implements HintManagerImpl.ActionToIgnore {
    RestoreDefaultSizeAction() {
      super("Restore Size");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabledAndVisible(myHint != null && (myManuallyResized || myHint.getDimensionServiceKey() != null));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      myManuallyResized = false;
      if (myStoreSize) {
        DimensionService.getInstance().setSize(DocumentationManager.NEW_JAVADOC_LOCATION_AND_SIZE, null, myManager.myProject);
        myHint.setDimensionServiceKey(null);
      }
      showHint();
    }
  }
}
