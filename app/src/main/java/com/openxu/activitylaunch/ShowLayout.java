package com.openxu.activitylaunch;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.Instrumentation;
import android.app.VoiceInteractor;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputQueue;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import com.android.server.am.IWindowSession;

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

/**
 * author : openXu
 * created time : 16/9/3 下午5:33
 * blog : http://blog.csdn.net/xmxkf
 * github : http://blog.csdn.net/xmxkf
 * class name : StartActivity
 * discription :
 *
 * StartActivity主要研究Activity的创建过程，
 */
public class ShowLayout extends Activity{


    /**
     * step1: ActivityThread.handleResumeActivity()
     * 这个方法是将activity置于resume状态，并回调onResume()方法
     */
    final void handleResumeActivity(IBinder token,
                                    boolean clearHide, boolean isForward, boolean reallyResume) {
        ...
        ActivityClientRecord r = performResumeActivity(token, clearHide);
        if (r != null) {
            final Activity a = r.activity;
            ...
            if (r.window == null && !a.mFinished && willBeVisible) {
                r.window = r.activity.getWindow();
                //①. 获取activity的顶层视图decor
                View decor = r.window.getDecorView();
                //让decor显示出来
                decor.setVisibility(View.INVISIBLE);
                //②. 获取WindowManager对象
                ViewManager wm = a.getWindowManager();
                WindowManager.LayoutParams l = r.window.getAttributes();
                a.mDecor = decor;
                l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
                l.softInputMode |= forwardBit;
                if (a.mVisibleFromClient) {
                    a.mWindowAdded = true;
                    //③. 将decor放入WindowManager(wm是WindowManagerImpl类型)中，这样activity就显示出来了
                    wm.addView(decor, l);
                }
            } else if (!willBeVisible) {
                if (localLOGV) Slog.v(
                        TAG, "Launch " + r + " mStartedActivity set");
                r.hideForNow = true;
            }
            ...

            // Tell the activity manager we have resumed.
            if (reallyResume) {
                try {
                    //这里会通过ActivityManagerNative的meRemote调用ActivityManagerService的activityResumed()方法
                    //ActivityManagerService会将activity置于resume状态，
                    ActivityManagerNative.getDefault().activityResumed(token);
                } catch (RemoteException ex) {
                }
            }

        } else {
          ...
        }
    }

    /**
     * 相关类
     */

    /**Window系列*/
    public abstract class Window {
        private WindowManager mWindowManager;
        public void setWindowManager(android.view.WindowManager wm, IBinder appToken, String appName,
                                     boolean hardwareAccelerated) {
            ...
            //调用WindowManagerImpl的静态方法new一个WindowManagerImpl对象
            mWindowManager = ((WindowManagerImpl)wm).createLocalWindowManager(this);
        }
        public android.view.WindowManager getWindowManager() {
            return mWindowManager;
        }
    }
    public class PhoneWindow extends Window implements MenuBuilder.Callback {
        private DecorView mDecor;
    }

    /**WindowManager系列，用于管理窗口视图的显示、添加、移除和状态更新等*/
    public interface ViewManager{
        public void addView(View view, ViewGroup.LayoutParams params);
        public void updateViewLayout(View view, ViewGroup.LayoutParams params);
        public void removeView(View view);
    }
    public interface WindowManager extends ViewManager {
        ...
    }
    public final class WindowManagerImpl implements WindowManager {
        //每个WindowManagerImpl对象都保存了一个WindowManagerGlobal的单例
        private final WindowManagerGlobal mGlobal = WindowManagerGlobal.getInstance();
        ...
        public WindowManagerImpl createLocalWindowManager(android.view.Window parentWindow) {
            return new WindowManagerImpl(mDisplay, parentWindow);
        }

        @Override
        public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
            applyDefaultToken(params);
            //调用WindowManagerGlobal对象的addView()方法
            mGlobal.addView(view, params, mDisplay, mParentWindow);
        }
        ...
    }

    /**Activity*/
    public class Activity ...{
        private Window mWindow;              //mWindow是PhoneWindow类型
        private WindowManager mWindowManager;//mWindowManager是WindowManagerImpl类型
        /**
         * Activity被创建后，执行attach()初始化的时候，就会创建PhoneWindow对象和WindowManagerImpl对象
         */
        final void attach(...) {
            ...
            //①.为activity创建一个PhoneWindow对象
            mWindow = new PhoneWindow(this);
            mWindow.setWindowManager(
                    (WindowManager)context.getSystemService(Context.WINDOW_SERVICE),
                    mToken, mComponent.flattenToString(),
                    (info.flags & ActivityInfo.FLAG_HARDWARE_ACCELERATED) != 0);
            if (mParent != null) {
                mWindow.setContainer(mParent.getWindow());
            }
            //②.调用Window的getWindowManager()方法初始化mWindowManager，其实就是new了一个WindowManagerImpl对象
            mWindowManager = mWindow.getWindowManager();
        }
    }

    /**
     * step2: WindowManagerGlobal.addView()
     * WindowManagerGlobal是单例模式，每个Activity在创建完成并调用attach()初始化的时候，
     * 会实例化WindowManagerImpl对象，WindowManagerImpl创建后就会获取WindowManagerGlobal的单例，
     * 这时候会调用initialize()方法，获取到IWindowManager类型的对象sWindowManagerService，
     * 这个sWindowManagerService实际上就代表WindowManagerService，因为IWindowManager是aidl类型的。
     * WindowManagerService服务用来管理系统中所有的窗口，控制视图的添加、移除、刷新等。
     *
     * WindowManagerGlobal.addView()方法中首先为Activity创建了一个视图层次结构的顶部类ViewRootImpl对象root,
     * ViewRootImpl构造方法中，会调用WindowManagerGlobal.getWindowSession()创建一个WindowManagerService
     * 的类型为IWindowSession的Binder代理对象mWindowSession，mWindowSession在整个应用程序中只有一个，因为
     * WindowManagerGlobal是单例模式，所以整个应用程序都是通过这个唯一的mWindowSession与ActivityManagerService
     * 通信，实现activity视图的显示控制。
     *
     */
    public final class WindowManagerGlobal {
        private static WindowManagerGlobal sDefaultWindowManager;
        private static IWindowManager sWindowManagerService;
        private static IWindowSession sWindowSession;
        //单例
        private WindowManagerGlobal() {
        }
        public static WindowManagerGlobal getInstance() {
            synchronized (WindowManagerGlobal.class) {
                if (sDefaultWindowManager == null) {
                    sDefaultWindowManager = new WindowManagerGlobal();
                }
                return sDefaultWindowManager;
            }
        }
        /**WindowManagerGlobal类被加载时，就获取到WindowManagerService对象*/
        public static void initialize() {
            getWindowManagerService();
        }

        public static IWindowManager getWindowManagerService() {
            //线程安全的
            synchronized (WindowManagerGlobal.class) {
                if (sWindowManagerService == null) {
                    sWindowManagerService = IWindowManager.Stub.asInterface(
                            ServiceManager.getService("window"));
                    try {
                        sWindowManagerService = getWindowManagerService();
                        ValueAnimator.setDurationScale(sWindowManagerService.getCurrentAnimatorScale());
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to get WindowManagerService, cannot set animator scale", e);
                    }
                }
                return sWindowManagerService;
            }
        }

        public static IWindowSession getWindowSession() {
            //线程安全的
            synchronized (WindowManagerGlobal.class) {
                if (sWindowSession == null) {
                    try {
                        //首先获得获得应用程序所使用的输入法管理器
                        InputMethodManager imm = InputMethodManager.getInstance();
                        //再获得系统中的WindowManagerService服务的一个Binder代理对象
                        IWindowManager windowManager = getWindowManagerService();
                        /*
                         * 调用它的成员函数openSession来请求WindowManagerService服务返回一个类型为Session的Binder本地对象.
                         * 这个Binder本地对象返回来之后，就变成了一个类型为Session的Binder代理代象，
                         * 即一个实现了IWindowSession接口的Binder代理代象，并且保存在ViewRoot类的静态成员变量sWindowSession中。
                         * 在请求WindowManagerService服务返回一个类型为Session的Binder本地对象的时候，
                         * 应用程序进程传递给WindowManagerService服务的参数有两个，
                         * 一个是实现IInputMethodClient接口的输入法客户端对象，另外一个是实现了IInputContext接口的一
                         * 个输入法上下文对象，
                         * 它们分别是通过调用前面所获得的一个输入法管理器的成员函数getClient和getInputContext来获得的。
                         */
                        sWindowSession = windowManager.openSession(
                                new IWindowSessionCallback.Stub() {
                                    @Override
                                    public void onAnimatorScaleChanged(float scale) {
                                        ValueAnimator.setDurationScale(scale);
                                    }
                                },
                                imm.getClient(), imm.getInputContext());
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to open window session", e);
                    }
                }
                return sWindowSession;
            }
        }

        public void addView(View view, ViewGroup.LayoutParams params,
                            Display display, Window parentWindow) {
            ...

            ViewRootImpl root;
            View panelParentView = null;

            synchronized (mLock) {
                ...
                int index = findViewLocked(view, false);
                if (index >= 0) {
                    if (mDyingViews.contains(view)) {
                        // Don't wait for MSG_DIE to make it's way through root's queue.
                        mRoots.get(index).doDie();
                    } else {
                        throw new IllegalStateException("View " + view
                                + " has already been added to the window manager.");
                    }
                    // The previous removeView() had not completed executing. Now it has.
                }
                ...

                //创建一个视图层次结构的顶部，ViewRootImpl实现所需视图和窗口之间的协议。
                root = new ViewRootImpl(view.getContext(), display);

                view.setLayoutParams(wparams);

                mViews.add(view);
                mRoots.add(root);
                mParams.add(wparams);
            }
            try {
                //将activity的根窗口mDecor添加到root中
                root.setView(view, wparams, panelParentView);
            } catch (RuntimeException e) {
                ...
                throw e;
            }
        }
    }

    /**
     * step3: ViewRootImpl.setView(view)
     */
    public final class ViewRootImpl implements ViewParent,
            View.AttachInfo.Callbacks, HardwareRenderer.HardwareDrawCallbacks {

        final IWindowSession mWindowSession;

        public ViewRootImpl(Context context, Display display) {
            mWindowSession = WindowManagerGlobal.getWindowSession();
            ...
        }

        public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
            synchronized (this) {
                if (mView == null) {
                    mView = view;
                    ...

                    int res; /* = WindowManagerImpl.ADD_OKAY; */
                    //请求对应用程序窗口视图的UI作第一次布局,应用程序窗口的绘图表面的创建过程
                    requestLayout();
                    ...
                    try {
                        ...
                        res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                                getHostVisibility(), mDisplay.getDisplayId(),
                                mAttachInfo.mContentInsets, mAttachInfo.mStableInsets,
                                mAttachInfo.mOutsets, mInputChannel);
                    } catch (RemoteException e) {
                        ...
                        throw new RuntimeException("Adding window failed", e);
                    }
                    ...

                }
            }

        @Override
        public void requestLayout () {
            if (!mHandlingLayoutInLayoutRequest) {
                //检查当前线程是否是主线程，对视图的操作只能在UI线程中进行，否则抛异常
                checkThread();
                //应用程序进程的UI线程正在被请求执行一个UI布局操作
                mLayoutRequested = true;
                scheduleTraversals();
            }
        }

        void checkThread() {
            if (mThread != Thread.currentThread()) {
                throw new CalledFromWrongThreadException(
                        "Only the original thread that created a view hierarchy can touch its views.");
            }
        }
        void scheduleTraversals() {
            if (!mTraversalScheduled) {
                mTraversalScheduled = true;
                mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
                //Choreographer类型，用于发送消息
                mChoreographer.postCallback(
                        Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
                ...
            }
        }

        final TraversalRunnable mTraversalRunnable = new TraversalRunnable();
        final class TraversalRunnable implements Runnable {
            @Override
            public void run() {
                doTraversal();
            }
        }
        void doTraversal() {
            if (mTraversalScheduled) {
                mTraversalScheduled = false;
                mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
                if (mProfile) {
                    Debug.startMethodTracing("ViewAncestor");
                }
                performTraversals();
                if (mProfile) {
                    Debug.stopMethodTracing();
                    mProfile = false;
                }
            }
        }
    }

    /**
     * step5: ViewRootImpl.performTraversals()
     */
    private void performTraversals() {
        final View host = mView;   //mView就是Activity的根窗口mDecor
        ...
        //一、下面代码主要确定Activity窗口的大小
        boolean windowSizeMayChange = false;
        //Activity窗口的宽度desiredWindowWidth和高度desiredWindowHeight
        int desiredWindowWidth;
        int desiredWindowHeight;
        ...
        //Activity窗口当前的宽度和高度是保存ViewRoot类的成员变量mWinFrame中的
        Rect frame = mWinFrame;
        if (mFirst) {
            /*
             *  如果Activity窗口是第一次被请求执行测量、布局和绘制操作，
             *  即ViewRoot类的成员变量mFirst的值等于true，那么它的当
             *  前宽度desiredWindowWidth和当前高度desiredWindowHeight
             *  就等于屏幕的宽度和高度，否则的话，
             *  它的当前宽度desiredWindowWidth和当前高度desiredWindowHeight
             *  就等于保存在ViewRoot类的成员变量mWinFrame中的宽度和高度值。
             */
            ...
            if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL
                    || lp.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                // NOTE -- system code, won't try to do compat mode.
                Point size = new Point();
                mDisplay.getRealSize(size);
                desiredWindowWidth = size.x;
                desiredWindowHeight = size.y;
            } else {
                DisplayMetrics packageMetrics =
                        mView.getContext().getResources().getDisplayMetrics();
                desiredWindowWidth = packageMetrics.widthPixels;
                desiredWindowHeight = packageMetrics.heightPixels;
            }

            ...

        } else {
            /*
             * 如果Activity窗口不是第一次被请求执行测量、布局和绘制操作，
             * 并且Activity窗口主动上一次请求WindowManagerService
             * 服务计算得到的宽度mWidth和高度mHeight不等于Activity窗
             * 口的当前宽度desiredWindowWidth和当前高度desiredWindowHeight，
             * 那么就说明Activity窗口的大小发生了变化，
             * 这时候变量windowSizeMayChange的值就会被标记为true，
             * 以便接下来可以对Activity窗口的大小变化进行处理
             */
            desiredWindowWidth = frame.width();
            desiredWindowHeight = frame.height();
            if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
                ...
                windowSizeMayChange = true;
            }
        }
        ...

        boolean insetsChanged = false;
        boolean layoutRequested = mLayoutRequested && (!mStopped || mReportNextDraw);
        if (layoutRequested) {
            final Resources res = mView.getContext().getResources();
            if (mFirst) {
                mAttachInfo.mInTouchMode = !mAddedTouchMode;
                ensureTouchModeLocally(mAddedTouchMode);
            } else {
                ...
                if (!mPendingOutsets.equals(mAttachInfo.mOutsets)) {
                    insetsChanged = true;
                }
                if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {

                    /*
                     * 如果Activity窗口的宽度被设置为ViewGroup.LayoutParams.WRAP_CONTENT或者
                     * 高度被设置为ViewGroup.LayoutParams.WRAP_CONTENT，那么就意味着Activity
                     * 窗口的大小要等于内容区域的大小。但是由于Activity窗口的大小是需要覆盖整个屏幕的，
                     * 因此，这时候就会Activity窗口的当前宽度desiredWindowWidth和当前高度desiredWindowHeight
                     * 设置为屏幕的宽度和高度。也就是说，如果我们将Activity窗口的宽度和高度设置为
                     * ViewGroup.LayoutParams.WRAP_CONTENT，实际上就意味着它的宽度和高度等于屏幕的宽度和高度。
                     * 这种情况也意味着Acitivity窗口的大小发生了变化，
                     * 因此，就将变量windowResizesToFitContent的值设置为true。
                     */
                    windowSizeMayChange = true;

                    if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL
                            || lp.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                        // NOTE -- system code, won't try to do compat mode.
                        Point size = new Point();
                        mDisplay.getRealSize(size);
                        desiredWindowWidth = size.x;
                        desiredWindowHeight = size.y;
                    } else {
                        DisplayMetrics packageMetrics = res.getDisplayMetrics();
                        desiredWindowWidth = packageMetrics.widthPixels;
                        desiredWindowHeight = packageMetrics.heightPixels;
                    }
                }
            }

            // Ask host how big it wants to be
            // 调用measureHierarchy()尝试测量Activity根窗口mDecor的大小，返回值为窗口大小是否变化了
            windowSizeMayChange |= measureHierarchy(host, lp, res,
                    desiredWindowWidth, desiredWindowHeight);
        }

        ...

        /*
         * 检查是否需要处理Activity窗口的大小变化事件:
         * mLayoutRequest==true，这说明应用程序进程正在请求对Activity窗口执行一次测量、布局和绘制操作;
         * windowSizeMayChange==true，这说明前面检测到了Activity窗口的大小发生了变化；
         * 当mDecor测量出来的宽高和Activity窗口的当前宽度mWidth和高度mHeight不一样，或者
         * ctivity窗口的大小被要求设置成WRAP_CONTENT，即设置成和屏幕的宽度desiredWindowWidth
         * 和高度desiredWindowHeight一致，但是WindowManagerService服务请求Activity窗口设置
         * 的宽度frame.width()和高度frame.height()与它们不一致，而且与Activity窗口上一次请求
         * WindowManagerService服务计算的宽度mWidth和高度mHeight也不一致，
         * 那么也是认为Activity窗口大小发生了变化的
         */
        boolean windowShouldResize = layoutRequested && windowSizeMayChange
                && ((mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight())
                || (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
                frame.width() < desiredWindowWidth && frame.width() != mWidth)
                || (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                frame.height() < desiredWindowHeight && frame.height() != mHeight));

        ...

        /**
         * 接下来的两段代码都是在满足下面的条件之一的情况下执行的：
         * 1. Activity窗口是第一次执行测量、布局和绘制操作，即ViewRoot类的成员变量mFirst的值等于true
         * 2. 前面得到的变量windowShouldResize的值等于true，即Activity窗口的大小的确是发生了变化。
         * 3. 前面得到的变量insetsChanged的值等于true，即Activity窗口的内容区域边衬发生了变化。
         * 4. Activity窗口的可见性发生了变化，即变量viewVisibilityChanged的值等于true。
         * 5. Activity窗口的属性发生了变化，即变量params指向了一个WindowManager.LayoutParams对象。
         */
        if (mFirst || windowShouldResize || insetsChanged ||
                viewVisibilityChanged || params != null) {
            ...
            try {

                //二、请求WindowManagerService服务计算Activity窗口的大小以及内容区域边衬大小和可见区域边衬大小
                relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);
                ...
            } catch (RemoteException e) {
            }

            ...
            //将计算得到的Activity窗口的宽度和高度保存在ViewRoot类的成员变量mWidth和mHeight中
            if (mWidth != frame.width() || mHeight != frame.height()) {
                mWidth = frame.width();
                mHeight = frame.height();
            }
            ...

            //这段代码用来检查是否需要重新测量Activity窗口的大小
            if (!mStopped || mReportNextDraw) {
                boolean focusChangedDueToTouchMode = ensureTouchModeLocally(
                        (relayoutResult&WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE) != 0);
                if (focusChangedDueToTouchMode || mWidth != host.getMeasuredWidth()
                        || mHeight != host.getMeasuredHeight() || contentInsetsChanged) {
                    int childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width);
                    int childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height);

                    // 一、测量控件大小（根窗口和其子控件树）
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    int width = host.getMeasuredWidth();
                    int height = host.getMeasuredHeight();
                    boolean measureAgain = false;
                    if (lp.horizontalWeight > 0.0f) {
                        width += (int) ((mWidth - width) * lp.horizontalWeight);
                        childWidthMeasureSpec = View.MeasureSpec.makeMeasureSpec(width,
                                View.MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
                    if (lp.verticalWeight > 0.0f) {
                        height += (int) ((mHeight - height) * lp.verticalWeight);
                        childHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(height,
                                View.MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
                    if (measureAgain) {
                        // 一、测量控件大小（根窗口和其子控件树）
                        performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    }

                    layoutRequested = true;
                }
            }
        }else{
            ...
        }

        final boolean didLayout = layoutRequested && (!mStopped || mReportNextDraw);
        boolean triggerGlobalLayoutListener = didLayout
                || mAttachInfo.mRecomputeGlobalAttributes;
        if (didLayout) {
            //二、布局过程
            performLayout(lp, desiredWindowWidth, desiredWindowHeight);
            ...
        }
        ...
        boolean skipDraw = false;
        boolean cancelDraw = mAttachInfo.mTreeObserver.dispatchOnPreDraw() ||
                viewVisibility != View.VISIBLE;

        if (!cancelDraw && !newSurface) {
            if (!skipDraw || mReportNextDraw) {
                ...
                //三、绘制过程
                performDraw();
            }
        } else {
            if (viewVisibility == View.VISIBLE) {
                // Try again
                scheduleTraversals();
            } else if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                ...
            }
        }

        mIsInTraversal = false;
    }






}