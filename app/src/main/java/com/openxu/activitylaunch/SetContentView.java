package com.openxu.activitylaunch;

import android.app.Activity;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.support.annotation.LayoutRes;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

/**
 * author : openXu
 * created time : 16/9/3 下午5:33
 * blog : http://blog.csdn.net/xmxkf
 * github : http://blog.csdn.net/xmxkf
 * class name : SetContentView
 * discription :
 *
 * SetContentView主要研究Activity加载layout的过程，
 * 下面是从调用setContentView()开始分析的过程；
 */
public class SetContentView extends Activity{


    @Override
    public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
        super.onCreate(savedInstanceState, persistentState);
        /**
         *step1:在OnCreate()方法中设置布局资源ID，Activity提供了三个设置视图的方法
         */
        setContentView(R.layout.activity_main);
        //setContentView(new TextView(this));
        //setContentView(new TextView(this),new ViewGroup.LayoutParams(...));
    }

    /**
     * 原来Activity中调用setContentView()方法最终调用的是PhoneWindow对
     * 象mWindow 的setContentView(...)方法。mWindow是在Activity创建的时候
     * 在attach()方法中初始化的，
     * attach()方法上一篇博客http://blog.csdn.net/xmxkf/article/details/52452218 中有讲解。
     * 每一个Activity组件都有一个关联的Window的实现类PhoneWindow的对象mWindow ，
     * 用来描述一个应用程序窗口，它封装了顶层窗口的外观和行为策略，
     * 它提供了标准的用户界面策略，如背景、标题区域、默认键处理等。
     * PhoneWindow管理着整个屏幕的内容，不包括屏幕最顶部的系统状态条。
     * 所以，PhoneWindow或者Window是与应用的一个页面所相关联。
     */
    public class Activity{
        private Window mWindow;

        //三个设置视图的方法
        public void setContentView(@LayoutRes int layoutResID) {
            getWindow().setContentView(layoutResID);
            //初始化ActionBar
            initWindowDecorActionBar();
        }
        public void setContentView(View view) {
            getWindow().setContentView(view);
            initWindowDecorActionBar();
        }
        public void setContentView(View view, ViewGroup.LayoutParams params) {
            getWindow().setContentView(view, params);
            initWindowDecorActionBar();
        }

        final void attach(...) {
            ...
            //初始化mWindow
            mWindow = new PhoneWindow(this);
            ...
        }
        public Window getWindow() {
            return mWindow;
        }
    }


    /**
     * step2:PhoneWindow.setContentView()
     * PhoneWindow中setContentView(view)接着调用setContentView(view, layoutParams)，
     * 而setContentView(layoutResID)和setContentView(view, layoutParams)方法中的步骤
     * 是差不多的，唯一的区别就是setCOntentView(layoutResID)中是通过
     * mLayoutInflater.inflate(layoutResID, mContentParent)将布局填充到mContentParent上，
     * 而setContentView(view, layoutParams)是将传过来的view直接 add到mContentParent中 。
     *
     * 到这一步我们发现，我们为某个activity写的layout视图最终是添加到一个叫mContentParent的ViewGroup
     * 中了。 在加入mContentParent中之前，首先判断如果mContentParent==null时，执行了installDecor()
     * 方法，我们猜想，installDecor()的作用大概就是初始化mContentParent，如果mContentParent已经初始
     * 化，而且窗口不是透明的，就清除mContentParent中的所有视图。mContentParent只会实例化一次，所以如果
     * 我们在Activity中多次调用setContentView()只是改变了mContentParent的子视图（也就是我们写的布局文件）。
     */
    public void setContentView ( int layoutResID){
        if (mContentParent == null) {
            installDecor();
        } else if (!hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            //移除该mContentParent内所有的所有子View
            mContentParent.removeAllViews();
        }
        if (hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            final Scene newScene = Scene.getSceneForLayout(mContentParent, layoutResID,
                    getContext());
            transitionTo(newScene);
        } else {
            //将我们的资源文件通过LayoutInflater对象转换为View树，并且添加至mContentParent中
            mLayoutInflater.inflate(layoutResID, mContentParent);
        }
        mContentParent.requestApplyInsets();
        final Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            cb.onContentChanged();
        }
    }
    public void setContentView (View view){
        setContentView(view, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }
    public void setContentView (View view, ViewGroup.LayoutParams params){
        //此处判断mContentParent是否为null，如果是null，则是第一次调用setContentView
        if (mContentParent == null) {
            //第一次需要初始化窗口和根布局（id为content的）
            installDecor();
        } else if (!hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            //如果不是第一次调用，需要清空根布局中的内容
            mContentParent.removeAllViews();
        }
        if (hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            view.setLayoutParams(params);
            final Scene newScene = new Scene(mContentParent, view);
            transitionTo(newScene);
        } else {
            mContentParent.addView(view, params);
        }
        mContentParent.requestApplyInsets();
        final Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            cb.onContentChanged();
        }
    }

    /**
     * step3. PhoneWindow.installDecor(）
     * PhoneWindow中引用了一个DecorView的对象，DecorView是FrameLayout的子类，
     * 相信你们应该多多少少知道Activity的顶层窗口是一个FramLayout，也正是这个DevorView
     * 的对象mDecor。在installDecor()方法中，第①步就是判断mDecor是否为null，如果为null，
     * 将会调用generateDecor()方法实例化一个DecorView对象，紧接着第②步判断mContentParent
     * 是否为null，如果为null，将调用generateLayout(mDecor)方法初始化mContentParent。
     * 现在就有一个疑问了，mDecor和mContentParent都是容器，他们是什么关系？
     * 各自代表的是屏幕中那一块的内容?
     * 带着问题我们看看generateLayout(mDecor)方法：
     */
    public class PhoneWindow extends Window implements MenuBuilder.Callback {
        //id为content的容器，这个容器就是用于盛放我们写的layout视图的
        private ViewGroup mContentParent;
        //mContentRoot是整个界面内容，包括title和content等等
        private ViewGroup mContentRoot;
        //窗口顶层FrameLayout，用于盛放mContentRoot
        private DecorView mDecor;

        /** DecorView是FrameLayout的子类*/
        private final class DecorView extends FrameLayout{
            ...
        }

        //实例化了一个DecorView对象
        protected DecorView generateDecor() {
            return new DecorView(getContext(), -1);
        }

        /** 初始化顶层窗口mDecor和根布局mContentParent*/
        private void installDecor() {
            if (mDecor == null) {
                //①.初始化窗口
                mDecor = generateDecor();
                mDecor.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                mDecor.setIsRootNamespace(true);
                ...
            }
            if (mContentParent == null) {
                //②.如果根布局
                mContentParent = generateLayout(mDecor);
                //③.初始化title和一些设置
                if (decorContentParent != null) {
                    mDecorContentParent = decorContentParent;
                    mDecorContentParent.setWindowCallback(getCallback());
                    if (mDecorContentParent.getTitle() == null) {
                        mDecorContentParent.setWindowTitle(mTitle);
                    }
                    ...
                } else {
                    mTitleView = (TextView) findViewById(R.id.title);
                    if (mTitleView != null) {
                        mTitleView.setLayoutDirection(mDecor.getLayoutDirection());
                        if ((getLocalFeatures() & (1 << FEATURE_NO_TITLE)) != 0) {
                            ...
                        } else {
                            mTitleView.setText(mTitle);
                        }
                    }
                }

                if (mDecor.getBackground() == null && mBackgroundFallbackResource != 0) {
                    mDecor.setBackgroundFallback(mBackgroundFallbackResource);
                }
                ...
            }
        }
    }


    /**
     * step 4. PhoneWindow.generateLayout(mDecor )
     * 初始化根布局mContentParent
     */
    protected ViewGroup generateLayout(DecorView decor) {
        //①.获取AndroidManifest.xml中指定的themes主题
        TypedArray a = getWindowStyle();
        //设置当前窗口是否有标题
        if (a.getBoolean(R.styleable.Window_windowNoTitle, false)) {
            //请求指定Activity窗口的风格类型
            requestFeature(FEATURE_NO_TITLE);
        } else if (a.getBoolean(R.styleable.Window_windowActionBar, false)) {
            requestFeature(FEATURE_ACTION_BAR);
        }
        ...
        //设置窗口是否全屏
        if (a.getBoolean(R.styleable.Window_windowFullscreen, false)) {
            setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN & (~getForcedWindowFlags()));
        }
        ...

        //②.根据上面设置的窗口属性Features, 设置相应的修饰布局文件layoutResource，这些xml文件位于frameworks/base/core/res/res/layout下
        int layoutResource;
        int features = getLocalFeatures();
        if ((features & (1 << FEATURE_SWIPE_TO_DISMISS)) != 0) {
            layoutResource = R.layout.screen_swipe_dismiss;
        } else if
        ...

        mDecor.startChanging();
        //③.将第2步选定的布局文件inflate为View树，这也是整个窗口的内容（包括title、content等等）
        View in = mLayoutInflater.inflate(layoutResource, null);
        //④.将整个窗口内容添加到根mDecor中
        decor.addView(in, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        //⑤.将整个窗口内容赋值给mContentRoot
        mContentRoot = (ViewGroup) in;

        //⑥.将窗口修饰布局文件中id="@android:id/content"的View赋值给mContentParent
        ViewGroup contentParent = (ViewGroup) findViewById(ID_ANDROID_CONTENT);
        if (contentParent == null) {
            throw new RuntimeException("Window couldn't find content container view");
        }
        ...

        mDecor.finishChanging();

        return contentParent;
    }


    /**
     * step5. LayoutInflater.inflate()
     * 经过上面的步骤，Activity的顶层窗口mDecor和用于容纳我们写的layout的容
     * 器mContentParent已经初始化完毕，接下来就是将我们的布局layout添加到mContentParent中
     */
    public View inflate(@LayoutRes int resource, @Nullable ViewGroup root) {
        return inflate(resource, root, root != null);
    }
    public View inflate(@LayoutRes int resource, @Nullable ViewGroup root, boolean attachToRoot) {
        final Resources res = getContext().getResources();
        if (DEBUG) {
            Log.d(TAG, "INFLATING from resource: \"" + res.getResourceName(resource) + "\" ("
                    + Integer.toHexString(resource) + ")");
        }

        final XmlResourceParser parser = res.getLayout(resource);
        try {
            return inflate(parser, root, attachToRoot);
        } finally {
            parser.close();
        }
    }

    /**
     * 将layout解析为view树，并附加到root（mContentParent）中
     */
    public View inflate(XmlPullParser parser, ViewGroup root, boolean attachToRoot) {
        synchronized (mConstructorArgs) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "inflate");

            final Context inflaterContext = mContext;
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            Context lastContext = (Context) mConstructorArgs[0];
            mConstructorArgs[0] = inflaterContext;
            //①.将最终返回的View初始化为root(也就是mContentParent)
            View result = root;
            try {
                int type;
                //②.循环直到解析到开始标签<>或者结尾标签</>
                while ((type = parser.next()) != XmlPullParser.START_TAG &&
                        type != XmlPullParser.END_DOCUMENT) {
                    // Empty
                }
                //第一次解析到的不是开始标签<>，说明layout文件没有<>标签,xml格式错误
                if (type != XmlPullParser.START_TAG) {
                    throw new InflateException(parser.getPositionDescription()
                            + ": No start tag found!");
                }

                //③.找到第一个开始标签，这个标签对应的name就是整个layout最外层的父控件
                final String name = parser.getName();
                ...
                if (TAG_MERGE.equals(name)) {
                    if (root == null || !attachToRoot) {
                        throw new InflateException("<merge /> can be used only with a valid "
                                + "ViewGroup root and attachToRoot=true");
                    }
                    rInflate(parser, root, inflaterContext, attrs, false);
                } else {
                    // Temp is the root view that was found in the xml
                    //★④.根据layout中第一个开始标签的名称创建一个View对象temp，temp就是整个xml中的根控件
                    final View temp = createViewFromTag(root, name, inflaterContext, attrs);

                    ViewGroup.LayoutParams params = null;
                    if (root != null) {
                        // Create layout params that match root, if supplied
                        // 根据父控件获取布局参数，后面将解析的view树添加到root中是要使用
                        params = root.generateLayoutParams(attrs);
                        //如果不需要附加到root父控件中
                        if (!attachToRoot) {
                            // Set the layout params for temp if we are not
                            // attaching. (If we are, we use addView, below)
                            //为temp设置布局参数如果我们不附加。（如果我们是，我们使用addView，下同）
                            temp.setLayoutParams(params);
                        }
                    }
                    // Inflate all children under temp against its context.
                    //★⑤.调用rInflateChildren递归解析temp中的所有子控件，通过这行代码整个layout就被解析为view树了
                    rInflateChildren(parser, temp, attrs, true);

                    //★⑥.如果root不为空，将view树添加到root中
                    //此处root为mContentParent，也就是将layout布局添加到mContentParent中了
                    if (root != null && attachToRoot) {
                        root.addView(temp, params);
                    }
                    if (root == null || !attachToRoot) {
                        //如果不用附加到root中，直接返回解析的view树
                        result = temp;
                    }
                }
            } catch (XmlPullParserException e) {
                InflateException ex = new InflateException(e.getMessage());
                ex.initCause(e);
                throw ex;
            } catch (Exception e) {
                InflateException ex = new InflateException(
                        parser.getPositionDescription()
                                + ": " + e.getMessage());
                ex.initCause(e);
                throw ex;
            } finally {
                // Don't retain static reference on context.
                mConstructorArgs[0] = lastContext;
                mConstructorArgs[1] = null;
            }

            Trace.traceEnd(Trace.TRACE_TAG_VIEW);

            return result;
        }
    }

    /**
     * step6. LayoutInflater.createViewFromTag()
     * 根据控件名实例化控件对象
     */
    View createViewFromTag(View parent, String name, Context context, AttributeSet attrs,
                           boolean ignoreThemeAttr) {
        if (name.equals("view")) {
            name = attrs.getAttributeValue(null, "class");
        }

        // Apply a theme wrapper, if allowed and one is specified.
        if (!ignoreThemeAttr) {
            final TypedArray ta = context.obtainStyledAttributes(attrs, ATTRS_THEME);
            final int themeResId = ta.getResourceId(0, 0);
            if (themeResId != 0) {
                context = new ContextThemeWrapper(context, themeResId);
            }
            ta.recycle();
        }

        if (name.equals(TAG_1995)) {
            // Let's party like it's 1995!
            return new BlinkLayout(context, attrs);
        }

        try {
            View view;
            if (mFactory2 != null) {
                view = mFactory2.onCreateView(parent, name, context, attrs);
            } else if (mFactory != null) {
                view = mFactory.onCreateView(name, context, attrs);
            } else {
                view = null;
            }

            if (view == null && mPrivateFactory != null) {
                view = mPrivateFactory.onCreateView(parent, name, context, attrs);
            }

            if (view == null) {
                final Object lastContext = mConstructorArgs[0];
                mConstructorArgs[0] = context;
                try {
                    //先判断name中是否有'.'字符，如果没有，此控件为android自带的View，此时会在name的前面加上包名"android.view."
                    if (-1 == name.indexOf('.')) {
                        view = onCreateView(parent, name, attrs);
                    } else {
                        //如果有这个'.'，则认为是自定义View，因为自定义View在使用的时候使用的全名，所以直接创建
                        view = createView(name, null, attrs);
                    }
                } finally {
                    mConstructorArgs[0] = lastContext;
                }
            }

            return view;
        } catch (InflateException e) {
            throw e;

        } catch (ClassNotFoundException e) {
            final InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Error inflating class " + name);
            ie.initCause(e);
            throw ie;

        } catch (Exception e) {
            final InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Error inflating class " + name);
            ie.initCause(e);
            throw ie;
        }
    }


    /**
     * step7. LayoutInflater.rInflate()
     * 解析layout最外层parent中的所有子控件
     * 此方法为递归方法，layout中有多少个ViewGroup就会递归调用多少次
     * 每一次调用就会完成layout中某一个ViewGroup中所有的子控件的解析
     */
    final void rInflateChildren(XmlPullParser parser, View parent, AttributeSet attrs,
                                boolean finishInflate) throws XmlPullParserException, IOException {
        rInflate(parser, parent, parent.getContext(), attrs, finishInflate);
    }
    void rInflate(XmlPullParser parser, View parent, Context context,
                  AttributeSet attrs, boolean finishInflate) throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int type;
        //如果遇到结束标签（</>）就结束，说明此parent中所有的子view已经解析完毕
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            //1.找到开始标签<>
            final String name = parser.getName();
            //2.根据name类型分别解析
            if (TAG_REQUEST_FOCUS.equals(name)) {
                parseRequestFocus(parser, parent);
            } else if (TAG_TAG.equals(name)) {
                parseViewTag(parser, parent, attrs);
            } else if (TAG_INCLUDE.equals(name)) {
                if (parser.getDepth() == 0) {
                    throw new InflateException("<include /> cannot be the root element");
                }

            /*
             * 如果是<include />，调用parseInclude方法用于解析<include/>标签：
             * ①.根据include标签的name属性找到对应的layout的id
             * ②.遍历开始标签解析layout中的view
             * ③.调用rInflateChildren(childParser, view, childAttrs, true)解析view中的子控件
             * ④.将view添加（add）进parent中
             */
                parseInclude(parser, context, parent, attrs);
            } else if (TAG_MERGE.equals(name)) {
                throw new InflateException("<merge /> must be the root element");
            } else {
                //如果是普通View,调用createViewFromTag创建view对象
                final View view = createViewFromTag(parent, name, context, attrs);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = viewGroup.generateLayoutParams(attrs);
                //★递归调用rInflateChildren解析view中的子控件
                //如果view不是ViewGroup，rInflateChildren()会在while的第一次循环结束
                //如果view是ViewGroup，并且里面有子控件，通过这行代码view中的所有子控件就被挂到view上了
                rInflateChildren(parser, view, attrs, true);
                //将view树添加到viewGroup中，到此为止完成一个view及其所有子控件的填充
                viewGroup.addView(view, params);
            }
        }
        if (finishInflate) {
        /*
         * ★parent的所有子控件都inflate完毕后调用onFinishInflate方法
         * 这个方法在自定义ViewGroup的时候经常用到，自定义ViewGroup中
         * 不能在构造方法中find子控件，因为构造方法中并没有完成子控件的实例化，
         * 只能在onFinishInflate回调方法中findViewById来初始化子控件
         */
            parent.onFinishInflate();
        }
    }


}

