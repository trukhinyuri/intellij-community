// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.hints.presentation

import com.intellij.codeInsight.hints.InlayPresentationFactory
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.IconUtil
import org.jetbrains.annotations.ApiStatus
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.Icon
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

/**
 * Presentation factory, which handles presentation mode and scales Icons and Insets
 */
class ScaleAwarePresentationFactory(
  val editor: Editor,
  private val delegate: PresentationFactory
) : InlayPresentationFactory by delegate {
  fun lineCentered(presentation: InlayPresentation): InlayPresentation {
    return LineCenteredInset(presentation = presentation, editor = editor)
  }

  override fun container(presentation: InlayPresentation,
                         padding: InlayPresentationFactory.Padding?,
                         roundedCorners: InlayPresentationFactory.RoundedCorners?,
                         background: Color?,
                         backgroundAlpha: Float): InlayPresentation {
    return ScaledContainerPresentation(presentation = presentation,
                                       editor = editor,
                                       padding = padding,
                                       roundedCorners = roundedCorners,
                                       background = background,
                                       backgroundAlpha = backgroundAlpha)
  }

  override fun icon(icon: Icon): InlayPresentation {
    return ScaleAwareIconPresentation(icon = icon, editor = editor, fontShift = 0)
  }

  fun icon(icon: Icon, debugName: String, fontShift: Int): InlayPresentation {
    return ScaleAwareIconPresentation(icon = icon, editor = editor, debugName = debugName, fontShift = fontShift)
  }

  fun inset(base: InlayPresentation, left: Int = 0, right: Int = 0, top: Int = 0, down: Int = 0): InlayPresentation {
    return ScaledInsetPresentation(
      presentation = base,
      left = left,
      right = right,
      top = top,
      down = down,
      editor = editor
    )
  }

  fun seq(vararg presentations: InlayPresentation): InlayPresentation = delegate.seq(*presentations)
}

@ApiStatus.Internal
class ScaledInsetPresentation(
  val presentation: InlayPresentation,
  val left: Int,
  val right: Int,
  val top: Int,
  val down: Int,
  val editor: Editor
) : ScaledDelegatedPresentation() {
  override val delegate: InsetPresentation by valueOf<InsetPresentation, Float> { fontSize ->
    InsetPresentation(
      presentation = presentation,
      left = scaleByFont(left, fontSize),
      right = scaleByFont(right, fontSize),
      top = scaleByFont(top, fontSize),
      down = scaleByFont(down, fontSize)
    )
  }.withState {
    editor.colorsScheme.editorFontSize2D
  }
}

@ApiStatus.Internal
class ScaledContainerPresentation(
  val presentation: InlayPresentation,
  val editor: Editor,
  val padding: InlayPresentationFactory.Padding? = null,
  val roundedCorners: InlayPresentationFactory.RoundedCorners? = null,
  val background: Color? = null,
  val backgroundAlpha: Float = 0.55f
) : ScaledDelegatedPresentation() {
  override val delegate: ContainerInlayPresentation by valueOf<ContainerInlayPresentation, Float> { fontSize ->
    ContainerInlayPresentation(
      presentation = presentation,
      padding = scaleByFont(padding, fontSize),
      roundedCorners = scaleByFont(roundedCorners, fontSize),
      background = background,
      backgroundAlpha = backgroundAlpha
    )
  }.withState {
    editor.colorsScheme.editorFontSize2D
  }
}

@ApiStatus.Internal
abstract class ScaledDelegatedPresentation : BasePresentation() {
  protected abstract val delegate: InlayPresentation

  override val width: Int
    get() = delegate.width

  override val height: Int
    get() = delegate.height

  override fun paint(g: Graphics2D, attributes: TextAttributes) {
    delegate.paint(g, attributes)
  }

  override fun toString(): String = delegate.toString()

  override fun mouseClicked(event: MouseEvent, translated: Point) {
    delegate.mouseClicked(event, translated)
  }

  override fun mouseMoved(event: MouseEvent, translated: Point) {
    delegate.mouseMoved(event, translated)
  }

  override fun mouseExited() {
    delegate.mouseExited()
  }
}

@ApiStatus.Internal
class LineCenteredInset(
  val presentation: InlayPresentation,
  val editor: Editor
) : ScaledDelegatedPresentation() {
  override val delegate: InlayPresentation by valueOf<InlayPresentation, Pair<Int, Int>> { (lineHeight, innerHeight) ->
    InsetPresentation(presentation = presentation, top = (lineHeight - innerHeight) / 2)
  }.withState {
    editor.lineHeight to presentation.height
  }
}

@ApiStatus.Internal
class ScaleAwareIconPresentation(val icon: Icon,
                                 private val editor: Editor,
                                 private val debugName: String = "image",
                                 val fontShift: Int) : BasePresentation() {
  override val width: Int
    get() = scaledIcon.iconWidth
  override val height: Int
    get() = scaledIcon.iconHeight

  override fun paint(g: Graphics2D, attributes: TextAttributes) {
    val graphics = g.create() as Graphics2D
    graphics.composite = AlphaComposite.SrcAtop.derive(1.0f)
    scaledIcon.paintIcon(editor.component, graphics, 0, 0)
    graphics.dispose()
  }

  override fun toString(): String = "<$debugName>"

  private val scaledIcon by valueOf<Icon, IconParameters> { (fontSize, scale) ->
    IconUtil.scaleByFont(icon = icon, ancestor = editor.component, fontSize = fontSize / scale)
  }.withState {
    IconParameters(editor.colorsScheme.editorFontSize2D - fontShift)
  }

  private data class IconParameters(val font: Float, val scale: Float = UISettings.getInstance().currentIdeScale)
}

private class StateDependantValue<TData : Any, TState : Any>(
  private val valueProvider: (TState) -> TData,
  private val stateProvider: () -> TState
) {
  private var currentState: TState? = null
  private lateinit var cachedValue: TData

  operator fun getValue(thisRef: Any?, property: KProperty<*>): TData {
    val state = stateProvider()
    if (state != currentState) {
      currentState = state
      cachedValue = valueProvider(state)
    }
    return cachedValue
  }
}

private fun <TData : Any, TState : Any> valueOf(dataProvider: (TState) -> TData): StateDependantValueBuilder<TData, TState> {
  return StateDependantValueBuilder(dataProvider)
}

private class StateDependantValueBuilder<TData : Any, TState : Any>(private val dataProvider: (TState) -> TData) {
  fun withState(stateProvider: () -> TState): StateDependantValue<TData, TState> {
    return StateDependantValue(dataProvider, stateProvider)
  }
}

fun scaleByFont(sizeFor12: Int, fontSize: Float): Int = (JBUIScale.getFontScale(fontSize) * sizeFor12).roundToInt()

private fun scaleByFont(paddingFor12: InlayPresentationFactory.Padding?, fontSize: Float): InlayPresentationFactory.Padding? {
  return paddingFor12?.let { (left, right, top, bottom) ->
    InlayPresentationFactory.Padding(
      left = scaleByFont(left, fontSize),
      right = scaleByFont(right, fontSize),
      top = scaleByFont(top, fontSize),
      bottom = scaleByFont(bottom, fontSize)
    )
  }
}

private fun scaleByFont(roundedCornersFor12: InlayPresentationFactory.RoundedCorners?, fontSize: Float): InlayPresentationFactory.RoundedCorners? {
  return roundedCornersFor12?.let { (arcWidth, arcHeight) ->
    InlayPresentationFactory.RoundedCorners(
      arcWidth = scaleByFont(arcWidth, fontSize),
      arcHeight = scaleByFont(arcHeight, fontSize)
    )
  }
}
