package com.openxu.activitylaunch;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.EventLogTags;
import android.util.Log;
import android.util.LogPrinter;
import android.view.View;
import android.view.ViewManager;
import android.view.WindowManager;

import com.openxu.bs.androidsuorce.ActivityRecord;
import com.openxu.bs.androidsuorce.ActivityStack;

import java.util.ArrayList;
import java.util.List;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

/**
 * author : openXu
 * created time : 16/9/3 下午5:33
 * blog : http://blog.csdn.net/xmxkf
 * github : http://blog.csdn.net/xmxkf
 * class name : StartActivity
 * discription :
 *
 * StartActivity主要研究Activity的创建过程，
 * 下面是从调用startActivity()开始到activity创建完成并回调onCreate()的全部过程；
 * 涉及到framework层的源码已经拷贝到工程中；下载源码困难的同学可参考：
 * http://grepcode.com/project/repository.grepcode.com/java/ext/com.google.android/android/
 *
 * 总结：
 * step1-step3:通过Binder机制将创建activity的消息传给ActivityManagerService
 *
 * step4-step8:根据activity的启动模式判断activity是否应该重新创建，还是只需要置于栈顶，并将目标activity记录放在栈顶
 *
 * step9-step10:判断activity是否已经创建，如果已经创建将直接置于resume状态，如果没有创建便重新开启
 *
 *step11-step12:判断activity所属的进程是否创建，如果没有创建将启动新的进程，如果创建了就直接启动activity
 *
 *step13-step15:进程的主线程执行ActivityThread的main方法，然后初始化进程相关的东西
 *
 *step16-step21:实例化栈顶的activity对象，并调用Activity.attach()初始化，回调onCreate()生命周期方法；
 *
 *step22: 调用ActivityThread.handleResumeActivity()使activity处于resume状态
 */
public class StartActivity extends Activity{


    @Override
    public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
        super.onCreate(savedInstanceState, persistentState);
        startActivity(new Intent(this, StartActivity.class));
    }

    /**
     * step1 : Activity.startActivityForResult()
     * 在Android系统中，我们比较熟悉的打开Activity通常有两种方式，
     * 第一种是点击应用程序图标，Launcher会启动应用程序的主Activity，
     * 我们知道Launcher其实也是一个应用程序，他是怎样打开我们的主Activity的呢？
     * 在应用程序被安装的时候，系统会找到AndroidManifest.xml中activity的配置信息，
     * 并将action=android.intent.action.MAIN&category=android.intent.category.LAUNCHER
     * 的activity记录下来，形成应用程序与主Activity 的映射关系，当点击启动图标时，
     * Launcher就会找到应用程序对应的主activity并将它启动。第二种是当主Activity
     * 启动之后，在应用程序内部可以调用startActivity()开启新的Activity，这种方式又可分为显示启动和隐式启动。
     * 不管使用哪种方式启动Activity，其实最终调用的都是startActivity()方法。所以如果要分析Activity的启动过程，
     * 我们就从startActivity()方法分析。跟踪发现Activity中重载的startActivity()方法最终都是调用
     * startActivityForResult(intent, requestCode , bundle)：
     */
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        //一般Activity的mParent都为null，mParent常用在ActivityGroup中，ActivityGroup已废弃
        if (mParent == null) {
            //启动新的Activity
            Instrumentation.ActivityResult ar =
                    mInstrumentation.execStartActivity(
                            this, mMainThread.getApplicationThread(), mToken, this,
                            intent, requestCode, options);
            if (ar != null) {
                //跟踪execStartActivity()，发现开启activity失败ar才可能为null，这时会调用onActivityResult
                mMainThread.sendActivityResult(
                        mToken, mEmbeddedID, requestCode, ar.getResultCode(),
                        ar.getResultData());
            }
            ...
        } else {
            //在ActivityGroup内部的Activity调用startActivity的时候会走到这里，处理逻辑和上面是类似的
            if (options != null) {
                mParent.startActivityFromChild(this, intent, requestCode, options);
            } else {
                // Note we want to go through this method for compatibility with
                // existing applications that may have overridden it.
                mParent.startActivityFromChild(this, intent, requestCode);
            }
        }
    }

    /**
     * step2 : Instrumentation
     *  mInstrumentation的execStartActivity(）方法中首先检查activity是否能打开，
     *  如果不能打开直接返回，否则继续调用ActivityManagerNative.getDefault().startActivity()开启activity，
     *  然后检查开启结果，如果开启失败，会抛出异常（比如未在AndroidManifest.xml注册）
     */
    public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, String target,
            Intent intent, int requestCode, Bundle options) {
        IApplicationThread whoThread = (IApplicationThread) contextThread;
        //mActivityMonitors是所有ActivityMonitor的集合，用于监视应用的Activity(记录状态)
        if (mActivityMonitors != null) {
            synchronized (mSync) {
                //先查找一遍看是否存在这个activity
                final int N = mActivityMonitors.size();
                for (int i=0; i<N; i++) {
                    final ActivityMonitor am = mActivityMonitors.get(i);
                    if (am.match(who, null, intent)) {
                        //如果找到了就跳出循环
                        am.mHits++;
                        //如果目标activity无法打开，直接return
                        if (am.isBlocking()) {
                            return requestCode >= 0 ? am.getResult() : null;
                        }
                        break;
                    }
                }
            }
        }
        try {
            intent.migrateExtraStreamToClipData();
            intent.prepareToLeaveProcess();
            //这里才是真正开启activity的地方，ActivityManagerNative中实际上调用的是ActivityManagerProxy的方法
            int result = ActivityManagerNative.getDefault()
                    .startActivity(whoThread, who.getBasePackageName(), intent,
                            intent.resolveTypeIfNeeded(who.getContentResolver()),
                            token, target, requestCode, 0, null, options);
            //checkStartActivityResult方法是抛异常专业户，它对上面开启activity的结果进行检查，如果无法打开activity，
            //则抛出诸如ActivityNotFoundException类似的各种异常
            checkStartActivityResult(result, intent);
        } catch (RemoteException e) {
            throw new RuntimeException("Failure from system", e);
        }
        return null;
    }

    /**
     * step3 :  ActivityManagerNative
     * 在讲解此步骤之前，先大致介绍一下ActivityManagerNative这个抽象类。
     * ActivityManagerNative继承自Binder(实现了IBinder)，实现了IActivityManager接口，但是没有实现他的抽象方法。
     *
     * ActivityManagerNative中有一个内部类ActivityManagerProxy，ActivityManagerProxy也实现
     * 了IActivityManager接口，并实现了IActivityManager中所有的方法。IActivityManager里面有很
     * 多像startActivity()、startService()、bindService()、registerReveicer()等方法，所以
     * IActivityManager为Activity管理器提供了统一的API。
     *
     * ActivityManagerNative通过getDefault()获取到一个ActivityManagerProxy的示例，并将远程代理对象IBinder
     * 传递给他，然后调用他的starXxx方法开启Service、Activity或者registReceiver，可以看出ActivityManagerNative
     * 只是一个装饰类，真正工作的是其内部类ActivityManagerProxy。
     *
     */
    public abstract class ActivityManagerNative extends Binder
            implements IActivityManager{
        static public IActivityManager getDefault() {
            //此处返回的IActivityManager示例是ActivityManagerProxy的对象
            return gDefault.get();
        }
        private static final Singleton<IActivityManager> gDefault = new Singleton<IActivityManager>() {
            protected IActivityManager create() {
                //android.os.ServiceManager中维护了HashMap<String, IBinder> sCache，他是系统Service对应的IBinder代理对象的集合
                //通过名称获取到ActivityManagerService对应的IBinder代理对象
                IBinder b = ServiceManager.getService("activity");
                if (false) {
                    Log.v("ActivityManager", "default service binder = " + b);
                }
                //返回一个IActivityManager对象，这个对象实际上是ActivityManagerProxy的对象
                IActivityManager am = asInterface(b);
                if (false) {
                    Log.v("ActivityManager", "default service = " + am);
                }
                return am;
            }
        };
        static public IActivityManager asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IActivityManager in =
                    (IActivityManager)obj.queryLocalInterface(descriptor);
            if (in != null) {
                return in;
            }
            //返回ActivityManagerProxy对象
            return new ActivityManagerProxy(obj);
        }

        class ActivityManagerProxy implements IActivityManager {

            private IBinder mRemote;

            public ActivityManagerProxy(IBinder remote) {
                mRemote = remote;
            }

            public IBinder asBinder() {
                return mRemote;
            }

            /*
             * Activity生命周期相关方法
             */
            public int startActivity(IApplicationThread caller, String callingPackage, Intent intent,
                                     String resolvedType, IBinder resultTo, String resultWho, int requestCode,
                                     int startFlags, ProfilerInfo profilerInfo, Bundle options) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                //下面的代码将参数持久化，便于ActivityManagerService中获取
                data.writeInterfaceToken(IActivityManager.descriptor);
                data.writeStrongBinder(caller != null ? caller.asBinder() : null);
                data.writeString(callingPackage);
                intent.writeToParcel(data, 0);
                data.writeString(resolvedType);
                data.writeStrongBinder(resultTo);
                data.writeString(resultWho);
                data.writeInt(requestCode);
                data.writeInt(startFlags);
                if (profilerInfo != null) {
                    data.writeInt(1);
                    profilerInfo.writeToParcel(data, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                } else {
                    data.writeInt(0);
                }
                if (options != null) {
                    data.writeInt(1);
                    options.writeToParcel(data, 0);
                } else {
                    data.writeInt(0);
                }
                //mRemote就是ActivityManagerService的远程代理对象，这句代码之后就进入到ActivityManagerService中了
                mRemote.transact(START_ACTIVITY_TRANSACTION, data, reply, 0);
                reply.readException();
                int result = reply.readInt();
                reply.recycle();
                data.recycle();
                return result;
            }

            public void activityResumed(IBinder token) throws RemoteException {...
                mRemote.transact(ACTIVITY_RESUMED_TRANSACTION, data, reply, 0);
            }

            public void activityPaused(IBinder token) throws RemoteException {...
                mRemote.transact(ACTIVITY_PAUSED_TRANSACTION, data, reply, 0);
            }

            public void activityStopped(IBinder token, Bundle state,
                                        PersistableBundle persistentState, CharSequence description) throws RemoteException {...
                mRemote.transact(ACTIVITY_STOPPED_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY);
            }

            public void activityDestroyed(IBinder token) throws RemoteException {...
                mRemote.transact(ACTIVITY_DESTROYED_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY);
            }

            public void moveTaskToFront(int task, int flags, Bundle options) throws RemoteException {...
                mRemote.transact(MOVE_TASK_TO_FRONT_TRANSACTION, data, reply, 0);
            }

            /*
             * Receiver广播注册等相关方法
             */
            public Intent registerReceiver(IApplicationThread caller, String packageName,
                                           IIntentReceiver receiver,
                                           IntentFilter filter, String perm, int userId) throws RemoteException {...
                mRemote.transact(REGISTER_RECEIVER_TRANSACTION, data, reply, 0);
            }

            public void unregisterReceiver(IIntentReceiver receiver) throws RemoteException {...
                mRemote.transact(UNREGISTER_RECEIVER_TRANSACTION, data, reply, 0);
            }

            /*
             * Servicek开启和关闭相关
             */
            public ComponentName startService(IApplicationThread caller, Intent service,
                                              String resolvedType, String callingPackage, int userId) throws RemoteException {...
                mRemote.transact(START_SERVICE_TRANSACTION, data, reply, 0);
            }

            public int stopService(IApplicationThread caller, Intent service,
                                   String resolvedType, int userId) throws RemoteException {...
                mRemote.transact(STOP_SERVICE_TRANSACTION, data, reply, 0);
            }

            ...

            public int bindService(IApplicationThread caller, IBinder token,
                                   Intent service, String resolvedType, IServiceConnection connection,
                                   int flags, String callingPackage, int userId) throws RemoteException {
                ...
                mRemote.transact(BIND_SERVICE_TRANSACTION, data, reply, 0);
            }

            public boolean unbindService(IServiceConnection connection) throws RemoteException {...
                mRemote.transact(UNBIND_SERVICE_TRANSACTION, data, reply, 0);
            }

            ...

            //获取内存信息
            public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) throws RemoteException {
                mRemote.transact(GET_MEMORY_INFO_TRANSACTION, data, reply, 0);
            }

            public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) throws RemoteException {
                mRemote.transact(GET_PROCESS_MEMORY_INFO_TRANSACTION, data, reply, 0);
            }

            //杀死进程
            public void killBackgroundProcesses(String packageName, int userId) throws RemoteException {
                mRemote.transact(KILL_BACKGROUND_PROCESSES_TRANSACTION, data, reply, 0);
            }

            public void killApplicationWithAppId(String pkg, int appid, String reason) throws RemoteException {
                mRemote.transact(KILL_APPLICATION_WITH_APPID_TRANSACTION, data, reply, 0);
            }

            ...

        }
    }

    /**
     * step4 : ActivityManagerService
     * 直接转调用ActivityStackSupervisor的startActivityMayWait()方法
     */
    public final class ActivityManagerService extends ActivityManagerNative
            implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {
        ...
        @Override
        public final int startActivity(IApplicationThread caller, String callingPackage,
                                       Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
                                       int startFlags, ProfilerInfo profilerInfo, Bundle options) {
            return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
                    resultWho, requestCode, startFlags, profilerInfo, options,
                    UserHandle.getCallingUserId());
        }
        @Override
        public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
                                             Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
                                             int startFlags, ProfilerInfo profilerInfo, Bundle options, int userId) {
            enforceNotIsolatedCaller("startActivity");
            userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                    false, ALLOW_FULL_ONLY, "startActivity", null);
            //mStackSupervisor的类型是ActivityStackSupervisor
            return mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent,
                    resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
                    profilerInfo, null, null, options, userId, null, null);
        }
        ...
    }


    /**
     * step5 : ActivityStackSupervisor.startActivityMayWait()
     * 根据上面传递的参数和应用信息重新封装一些参数，然后调用startActivityLocked()方法
     */
    final int startActivityMayWait(IApplicationThread caller, int callingUid,
                                   String callingPackage, Intent intent, String resolvedType,
                                   IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                                   IBinder resultTo, String resultWho, int requestCode, int startFlags,
                                   ProfilerInfo profilerInfo, WaitResult outResult, Configuration config,
                                   Bundle options, int userId, IActivityContainer iContainer, TaskRecord inTask) {
        ...

        // Don't modify the client's object!
        intent = new Intent(intent);

        // 调用resolveActivity()根据意图intent，解析目标Activity的一些信息保存到aInfo中，
        // 这些信息包括activity的aInfo.applicationInfo.packageName、name、applicationInfo、processName、theme、launchMode、permission、flags等等
        // 这都是在AndroidManifest.xml中为activity配置的
        ActivityInfo aInfo = resolveActivity(intent, resolvedType, startFlags,
                profilerInfo, userId);

        ...
        synchronized (mService) {
            //下面省略的代码用于重新组织startActivityLocked()方法需要的参数
            ...
            //调用startActivityLocked开启目标activity
            int res = startActivityLocked(caller, intent, resolvedType, aInfo,
                    voiceSession, voiceInteractor, resultTo, resultWho,
                    requestCode, callingPid, callingUid, callingPackage,
                    realCallingPid, realCallingUid, startFlags, options,
                    componentSpecified, null, container, inTask);
            ...

            if (outResult != null) {
                //如果outResult不为null,则设置开启activity的结果
                outResult.result = res;
                ...

                return res;
            }
        }
    }

    /**
     * step6 : ActivityStackSupervisor.startActivityLocked()
     * 这个方法主要是判断一些错误信息和检查权限，如果没有发现错误（err==START_SUCCESS）就继续开启activity，
     * 否则直接返回错误码
     */
    final int startActivityLocked(IApplicationThread caller,
                                  Intent intent, String resolvedType, ActivityInfo aInfo,
                                  IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                                  IBinder resultTo, String resultWho, int requestCode,
                                  int callingPid, int callingUid, String callingPackage,
                                  int realCallingPid, int realCallingUid, int startFlags, Bundle options,
                                  boolean componentSpecified, ActivityRecord[] outActivity, ActivityContainer container,
                                  TaskRecord inTask) {
        int err = ActivityManager.START_SUCCESS;
        //调用者的进程信息，也就是哪个进程要开启此Activity的
        ProcessRecord callerApp = null;
        //下面有很多if语句，用于判断一些错误信息，并给err赋值相应的错误码
        if (caller != null) {
            callerApp = mService.getRecordForAppLocked(caller);
            if (callerApp != null) {
                callingPid = callerApp.pid;
                callingUid = callerApp.info.uid;
            } else {
                err = ActivityManager.START_PERMISSION_DENIED;
            }
        }
        ...
        if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
            err = ActivityManager.START_INTENT_NOT_RESOLVED;
        }
        if (err == ActivityManager.START_SUCCESS && aInfo == null) {
            // 未找到需要打开的activity的class文件
            err = ActivityManager.START_CLASS_NOT_FOUND;
        }
        ...
        //上面判断完成之后，接着判断如果err不为START_SUCCESS，则说明开启activity失败，直接返回错误码
        if (err != ActivityManager.START_SUCCESS) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                        resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            return err;
        }

        //检查权限，有些activity在清单文件中注册了权限，比如要开启系统相机，就需要注册相机权限，否则此处就会跑出异常
        final int startAnyPerm = mService.checkPermission(
                START_ANY_ACTIVITY, callingPid, callingUid);
        final int componentPerm = mService.checkComponentPermission(aInfo.permission, callingPid,
                callingUid, aInfo.applicationInfo.uid, aInfo.exported);
        if (startAnyPerm != PERMISSION_GRANTED && componentPerm != PERMISSION_GRANTED) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                        resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
            }
            String msg;
            //权限被拒绝，抛出异常
            if (!aInfo.exported) {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " not exported from uid " + aInfo.applicationInfo.uid;
            } else {
                msg = "Permission Denial: starting " + intent.toString()
                        + " from " + callerApp + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires " + aInfo.permission;
            }
            throw new SecurityException(msg);
        }

        //★★★经过上面的判断后，创建一个新的Activity记录，
        // 这个ActivityRecord就是被创建的activity在历史堆栈中的一个条目，表示一个活动
        ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
                intent, resolvedType, aInfo, mService.mConfiguration, resultRecord, resultWho,
                requestCode, componentSpecified, this, container, options);
        ...
        //继续调用startActivityUncheckedLocked()
        err = startActivityUncheckedLocked(r, sourceRecord, voiceSession, voiceInteractor,
                startFlags, true, options, inTask);
        ...
        return err;
    }

    /**
     * step7 : ActivityStackSupervisor.startActivityUncheckedLocked()
     * 通过第6步，新的activity记录已经创建了，接下来就是将这个activity放入到某个进程堆栈中；
     * startActivityLocked()中首先获取activity的启动模式（AndroidManifest.xml中为activity配置的launchMode属值），
     * 启动模式一共有四种ActivityInfo.LAUNCH_MULTIPLE（standard标准）、
     *               ActivityInfo.LAUNCH_SINGLE_INSTANCE（全局单例）、
     *               ActivityInfo.LAUNCH_SINGLE_TASK（进程中单例）、
     *               ActivityInfo.LAUNCH_SINGLE_TOP（栈顶单例）
     * 不清楚的可以参考官方网站http://developer.android.com/reference/android/content/pm/ActivityInfo.html
     *
     * 如果目标activity已经在某个历史进程中存在，需要根据启动模式分别判断并做相应处理，
     * 举个例子：如果启动模式为LAUNCH_SINGLE_INSTANCE，发现目标activity在某个进程中已经被启动过，
     *         这时候就将此进程置于进程堆栈栈顶，然后清除位于目标activity之上的activity，
     *         这样目标activity就位于栈顶了，这种情况就算是activity启动成功，直接返回
     *
     * 经过上面的判断处理，发现必须创建新的activity，并将其放入到某个进程中，就会进一步获取需要栖息的进程堆栈targetStack（创建新进程or已有的进程），
     * 最后调用(ActivityStack)targetStack.startActivityLocked()方法：
     */
    final int startActivityUncheckedLocked(ActivityRecord r, ActivityRecord sourceRecord,
                                           IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags,
                                           boolean doResume, Bundle options, TaskRecord inTask) {
        ...
        //① 获取并配置activity配置的启动模式
        int launchFlags = intent.getFlags();
        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (launchSingleInstance || launchSingleTask)) {
            launchFlags &=
                    ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        } else {
           ...
        }
        ...
        /*
         * 如果调用者不是来自另一个activity（不是在activity中调用startActivity）,
         * 但是给了我们用于放入心activity的一个明确的task，将执行下面代码
         *
         * 我们往上追溯，发现inTask是step4 中 ActivityManagerService.startActivityAsUser()方法传递的null，
         * 所以if里面的不会执行
         */
        if (sourceRecord == null && inTask != null && inTask.stack != null) {
            ...
        } else {
            inTask = null;
        }
        //根据activity的设置，如果满足下列条件，将launchFlags置为FLAG_ACTIVITY_NEW_TASK（创建新进程）
        if (inTask == null) {
            if (sourceRecord == null) {
                // This activity is not being started from another...  in this
                // case we -always- start a new task.
                //如果调用者为null，将launchFlags置为 创建一个新进程
                if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0 && inTask == null) {
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
                }
            } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                // 如果调用者的模式是SINGLE_INSTANCE，需要开启新进程
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            } else if (launchSingleInstance || launchSingleTask) {
                // 如果需要开启的activity的模式是SingleInstance或者SingleTask，也需要开新进程
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        }

        ActivityInfo newTaskInfo = null;   //新进程
        Intent newTaskIntent = null;
        ActivityStack sourceStack;    //调用者所在的进程
        //下面省略的代码是为上面三个变量赋值
        ...

        /*
         * ② 我们尝试将新的activity放在一个现有的任务中。但是如果activity被要求是singleTask或者singleInstance，
         * 我们会将activity放入一个新的task中.下面的if中主要处理将目标进程置于栈顶，然后将目标activity显示
         */
        if (((launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (launchFlags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || launchSingleInstance || launchSingleTask) {
            //如果被开启的activity不是需要开启新的进程，而是single instance或者singleTask，
            if (inTask == null && r.resultTo == null) {
                //检查此activity是否已经开启了,findTaskLocked()方法用于查找目标activity所在的进程
                ActivityRecord intentActivity = !launchSingleInstance ?
                        findTaskLocked(r) : findActivityLocked(intent, r.info);
                if (intentActivity != null) {
                    ...
                    targetStack = intentActivity.task.stack;
                    ...
                    //如果目标activity已经开启，目标进程不在堆栈顶端，我们需要将它置顶
                    final ActivityStack lastStack = getLastStack();
                    ActivityRecord curTop = lastStack == null? null : lastStack.topRunningNonDelayedActivityLocked(notTop);
                    boolean movedToFront = false;
                    if (curTop != null && (curTop.task != intentActivity.task ||
                            curTop.task != lastStack.topTask())) {
                        r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                        if (sourceRecord == null || (sourceStack.topActivity() != null &&
                                sourceStack.topActivity().task == sourceRecord.task)) {
                            ...
                            //置顶进程
                            targetStack.moveTaskToFrontLocked(intentActivity.task, r, options,
                                    "bringingFoundTaskToFront");
                            ...
                            movedToFront = true;
                        }
                    }
                    ...
                    if ((launchFlags &
                            (Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            == (Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)) {
                        //如果调用者要求完全替代已经存在的进程
                        reuseTask = intentActivity.task;
                        reuseTask.performClearTaskLocked();
                        reuseTask.setIntent(r);
                    } else if ((launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                            || launchSingleInstance || launchSingleTask) {
                        //将进程堆栈中位于目标activity上面的其他activitys清理掉
                        ActivityRecord top = intentActivity.task.performClearTaskLocked(r, launchFlags);
                    } else if (r.realActivity.equals(intentActivity.task.realActivity)) {
                        //如果进程最上面的activity就是目标activity,进行一些设置操作
                        ...
                    } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                        ...
                    } else if (!intentActivity.task.rootWasReset) {
                        ...
                    }
                    if (!addingToTask && reuseTask == null) {
                        //让目标activity显示，会调用onResume
                        if (doResume) {
                            targetStack.resumeTopActivityLocked(null, options);
                        } else {
                            ActivityOptions.abort(options);
                        }
                        //直接返回
                        return ActivityManager.START_TASK_TO_FRONT;
                    }
                }
            }
        }

        // ③ 判断包名是否解析成功，如果包名解析不成功无法开启activity
        if (r.packageName != null) {
            //当前处于堆栈顶端的进程和activity
            ActivityStack topStack = getFocusedStack();
            ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(notTop);
            if (top != null && r.resultTo == null) {
                if (top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                    if (top.app != null && top.app.thread != null) {
                        if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                                || launchSingleTop || launchSingleTask) {
                            ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top,
                                    top.task);
                            ...
                            return ActivityManager.START_DELIVERED_TO_TOP;
                        }
                    }
                }
            }

        } else {
            // 包名为空，直接返回，没有找到要打开的activity
            ...
            return ActivityManager.START_CLASS_NOT_FOUND;
        }

        // ④ 判断activiy应该在那个进程中启动，如果该进程中已经存在目标activity，根据启动模式做相应处理
        ...
        // 判断是否需要开启新进程?
        boolean newTask = false;
        if (r.resultTo == null && inTask == null && !addingToTask
                && (launchFlags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            ...
            newTask = true;   //如果有FLAG_ACTIVITY_NEW_TASK标志，将为目标activity开启新的进程
            targetStack = adjustStackFocus(r, newTask);
            if (!launchTaskBehind) {
                targetStack.moveToFront("startingNewTask");
            }
            if (reuseTask == null) {
                r.setTask(targetStack.createTaskRecord(getNextTaskId(),
                        newTaskInfo != null ? newTaskInfo : r.info,
                        newTaskIntent != null ? newTaskIntent : intent,
                        voiceSession, voiceInteractor, !launchTaskBehind /* toTop */),
                        taskToAffiliate);
                if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r + " in new task " +
                        r.task);
            } else {
                r.setTask(reuseTask, taskToAffiliate);
            }
            ...
        } else if (sourceRecord != null) {
            //调用者不为空
            final TaskRecord sourceTask = sourceRecord.task;
            //默认在调用者所在进程启动，需要将进程置前
            targetStack = sourceTask.stack;
            targetStack.moveToFront("sourceStackToFront");
            final TaskRecord topTask = targetStack.topTask();
            if (topTask != sourceTask) {
                targetStack.moveTaskToFrontLocked(sourceTask, r, options, "sourceTaskToFront");
            }
            if (!addingToTask && (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                //没有FLAG_ACTIVITY_CLEAR_TOP标志时，开启activity
                ActivityRecord top = sourceTask.performClearTaskLocked(r, launchFlags);
                if (top != null) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    ...
                    targetStack.resumeTopActivityLocked(null);
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            } else if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
                //如果activity在当前进程中已经开启，清除位于他之上的activity
                final ActivityRecord top = sourceTask.findActivityInHistoryLocked(r);
                if (top != null) {
                    final TaskRecord task = top.task;
                    task.moveActivityToFrontLocked(top);
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, task);
                    ...
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            }
            r.setTask(sourceTask, null);
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r + " in existing task " + r.task + " from source " + sourceRecord);

        } else if (inTask != null) {
            //在调用者指定的确定的进程中开启目标activity
            ...
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r  + " in explicit task " + r.task);
        } else {
            ...
        }

        ...
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, r, r.task);
        targetStack.mLastPausedActivity = null;
        //⑤ 继续调用目标堆栈ActivityStack的startActivityLocked()方法，这个方法没有返回值，执行完毕之后直接返回START_SUCCESS
        targetStack.startActivityLocked(r, newTask, doResume, keepCurTransition, options);
        if (!launchTaskBehind) {
            // Don't set focus on an activity that's going to the back.
            mService.setFocusedActivityLocked(r, "startedActivity");
        }
        return ActivityManager.START_SUCCESS;
    }


    /**
     * step8 : ActivityStack.startActivityLocked()
     * ActivityStack用于管理activity的堆栈状态，startActivityLocked()方法就是将某个activity记录放入堆栈中
     * startActivityLocked()方法接受的参数：
     *                      r为需要启动的activity的记录信息；
     *                      newTask：是否需要启动新进程
     *                      doResume：是否需要获取焦点（true）
     *                      ...
     * 这个方法中主要进行一些堆栈切换工作，将目标activity所在的堆栈置顶，
     * 然后再栈顶放入新的activtiy记录，最后调用mStackSupervisor.resumeTopActivitiesLocked(this, r, options)方法
     * 将位于栈顶的activity显示出来：
     *
     */
    final void startActivityLocked(ActivityRecord r, boolean newTask,
                                   boolean doResume, boolean keepCurTransition, Bundle options) {
        TaskRecord rTask = r.task;
        final int taskId = rTask.taskId;
        // mLaunchTaskBehind tasks get placed at the back of the task stack.
        if (!r.mLaunchTaskBehind && (taskForIdLocked(taskId) == null || newTask)) {
            // Last activity in task had been removed or ActivityManagerService is reusing task.
            // Insert or replace.
            // Might not even be in.
            insertTaskAtTop(rTask);
            mWindowManager.moveTaskToTop(taskId);
        }
        TaskRecord task = null;
        //①.不用创建新进程的情况，需要做一些任务切换操作
        if (!newTask) {
            boolean startIt = true;
            //遍历所有的任务，找到目标activity所在的堆栈，taskNdx为所有的task的数量，肯定是大于0
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                task = mTaskHistory.get(taskNdx);
                if (task.getTopActivity() == null) {
                    // 如果进程中activity为空，继续遍历
                    continue;
                }
                if (task == r.task) {
                    //如果当前task==需要开启的activity的进程
                    if (!startIt) {
                        if (DEBUG_ADD_REMOVE) Slog.i(TAG, "Adding activity " + r + " to task "
                                + task, new RuntimeException("here").fillInStackTrace());
                        // 将需要启动的activity的记录放入task堆栈的顶层
                        task.addActivityToTop(r);
                        r.putInHistory();
                        mWindowManager.addAppToken(task.mActivities.indexOf(r), r.appToken,
                                r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                                (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0,
                                r.userId, r.info.configChanges, task.voiceSession != null,
                                r.mLaunchTaskBehind);
                        ...
                        return;
                    }
                    break;
                } else if (task.numFullscreen > 0) {
                    startIt = false;
                }
            }
        }

        //②. 在处于栈顶的进程中放置新的activity，这个activity将是即将和用户交互的界面
        task = r.task;
        //将activity插入历史堆栈顶层
        task.addActivityToTop(r);
        task.setFrontOfTask();
        r.putInHistory();
        if (!isHomeStack() || numActivities() > 0) {
            /*
             * 如果我们需要切换到一个新的任务，或者下一个activity不是当前正在运行的，
             * 我们需要显示启动预览窗口，在这里可能执行一切窗口切换的动画效果
             */
            boolean showStartingIcon = newTask;
            ProcessRecord proc = r.app;
            ...
            if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                mWindowManager.prepareAppTransition(AppTransition.TRANSIT_NONE, keepCurTransition);
                mNoAnimActivities.add(r);
            } else {
                //执行切换动画
                mWindowManager.prepareAppTransition(...);
                mNoAnimActivities.remove(r);
            }
            mWindowManager.addAppToken(task.mActivities.indexOf(r),
                    r.appToken, r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                    (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0, r.userId,
                    r.info.configChanges, task.voiceSession != null, r.mLaunchTaskBehind);
            ...
        } else {
            /*
             * 如果需要启动的activity的信息已经是堆栈中第一个，不需要执行动画
             */
            mWindowManager.addAppToken(task.mActivities.indexOf(r), r.appToken,
                    r.task.taskId, mStackId, r.info.screenOrientation, r.fullscreen,
                    (r.info.flags & ActivityInfo.FLAG_SHOW_ON_LOCK_SCREEN) != 0, r.userId,
                    r.info.configChanges, task.voiceSession != null, r.mLaunchTaskBehind);
            ...
        }
        ...
        if (doResume) {
            //此处的doResume参数为true，继续调用ActivityStackSupervisor.resumeTopActivitiesLocked()
            mStackSupervisor.resumeTopActivitiesLocked(this, r, options);
        }
    }


    /**
     * step9 : ActivityStackSupervisor.resumeTopActivitiesLocked()
     * 这个方法按方法名可以猜测它会将堆栈中的最顶部的activity显示出来，
     */
    boolean resumeTopActivitiesLocked(ActivityStack targetStack, ActivityRecord target,
                                      Bundle targetOptions) {
        if (targetStack == null) {
            targetStack = getFocusedStack();
        }
        // Do targetStack first.
        boolean result = false;
        //是否是栈顶的任务
        if (isFrontStack(targetStack)) {
            result = targetStack.resumeTopActivityLocked(target, targetOptions);
        }
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                if (stack == targetStack) {
                    // Already started above.
                    continue;
                }
                if (isFrontStack(stack)) {
                    //会调用resumeTopActivityLocked(ActivityRecord prev, Bundle options)
                    stack.resumeTopActivityLocked(null);
                }
            }
        }
        return result;
    }


    /**
     * step10 : ActivityStack.resumeTopActivityLocked()
     * resumeTopActivityLocked()方法继续调用resumeTopActivityInnerLocked()方法，
     */
    final boolean resumeTopActivityLocked(ActivityRecord prev, Bundle options) {
        if (mStackSupervisor.inResumeTopActivity) {
            // Don't even start recursing.
            return false;
        }

        boolean result = false;
        try {
            // Protect against recursion.
            mStackSupervisor.inResumeTopActivity = true;
            if (mService.mLockScreenShown == ActivityManagerService.LOCK_SCREEN_LEAVING) {
                mService.mLockScreenShown = ActivityManagerService.LOCK_SCREEN_HIDDEN;
                mService.updateSleepIfNeededLocked();
            }
            //继续调用resumeTopActivityInnerLocked()
            result = resumeTopActivityInnerLocked(prev, options);
        } finally {
            mStackSupervisor.inResumeTopActivity = false;
        }
        return result;
    }

    /**
     * resumeTopActivityInnerLocked()中，主要会经历三个步骤，第一步需要将当前正在显示的activity置于pausing状态;
     * 然后启动栈顶的activity(也就是目标activity)，如果目标activity已经被启动过，会将其置于resume状态；
     * 否则将重新启动activity，由于现在我们研究的acivity的启动，所以继续跟踪ActivityStackSupervisor.startSpecificActivityLocked()
     */
    final boolean resumeTopActivityInnerLocked(ActivityRecord prev, Bundle options) {
        ...

        // We need to start pausing the current activity so the top one
        // can be resumed...
        //① 需要将现在的activity置于pausing状态，然后才能将栈顶的activity处于resume状态
        boolean dontWaitForPause = (next.info.flags&ActivityInfo.FLAG_RESUME_WHILE_PAUSING) != 0;
        boolean pausing = mStackSupervisor.pauseBackStacks(userLeaving, true, dontWaitForPause);
        if (mResumedActivity != null) {
            if (DEBUG_STATES) Slog.d(TAG, "resumeTopActivityLocked: Pausing " + mResumedActivity);
            pausing |= startPausingLocked(userLeaving, false, true, dontWaitForPause);
        }
        ...

        // Launching this app's activity, make sure the app is no longer
        // considered stopped.
        //② 启动栈顶的activity
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    next.packageName, false, next.userId); /* TODO: Verify if correct userid */
        } catch (RemoteException e1) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }
        ...

        //③ 判断栈顶activity是否启动，如果已经启动将其置为resume状态，如果没有启动将重新启动activity
        if (next.app != null && next.app.thread != null) {
            //如果栈顶的activity不为空，并且其thread成员（ApplicationThread）不为空，说明activity已经启动（执行了attach()）
            if (DEBUG_SWITCH) Slog.v(TAG, "Resume running: " + next);
            // This activity is now becoming visible.
            mWindowManager.setAppVisibility(next.appToken, true);
            ...
            try {
                ...
                next.sleeping = false;
                mService.showAskCompatModeDialogLocked(next);
                next.app.pendingUiClean = true;
                next.app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_TOP);
                next.clearOptionsLocked();
                //回调activity的onResume()方法
                next.app.thread.scheduleResumeActivity(next.appToken, next.app.repProcState,
                        mService.isNextTransitionForward(), resumeAnimOptions);
                ...
            } catch (Exception e) {

                //抛异常后，需要启动activity
                mStackSupervisor.startSpecificActivityLocked(next, true, false);
                if (DEBUG_STACK) mStackSupervisor.validateTopActivitiesLocked();
                return true;
            }
            ...
        } else {
            // 否则需要重新启动activity
            ...
            mStackSupervisor.startSpecificActivityLocked(next, true, true);
        }
        return true;
    }


    /**
     * step11：ActivityStackSupervisor.startSpecificActivityLocked()
     * 这个方法用于判断需要开启的activity所在的进程是否已经启动，
     * 如果已经启动，会执行第①中情况开启activity，
     * 如果没有启动，将会走第②中情况，先去启动进程，然后在开启activity。
     * 由于第②种情况是一个比较完整的过程，并且后面也会调用realStartActivityLocked()方法开启activity，
     * 所以，我们继续分析第②种。
     */
    void startSpecificActivityLocked(ActivityRecord r,
                                     boolean andResume, boolean checkConfig) {
        //判断activity所属的应用程序的进程（process + uid）是否已经启动
        ProcessRecord app = mService.getProcessRecordLocked(r.processName,
                r.info.applicationInfo.uid, true);
        r.task.stack.setLaunchTime(r);
        if (app != null && app.thread != null) {
            try {
                ...
                /*
                 * ① 如果应用已经启动，并且进程中的thread对象不为空，
                 *   调用realStartActivityLocked()方法创建activity对象
                 *
                 *   继续跟下去，会发现调用activity的onCreate方法
                 */
                realStartActivityLocked(r, app, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }
            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }
        //② 如果抛出了异常或者获取的应用进程为空，需用重新启动应用程序，点击Launcher桌面上图表时走这里
        mService.startProcessLocked(r.processName, r.info.applicationInfo, true, 0,
                "activity", r.intent.getComponent(), false, false, true);
    }

    /**
     * step12: ActivityManagerService.startProcessLocked()
     * 创建新进程。在第二个startProcessLocked()方法中主要进行一些判断，判断是否需要创建新进程，
     * 紧接着调用无返回值的startProcessLocked()方法，在这个方法中通过Process.start接口创建出新的进程
     */
    final ProcessRecord startProcessLocked(String processName,
                                           ApplicationInfo info, boolean knownToBeDead, int intentFlags,
                                           String hostingType, ComponentName hostingName, boolean allowWhileBooting,
                                           boolean isolated, boolean keepIfLarge) {
        return startProcessLocked(processName, info, knownToBeDead, intentFlags, hostingType,
                hostingName, allowWhileBooting, isolated, 0 /* isolatedUid */, keepIfLarge,
                null /* ABI override */, null /* entryPoint */, null /* entryPointArgs */,
                null /* crashHandler */);
    }
    final ProcessRecord startProcessLocked(String processName, ApplicationInfo info,
                                           boolean knownToBeDead, int intentFlags, String hostingType, ComponentName hostingName,
                                           boolean allowWhileBooting, boolean isolated, int isolatedUid, boolean keepIfLarge,
                                           String abiOverride, String entryPoint, String[] entryPointArgs, Runnable crashHandler) {
        long startTime = SystemClock.elapsedRealtime();
        ProcessRecord app;
        //isolated==false
        if (!isolated) {
            //再次检查是否已经有以process + uid命名的进程存在
            app = getProcessRecordLocked(processName, info.uid, keepIfLarge);
            checkTime(startTime, "startProcess: after getProcessRecord");
        } else {
            app = null;
        }
        //接下来有一些if语句，用于判断是否需要创建新进程，如果满足下面三种情况，就不会创建新进程
        // We don't have to do anything more if:
        // (1) There is an existing application record; and
        // (2) The caller doesn't think it is dead, OR there is no thread
        //     object attached to it so we know it couldn't have crashed; and
        // (3) There is a pid assigned to it, so it is either starting or
        //     already running.
        ...

        //继续调用startProcessLocked()真正创建新进程
        startProcessLocked(app, hostingType, hostingNameStr, abiOverride, entryPoint, entryPointArgs);
        return (app.pid != 0) ? app : null;
    }

    private final void startProcessLocked(ProcessRecord app, String hostingType,
                                          String hostingNameStr, String abiOverride, String entryPoint, String[] entryPointArgs) {
        ...
        try {
            //下面代码主要是初始化新进程需要的参数
            int uid = app.uid;
            int[] gids = null;
            int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
            if (!app.isolated) {
                int[] permGids = null;
                try {
                    final PackageManager pm = mContext.getPackageManager();
                    permGids = pm.getPackageGids(app.info.packageName);

                    ...
                } catch (PackageManager.NameNotFoundException e) {
                }
                if (permGids == null) {
                    gids = new int[2];
                } else {
                    gids = new int[permGids.length + 2];
                    System.arraycopy(permGids, 0, gids, 2, permGids.length);
                }
                gids[0] = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
                gids[1] = UserHandle.getUserGid(UserHandle.getUserId(uid));
            }
            ...

            app.gids = gids;
            app.requiredAbi = requiredAbi;
            app.instructionSet = instructionSet;

            //是否是Activity所在的进程，此处entryPoint为null所以isActivityProcess为true
            boolean isActivityProcess = (entryPoint == null);
            if (entryPoint == null)
                entryPoint = "android.app.ActivityThread";
            /*
             * 调用Process.start接口来创建一个新的进程，并会创建一个android.app.ActivityThread类的对象，
             * 并且执行它的main函数，ActivityThread是应用程序的主线程
             */
            Process.ProcessStartResult startResult =
                    Process.start(entryPoint,
                    app.processName, uid, uid, gids, debugFlags, mountExternal,
                    app.info.targetSdkVersion, app.info.seinfo, requiredAbi, instructionSet,
                    app.info.dataDir, entryPointArgs);
            ...
        } catch (RuntimeException e) {
            //创建进程失败
            Slog.e(TAG, "Failure starting process " + app.processName, e);
        }
    }

    /**
     * 经过step12后，新的进程已经创建完成，我们知道线程是程序执行的最小单元，
     * 线程栖息于进程中，每个进程在创建完毕后都会有一个主线程被开启，在大多数变成语言中线程的入口都是通过main函数，
     * 这里也不例外，当进程创建完毕后，进程的主线程就被创建了，并会调用其main方法，接着我们来到ActivityThread:
     *
     * step13: ActivityThread
     * ActivityThread是应用程序进程中的主线程，他的作用是调度和执行activities、广播和其他操作。
     * main方法开启了消息循环机制，并调用attach()方法，attach()方法会调用ActivityManagerNative.getDefault()
     * 获取到一个ActivityManagerProxy示例，上面step3中我们讲解了ActivityManagerNative这个类，
     * ActivityManagerProxy中维护了ActivityManagerService的远程代理对象mRemote；
     * 然后会调用attachApplication()方法通过mRemote调用到ActivityManagerService的attachApplication()中，
     * 传入的mAppThread是ApplicationThread类型，mAppThread实际上通过Handler实现ActivityManagerService与
     * ActivityThread的消息通信。
     */
    public final class ActivityThread {
        final ApplicationThread mAppThread = new ApplicationThread();
        //新的进程创建后就会执行这个main方法
        public static void main(String[] args) {
            ...

            Looper.prepareMainLooper();

            //创建一个ActivityThread实例，并调用他的attach方法
            ActivityThread thread = new ActivityThread();
            thread.attach(false);
            ...

            if (false) {
                Looper.myLooper().setMessageLogging(new
                        LogPrinter(Log.DEBUG, "ActivityThread"));
            }
            ...
            //进入消息循环
            Looper.loop();

            throw new RuntimeException("Main thread loop unexpectedly exited");
        }

        private void attach(boolean system) {
            sCurrentActivityThread = this;
            mSystemThread = system;
            if (!system) {
                ...
                /*
                 * ActivityManagerNative.getDefault()方法返回的是一个ActivityManagerProxy对象，
                 * ActivityManagerProxy实现了IActivityManager接口，并维护了一个mRemote，
                 * 这个mRemote就是ActivityManagerService的远程代理对象
                 */
                final IActivityManager mgr = ActivityManagerNative.getDefault();
                try {
                    //调用attachApplication()，并将mAppThread传入，mAppThread是ApplicationThread类的示例，他的作用是用来进程间通信的
                    mgr.attachApplication(mAppThread);
                } catch (RemoteException ex) {
                    // Ignore
                }
                ...
            } else {
               ...
            }
         ...
        }
    }

    /**
     * step14: ActivityManagerNative内部类ActivityManagerProxy.attachApplication()
     * attachApplication()接受IApplicationThread实例，step13中attach()方法传入的
     * ApplicationThread实现了IApplicationThread，然后通过ActivityManagerService的远程代理对象mRemote，
     * 进入ActivityManagerService的attachApplication
     */
    public void attachApplication(IApplicationThread app) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(IActivityManager.descriptor);
        data.writeStrongBinder(app.asBinder());
        mRemote.transact(ATTACH_APPLICATION_TRANSACTION, data, reply, 0);
        reply.readException();
        data.recycle();
        reply.recycle();
    }

    /**
     * step15: ActivityManagerService.attachApplication()
     * attachApplication()方法调用了attachApplicationLocked()方法，
     * 在step12中，我们创建了一个ProcessRecord，这里通过进程的pid将他取出来，赋值给app，
     * 并初始化app的一些成员变量，然后为当前进程启动顶层activity、一些服务和广播;
     * 这里我们就不深入研究到底启动的是那些，我们主要研究activity的启动，所以重点看第③步，
     * step6中最后创建了一个ActivityRecord实例r，这个r只是进程堆栈中的一个活动记录，
     * 然后再step8中将这个r插入到堆栈最顶端，所以这个r相当于一个占位，并不是真正启动的Activity，
     * 真正启动Activity需要判断进程是否存在，如果存在就直接启动，如果不存在需要启动进程后再执行此处第③步
     * 调用ActivityStackSupervisor.attachApplicationLocked(ProcessRecord)方法：
     */
    @Override
    public final void attachApplication(IApplicationThread thread) {
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            final long origId = Binder.clearCallingIdentity();
            //接着调用attachApplicationLocked()
            attachApplicationLocked(thread, callingPid);
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final boolean attachApplicationLocked(IApplicationThread thread,
                                                  int pid) {
        //① 获取到进程
        ProcessRecord app;
        if (pid != MY_PID && pid >= 0) {
            synchronized (mPidsSelfLocked) {
                app = mPidsSelfLocked.get(pid);
            }
        } else {
            app = null;
        }
        if (app == null) {
            if (pid > 0 && pid != MY_PID) {
                ...
                Process.killProcessQuiet(pid);
            } else {
                thread.scheduleExit();
                ...
            }
            return false;
        }
        ...
        //② 对app的一些成员变量进行初始化
        app.makeActive(thread, mProcessStats);
        app.curAdj = app.setAdj = -100;
        app.curSchedGroup = app.setSchedGroup = Process.THREAD_GROUP_DEFAULT;
        app.forcingToForeground = null;
        updateProcessForegroundLocked(app, false, false);
        app.hasShownUi = false;
        app.debugging = false;
        app.cached = false;
        app.killedByAm = false;

        ...
        boolean normalMode = mProcessesReady || isAllowedWhileBooting(app.info);
        ...
        boolean badApp = false;
        boolean didSomething = false;

        // See if the top visible activity is waiting to run in this process...
        /*
         * ③ 检查当前进程中顶端的activity是否等着被运行，这个顶端的activity就是我们要启动的activity；
         *
         *    此处适用于需要为activity创建新进程的情况（比如点击Launcher桌面上的图标启动应用，或者打开配置了process的activity）
         *
         *    如果应用程序已经启动，在应用程序内部启动activity(未配置process)不会创建进程，这种情况回到step11中的第①步直接开启activity
         */
        if (normalMode) {
            try {
                if (mStackSupervisor.attachApplicationLocked(app)) {
                    didSomething = true;
                }
            } catch (Exception e) {
                Slog.wtf(TAG, "Exception thrown launching activities in " + app, e);
                badApp = true;
            }
        }

        // Find any services that should be running in this process...
        //④ 查找当前进程中应该启动的服务，并将其启动
        if (!badApp) {
            try {
                didSomething |= mServices.attachApplicationLocked(app, processName);
            } catch (Exception e) {
                Slog.wtf(TAG, "Exception thrown starting services in " + app, e);
                badApp = true;
            }
        }

        // Check if a next-broadcast receiver is in this process...
        //⑤ 查找当前进程中应该注册的广播
        if (!badApp && isPendingBroadcastProcessLocked(pid)) {
            try {
                didSomething |= sendPendingBroadcastsLocked(app);
            } catch (Exception e) {
                // If the app died trying to launch the receiver we declare it 'bad'
                Slog.wtf(TAG, "Exception thrown dispatching broadcasts in " + app, e);
                badApp = true;
            }
        }
        // Check whether the next backup agent is in this process...
        ...

        return true;
    }


    /**
     * step16: ActivityStackSupervisor.attachApplicationLocked(ProcessRecord)
     *
     * 这个方法中首先遍历进程中的堆栈，找到位于顶层的堆栈，然后调用topRunningActivityLocked()
     * 获取位于栈顶的ActivityRecord记录，最后调用realStartActivityLocked()方法启动activity
     *
     */
    boolean attachApplicationLocked(ProcessRecord app) throws RemoteException {
        final String processName = app.processName;
        boolean didSomething = false;
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                //遍历进程中的堆栈，找到最顶层的堆栈
                final ActivityStack stack = stacks.get(stackNdx);
                if (!isFrontStack(stack)) {
                    continue;
                }
                //
                /*
                 * 获取位于顶层堆栈中栈顶的activity，这个activity就是目标activity(需要被启动的);
                 * 这个hr就是step6中创建的ActivityRecord实例r
                 */
                ActivityRecord hr = stack.topRunningActivityLocked(null);
                if (hr != null) {
                    if (hr.app == null && app.uid == hr.info.applicationInfo.uid
                            && processName.equals(hr.processName)) {
                        try {
                            //调用realStartActivityLocked()方法启动activity，同step11中的第①步
                            if (realStartActivityLocked(hr, app, true, true)) {
                                didSomething = true;
                            }
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Exception in new application when starting activity "
                                    + hr.intent.getComponent().flattenToShortString(), e);
                            throw e;
                        }
                    }
                }
            }
        }
        if (!didSomething) {
            ensureActivitiesVisibleLocked(null, 0);
        }
        return didSomething;
    }


    /**
     * ★★★step17: ActivityStackSupervisor.realStartActivityLocked()
     * 这个方法调用app.thread.scheduleLaunchActivity()真正的启动一个activity，
     * 这个thread是IApplicationThread的实例，也就是ActivityThread中的成员变量ApplicationThread mAppThread；
     * 在step15的第②步初始化app时调用app.makeActive(thread, mProcessStats)为其赋值的。
     * 我们接着看看ApplicationThread.scheduleLaunchActivity():
     *
     */
    final boolean realStartActivityLocked(ActivityRecord r,
                                          ProcessRecord app, boolean andResume, boolean checkConfig)
            throws RemoteException {
        ...

        r.app = app;
        ...

        int idx = app.activities.indexOf(r);
        if (idx < 0) {
            app.activities.add(r);
        }
        final ActivityStack stack = r.task.stack;

        try {
            ...
            List<ResultInfo> results = null;
            List<ReferrerIntent> newIntents = null;
            if (andResume) {
                results = r.results;
                newIntents = r.newIntents;
            }
            ...
            //
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken,
                    System.identityHashCode(r), r.info, new Configuration(mService.mConfiguration),
                    r.compat, r.launchedFromPackage, r.task.voiceInteractor, app.repProcState,
                    r.icicle, r.persistentState, results, newIntents, !andResume,
                    mService.isNextTransitionForward(), profilerInfo);
           ...
        } catch (RemoteException e) {
            if (r.launchFailed) {
                //This is the second time we failed -- finish activity and give up.
                ...
                return false;
            }
            // This is the first time we failed -- restart process and retry.
            app.activities.remove(r);
            throw e;
        }
        ...

        return true;
    }


    /**
     * step18: ApplicationThread.scheduleLaunchActivity()
     * 这里的第二个参数r，是一个ActivityRecord类型的Binder对象，用来作来这个Activity的token值。
     *
     * ActivityThread中有一个H的成员变量，它是一个Handler，
     * 专门接受ApplicationThread发送的消息，然后调用ActivityThread中的方法，
     * 我们看看这个H是怎样处理消息的：
     *
     */
    @Override
    public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
                                             ActivityInfo info, Configuration curConfig, Configuration overrideConfig,
                                             CompatibilityInfo compatInfo, String referrer, IVoiceInteractor voiceInteractor,
                                             int procState, Bundle state, PersistableBundle persistentState,
                                             List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
                                             boolean notResumed, boolean isForward, ProfilerInfo profilerInfo) {
        updateProcessState(procState, false);

        ActivityClientRecord r = new ActivityClientRecord();

        r.token = token;
        r.ident = ident;
        r.intent = intent;
        r.referrer = referrer;
        r.voiceInteractor = voiceInteractor;
        r.activityInfo = info;
        r.compatInfo = compatInfo;
        r.state = state;
        r.persistentState = persistentState;

        r.pendingResults = pendingResults;
        r.pendingIntents = pendingNewIntents;

        r.startsNotResumed = notResumed;
        r.isForward = isForward;

        r.profilerInfo = profilerInfo;

        r.overrideConfig = overrideConfig;
        updatePendingConfiguration(curConfig);

        sendMessage(H.LAUNCH_ACTIVITY, r);
    }


    /**
     * step19:ActivityThread.H
     * 上面调用sendMessage(H.LAUNCH_ACTIVITY, r)之后，mH会收到一个LAUNCH_ACTIVITY消息，
     * 然后调用了ActivityThread.handleLaunchActivity(r, null)
     */
    public final class ActivityThread {
        ...
        final ApplicationThread mAppThread = new ApplicationThread();
        final H mH = new H();

        private void sendMessage(int what, Object obj) {
            sendMessage(what, obj, 0, 0, false);
        }
        ...
        private void sendMessage(int what, Object obj, int arg1, int arg2, boolean async) {
            if (DEBUG_MESSAGES) Slog.v(
                    TAG, "SCHEDULE " + what + " " + mH.codeToString(what)
                            + ": " + arg1 + " / " + obj);
            Message msg = Message.obtain();
            msg.what = what;
            msg.obj = obj;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            if (async) {
                msg.setAsynchronous(true);
            }
            mH.sendMessage(msg);
        }

        private class H extends Handler {
            public void handleMessage(Message msg) {
                if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + codeToString(msg.what));
                switch (msg.what) {
                    case LAUNCH_ACTIVITY: {     //启动activity
                        ...
                        handleLaunchActivity(r, null);
                        ...
                    }
                    break;
                    case RELAUNCH_ACTIVITY: {   //重新启动activity
                        ...
                        handleRelaunchActivity(r);
                        ...
                    }
                    break;
                    case PAUSE_ACTIVITY:        //activity失去焦点
                        ...
                        handlePauseActivity((IBinder) msg.obj, false, (msg.arg1 & 1) != 0, msg.arg2,
                                (msg.arg1 & 2) != 0);
                        ...
                        break;
                    ...
                    case RESUME_ACTIVITY:       //activity获取焦点
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "activityResume");
                        handleResumeActivity((IBinder) msg.obj, true, msg.arg1 != 0, true);
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                        break;
                    ...
                }
            }
        }
    }

    /**
     * step20:ActivityThread.handleLaunchActivity(r, null)
     *
     *  这里首先调用performLaunchActivity()方法创建Activity对象(调用它的attach()和onCreate()方法)，
     *  然后调用handleResumeActivity函数来使这个Activity进入Resumed状态，并回调这个Activity的onResume函数。
     */
    private void handleLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        ...
        //创建Activity对象，并初始化，然后调用activity.attach()和onCreate()
        Activity a = performLaunchActivity(r, customIntent);
        if (a != null) {
            r.createdConfig = new Configuration(mConfiguration);
            Bundle oldState = r.state;
            //调用activity.onResume()
            handleResumeActivity(r.token, false, r.isForward,
                    !r.activity.mFinished && !r.startsNotResumed);

            ...
        } else {
            ...
        }
    }

    /**
     * step21: ActivityThread.performLaunchActivity()
     * 首先根据activity的className加载类文件，并创建activity实例，然后初始化上下文Context，并调用attach()方法初始化activity,
     * 通过mInstrumentation调用activity的onCreate()方法，这样Activity算是创建完成了
     */
    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        //① 收集要启动的Activity的相关信息，主要package和component
        ActivityInfo aInfo = r.activityInfo;
        ...
        ComponentName component = r.intent.getComponent();
        ...

        Activity activity = null;
        try {
            //② 通过类加载器将activity的类加载进来，然后创建activity对象
            ClassLoader cl = r.packageInfo.getClassLoader();
            activity = mInstrumentation.newActivity(
                    cl, component.getClassName(), r.intent);
            StrictMode.incrementExpectedActivityCount(activity.getClass());
            r.intent.setExtrasClassLoader(cl);
            r.intent.prepareToEnterProcess();
            if (r.state != null) {
                r.state.setClassLoader(cl);
            }
        } catch (Exception e) {
            ...
        }

        try {
            //③ 创建Application，也就是AndroidManifest.xml配置的<application/>
            Application app = r.packageInfo.makeApplication(false, mInstrumentation);
            ...
            if (activity != null) {
                //④ 创建Activity的上下文Content，并通过attach方法将这些信息设置到Activity中去
                Context appContext = createBaseContextForActivity(r, activity);
                CharSequence title = r.activityInfo.loadLabel(appContext.getPackageManager());
                Configuration config = new Configuration(mCompatConfiguration);

                //⑤ 调用activity的attach()方法，这个方法的作用主要是为activity初始化一些成员变量
                activity.attach(appContext, this, getInstrumentation(), r.token,
                        r.ident, app, r.intent, r.activityInfo, title, r.parent,
                        r.embeddedID, r.lastNonConfigurationInstances, config,
                        r.referrer, r.voiceInteractor);

                if (customIntent != null) {
                    activity.mIntent = customIntent;
                }
                r.lastNonConfigurationInstances = null;
                activity.mStartedActivity = false;
                int theme = r.activityInfo.getThemeResource();
                if (theme != 0) {
                    activity.setTheme(theme);
                }

                activity.mCalled = false;
                /*
                 * ⑥ 通过mInstrumentation调用activity的onCreate()方法，
                 *    mInstrumentation的作用是监控Activity与系统的交互操做。
                 *    这时候activity的生命周期就开始了
                 */
                if (r.isPersistable()) {
                    mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
                } else {
                    mInstrumentation.callActivityOnCreate(activity, r.state);
                }
                //下面主要为堆栈中的ActivityClientRecord设置一些数据
                ...
                r.activity = activity;
                r.stopped = true;
                if (!r.activity.mFinished) {
                    activity.performStart();
                    r.stopped = false;
                }
                ...
            }
            r.paused = true;
            ...
        } catch (SuperNotCalledException e) {
            throw e;

        } catch (Exception e) {
            if (!mInstrumentation.onException(activity, e)) {
                throw new RuntimeException(
                        "Unable to start activity " + component
                                + ": " + e.toString(), e);
            }
        }

        return activity;
    }

    /**
     * step22: ActivityThread.handleResumeActivity()
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
                //获取activity的顶层视图decor
                View decor = r.window.getDecorView();
                //让decor显示出来
                decor.setVisibility(View.INVISIBLE);
                ViewManager wm = a.getWindowManager();
                WindowManager.LayoutParams l = r.window.getAttributes();
                a.mDecor = decor;
                l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
                l.softInputMode |= forwardBit;
                if (a.mVisibleFromClient) {
                    a.mWindowAdded = true;
                    //将decor放入WindowManager中，这样activity就显示出来了
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



}
