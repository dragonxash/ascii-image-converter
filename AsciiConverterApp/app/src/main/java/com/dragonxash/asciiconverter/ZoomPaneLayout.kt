package com.dragonxash.asciiconverter

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs

/**
 * 预览用的容器：**双指缩放 + 单指拖动 + 两轴滚动**。
 *
 * 它顶掉了原来那个 `ScrollView`，原因有两个：
 *
 * 1. `ScrollView` 只认纵向。放大之后横向那部分根本够不着，右边的内容是死区。
 * 2. 它的可滚动范围是**按子 View 的测量尺寸**算的。用 `scaleX/scaleY` 把内容放大之后
 *    测量尺寸并没变，于是滚不到被放大的部分——除非把布局尺寸也一起改大，
 *    而那会牵动文字重新排版（等宽字符一换行，字符画就散了）。
 *
 * 所以这里自己管：**子 View 的布局尺寸一律不动，只在绘制时叠一层变换**。
 * 缩放和平移都折算成「内容左上角离视口左上角差多少」这一个量，夹取逻辑就很简单。
 *
 * 摆放要求：**只放一个直接子 View**，它就是被缩放的内容。
 */
class ZoomPaneLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 当前倍数。1 = 原始大小（图片正好铺满宽度）。 */
    var scale = 1f
        private set

    /** 倍数变了就回调一次，外面用它显示 / 隐藏「复位」角标。 */
    var onScaleChanged: ((Float) -> Unit)? = null

    /** 内容左上角相对视口左上角的偏移（px，永远 <= 0）。 */
    private var offsetX = 0f
    private var offsetY = 0f

    private val content: View?
        get() = if (childCount > 0) getChildAt(0) else null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)

    /** 这一轮手势里捏过没有。捏过就不甩——理由见 [startFling]。 */
    private var zoomedInGesture = false

    /**
     * 自己驱动惯性滑动。
     *
     * 没走 `computeScroll()` 那条路——它得靠父容器主动来调，行为不完全由自己掌握；
     * 用 `postOnAnimation` 一帧一帧推，逻辑清楚、也不依赖框架的调用时机。
     */
    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) {
                return
            }
            offsetX = scroller.currX.toFloat()
            offsetY = scroller.currY.toFloat()
            clampOffsets()
            applyTransform()
            postOnAnimation(this)
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomedInGesture = true
                return zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
            }
        }
    ).apply {
        // 「双击后按住拖动」那种快速缩放要关掉：双击在文本预览里是选词，
        // 留着它会和选字打架。
        isQuickScaleEnabled = false
    }

    // region 对外操作

    /** 回到 1×，内容也重新贴回左上角。 */
    fun resetZoom() {
        scroller.forceFinished(true)
        removeCallbacks(flingStep)
        if (scale == 1f && offsetX == 0f && offsetY == 0f) {
            return
        }
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        applyTransform()
        onScaleChanged?.invoke(scale)
    }

    // endregion

    // region 测量

    /**
     * 把内容按「它想多大就多大」来量（[FrameLayout] 量孩子走的就是这个钩子）。
     *
     * 宽度分两种情况，判断方式和 `HorizontalScrollView` 一致：
     *
     * - 内容写的是 `match_parent`（图片模式的图，宽度本来就该铺满）→ 按视口宽度卡死；
     * - 内容写的是 `wrap_content`（文本模式的字，宽度由内容自己决定）→ **不设上限**，
     *   否则文字会被挤着换行，等宽字符一换行，字符画就散了。
     *
     * 高度一律不设上限：内容比视口高是常态（本来就靠滚动看下半截）。
     * 真正露到外面的部分由 [FrameLayout] 自带的 clipChildren 裁掉。
     */
    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int
    ) {
        val lp = child.layoutParams as MarginLayoutParams
        val available = (
            MeasureSpec.getSize(parentWidthMeasureSpec) -
                lp.leftMargin - lp.rightMargin
            ).coerceAtLeast(0)
        val childWidthSpec = if (lp.width == LayoutParams.MATCH_PARENT) {
            MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        }
        child.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 内容尺寸变了（换图、换字号、转屏、切模式），把平移量重新夹回合法范围
        clampOffsets()
        applyTransform()
    }

    /**
     * 把内容钉死在左上角。
     *
     * 缩放平移这套算法的前提是「内容左上角就在视口左上角」，而 [FrameLayout] 默认给的
     * 是 `Gravity.START`——阿拉伯语这类 RTL 语言下 START 会解析成右对齐，内容直接被推到
     * 屏幕外，缩放和拖动就全乱了。布局里也写了 `layout_gravity="top|left"`，
     * 这里再钉一次，免得以后有人在 XML 里漏掉。
     */
    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as? LayoutParams ?: continue
            lp.gravity = Gravity.TOP or Gravity.LEFT
        }
    }

    // endregion

    // region 变换

    /**
     * 缩放，并让手指按住的那个点在屏幕上**停在原地**。
     *
     * 支点设在内容左上角，于是「内容上的一点 x」画出来的位置就是 `offset + x * scale`；
     * 令焦点 `f` 缩放前后对得上，就能解出新的 offset。
     */
    private fun zoomTo(target: Float, focusX: Float, focusY: Float): Boolean {
        val next = target.coerceIn(MIN_SCALE, MAX_SCALE)
        if (abs(next - scale) < 0.001f) {
            return false
        }
        val ratio = next / scale
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        scale = next
        clampOffsets()
        applyTransform()
        onScaleChanged?.invoke(scale)
        return true
    }

    /** @return 横向能滚到的左边界（<= 0）。内容不比视口宽就是 0，也就是不能滚。 */
    private fun minOffsetX(): Float {
        val c = content ?: return 0f
        val scaled = c.width * scale
        return if (scaled <= width) 0f else width - scaled
    }

    /** @return 纵向能滚到的上边界（<= 0）。 */
    private fun minOffsetY(): Float {
        val c = content ?: return 0f
        val scaled = c.height * scale
        return if (scaled <= height) 0f else height - scaled
    }

    private fun canPan(): Boolean = minOffsetX() < 0f || minOffsetY() < 0f

    private fun clampOffsets() {
        offsetX = offsetX.coerceIn(minOffsetX(), 0f)
        offsetY = offsetY.coerceIn(minOffsetY(), 0f)
    }

    private fun applyTransform() {
        val c = content ?: return
        // 支点放左上角，平移量才能直接当成「内容左上角离视口左上角多远」用
        c.pivotX = 0f
        c.pivotY = 0f
        c.scaleX = scale
        c.scaleY = scale
        c.translationX = offsetX
        c.translationY = offsetY
    }

    // endregion

    // region 触摸

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 缩放检测器和速度追踪都放在这儿：这里能看到**每一**个事件，
        // 而 onTouchEvent 只在我们把手势抢过来之后才收得到——
        // 被抢之前在子 View 手里那一段，速度数据不能丢（否则算不出甩动速度）。
        scaleDetector.onTouchEvent(ev)
        trackVelocity(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            zoomedInGesture = false
        }

        val handled = super.dispatchTouchEvent(ev)

        // 回收统一放这儿：onTouchEvent 可能一次都没被调到（事件全被子 View 吃了），
        // 放那儿会漏
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            releaseVelocityTracker()
        }
        return handled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 手指一落下，上一轮的惯性滑动立刻停
                scroller.forceFinished(true)
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 = 要缩放，从子 View 手里把手势收回来
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    return true
                }
                if (!canPan()) {
                    return false
                }
                // 单指要过了触摸阈值才算拖动。不然点一下、长按选字、单击定位光标
                // 这些都会被我们抢走——文本预览是可选中复制的，不能碰。
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    lastX = ev.x
                    lastY = ev.y
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastX = ev.x
                lastY = ev.y
            }

            MotionEvent.ACTION_MOVE -> {
                // 缩放进行中不平移，否则内容会跟着焦点一起「飘」
                if (!scaleDetector.isInProgress) {
                    offsetX += ev.x - lastX
                    offsetY += ev.y - lastY
                    clampOffsets()
                    applyTransform()
                }
                lastX = ev.x
                lastY = ev.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一根手指之后指针会重新编号，基准得换成还留在屏幕上的那根，
                // 不然下一帧会突然跳一下
                val keep = if (ev.actionIndex == 0) 1 else 0
                if (keep < ev.pointerCount) {
                    lastX = ev.getX(keep)
                    lastY = ev.getY(keep)
                }
            }

            MotionEvent.ACTION_UP -> startFling()
        }
        return true
    }

    private fun trackVelocity(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            releaseVelocityTracker()
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(ev)
    }

    private fun releaseVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun startFling() {
        val tracker = velocityTracker ?: return
        // 这一轮只要捏过就不甩：捏合时两根手指是往两边张的，
        // 算出来的"速度"大得离谱，松手会把画面直接甩飞。
        if (zoomedInGesture) {
            return
        }
        tracker.computeCurrentVelocity(1000)
        val vx = tracker.xVelocity
        val vy = tracker.yVelocity
        if (abs(vx) < minFlingVelocity && abs(vy) < minFlingVelocity) {
            return
        }
        // 起止范围交给 OverScroller，撞到头它自己会停。
        //
        // 前两个参数是**起始滚动位置**（OverScroller 的 currX/currY 就从这里开始），
        // 必须传当前的 offset——传 0 的话 [flingStep] 第一帧就会把内容按回左上角，
        // 表现为"一松手画面跳回左上角再从那里甩出去"。
        removeCallbacks(flingStep)
        scroller.fling(
            offsetX.toInt(), offsetY.toInt(),
            vx.toInt(), vy.toInt(),
            minOffsetX().toInt(), 0,
            minOffsetY().toInt(), 0
        )
        if (!scroller.isFinished) {
            postOnAnimation(flingStep)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scroller.forceFinished(true)
        removeCallbacks(flingStep)
        releaseVelocityTracker()
    }

    // endregion

    private companion object {
        /** 最小 1×：缩到比铺满还小没有意义，而且「往里捏就复位」也更好理解。 */
        const val MIN_SCALE = 1f

        /** 字符画放太大只会看到一堆色块，8 倍够读最小的字了。 */
        const val MAX_SCALE = 8f
    }
}
