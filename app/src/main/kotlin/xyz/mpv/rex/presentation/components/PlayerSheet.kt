@file:Suppress("DEPRECATION")

package xyz.mpv.rex.presentation.components

import android.annotation.SuppressLint
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val sheetAnimationSpec = tween<Float>(350)

private const val DIM_ALPHA = 0.5f

// Side panel (landscape) metrics
/** Panel width. A smaller `customMaxWidth` from a call site wins; never more than 55% of the screen. */
private val SIDE_PANEL_WIDTH = 400.dp
private const val SIDE_PANEL_MAX_SCREEN_FRACTION = 0.55f
private val SIDE_PANEL_HANDLE_WIDTH = 22.dp
private val SIDE_PANEL_HANDLE_HEIGHT = 120.dp
private val SIDE_PANEL_CONTENT_PADDING_TOP = 16.dp
private val SIDE_PANEL_CONTENT_PADDING_BOTTOM = 12.dp
private const val SIDE_PANEL_SURFACE_ALPHA = 0.94f

/**
 * Scales dp AND text inside the landscape panel. 1.0 = no change.
 * Try 0.9 if the content still feels oversized (touch targets shrink by the same ratio).
 */
private const val SIDE_PANEL_CONTENT_SCALE = 1.0f

/**
 * Adaptive container used by every in-player menu.
 *
 * - Portrait: classic bottom sheet (dimmed background, swipe down to dismiss).
 * - Landscape: side panel pinned to the physical RIGHT edge of the screen, regardless of the
 *   app language / layout direction (same convention as VLC, MX Player, etc.). The panel frame is
 *   forced to LTR so it always slides in from the right, while the panel CONTENT keeps the
 *   original layout direction, so Arabic text and icons stay correctly mirrored inside it.
 *
 * Call sites do not need to change. The optional [dimBackground] parameter lets a menu choose
 * whether to dim the video: `null` = automatic (dim in portrait, no dim in landscape so the user
 * can see the effect of a setting while changing it).
 *
 * The panel wraps its content, is 400-420dp wide and renders content slightly compact.
 * Landscape dismissal: tap outside, system back, or drag the handle on the panel's left edge.
 * Dragging is deliberately limited to that handle so it never conflicts with horizontal pagers
 * (e.g. the tabs in MoreSheet) or sliders inside the content.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PlayerSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  tonalElevation: Dp = 1.dp,
  customMaxWidth: Dp? = null,
  customMaxHeight: Dp? = null,
  surfaceColor: Color? = null,
  dimBackground: Boolean? = null,
  content: @Composable () -> Unit,
) {
  val isLandscape = LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE
  if (isLandscape) {
    SidePanelSheet(
      onDismissRequest = onDismissRequest,
      modifier = modifier,
      tonalElevation = tonalElevation,
      customMaxWidth = customMaxWidth,
      surfaceColor = surfaceColor,
      dimBackground = dimBackground ?: false,
      content = content,
    )
  } else {
    BottomSheetLayout(
      onDismissRequest = onDismissRequest,
      modifier = modifier,
      tonalElevation = tonalElevation,
      customMaxWidth = customMaxWidth,
      customMaxHeight = customMaxHeight,
      surfaceColor = surfaceColor,
      dimBackground = dimBackground ?: true,
      content = content,
    )
  }
}

// ---------------------------------------------------------------------------------------------
// Landscape: side panel pinned to the right edge
// ---------------------------------------------------------------------------------------------

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun SidePanelSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier,
  tonalElevation: Dp,
  customMaxWidth: Dp?,
  surfaceColor: Color?,
  dimBackground: Boolean,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val configuration = LocalConfiguration.current
  val contentLayoutDirection = LocalLayoutDirection.current
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)

  val requestedWidth =
    if (customMaxWidth != null && customMaxWidth < SIDE_PANEL_WIDTH) customMaxWidth else SIDE_PANEL_WIDTH
  val panelWidth = requestedWidth.coerceAtMost(configuration.screenWidthDp.dp * SIDE_PANEL_MAX_SCREEN_FRACTION)
  val panelMaxHeight = configuration.screenHeightDp.dp

  // The surface colour is translucent, so Surface can't derive a content colour from it
  // (it would fall back to LocalContentColor = black). Set it explicitly.
  val containerColor =
    surfaceColor ?: MaterialTheme.colorScheme.surfaceContainer.copy(alpha = SIDE_PANEL_SURFACE_ALPHA)
  val onContainerColor =
    if (surfaceColor != null) contentColorFor(surfaceColor) else MaterialTheme.colorScheme.onSurface

  val scaledDensity =
    remember(density) {
      Density(
        density = density.density * SIDE_PANEL_CONTENT_SCALE,
        fontScale = density.fontScale * SIDE_PANEL_CONTENT_SCALE,
      )
    }

  var backgroundAlpha by remember { mutableFloatStateOf(0f) }
  val alpha by animateFloatAsState(
    backgroundAlpha,
    animationSpec = sheetAnimationSpec,
    label = "alpha",
  )

  val decayAnimationSpec = rememberSplineBasedDecay<Float>()
  val anchoredDraggableState =
    remember {
      AnchoredDraggableState(
        initialValue = 1,
        snapAnimationSpec = sheetAnimationSpec,
        decayAnimationSpec = decayAnimationSpec,
        positionalThreshold = { with(density) { 56.dp.toPx() } },
        velocityThreshold = { with(density) { 125.dp.toPx() } },
      )
    }

  val dismissWithAnimation: () -> Unit = {
    if (anchoredDraggableState.currentValue == 0) {
      scope.launch {
        backgroundAlpha = 0f
        anchoredDraggableState.animateTo(1)
      }
    }
  }

  // Force LTR for the frame so "End" is always the physical right edge.
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha))
          // Tap outside the panel closes it (transparent layer when dimming is off).
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = dismissWithAnimation,
          ),
      contentAlignment = Alignment.CenterEnd,
    ) {
      Surface(
        modifier =
          Modifier
            .width(panelWidth)
            .heightIn(max = panelMaxHeight)
            .onSizeChanged {
              val anchors =
                DraggableAnchors {
                  0 at 0f
                  1 at it.width.toFloat()
                }
              anchoredDraggableState.updateAnchors(anchors)
            }.clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = {}, // swallow taps so they don't reach the dismiss layer
            ).then(modifier)
            .offset {
              IntOffset(
                anchoredDraggableState.offset
                  .takeIf { it.isFinite() }
                  ?.roundToInt()
                  ?: 0,
                0,
              )
            }.windowInsetsPadding(
              WindowInsets.systemBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
            ).imePadding(),
        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
        color = containerColor,
        contentColor = onContainerColor,
        tonalElevation = tonalElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        content = {
          BackHandler(
            enabled = anchoredDraggableState.targetValue == 0,
            onBack = dismissWithAnimation,
          )
          Box {
            // Content wraps its own height (up to the screen height), so short menus
            // don't leave dead space below them.
            Box(
              modifier =
                Modifier.padding(
                  start = SIDE_PANEL_HANDLE_WIDTH,
                  top = SIDE_PANEL_CONTENT_PADDING_TOP,
                  bottom = SIDE_PANEL_CONTENT_PADDING_BOTTOM,
                ),
            ) {
              // Restore the app's real layout direction (RTL for Arabic) for the content.
              CompositionLocalProvider(
                LocalLayoutDirection provides contentLayoutDirection,
                LocalDensity provides scaledDensity,
              ) {
                content()
              }
            }

            // Drag handle on the left edge: the only area that drags the panel away, so it
            // never conflicts with horizontal pagers or sliders inside the content.
            Box(
              modifier =
                Modifier
                  .align(Alignment.CenterStart)
                  .width(SIDE_PANEL_HANDLE_WIDTH)
                  .height(SIDE_PANEL_HANDLE_HEIGHT)
                  .anchoredDraggable(
                    state = anchoredDraggableState,
                    orientation = Orientation.Horizontal,
                  ),
              contentAlignment = Alignment.Center,
            ) {
              Box(
                modifier =
                  Modifier
                    .size(width = 4.dp, height = 36.dp)
                    .background(
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                      shape = xyz.mpv.rex.ui.theme.pillShape,
                    ),
              )
            }
          }
        },
      )

      LaunchedEffect(true) {
        backgroundAlpha = if (dimBackground) DIM_ALPHA else 0f
      }

      LaunchedEffect(anchoredDraggableState) {
        scope.launch { anchoredDraggableState.animateTo(0) }
        snapshotFlow { anchoredDraggableState.currentValue }
          .drop(1)
          .filter { it == 1 }
          .collectLatest { latestOnDismissRequest() }
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Portrait: original bottom sheet (unchanged behavior)
// ---------------------------------------------------------------------------------------------

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun BottomSheetLayout(
  onDismissRequest: () -> Unit,
  modifier: Modifier,
  tonalElevation: Dp,
  customMaxWidth: Dp?,
  customMaxHeight: Dp?,
  surfaceColor: Color?,
  dimBackground: Boolean,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
  val maxWidth = customMaxWidth ?:
  if (LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE) {
    640.dp
  } else {
    420.dp
  }
  val isImeVisible = WindowInsets.ime.getBottom(density) > 0
  val maxHeight = customMaxHeight ?: when {
    isImeVisible -> LocalConfiguration.current.screenHeightDp.dp
    LocalConfiguration.current.orientation == ORIENTATION_PORTRAIT ->
      LocalConfiguration.current.screenHeightDp.dp * .90f
    else -> LocalConfiguration.current.screenHeightDp.dp
  }

  var backgroundAlpha by remember { mutableFloatStateOf(0f) }
  val alpha by animateFloatAsState(
    backgroundAlpha,
    animationSpec = sheetAnimationSpec,
    label = "alpha",
  )

  val decayAnimationSpec = rememberSplineBasedDecay<Float>()
  val anchoredDraggableState =
    remember {
      AnchoredDraggableState(
        initialValue = 1,
        snapAnimationSpec = sheetAnimationSpec,
        decayAnimationSpec = decayAnimationSpec,
        positionalThreshold = { with(density) { 56.dp.toPx() } },
        velocityThreshold = { with(density) { 125.dp.toPx() } },
      )
    }
  val internalOnDismissRequest = {
    if (anchoredDraggableState.currentValue == 0) {
      scope.launch {
        backgroundAlpha = 0f
        anchoredDraggableState.animateTo(1)
      }
    }
  }
  Box(
    modifier =
      Modifier
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = internalOnDismissRequest,
        ).fillMaxSize()
        .background(Color.Black.copy(alpha))
        .onSizeChanged {
          val anchors =
            DraggableAnchors {
              0 at 0f
              1 at it.height.toFloat()
            }
          anchoredDraggableState.updateAnchors(anchors)
        },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      modifier =
        Modifier
          .sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ).nestedScroll(
            remember(anchoredDraggableState) {
              anchoredDraggableState.preUpPostDownNestedScrollConnection()
            },
          ).then(modifier)
          .offset {
            IntOffset(
              0,
              anchoredDraggableState.offset
                .takeIf { it.isFinite() }
                ?.roundToInt()
                ?: 0,
            )
          }.anchoredDraggable(
            state = anchoredDraggableState,
            orientation = Orientation.Vertical,
          ).windowInsetsPadding(
            WindowInsets.systemBars
              .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
          ).imePadding(),
      shape = MaterialTheme.shapes.extraLargeIncreased.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize),
      color = surfaceColor ?: MaterialTheme.colorScheme.surfaceContainerLow,
      tonalElevation = tonalElevation,
      content = {
        BackHandler(
          enabled = anchoredDraggableState.targetValue == 0,
          onBack = internalOnDismissRequest,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = xyz.mpv.rex.ui.theme.pillShape
                    )
            )
            content()
        }
      },
    )

    LaunchedEffect(true) {
      backgroundAlpha = if (dimBackground) DIM_ALPHA else 0f
    }

    LaunchedEffect(anchoredDraggableState) {
      scope.launch { anchoredDraggableState.animateTo(0) }
      snapshotFlow { anchoredDraggableState.currentValue }
        .drop(1)
        .filter { it == 1 }
        .collectLatest { latestOnDismissRequest() }
    }
  }
}

private fun <T> AnchoredDraggableState<T>.preUpPostDownNestedScrollConnection() =
  object : NestedScrollConnection {
    override fun onPreScroll(
      available: Offset,
      source: NestedScrollSource,
    ): Offset {
      val delta = available.toFloat()
      return if (delta < 0 && source == NestedScrollSource.UserInput) {
        dispatchRawDelta(delta).toOffset()
      } else {
        Offset.Zero
      }
    }

    override fun onPostScroll(
      consumed: Offset,
      available: Offset,
      source: NestedScrollSource,
    ): Offset =
      if (source == NestedScrollSource.UserInput) {
        dispatchRawDelta(available.toFloat()).toOffset()
      } else {
        Offset.Zero
      }

    override suspend fun onPreFling(available: Velocity): Velocity {
      val toFling = available.toFloat()
      return if (toFling < 0 && offset > anchors.minPosition()) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    override suspend fun onPostFling(
      consumed: Velocity,
      available: Velocity,
    ): Velocity {
      val toFling = available.toFloat()
      return if (toFling > 0) {
        settle(toFling)
        available
      } else {
        Velocity.Zero
      }
    }

    private fun Float.toOffset(): Offset = Offset(0f, this)

    @JvmName("velocityToFloat")
    private fun Velocity.toFloat() = y

    private fun Offset.toFloat(): Float = y
  }
