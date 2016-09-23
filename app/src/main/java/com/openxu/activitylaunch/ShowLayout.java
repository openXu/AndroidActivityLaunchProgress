package com.openxu.activitylaunch;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.VoiceInteractor;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputQueue;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import com.android.server.am.IWindowSession;

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
     * 的类型为IWindowSession的Binder代理对象mWindowSession，这个mWindowSession将用于Activity与
     * WindowManagerService通信，实现activity视图的显示控制。
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
                        InputMethodManager imm = InputMethodManager.getInstance();
                        IWindowManager windowManager = getWindowManagerService();
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

                /**
                 * 创建一个视图层次结构的顶部，ViewRootImpl实现所需视图和窗口之间的协议。
                 * ViewRootImpl被创建后，会获取到一个IWindowSession类型
                 */
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
     * step4: ViewRootImpl.setView(view)
     */
    public final class ViewRootImpl implements ViewParent,
            View.AttachInfo.Callbacks, HardwareRenderer.HardwareDrawCallbacks {

        final IWindowSession mWindowSession;

        public ViewRootImpl(Context context, Display display) {
            /**
             * WindowManagerGlobal是单例模式，应用程序第一个Activity被创建之后请求显示的时候，getWindowSession
             */
            mWindowSession = WindowManagerGlobal.getWindowSession();
            ...
        }

        public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
            synchronized (this) {
                if (mView == null) {
                    mView = view;

                    mAttachInfo.mDisplayState = mDisplay.getState();
                    mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);

                    mViewLayoutDirectionInitial = mView.getRawLayoutDirection();
                    //事件处理
                    mFallbackEventHandler.setView(view);
                    mWindowAttributes.copyFrom(attrs);
                    if (mWindowAttributes.packageName == null) {
                        mWindowAttributes.packageName = mBasePackageName;
                    }
                    attrs = mWindowAttributes;
                    // Keep track of the actual window flags supplied by the client.
                    mClientWindowLayoutFlags = attrs.flags;

                    setAccessibilityFocus(null, null);

                    if (view instanceof RootViewSurfaceTaker) {
                        mSurfaceHolderCallback =
                                ((RootViewSurfaceTaker)view).willYouTakeTheSurface();
                        if (mSurfaceHolderCallback != null) {
                            mSurfaceHolder = new TakenSurfaceHolder();
                            mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                        }
                    }

                    // Compute surface insets required to draw at specified Z value.
                    // 计算surface镶嵌所需的指定的Z值。
                    // TODO: Use real shadow insets for a constant max Z.
                    if (!attrs.hasManualSurfaceInsets) {
                        final int surfaceInset = (int) Math.ceil(view.getZ() * 2);
                        attrs.surfaceInsets.set(surfaceInset, surfaceInset, surfaceInset, surfaceInset);
                    }

                    CompatibilityInfo compatibilityInfo = mDisplayAdjustments.getCompatibilityInfo();
                    mTranslator = compatibilityInfo.getTranslator();

                    // If the application owns the surface, don't enable hardware acceleration
                    if (mSurfaceHolder == null) {
                        enableHardwareAcceleration(attrs);
                    }

                    boolean restore = false;
                    if (mTranslator != null) {
                        mSurface.setCompatibilityTranslator(mTranslator);
                        restore = true;
                        attrs.backup();
                        mTranslator.translateWindowLayout(attrs);
                    }
                    if (DEBUG_LAYOUT) Log.d(TAG, "WindowLayout in setView:" + attrs);

                    if (!compatibilityInfo.supportsScreen()) {
                        attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                        mLastInCompatMode = true;
                    }

                    mSoftInputMode = attrs.softInputMode;
                    mWindowAttributesChanged = true;
                    mWindowAttributesChangesFlag = WindowManager.LayoutParams.EVERYTHING_CHANGED;
                    mAttachInfo.mRootView = view;
                    mAttachInfo.mScalingRequired = mTranslator != null;
                    mAttachInfo.mApplicationScale =
                            mTranslator == null ? 1.0f : mTranslator.applicationScale;
                    if (panelParentView != null) {
                        mAttachInfo.mPanelParentWindowToken
                                = panelParentView.getApplicationWindowToken();
                    }
                    mAdded = true;
                    int res; /* = WindowManagerImpl.ADD_OKAY; */

                    // Schedule the first layout -before- adding to the window
                    // manager, to make sure we do the relayout before receiving
                    // any other events from the system.
                    requestLayout();
                    if ((mWindowAttributes.inputFeatures
                            & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                        mInputChannel = new InputChannel();
                    }
                    try {
                        mOrigWindowType = mWindowAttributes.type;
                        mAttachInfo.mRecomputeGlobalAttributes = true;
                        collectViewAttributes();
                        //添加到WindowManagerServices中
                        res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                                getHostVisibility(), mDisplay.getDisplayId(),
                                mAttachInfo.mContentInsets, mAttachInfo.mStableInsets,
                                mAttachInfo.mOutsets, mInputChannel);
                    } catch (RemoteException e) {
                        mAdded = false;
                        mView = null;
                        mAttachInfo.mRootView = null;
                        mInputChannel = null;
                        mFallbackEventHandler.setView(null);
                        unscheduleTraversals();
                        setAccessibilityFocus(null, null);
                        throw new RuntimeException("Adding window failed", e);
                    } finally {
                        if (restore) {
                            attrs.restore();
                        }
                    }

                    if (mTranslator != null) {
                        mTranslator.translateRectInScreenToAppWindow(mAttachInfo.mContentInsets);
                    }
                    mPendingOverscanInsets.set(0, 0, 0, 0);
                    mPendingContentInsets.set(mAttachInfo.mContentInsets);
                    mPendingStableInsets.set(mAttachInfo.mStableInsets);
                    mPendingVisibleInsets.set(0, 0, 0, 0);
                    if (DEBUG_LAYOUT) Log.v(TAG, "Added window " + mWindow);
                    if (res < WindowManagerGlobal.ADD_OKAY) {
                        mAttachInfo.mRootView = null;
                        mAdded = false;
                        mFallbackEventHandler.setView(null);
                        unscheduleTraversals();
                        setAccessibilityFocus(null, null);
                        switch (res) {
                            case WindowManagerGlobal.ADD_BAD_APP_TOKEN:
                            case WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN:
                                throw new WindowManager.BadTokenException(
                                        "Unable to add window -- token " + attrs.token
                                                + " is not valid; is your activity running?");
                            case WindowManagerGlobal.ADD_NOT_APP_TOKEN:
                                throw new WindowManager.BadTokenException(
                                        "Unable to add window -- token " + attrs.token
                                                + " is not for an application");
                            case WindowManagerGlobal.ADD_APP_EXITING:
                                throw new WindowManager.BadTokenException(
                                        "Unable to add window -- app for token " + attrs.token
                                                + " is exiting");
                            case WindowManagerGlobal.ADD_DUPLICATE_ADD:
                                throw new WindowManager.BadTokenException(
                                        "Unable to add window -- window " + mWindow
                                                + " has already been added");
                            case WindowManagerGlobal.ADD_STARTING_NOT_NEEDED:
                                // Silently ignore -- we would have just removed it
                                // right away, anyway.
                                return;
                            case WindowManagerGlobal.ADD_MULTIPLE_SINGLETON:
                                throw new WindowManager.BadTokenException(
                                        "Unable to add window " + mWindow +
                                                " -- another window of this type already exists");
                            case WindowManagerGlobal.ADD_PERMISSION_DENIED:
                                throw new WindowManager.BadTokenException(
                                        "Unable to add window " + mWindow +
                                                " -- permission denied for this window type");
                            case WindowManagerGlobal.ADD_INVALID_DISPLAY:
                                throw new WindowManager.InvalidDisplayException(
                                        "Unable to add window " + mWindow +
                                                " -- the specified display can not be found");
                            case WindowManagerGlobal.ADD_INVALID_TYPE:
                                throw new WindowManager.InvalidDisplayException(
                                        "Unable to add window " + mWindow
                                                + " -- the specified window type is not valid");
                        }
                        throw new RuntimeException(
                                "Unable to add window -- unknown error code " + res);
                    }

                    if (view instanceof RootViewSurfaceTaker) {
                        mInputQueueCallback =
                                ((RootViewSurfaceTaker)view).willYouTakeTheInputQueue();
                    }
                    if (mInputChannel != null) {
                        if (mInputQueueCallback != null) {
                            mInputQueue = new InputQueue();
                            mInputQueueCallback.onInputQueueCreated(mInputQueue);
                        }
                        mInputEventReceiver = new WindowInputEventReceiver(mInputChannel,
                                Looper.myLooper());
                    }

                    view.assignParent(this);
                    mAddedTouchMode = (res & WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE) != 0;
                    mAppVisible = (res & WindowManagerGlobal.ADD_FLAG_APP_VISIBLE) != 0;

                    if (mAccessibilityManager.isEnabled()) {
                        mAccessibilityInteractionConnectionManager.ensureConnection();
                    }

                    if (view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    }

                    // Set up the input pipeline.
                    CharSequence counterSuffix = attrs.getTitle();
                    mSyntheticInputStage = new SyntheticInputStage();
                    InputStage viewPostImeStage = new ViewPostImeInputStage(mSyntheticInputStage);
                    InputStage nativePostImeStage = new NativePostImeInputStage(viewPostImeStage,
                            "aq:native-post-ime:" + counterSuffix);
                    InputStage earlyPostImeStage = new EarlyPostImeInputStage(nativePostImeStage);
                    InputStage imeStage = new ImeInputStage(earlyPostImeStage,
                            "aq:ime:" + counterSuffix);
                    InputStage viewPreImeStage = new ViewPreImeInputStage(imeStage);
                    InputStage nativePreImeStage = new NativePreImeInputStage(viewPreImeStage,
                            "aq:native-pre-ime:" + counterSuffix);

                    mFirstInputStage = nativePreImeStage;
                    mFirstPostImeInputStage = earlyPostImeStage;
                    mPendingInputEventQueueLengthCounterName = "aq:pending:" + counterSuffix;
                }
            }
        }


        @Override
        public void requestLayout() {
            if (!mHandlingLayoutInLayoutRequest) {
                //检查当前线程是否是主线程，对视图的操作只能在主线程中进行，否则抛异常
                checkThread();
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
                if (!mUnbufferedInputDispatch) {
                    scheduleConsumeBatchedInput();
                }
                //通知硬件渲染器一个新的frame将要被添加
                notifyRendererOfFramePending();
                pokeDrawLockIfNeeded();
            }
        }

    }













}
