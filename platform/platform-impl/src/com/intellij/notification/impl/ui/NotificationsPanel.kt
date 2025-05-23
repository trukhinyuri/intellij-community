// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.notification.impl.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.IdeBundle
import com.intellij.ide.IdeCoreBundle
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.ui.UISettingsListener
import com.intellij.idea.ActionsBundle
import com.intellij.notification.Notification
import com.intellij.notification.impl.*
import com.intellij.notification.impl.actions.ClearAllNotificationsAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Divider
import com.intellij.openapi.ui.NullableComponent
import com.intellij.openapi.ui.OnePixelDivider
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Clock
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.*
import com.intellij.ui.components.*
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.Alarm
import com.intellij.util.SingleEdtTaskScheduler
import com.intellij.util.UtilBundle
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.*
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.awt.*
import java.awt.event.*
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.*
import java.util.function.Consumer
import javax.accessibility.AccessibleContext
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.HyperlinkEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.JTextComponent
import kotlin.time.Duration.Companion.days

private const val FONT_KEY = "FontFunction"

@ApiStatus.Internal
class NotificationsPanel(private val project: Project, parentDisposable: Disposable) : ProjectNotificationsModelListener, Disposable {
  private val mainPanel: JBPanelWithEmptyText = JBPanelWithEmptyText(BorderLayout())
  val component: JComponent
    get() = mainPanel

  private val myNotifications = ArrayList<Notification>()

  private val suggestions: NotificationGroupComponent
  private val timeline: NotificationGroupComponent
  private val searchController: SearchController
  private val autoProportionController: AutoProportionController

  private val singleSelectionHandler = SingleTextSelectionHandler()

  private val searchUpdateAlarm = SingleEdtTaskScheduler.createSingleEdtTaskScheduler()

  private val splitterWrapper: JPanel

  init {
    mainPanel.background = NotificationComponent.BG_COLOR
    setEmptyState()
    handleFocus()

    suggestions = NotificationGroupComponent(this, true, project)
    timeline = NotificationGroupComponent(this, false, project)
    searchController = SearchController(this, suggestions, timeline)

    mainPanel.add(createSearchComponent(), BorderLayout.NORTH)

    val splitter = MySplitter()
    splitter.firstComponent = suggestions
    splitter.secondComponent = timeline
    splitterWrapper = object : NonOpaquePanel(splitter) {
      override fun isVisible() = super.isVisible() && splitter.isVisible
    }
    mainPanel.add(splitterWrapper)

    autoProportionController = AutoProportionController(splitter, suggestions, timeline)

    suggestions.setRemoveCallback(Consumer(ApplicationNotificationsModel::remove))
    timeline.setRemoveCallback(Consumer(ApplicationNotificationsModel::remove))
    timeline.setClearCallback { ApplicationNotificationsModel.clearTimeline(project) }

    Disposer.register(parentDisposable, this)

    val connection = ApplicationManager.getApplication().messageBus.connect(this)
    connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
      suggestions.updateLaf()
      timeline.updateLaf()
    })
    connection.subscribe(UISettingsListener.TOPIC, UISettingsListener {
      suggestions.updateLaf()
      timeline.updateLaf()
    })
  }

  private fun createSearchComponent(): SearchTextField {
    val searchField = object : SearchTextField(false) {
      override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        size.height = JBUIScale.scale(35)
        return size
      }

      override fun updateUI() {
        super.updateUI()
        textEditor?.border = null
      }

      override fun preprocessEventForTextField(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.VK_ESCAPE && event.id == KeyEvent.KEY_PRESSED) {
          isVisible = false
          searchController.cancelSearch()
          splitterWrapper.border = null
          return true
        }
        return super.preprocessEventForTextField(event)
      }
    }
    searchField.textEditor.border = JBUI.Borders.empty(0, 5, 0, 3)
    searchField.border = JBUI.Borders.customLineBottom(JBColor.border())
    searchField.isVisible = false

    if (ExperimentalUI.isNewUI()) {
      searchController.background = JBUI.CurrentTheme.ToolWindow.background()
      searchField.textEditor.background = searchController.background
    }
    else {
      searchController.background = UIUtil.getTextFieldBackground()
    }

    searchController.searchField = searchField

    searchField.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        searchUpdateAlarm.cancelAndRequest(100, ModalityState.stateForComponent(searchField), searchController::doSearch)
      }
    })

    return searchField
  }

  fun createActions(): ActionGroup {
    val gearAction = object : DumbAwareAction() {
      override fun actionPerformed(e: AnActionEvent) {
        searchController.startSearch()
        splitterWrapper.border = JBUI.Borders.emptyTop(4)
      }
    }

    val actionManager = ActionManager.getInstance()
    val findAction = actionManager.getAction(IdeActions.ACTION_FIND)
    if (findAction == null) {
      gearAction.templatePresentation.text = ActionsBundle.actionText(IdeActions.ACTION_FIND)
    }
    else {
      gearAction.copyFrom(findAction)
      gearAction.registerCustomShortcutSet(findAction.shortcutSet, mainPanel)
    }

    val group = DefaultActionGroup()
    group.add(gearAction)
    group.addSeparator()

    val clearAction = actionManager.getAction(ClearAllNotificationsAction.ID)
    if (clearAction != null) {
      group.add(clearAction)
    }

    val markAction = actionManager.getAction("MarkNotificationsAsRead")
    if (markAction != null) {
      group.add(markAction)
    }
    return group
  }

  fun setEmptyState() {
    mainPanel.emptyText.appendLine(IdeBundle.message("notifications.toolwindow.empty.text.first.line"))
    @Suppress("DialogTitleCapitalization")
    mainPanel.emptyText.appendLine(IdeBundle.message("notifications.toolwindow.empty.text.second.line"))
  }

  fun clearEmptyState() {
    mainPanel.emptyText.clear()
  }

  private fun handleFocus() {
    val listener = AWTEventListener {
      if (it is MouseEvent && it.id == MouseEvent.MOUSE_PRESSED
          && !UIUtil.isFocusAncestor(mainPanel)
          && UIUtil.isAncestor(mainPanel, it.component)) {
        it.component.requestFocus()
      }
    }
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    Disposer.register(this, Disposable {
      Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
    })
  }

  override fun add(notification: Notification) {
    if (notification.isSuggestionType) {
      suggestions.add(notification, singleSelectionHandler)
    }
    else {
      timeline.add(notification, singleSelectionHandler)
    }

    myNotifications.add(notification)

    searchController.update()
    autoProportionController.update()
  }

  override fun add(notifications: List<Notification>) {
    notifications.forEach {
      if (it.isSuggestionType) {
        suggestions.add(it, singleSelectionHandler)
      }
      else {
        timeline.add(it, singleSelectionHandler)
      }
      myNotifications.add(it)
    }

    searchController.update()
    autoProportionController.update()
  }

  override fun getNotifications(): ArrayList<Notification> = ArrayList(myNotifications)

  override fun isEmpty(): Boolean = suggestions.isEmpty() && timeline.isEmpty()

  override fun clearUnreadStates() {
    suggestions.clearNewState()
    timeline.clearNewState()
  }

  override fun remove(notification: Notification) {
    if (notification.isSuggestionType) {
      suggestions.remove(notification)
    }
    else {
      timeline.remove(notification)
    }

    myNotifications.remove(notification)

    searchController.update()
    autoProportionController.update()
  }

  override fun expireAll() {
    val notifications = ArrayList(myNotifications)

    suggestions.expireAll()
    timeline.expireAll()

    myNotifications.clear()

    searchController.update()

    for (n in notifications) {
      n.expire()
    }
  }

  override fun clearTimeline() {
    val notifications = timeline.clear().toSet()

    myNotifications.removeAll(notifications)

    searchController.update()
    autoProportionController.update()
  }

  override fun clearAll() {
    suggestions.clear()
    timeline.clear()

    myNotifications.clear()

    searchController.update()
    autoProportionController.update()
  }

  fun updateComponents() {
    suggestions.updateComponents()
    timeline.updateComponents()
  }

  override fun dispose() {
    searchUpdateAlarm.cancel()
  }

  fun fullRepaint() {
    mainPanel.doLayout()
    mainPanel.revalidate()
    mainPanel.repaint()
  }
}

private class MySplitter : OnePixelSplitter(true, .5f) {
  override fun createDivider(): Divider {
    return object : OnePixelDivider(true, this) {
      override fun setVisible(aFlag: Boolean) {
        super.setVisible(aFlag)
        setResizeEnabled(aFlag)
        if (!aFlag) {
          setBounds(0, 0, 0, 0)
        }
      }

      override fun setBackground(bg: Color?) {
        super.setBackground(JBColor.border())
      }
    }
  }
}

private fun JComponent.mediumFontFunction() {
  font = JBFont.medium()
  val f: (JComponent) -> Unit = {
    it.font = JBFont.medium()
  }
  putClientProperty(FONT_KEY, f)
}

private fun JComponent.smallFontFunction() {
  font = JBFont.smallOrNewUiMedium()
  val f: (JComponent) -> Unit = {
    it.font = JBFont.smallOrNewUiMedium()
  }
  putClientProperty(FONT_KEY, f)
}

private class SearchController(
  private val mainContent: NotificationsPanel,
  private val suggestions: NotificationGroupComponent,
  private val timeline: NotificationGroupComponent,
) {
  lateinit var searchField: SearchTextField
  lateinit var background: Color

  fun startSearch() {
    searchField.isVisible = true
    searchField.selectText()
    searchField.requestFocus()

    mainContent.clearEmptyState()

    if (searchField.text.isNotEmpty()) {
      doSearch()
    }
  }

  fun doSearch() {
    val query = searchField.text

    if (query.isEmpty()) {
      searchField.textEditor.background = background
      clearSearch()
      return
    }

    var result = false
    val function: (NotificationComponent) -> Unit = {
      if (it.applySearchQuery(query)) {
        result = true
      }
    }
    suggestions.iterateComponents(function)
    timeline.iterateComponents(function)
    searchField.textEditor.background = if (result) background else LightColors.RED
    mainContent.fullRepaint()
  }

  fun update() {
    if (searchField.isVisible && searchField.text.isNotEmpty()) {
      doSearch()
    }
  }

  fun cancelSearch() {
    mainContent.setEmptyState()
    clearSearch()
  }

  private fun clearSearch() {
    val function: (NotificationComponent) -> Unit = { it.applySearchQuery(null) }
    suggestions.iterateComponents(function)
    timeline.iterateComponents(function)
    mainContent.fullRepaint()
  }
}

private class AutoProportionController(
  private val splitter: MySplitter,
  private val suggestions: NotificationGroupComponent,
  private val timeline: NotificationGroupComponent,
) : PropertyChangeListener {
  private var myEvent = false
  private var myEnabled = true

  init {
    splitter.addPropertyChangeListener(Splitter.PROP_PROPORTION, this)
  }

  override fun propertyChange(evt: PropertyChangeEvent?) {
    if (!myEvent) {
      myEnabled = false
      splitter.removePropertyChangeListener(Splitter.PROP_PROPORTION, this)
    }
  }

  fun update() {
    ApplicationManager.getApplication().invokeLater(::doUpdate)
  }

  private fun doUpdate() {
    if (!myEnabled || suggestions.isEmpty() || timeline.isEmpty()) {
      return
    }

    val height = splitter.height
    if (height == 0) {
      return
    }

    val firstHeight = suggestions.preferredSize.height + JBUI.scale(10)

    if (firstHeight < height / 2) {
      setProportion(firstHeight / height.toFloat())
    }
    else {
      setProportion(0.5f)
    }
  }

  private fun setProportion(value: Float) {
    try {
      myEvent = true
      splitter.proportion = value
    }
    finally {
      myEvent = false
    }
  }
}

private class NotificationGroupComponent(
  private val myMainContent: NotificationsPanel,
  private val mySuggestionType: Boolean,
  private val myProject: Project,
) :
  JBPanel<NotificationGroupComponent>(BorderLayout()), NullableComponent {

  companion object {
  }

  private val myTitle = JBLabel(
    IdeBundle.message(if (mySuggestionType) "notifications.toolwindow.suggestions" else "notifications.toolwindow.timeline"))

  private val myList = JPanel(VerticalLayout(JBUI.scale(10)))
  private val myScrollPane = object : JBScrollPane(myList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                   ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
    override fun setupCorners() {
      super.setupCorners()
      border = null
    }

    override fun updateUI() {
      super.updateUI()
      border = null
    }
  }

  private val myEventHandler = ComponentEventHandler()

  private val myTimeComponents = ArrayList<JLabel>()
  private val myTimeAlarm = Alarm(myProject)

  private lateinit var myClearCallback: () -> Unit
  private lateinit var myRemoveCallback: Consumer<Notification>

  init {
    background = NotificationComponent.BG_COLOR

    val mainPanel = JPanel(BorderLayout(0, JBUI.scale(8)))
    mainPanel.isOpaque = false
    mainPanel.border = JBUI.Borders.emptyTop(8)
    add(mainPanel)

    myTitle.mediumFontFunction()
    myTitle.foreground = NotificationComponent.INFO_COLOR

    if (mySuggestionType) {
      myTitle.border = JBUI.Borders.emptyLeft(18)
      mainPanel.add(myTitle, BorderLayout.NORTH)
    }
    else {
      val panel = JPanel(BorderLayout())
      panel.isOpaque = false
      panel.border = JBUI.Borders.emptyLeft(18)

      panel.add(myTitle, BorderLayout.WEST)

      val clearAll = LinkLabel(IdeBundle.message("notifications.toolwindow.timeline.clear.all"), null) { _: LinkLabel<Unit>, _: Unit? ->
        myClearCallback.invoke()
      }
      clearAll.mediumFontFunction()
      clearAll.border = JBUI.Borders.emptyRight(20)
      panel.add(clearAll, BorderLayout.EAST)

      mainPanel.add(panel, BorderLayout.NORTH)
    }

    myList.isOpaque = true
    myList.background = NotificationComponent.BG_COLOR
    myList.border = JBUI.Borders.empty(0, 8, 0, 10)

    ScrollableContentBorder.setup(myScrollPane, Side.TOP)
    mainPanel.add(myScrollPane)

    myEventHandler.add(this)
  }

  fun updateLaf() {
    updateComponents()
    iterateComponents { it.updateLaf() }
  }

  fun add(notification: Notification, singleSelectionHandler: SingleTextSelectionHandler) {
    val component = NotificationComponent(myProject, notification, myTimeComponents, singleSelectionHandler)
    component.setNew(true)

    myList.add(component, 0)
    updateLayout()
    myEventHandler.add(component)

    updateContent()

    component.setDoNotAskHandler { forProject ->
      component.myNotificationWrapper.notification!!
        .setDoNotAskFor(if (forProject) myProject else null)
        .also { myRemoveCallback.accept(it) }
        .hideBalloon()
    }

    component.setRemoveCallback(myRemoveCallback)
  }

  private fun updateLayout() {
    val layout = myList.layout
    iterateComponents { component ->
      layout.removeLayoutComponent(component)
      layout.addLayoutComponent(null, component)
    }
  }

  fun isEmpty(): Boolean {
    val count = myList.componentCount
    for (i in 0 until count) {
      if (myList.getComponent(i) is NotificationComponent) {
        return false
      }
    }
    return true
  }

  fun setRemoveCallback(callback: Consumer<Notification>) {
    myRemoveCallback = callback
  }

  fun remove(notification: Notification) {
    val count = myList.componentCount
    for (i in 0 until count) {
      val component = myList.getComponent(i) as NotificationComponent
      if (component.myNotificationWrapper.notification === notification) {
        if (notification.isSuggestionType || notification.isRemoveWhenExpired) {
          component.removeFromParent()
          myList.remove(i)
        }
        else {
          component.expire()
        }
        break
      }
    }
    updateContent()
  }

  fun setClearCallback(callback: () -> Unit) {
    myClearCallback = callback
  }

  fun expireAll() {
    if (mySuggestionType) {
      clear()
    }
    else {
      iterateComponents {
        if (it.myNotificationWrapper.notification != null) {
          it.expire()
        }
      }
      updateContent()
    }
  }

  fun clear(): List<Notification> {
    val notifications = mutableListOf<Notification>()
    iterateComponents {
      val notification = it.myNotificationWrapper.notification
      if (notification != null) {
        notifications.add(notification)
      }
      it.removeFromParent()
    }
    myList.removeAll()
    updateContent()
    return notifications.toList()
  }

  fun clearNewState() {
    iterateComponents { it.setNew(false) }
  }

  fun updateComponents() {
    UIUtil.uiTraverser(this)
      .filter(JComponent::class.java)
      .forEach {
        val value = it.getClientProperty(FONT_KEY)
        if (value != null) {
          @Suppress("UNCHECKED_CAST")
          (value as (JComponent) -> Unit).invoke(it)
        }
      }
    myMainContent.fullRepaint()
  }

  inline fun iterateComponents(f: (NotificationComponent) -> Unit) {
    val count = myList.componentCount
    for (i in 0 until count) {
      f.invoke(myList.getComponent(i) as NotificationComponent)
    }
  }

  private fun updateContent() {
    if (!mySuggestionType && !myTimeAlarm.isDisposed) {
      myTimeAlarm.cancelAllRequests()

      object : Runnable {
        override fun run() {
          for (timeComponent in myTimeComponents) {
            timeComponent.text = formatPrettyDateTime(timeComponent.getClientProperty(NotificationComponent.TIME_KEY) as Long)
          }

          if (myTimeComponents.isNotEmpty() && !myTimeAlarm.isDisposed) {
            myTimeAlarm.addRequest(this, 30000)
          }
        }
      }.run()
    }

    myMainContent.fullRepaint()
  }

  private fun formatPrettyDateTime(time: Long): @NlsSafe String {
    val c = Calendar.getInstance()

    c.timeInMillis = Clock.getTime()
    val currentYear = c[Calendar.YEAR]
    val currentDayOfYear = c[Calendar.DAY_OF_YEAR]

    c.timeInMillis = time
    val year = c[Calendar.YEAR]
    val dayOfYear = c[Calendar.DAY_OF_YEAR]

    if (currentYear == year && currentDayOfYear == dayOfYear) {
      return DateFormatUtil.formatTime(time)
    }

    val isYesterdayOnPreviousYear = currentYear == year + 1 && currentDayOfYear == 1 && dayOfYear == c.getActualMaximum(
      Calendar.DAY_OF_YEAR)
    val isYesterday = isYesterdayOnPreviousYear || currentYear == year && currentDayOfYear == dayOfYear + 1
    if (isYesterday) {
      return UtilBundle.message("date.format.yesterday")
    }

    return DateFormatUtil.formatDate(time)
  }

  override fun isVisible(): Boolean {
    if (super.isVisible()) {
      val count = myList.componentCount
      for (i in 0 until count) {
        if (myList.getComponent(i).isVisible) {
          return true
        }
      }
    }
    return false
  }

  override fun isNull(): Boolean = !isVisible
}

private class NotificationComponent(
  val project: Project,
  notification: Notification,
  timeComponents: ArrayList<JLabel>,
  val singleSelectionHandler: SingleTextSelectionHandler,
) :
  JBPanel<NotificationComponent>() {

  companion object {
    val BG_COLOR: Color
      get() {
        if (ExperimentalUI.isNewUI()) {
          return JBUI.CurrentTheme.ToolWindow.background()
        }
        return UIUtil.getListBackground()
      }
    val INFO_COLOR = JBColor.namedColor("Label.infoForeground", JBColor(Gray.x80, Gray.x8C))
    internal const val NEW_COLOR_NAME = "NotificationsToolwindow.newNotification.background"
    internal val NEW_DEFAULT_COLOR = JBColor(0xE6EEF7, 0x45494A)
    val NEW_COLOR = JBColor.namedColor(NEW_COLOR_NAME, NEW_DEFAULT_COLOR)
    val NEW_HOVER_COLOR = JBColor.namedColor("NotificationsToolwindow.newNotification.hoverBackground", JBColor(0xE6EEF7, 0x45494A))
    val HOVER_COLOR = JBColor.namedColor("NotificationsToolwindow.Notification.hoverBackground", BG_COLOR)
    const val TIME_KEY = "TimestampKey"
  }

  val myNotificationWrapper = NotificationWrapper(notification)
  private var myIsNew = false
  private var myHoverState = false
  private val myMoreButton: Component?
  private var myMorePopupVisible = false
  private var myRoundColor = BG_COLOR
  private lateinit var myDoNotAskHandler: (Boolean) -> Unit
  private lateinit var myRemoveCallback: Consumer<Notification>

  private var myMorePopup: JBPopup? = null
  var myMoreAwtPopup: JPopupMenu? = null
  var myDropDownPopup: JPopupMenu? = null

  @JvmField
  internal val popupAlarm = SingleEdtTaskScheduler.createSingleEdtTaskScheduler()

  private var myLafUpdater: Runnable? = null

  init {
    isOpaque = true
    background = BG_COLOR
    border = JBUI.Borders.empty(10, 10, 10, 0)

    layout = object : BorderLayout(JBUI.scale(7), 0) {
      private var myEastComponent: Component? = null

      @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
      override fun addLayoutComponent(name: String?, comp: Component) {
        if (EAST == name) {
          myEastComponent = comp
        }
        else {
          super.addLayoutComponent(name, comp)
        }
      }

      override fun layoutContainer(target: Container) {
        super.layoutContainer(target)
        if (myEastComponent != null && myEastComponent!!.isVisible) {
          val insets = target.insets
          val height = target.height - insets.bottom - insets.top
          val component = myEastComponent!!
          component.setSize(component.width, height)
          val d = component.preferredSize
          component.setBounds(target.width - insets.right - d.width, insets.top, d.width, height)
        }
      }
    }

    val iconPanel = JPanel(BorderLayout())
    iconPanel.isOpaque = false
    iconPanel.add(JBLabel(NotificationsUtil.getIcon(notification)), BorderLayout.NORTH)
    add(iconPanel, BorderLayout.WEST)

    val centerPanel = JPanel(VerticalLayout(JBUI.scale(8)))
    centerPanel.isOpaque = false
    centerPanel.border = JBUI.Borders.emptyRight(10)

    var titlePanel: JPanel? = null

    if (notification.hasTitle()) {
      val titleContent = NotificationsUtil.buildHtml(notification, null, false, null, null)
      val title = object : JBLabel(titleContent) {
        override fun updateUI() {
          val oldEditor = UIUtil.findComponentOfType(this, JEditorPane::class.java)
          if (oldEditor != null) {
            singleSelectionHandler.remove(oldEditor)
          }

          super.updateUI()

          val newEditor = UIUtil.findComponentOfType(this, JEditorPane::class.java)
          if (newEditor != null) {
            singleSelectionHandler.add(newEditor, true)
          }
        }

        private val mySizeCache = PreferredSizeCache { super.getPreferredSize() }

        override fun getPreferredSize(): Dimension {
          return mySizeCache.getSize(this)
        }

        override fun firePropertyChange(propertyName: String?, oldValue: Any?, newValue: Any?) {
          if (skipUpdateGraphicsConfiguration(propertyName, oldValue, newValue)) {
            return
          }
          super.firePropertyChange(propertyName, oldValue, newValue)
        }

        private fun skipUpdateGraphicsConfiguration(propertyName: String?, oldValue: Any?, newValue: Any?): Boolean {
          if ("graphicsConfiguration" == propertyName) {
            if (oldValue != null && newValue == null) {
              return true
            }
            if (oldValue == null && newValue != null) {
              val basicHtml = getClientProperty(BasicHTML.propertyKey)
              val accessibleName = getClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY)
              if (basicHtml != null && accessibleName != null) {
                putClientProperty(BasicHTML.propertyKey, basicHtml)
                putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, accessibleName)
                return true
              }
            }
          }
          return false
        }
      }
      title.verticalTextPosition = SwingConstants.TOP

      try {
        title.setCopyable(true)
      }
      catch (_: Exception) {
      }

      NotificationsManagerImpl.setTextAccessibleName(title, titleContent)

      val editor = UIUtil.findComponentOfType(title, JEditorPane::class.java)
      if (editor != null) {
        singleSelectionHandler.add(editor, true)
      }

      if (notification.isSuggestionType) {
        centerPanel.add(title)
      }
      else {
        titlePanel = JPanel(BorderLayout())
        titlePanel.isOpaque = false
        titlePanel.add(title)
        centerPanel.add(titlePanel)
      }
    }

    if (notification.hasContent()) {
      val textContent = NotificationsUtil.buildFullContent(notification)
      val textComponent = createTextComponent(textContent)

      NotificationsManagerImpl.setTextAccessibleName(textComponent, textContent)

      singleSelectionHandler.add(textComponent, true)

      if (!notification.hasTitle() && !notification.isSuggestionType) {
        titlePanel = JPanel(BorderLayout())
        titlePanel.isOpaque = false
        titlePanel.add(textComponent)
        centerPanel.add(titlePanel)
      }
      else {
        centerPanel.add(textComponent)
      }
    }

    val actions = notification.actions
    val actionsSize = actions.size
    val helpAction = notification.contextHelpAction

    if (actionsSize > 0 || helpAction != null) {
      val layout = HorizontalLayout(JBUIScale.scale(16))
      val actionPanel = JPanel(if (!notification.isSuggestionType && actions.size > 1) DropDownActionLayout(layout) else layout)
      actionPanel.isOpaque = false

      if (notification.isSuggestionType) {
        if (actionsSize > 0) {
          val button = JButton(actions[0].templateText)
          button.isOpaque = false
          button.addActionListener {
            runAction(actions[0], it.source)
          }
          actionPanel.add(button)

          if (actionsSize == 2) {
            actionPanel.add(createAction(actions[1]))
          }
          else if (actionsSize > 2) {
            if (notification.isAddExtraAction) {
              actionPanel.add(createAction(actions[1]))
              if (actionsSize == 3) {
                actionPanel.add(createAction(actions[2]))
              }
              else {
                actionPanel.add(MoreAction(this, actions, 2))
              }
            }
            else {
              actionPanel.add(MoreAction(this, actions, 1))
            }
          }
        }
      }
      else {
        if (actionsSize > 1 && notification.collapseDirection == Notification.CollapseActionsDirection.KEEP_RIGHTMOST) {
          actionPanel.add(MyDropDownAction(this))
        }

        for (action in actions) {
          actionPanel.add(createAction(action))
        }

        if (actionsSize > 1 && notification.collapseDirection == Notification.CollapseActionsDirection.KEEP_LEFTMOST) {
          actionPanel.add(MyDropDownAction(this))
        }
      }
      if (helpAction != null) {
        val presentation = helpAction.templatePresentation
        val helpLabel = ContextHelpLabel.create(StringUtil.defaultIfEmpty(presentation.text, ""), presentation.description)
        helpLabel.foreground = UIUtil.getLabelDisabledForeground()
        actionPanel.add(helpLabel)
      }
      if (!notification.hasTitle() && !notification.hasContent() && !notification.isSuggestionType) {
        titlePanel = JPanel(BorderLayout())
        titlePanel.isOpaque = false
        actionPanel.add(titlePanel, HorizontalLayout.RIGHT)
      }
      centerPanel.add(actionPanel)
    }

    add(UiDataProvider.wrapComponent(centerPanel) { sink ->
      sink[Notification.KEY] = notification
    })

    if (notification.isSuggestionType) {
      val button = createPopupAction(notification)
      button.border = JBUI.Borders.emptyRight(5)
      button.isVisible = false
      myMoreButton = button

      val eastPanel = JPanel(BorderLayout())
      eastPanel.isOpaque = false
      eastPanel.add(button, BorderLayout.NORTH)
      add(eastPanel, BorderLayout.EAST)
      setComponentZOrder(eastPanel, 0)
    }
    else {
      val timeComponent = JBLabel(DateFormatUtil.formatPrettyDateTime(notification.timestamp))
      timeComponent.putClientProperty(TIME_KEY, notification.timestamp)
      timeComponent.toolTipText = DateFormatUtil.formatDateTime(notification.timestamp)
      timeComponent.border = JBUI.Borders.emptyRight(10)
      timeComponent.smallFontFunction()
      timeComponent.foreground = INFO_COLOR

      timeComponents.add(timeComponent)

      if (NotificationsConfigurationImpl.getInstanceImpl().isRegistered(notification.groupId)) {
        val button = createPopupAction(notification)
        myMoreButton = button

        val buttonWrapper = JPanel(BorderLayout())
        buttonWrapper.isOpaque = false
        buttonWrapper.border = JBUI.Borders.emptyRight(10)
        buttonWrapper.add(button, BorderLayout.NORTH)
        buttonWrapper.preferredSize = buttonWrapper.preferredSize

        button.isVisible = false

        val timeWrapper = JPanel(BorderLayout())
        timeWrapper.isOpaque = false
        timeWrapper.border = JBUI.Borders.emptyTop(3)
        timeWrapper.add(timeComponent, BorderLayout.NORTH)

        val eastPanel = JPanel(BorderLayout())
        eastPanel.isOpaque = false
        eastPanel.add(buttonWrapper, BorderLayout.WEST)
        eastPanel.add(timeWrapper, BorderLayout.EAST)
        titlePanel?.add(eastPanel, BorderLayout.EAST)
      }
      else {
        titlePanel?.add(timeComponent, BorderLayout.EAST)
        myMoreButton = null
      }
    }
  }

  private class MyActionGroup : DefaultActionGroup(), TooltipDescriptionProvider {
    init {
      isPopup = true
    }
  }

  @Suppress("DialogTitleCapitalization")
  private fun createAction(action: AnAction): JComponent =
    object : LinkLabel<AnAction>(action.templateText, action.templatePresentation.icon, { lnk, act -> runAction(act, lnk) }, action) {
      override fun getTextColor() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    }

  private fun createPopupAction(notification: Notification): JComponent {
    val group = MyActionGroup()

    if (NotificationsConfigurationImpl.getInstanceImpl().isRegistered(notification.groupId)) {
      group.add(object : DumbAwareAction(IdeBundle.message("notification.settings.action.text")) {
        override fun actionPerformed(e: AnActionEvent) {
          doShowSettings()
        }
      })
      group.addSeparator()
    }

    if (notification.isSuggestionType) {
      val remindAction = RemindLaterManager.createAction(notification, 1.days)
      if (remindAction != null) {
        @Suppress("DialogTitleCapitalization")
        group.add(object : DumbAwareAction(IdeBundle.message("notifications.toolwindow.remind.tomorrow")) {
          override fun actionPerformed(e: AnActionEvent) {
            remindAction.run()
            myRemoveCallback.accept(myNotificationWrapper.notification!!)
            myNotificationWrapper.notification!!.hideBalloon()
          }
        })
      }
    }

    @Suppress("DialogTitleCapitalization")
    group.add(object : DumbAwareAction(IdeBundle.message("notifications.toolwindow.dont.show.again.for.this.project")) {
      override fun actionPerformed(e: AnActionEvent) {
        myDoNotAskHandler.invoke(true)
      }
    })
    @Suppress("DialogTitleCapitalization")
    group.add(object : DumbAwareAction(IdeBundle.message("notifications.toolwindow.dont.show.again")) {
      override fun actionPerformed(e: AnActionEvent) {
        myDoNotAskHandler.invoke(false)
      }
    })

    val presentation = Presentation()
    presentation.description = IdeBundle.message("tooltip.turn.notification.off")
    presentation.isPopupGroup = true
    presentation.icon = AllIcons.Actions.More
    presentation.putClientProperty(ActionUtil.HIDE_DROPDOWN_ICON, true)

    val button = object : ActionButton(group, presentation, ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
      override fun createAndShowActionGroupPopup(actionGroup: ActionGroup, event: AnActionEvent): JBPopup {
        myMorePopupVisible = true
        val popup = super.createAndShowActionGroupPopup(actionGroup, event)
        myMorePopup = popup
        popup.addListener(object : JBPopupListener {
          override fun onClosed(event: LightweightWindowEvent) {
            myMorePopup = null
            ApplicationManager.getApplication().invokeLater {
              myMorePopupVisible = false
              isVisible = myHoverState
            }
          }
        })
        return popup
      }
    }

    return button
  }

  private fun doShowSettings() {
    NotificationCollector.getInstance().logNotificationSettingsClicked(myNotificationWrapper.id, myNotificationWrapper.displayId,
                                                                       myNotificationWrapper.groupId)
    val configurable = NotificationsConfigurable()
    ShowSettingsUtil.getInstance().editConfigurable(project, configurable, Runnable {
      val runnable = configurable.enableSearch(myNotificationWrapper.groupId)
      runnable?.run()
    })
  }

  private fun runAction(action: AnAction, component: Any) {
    setNew(false)
    NotificationCollector.getInstance().logNotificationActionInvoked(null, myNotificationWrapper.notification!!, action,
                                                                     NotificationCollector.NotificationPlace.ACTION_CENTER)
    Notification.fire(myNotificationWrapper.notification!!, action, DataManager.getInstance().getDataContext(component as Component))
  }

  fun expire() {
    closePopups()
    myNotificationWrapper.notification = null
    setNew(false)

    for (component in UIUtil.findComponentsOfType(this, LinkLabel::class.java)) {
      component.isEnabled = false
    }

    if (myMoreButton != null) {
      myMoreButton.isVisible = false
    }
  }

  fun removeFromParent() {
    closePopups()
    for (component in UIUtil.findComponentsOfType(this, JTextComponent::class.java)) {
      singleSelectionHandler.remove(component)
    }
  }

  private fun closePopups() {
    myMorePopup?.cancel()
    myMoreAwtPopup?.isVisible = false
    myDropDownPopup?.isVisible = false
    popupAlarm.cancel()
  }

  private fun createTextComponent(text: @Nls String): JEditorPane {
    val component = object : JEditorPane() {
      val sizeCache = PreferredSizeCache { super.getPreferredSize() }

      override fun getPreferredSize(): Dimension {
        return sizeCache.getSize(this)
      }
    }
    component.isEditable = false
    component.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    component.contentType = "text/html"
    component.isOpaque = false
    component.border = null

    NotificationsUtil.configureHtmlEditorKit(component, false)

    if (myNotificationWrapper.notification!!.listener != null) {
      component.addHyperlinkListener { e ->
        val notification = myNotificationWrapper.notification
        if (notification != null && e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
          val listener = notification.listener
          if (listener != null) {
            NotificationCollector.getInstance().logHyperlinkClicked(notification)
            listener.hyperlinkUpdate(notification, e)
          }
        }
      }
    }

    component.putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, StringUtil.unescapeXmlEntities(StringUtil.stripHtml(text, " ")))

    component.text = text

    component.isEditable = false
    if (component.caret != null) {
      component.caretPosition = 0
    }

    myLafUpdater = Runnable {
      NotificationsUtil.configureHtmlEditorKit(component, false)
      component.sizeCache.clearCache()
      component.text = text
      component.revalidate()
      component.repaint()
    }

    return component
  }

  fun updateLaf() {
    myLafUpdater?.run()
    updateColor()
  }

  fun setDoNotAskHandler(handler: (Boolean) -> Unit) {
    myDoNotAskHandler = handler
  }

  fun setRemoveCallback(callback: Consumer<Notification>) {
    myRemoveCallback = callback
  }

  fun isHover(): Boolean = myHoverState

  fun setNew(state: Boolean) {
    if (myIsNew != state) {
      myIsNew = state
      updateColor()
    }
  }

  fun setHover(state: Boolean) {
    myHoverState = state
    if (myMoreButton != null) {
      if (!myMorePopupVisible) {
        myMoreButton.isVisible = state && myNotificationWrapper.notification != null
      }
    }
    updateColor()
  }

  private fun updateColor() {
    if (myHoverState) {
      if (myIsNew) {
        setColor(NEW_HOVER_COLOR)
      }
      else {
        setColor(HOVER_COLOR)
      }
    }
    else if (myIsNew) {
      if (UIManager.getColor(NEW_COLOR_NAME) != null) {
        setColor(NEW_COLOR)
      }
      else {
        setColor(NEW_DEFAULT_COLOR)
      }
    }
    else {
      setColor(BG_COLOR)
    }
  }

  private fun setColor(color: Color) {
    myRoundColor = color
    repaint()
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    if (myRoundColor !== BG_COLOR) {
      g.color = myRoundColor
      val config = GraphicsUtil.setupAAPainting(g)
      val cornerRadius = NotificationBalloonRoundShadowBorderProvider.CORNER_RADIUS.get()
      g.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius)
      config.restore()
    }
  }

  fun applySearchQuery(query: String?): Boolean {
    if (query == null) {
      isVisible = true
      return true
    }

    val result = matchQuery(query)
    isVisible = result
    return result
  }

  private fun matchQuery(query: @NlsSafe String): Boolean {
    if (myNotificationWrapper.title.contains(query, true)) {
      return true
    }
    val subtitle = myNotificationWrapper.subtitle
    if (subtitle != null && subtitle.contains(query, true)) {
      return true
    }
    if (myNotificationWrapper.content.contains(query, true)) {
      return true
    }
    for (action in myNotificationWrapper.actions) {
      if (action != null && action.contains(query, true)) {
        return true
      }
    }
    return false
  }
}

private class PreferredSizeCache(private val mySuperSize: () -> Dimension) {
  private var myCachedSize: Dimension? = null
  private var myCachedWidth = -1
  private var myCachedHeight = -1

  fun getSize(component: Component): Dimension {
    if (myCachedSize == null || myCachedWidth != component.width || myCachedHeight != component.height) {
      myCachedSize = mySuperSize.invoke()
      myCachedWidth = component.width
      myCachedHeight = component.height
    }
    return myCachedSize!!
  }

  fun clearCache() {
    myCachedSize = null
  }
}

private class MoreAction(val notificationComponent: NotificationComponent, actions: List<AnAction>, startIndex: Int) :
  NotificationsManagerImpl.DropDownAction(null, null) {
  val group = DefaultActionGroup()

  init {
    val size = actions.size
    for (i in startIndex..<size) {
      group.add(actions[i])
    }

    setListener(LinkListener { link, _ ->
      if (notificationComponent.myMoreAwtPopup != null) {
        notificationComponent.myMoreAwtPopup!!.isVisible = false
        notificationComponent.myMoreAwtPopup = null
        return@LinkListener
      }

      notificationComponent.popupAlarm.cancel()

      val popup = NotificationsManagerImpl.showPopup(link, group)
      notificationComponent.myMoreAwtPopup = popup
      popup?.addPopupMenuListener(object : PopupMenuListenerAdapter() {
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
          notificationComponent.popupAlarm.request(500) { notificationComponent.myMoreAwtPopup = null }
        }
      })
    }, null)

    text = IdeCoreBundle.message("notifications.action.more")
  }

  override fun getTextColor() = JBUI.CurrentTheme.Link.Foreground.ENABLED
}

private class MyDropDownAction(val notificationComponent: NotificationComponent) : NotificationsManagerImpl.DropDownAction(null, null) {
  var collapseActionsDirection: Notification.CollapseActionsDirection = notificationComponent.myNotificationWrapper.notification!!.collapseDirection

  init {
    setListener(LinkListener { link, _ ->
      if (notificationComponent.myDropDownPopup != null) {
        notificationComponent.myDropDownPopup!!.isVisible = false
        notificationComponent.myDropDownPopup = null
        return@LinkListener
      }

      val group = DefaultActionGroup()
      val layout = link.parent.layout as DropDownActionLayout

      for (action in layout.actions) {
        if (!action.isVisible) {
          group.add(action.linkData)
        }
      }

      notificationComponent.popupAlarm.cancel()

      val popup = NotificationsManagerImpl.showPopup(link, group)
      notificationComponent.myDropDownPopup = popup
      popup?.addPopupMenuListener(object : PopupMenuListenerAdapter() {
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
          notificationComponent.popupAlarm.request(500) { notificationComponent.myDropDownPopup = null }
        }
      })
    }, null)

    text = notificationComponent.myNotificationWrapper.notification!!.dropDownText
    isVisible = false
  }

  override fun getTextColor() = JBUI.CurrentTheme.Link.Foreground.ENABLED
}

private class NotificationWrapper(notification: Notification) {
  val title = notification.title
  val subtitle = notification.subtitle
  val content = notification.content
  val id = notification.id
  val displayId = notification.displayId
  val groupId = notification.groupId
  val actions: List<String?> = notification.actions.map { it.templateText }
  var notification: Notification? = notification
}

private class DropDownActionLayout(layout: LayoutManager2) : FinalLayoutWrapper(layout) {
  val actions = ArrayList<LinkLabel<AnAction>>()
  private lateinit var myDropDownAction: MyDropDownAction

  override fun addLayoutComponent(comp: Component, constraints: Any?) {
    super.addLayoutComponent(comp, constraints)
    add(comp)
  }

  override fun addLayoutComponent(name: String?, comp: Component) {
    super.addLayoutComponent(name, comp)
    add(comp)
  }

  private fun add(component: Component) {
    if (component is MyDropDownAction) {
      myDropDownAction = component
    }
    else if (component is LinkLabel<*>) {
      @Suppress("UNCHECKED_CAST")
      actions.add(component as LinkLabel<AnAction>)
    }
  }

  override fun layoutContainer(parent: Container) {
    val width = parent.width

    myDropDownAction.isVisible = false
    for (action in actions) {
      action.isVisible = true
    }
    layout.layoutContainer(parent)

    val keepRightmost = myDropDownAction.collapseActionsDirection == Notification.CollapseActionsDirection.KEEP_RIGHTMOST
    val collapseStart = if (keepRightmost) 0 else actions.size - 1
    val collapseDelta = if (keepRightmost) 1 else -1
    var collapseIndex = collapseStart

    if (parent.preferredSize.width > width) {
      myDropDownAction.isVisible = true
      actions[collapseIndex].isVisible = false
      collapseIndex += collapseDelta
      layout.layoutContainer(parent)

      while (parent.preferredSize.width > width && collapseIndex >= 0 && collapseIndex < actions.size) {
        actions[collapseIndex].isVisible = false
        collapseIndex += collapseDelta
        layout.layoutContainer(parent)
      }
    }
  }
}

private class ComponentEventHandler {
  private var myHoverComponent: NotificationComponent? = null

  private val myMouseHandler = object : MouseAdapter() {
    override fun mouseMoved(e: MouseEvent) {
      if (myHoverComponent == null) {
        val component = ComponentUtil.getParentOfType(NotificationComponent::class.java, e.component)
        if (component != null) {
          if (!component.isHover()) {
            component.setHover(true)
          }
          myHoverComponent = component
        }
      }
    }

    override fun mouseExited(e: MouseEvent) {
      if (myHoverComponent != null) {
        val component = myHoverComponent!!
        if (component.isHover()) {
          component.setHover(false)
        }
        myHoverComponent = null
      }
    }
  }

  fun add(component: Component) {
    addAll(component) { c ->
      c.addMouseListener(myMouseHandler)
      c.addMouseMotionListener(myMouseHandler)
    }
  }

  private fun addAll(component: Component, listener: (Component) -> Unit) {
    listener.invoke(component)

    if (component is JBOptionButton) {
      component.addContainerListener(object : ContainerAdapter() {
        override fun componentAdded(e: ContainerEvent) {
          addAll(e.child, listener)
        }
      })
    }

    for (child in UIUtil.uiChildren(component)) {
      addAll(child, listener)
    }
  }
}