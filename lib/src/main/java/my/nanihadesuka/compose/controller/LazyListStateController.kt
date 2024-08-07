package my.nanihadesuka.compose.controller

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import my.nanihadesuka.compose.ScrollbarSelectionMode
import kotlin.math.floor

@Composable
internal fun rememberLazyListStateController(
    state: LazyListState,
    thumbMinLength: Float,
    thumbMaxLength: Float,
    alwaysShowScrollBar: Boolean,
    selectionMode: ScrollbarSelectionMode
): LazyListStateController {
    val coroutineScope = rememberCoroutineScope()

    val thumbMinLengthUpdated = rememberUpdatedState(thumbMinLength)
    val thumbMaxLengthUpdated = rememberUpdatedState(thumbMaxLength)
    val alwaysShowScrollBarUpdated = rememberUpdatedState(alwaysShowScrollBar)
    val selectionModeUpdated = rememberUpdatedState(selectionMode)
    val reverseLayout = remember { derivedStateOf { state.layoutInfo.reverseLayout } }

    val isSelected = remember { mutableStateOf(false) }
    val dragOffset = remember { mutableFloatStateOf(0f) }

    val realFirstVisibleItem = remember {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.firstOrNull {
                it.index == state.firstVisibleItemIndex
            }
        }
    }

    val isStickyHeaderInAction = remember {
        derivedStateOf {
            val realIndex = realFirstVisibleItem.value?.index ?: return@derivedStateOf false
            val firstVisibleIndex = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                ?: return@derivedStateOf false
            realIndex != firstVisibleIndex
        }
    }

    fun LazyListItemInfo.fractionHiddenTop(firstItemOffset: Int) =
        if (size == 0) 0f else firstItemOffset / size.toFloat()

    fun LazyListItemInfo.fractionVisibleBottom(viewportEndOffset: Int) =
        if (size == 0) 0f else (viewportEndOffset - offset).toFloat() / size.toFloat()

    val thumbSizeNormalizedReal = remember {
        derivedStateOf {
            state.layoutInfo.let {
                if (it.totalItemsCount == 0)
                    return@let 0f

                val firstItem = realFirstVisibleItem.value ?: return@let 0f
                val firstPartial =
                    firstItem.fractionHiddenTop(state.firstVisibleItemScrollOffset)
                val lastPartial = 1f - it.visibleItemsInfo.last().fractionVisibleBottom(
                    it.viewportEndOffset - it.afterContentPadding
                )

                val realSize = it.visibleItemsInfo.size - if (isStickyHeaderInAction.safeValue) 1 else 0
                val realVisibleSize = realSize.toFloat() - firstPartial - lastPartial
                realVisibleSize / it.totalItemsCount.toFloat()
            }
        }
    }

    val thumbSizeNormalized = remember {
        derivedStateOf {
            thumbSizeNormalizedReal.safeValue.coerceIn(
                thumbMinLengthUpdated.safeValue,
                thumbMaxLengthUpdated.safeValue,
            )
        }
    }

    fun offsetCorrection(top: Float): Float {
        val topRealMax = (1f - thumbSizeNormalizedReal.safeValue).coerceIn(0f, 1f)
        if (thumbSizeNormalizedReal.safeValue >= thumbMinLengthUpdated.safeValue) {
            return when {
                reverseLayout.safeValue -> topRealMax - top
                else -> top
            }
        }

        val topMax = 1f - thumbMinLengthUpdated.safeValue
        return when {
            reverseLayout.safeValue -> (topRealMax - top) * topMax / topRealMax
            else -> top * topMax / topRealMax
        }
    }

    val thumbOffsetNormalized = remember {
        derivedStateOf {
            state.layoutInfo.let {
                if (it.totalItemsCount == 0 || it.visibleItemsInfo.isEmpty())
                    return@let 0f

                val firstItem = realFirstVisibleItem.value ?: return@let 0f
                val top = firstItem
                    .run { index.toFloat() + fractionHiddenTop(state.firstVisibleItemScrollOffset) } / it.totalItemsCount.toFloat()
                offsetCorrection(top)
            }
        }
    }

    val thumbIsInAction = remember {
        derivedStateOf { state.isScrollInProgress || isSelected.safeValue || alwaysShowScrollBarUpdated.safeValue }
    }

    return remember {
        LazyListStateController(
            thumbSizeNormalized = thumbSizeNormalized,
            thumbSizeNormalizedReal = thumbSizeNormalizedReal,
            thumbOffsetNormalized = thumbOffsetNormalized,
            thumbIsInAction = thumbIsInAction,
            _isSelected = isSelected,
            dragOffset = dragOffset,
            selectionMode = selectionModeUpdated,
            realFirstVisibleItem = realFirstVisibleItem,
            reverseLayout = reverseLayout,
            thumbMinLength = thumbMinLengthUpdated,
            coroutineScope = coroutineScope,
            state = state,
        )
    }
}

internal class LazyListStateController(
    override val thumbSizeNormalized: State<Float>,
    override val thumbOffsetNormalized: State<Float>,
    override val thumbIsInAction: State<Boolean>,
    private val _isSelected: MutableState<Boolean>,
    private val dragOffset: MutableFloatState,
    private val thumbSizeNormalizedReal: State<Float>,
    private val realFirstVisibleItem: State<LazyListItemInfo?>,
    private val selectionMode: State<ScrollbarSelectionMode>,
    private val reverseLayout: State<Boolean>,
    private val thumbMinLength: State<Float>,
    private val state: LazyListState,
    private val coroutineScope: CoroutineScope,
) : StateController<Int> {
    override val isSelected: State<Boolean> = _isSelected

    private val firstVisibleItemIndex = derivedStateOf { state.firstVisibleItemIndex }

    override fun indicatorValue() = firstVisibleItemIndex.safeValue

    override fun onDraggableState(deltaPixels: Float, maxLengthPixels: Float) {
        val displace = if (reverseLayout.safeValue) -deltaPixels else deltaPixels // side effect ?
        if (isSelected.safeValue) {
            setScrollOffset(dragOffset.floatValue + displace / maxLengthPixels)
        }
    }

    override fun onDragStarted(offsetPixels: Float, maxLengthPixels: Float) {
        if (maxLengthPixels <= 0f) return
        val newOffset = when {
            reverseLayout.safeValue -> (maxLengthPixels - offsetPixels) / maxLengthPixels
            else -> offsetPixels / maxLengthPixels
        }
        val currentOffset = when {
            reverseLayout.safeValue -> 1f - thumbOffsetNormalized.value - thumbSizeNormalized.safeValue
            else -> thumbOffsetNormalized.safeValue
        }

        when (selectionMode.value) {
            ScrollbarSelectionMode.Full -> {
                if (newOffset in currentOffset..(currentOffset + thumbSizeNormalized.safeValue))
                    setDragOffset(currentOffset)
                else
                    setScrollOffset(newOffset)
                _isSelected.value = true
            }

            ScrollbarSelectionMode.Thumb -> {
                if (newOffset in currentOffset..(currentOffset + thumbSizeNormalized.safeValue)) {
                    setDragOffset(currentOffset)
                    _isSelected.value = true
                }
            }

            ScrollbarSelectionMode.Disabled -> Unit
        }
    }

    override fun onDragStopped() {
        _isSelected.value = false
    }

    private fun setDragOffset(value: Float) {
        val maxValue = (1f - thumbSizeNormalized.safeValue).coerceAtLeast(0f)
        dragOffset.floatValue = value.coerceIn(0f, maxValue)
    }

    private fun offsetCorrectionInverse(top: Float): Float {
        if (thumbSizeNormalizedReal.safeValue >= thumbMinLength.safeValue)
            return top
        val topRealMax = 1f - thumbSizeNormalizedReal.safeValue
        val topMax = 1f - thumbMinLength.safeValue
        return top * topRealMax / topMax
    }

    private fun setScrollOffset(newOffset: Float) {
        setDragOffset(newOffset)
        val totalItemsCount = state.layoutInfo.totalItemsCount.toFloat()
        val exactIndex = offsetCorrectionInverse(totalItemsCount * dragOffset.floatValue)
        val index: Int = floor(exactIndex).toInt()
        val remainder: Float = exactIndex - floor(exactIndex)

        coroutineScope.launch {
            state.scrollToItem(index = index, scrollOffset = 0)
            val offset = realFirstVisibleItem.value
                ?.size
                ?.let { it.toFloat() * remainder }
                ?: 0f
            state.scrollBy(offset)
        }
    }
}
