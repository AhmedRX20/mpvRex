@file:Suppress("DEPRECATION")

package xyz.mpv.rex.presentation.components

import android.annotation.SuppressLint
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun PlayerSheet(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  tonalElevation: Dp = 1.dp,
  customMaxWidth: Dp? = null,
  customMaxHeight: Dp? = null,
  surfaceColor: Color? = null,
  // Landscape: slide in from the screen's end edge (right in LTR, left in RTL) so the video stays visible.
  // Portrait: keep the classic bottom sheet, a side panel would cover most of a phone-width screen.
  asSideSheet: Boolean = LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE,
  content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
  val maxWidth = customMaxWidth ?: when {
    asSideSheet -> 400.dp
    else -> 420.dp
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
          // Distance to travel off-screen: the full height (bottom sheet) or the full width (side sheet).
          val distance = if (asSideSheet) it.width.toFloat() else it.height.toFloat()
          val anchors =
            DraggableAnchors {
              0 at 0f
              1 at distance
            }
          anchoredDraggableState.updateAnchors(anchors)
        },
    contentAlignment = if (asSideSheet) Alignment.CenterEnd else Alignment.BottomCenter,
  ) {
    val nestedScrollConnection =
      remember(anchoredDraggableState) {
        anchoredDraggableState.preUpPostDownNestedScrollConnection()
      }
    val surfaceBase = surfaceColor ?: MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
      modifier =
        Modifier
          .then(
            if (asSideSheet) {
              Modifier.fillMaxHeight().sizeIn(maxWidth = maxWidth)
            } else {
              Modifier.sizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
            },
          ).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
          ).then(
            // The vertical nested-scroll hand-off only makes sense for the bottom sheet.
            if (asSideSheet) Modifier else Modifier.nestedScroll(nestedScrollConnection),
          ).then(modifier)
          .offset {
            // offset {} is layout-direction aware: a positive x moves toward the END edge,
            // which is exactly the direction the side sheet leaves the screen in LTR and RTL.
            val px = anchoredDraggableState.offset.takeIf { it.isFinite() }?.roundToInt() ?: 0
            if (asSideSheet) IntOffset(px, 0) else IntOffset(0, px)
          }.anchoredDraggable(
            state = anchoredDraggableState,
            orientation = if (asSideSheet) Orientation.Horizontal else Orientation.Vertical,
            // Dragging is in absolute screen coordinates, so flip it in RTL for the side sheet.
            reverseDirection = asSideSheet && isRtl,
          ).windowInsetsPadding(
            if (asSideSheet) {
              WindowInsets.systemBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
            } else {
              WindowInsets.systemBars
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            },
          ).imePadding(),
      shape =
        if (asSideSheet) {
          MaterialTheme.shapes.extraLargeIncreased.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
        } else {
          MaterialTheme.shapes.extraLargeIncreased.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize)
        },
      // Slightly translucent in side mode so the video still reads through the panel (glass look).
      color = if (asSideSheet) surfaceBase.copy(alpha = 0.92f) else surfaceBase,
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
            if (asSideSheet) {
              Spacer(modifier = Modifier.height(12.dp))
            } else {
              Box(
                  modifier = Modifier
                      .padding(vertical = 12.dp)
                      .size(width = 36.dp, height = 4.dp)
                      .background(
                          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                          shape = xyz.mpv.rex.ui.theme.pillShape
                      )
              )
            }
            content()
        }
      },
    )

    LaunchedEffect(true) {
      // Lighter scrim for the side sheet: the video should stay visible while you adjust things.
      backgroundAlpha = if (asSideSheet) 0.25f else 0.5f
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
