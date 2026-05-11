package leon.qujing;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.XModuleResources;
import android.os.Build;
import android.os.Process;
import android.security.NetworkSecurityPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class XposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    public static XModuleResources res;
    public static ClassLoader classLoader;
    public static String packageName;
    Boolean isFirstApplication;
    String processName;
    ApplicationInfo appInfo;
    public static String StartupAPP = "android";

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        try {
            res = XModuleResources.createInstance(startupParam.modulePath, null);
            XposedBridge.log("QuJing: initZygote success");
        } catch (Throwable e) {
            XposedBridge.log("QuJing: initZygote error: " + e);
        }
    }

    private boolean isNeedHook() throws IOException {
        BufferedReader reader = null;
        try {
            HttpURLConnection connection = null;
            URL url = new URL("http://127.0.0.1:61000/querytargetapp");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            InputStream in = connection.getInputStream();
            reader = new BufferedReader(new InputStreamReader(in));
        } catch (Exception e) {
            try {
                XposedBridge.log("QuJing: HttpURLConnection Exception: " + e.getMessage());
                XposedBridge.log("QuJing: try curl access http");
                java.lang.Process p = Runtime.getRuntime().exec("/system/bin/curl http://127.0.0.1:61000/querytargetapp");
                p.waitFor();
                reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            } catch (Exception e2) {
                XposedBridge.log("QuJing: curl Exception: " + e2.getMessage());
                XposedBridge.log("QuJing: " + e2);
                XposedBridge.log("QuJing: 被注入应用如果没有网络权限，曲境将无法运行");
                return false;
            }
        }
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }
        String TargetAppsStr = result.toString();
        XposedBridge.log("QuJing: TargetAppsStr: " + TargetAppsStr);
        return TargetAppsStr.contains(XposedEntry.packageName + ";");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        try {
            XposedBridge.log("QuJing: handleLoadPackage for " + loadPackageParam.packageName);

            if (loadPackageParam.packageName.equals(StartupAPP)) {
                try {
                    new QuJingServer(61000);
                    XposedBridge.log("QuJing: server start at 61000.");

                    if (Build.VERSION.SDK_INT >= 24) {
                        hookContextImpl(loadPackageParam.classLoader);
                    }
                    
                    // 为高版本Android系统添加额外的hook
                    hookSystemServicesForModernAndroid(loadPackageParam.classLoader);
                } catch (Throwable e) {
                    XposedBridge.log("QuJing: Startup package error: " + e);
                }
                return;
            }

            if (loadPackageParam.processName != null && loadPackageParam.processName.contains(":")) {
                XposedBridge.log("QuJing: Skip child process: " + loadPackageParam.processName);
                return;
            }
            
            gatherInfo(loadPackageParam);
            
            // 在高版本Android上更全面地禁用明文流量限制
            disableCleartextTrafficRestrictions(loadPackageParam.classLoader);

            // 禁用隐藏API限制
            disableHiddenApiRestrictions(loadPackageParam.classLoader);

            disableHooks(loadPackageParam.classLoader);
            
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Context context = (Context) param.args[0];
                                classLoader = context.getClassLoader();
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        boolean isHook = false;
                                        try {
                                            isHook = isNeedHook();
                                        } catch (IOException e) {
                                            XposedBridge.log("QuJing: isNeedHook error: " + e);
                                        }
                                        XposedBridge.log("QuJing: " + XposedEntry.packageName + " isHook: " + isHook);
                                        if (isHook) {
                                            try {
                                                int pid = Process.myPid();
                                                new QuJingServer(pid);
                                                XposedBridge.log("QuJing: QuJingServer Listening @:" + pid + " packageName: " + XposedEntry.packageName);
                                            } catch (Throwable e) {
                                                XposedBridge.log("QuJing: QuJingServer start failed: " + e);
                                            }
                                        }
                                    }
                                }).start();
                            } catch (Throwable e) {
                                XposedBridge.log("QuJing: Application attach error: " + e);
                            }
                        }
                    });
        } catch (Throwable e) {
            XposedBridge.log("QuJing: handleLoadPackage failed: " + e);
        }
    }

    private void hookContextImpl(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", classLoader, "checkMode", int.class, XC_MethodReplacement.returnConstant(null));
            XposedBridge.log("QuJing: Hooked ContextImpl.checkMode");
        } catch (Throwable e) {
            XposedBridge.log("QuJing: Hook ContextImpl.checkMode failed: " + e);
        }
    }

    private void hookSystemServicesForModernAndroid(ClassLoader classLoader) {
        // 为Android 12+系统hook更多安全限制
        if (Build.VERSION.SDK_INT >= 31) { // Android 12 (S)
            try {
                // Hook NetworkSecurityPolicy相关类
                if (Build.VERSION.SDK_INT >= 24) { // Android N
                    hookNetworkSecurityPolicy();
                }
                XposedBridge.log("QuJing: System services hooked for Android " + Build.VERSION.SDK_INT);
            } catch (Throwable e) {
                XposedBridge.log("QuJing: Hook system services failed: " + e);
            }
        }
    }

    private void hookNetworkSecurityPolicy() {
        try {
            XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy", null,
                    "isCleartextTrafficPermitted", String.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return true;
                        }
                    });
            
            XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy", null,
                    "isCleartextTrafficPermitted", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return true;
                        }
                    });
            XposedBridge.log("QuJing: Hooked NetworkSecurityPolicy");
        } catch (Throwable e) {
            XposedBridge.log("QuJing: Hook NetworkSecurityPolicy failed: " + e);
        }
    }

    private void disableCleartextTrafficRestrictions(ClassLoader classLoader) {
        // 尝试多种方法确保明文流量被允许
        try {
            ClassLoader cl = XposedEntry.classLoader != null ? XposedEntry.classLoader : classLoader;
            
            // Android 6-10的方法
            try {
                XposedHelpers.findAndHookMethod("com.android.okhttp.HttpHandler$CleartextURLFilter", cl, 
                        "checkURLPermitted", URL.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("QuJing: HttpHandler$CleartextURLFilter skip, packageName:" + XposedEntry.packageName);
                        param.setResult(null);
                    }
                });
                XposedBridge.log("QuJing: Hooked old HttpHandler");
            } catch (Throwable e) {
                XposedBridge.log("QuJing: Hook old HttpHandler failed: " + e);
            }
            
            // Android 11+ 新的OkHttp路径
            try {
                XposedHelpers.findAndHookMethod("com.android.okhttp.internal.http.HttpEngine", cl,
                        "checkCleartextTrafficPermitted", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(null);
                    }
                });
                XposedBridge.log("QuJing: Hooked HttpEngine");
            } catch (Throwable e) {
                XposedBridge.log("QuJing: Hook HttpEngine failed: " + e);
            }
            
            // 尝试直接hook NetworkSecurityPolicy
            hookNetworkSecurityPolicy();
            
        } catch (Throwable e) {
            XposedBridge.log("QuJing: disableCleartextTrafficRestrictions failed: " + e);
        }
    }

    private void disableHiddenApiRestrictions(ClassLoader classLoader) {
        try {
            if (Build.VERSION.SDK_INT >= 28) { // Android 9 (P)
                // Android 9+的隐藏API限制
                try {
                    Class<?> vmRuntimeClass = XposedHelpers.findClass("dalvik.system.VMRuntime", null);
                    Object vmRuntime = XposedHelpers.callStaticMethod(vmRuntimeClass, "getRuntime");
                    XposedHelpers.callMethod(vmRuntime, "setHiddenApiExemptions", (Object) new String[]{"L"});
                    XposedBridge.log("QuJing: Disabled hidden API restrictions via VMRuntime");
                } catch (Throwable e) {
                    XposedBridge.log("QuJing: Failed to disable hidden API via VMRuntime: " + e);
                    
                    // 备用方法：尝试hook hidden API检查
                    try {
                        XposedHelpers.findAndHookMethod("dalvik.system.VMRuntime", null,
                                "shouldBlockHiddenApiAccess", String.class, int.class, new XC_MethodReplacement() {
                                    @Override
                                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                        return false;
                                    }
                                });
                    } catch (Throwable e2) {
                        XposedBridge.log("QuJing: Failed to hook hidden API check: " + e2);
                    }
                }
            }
        } catch (Throwable e) {
            XposedBridge.log("QuJing: disableHiddenApiRestrictions failed: " + e);
        }
    }

    private void disableHooks(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args[0] != null && param.args[0].toString().startsWith("de.robv.android.xposed.")) {
                        //改成一个不存在的类
                        param.args[0] = "de.robv.android.xposed.ThTest";
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("QuJing: Hook ClassLoader.loadClass failed: " + e);
        }

        try {
            XposedHelpers.findAndHookMethod(Class.class, "getDeclaredField", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args[0] != null && param.args[0].toString().equals("disableHooks")) {
                        param.args[0] = "disableHook";
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("QuJing: Hook Class.getDeclaredField failed: " + e);
        }
    }

    private void gatherInfo(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        packageName = loadPackageParam.packageName;
        isFirstApplication = loadPackageParam.isFirstApplication;
        processName = loadPackageParam.processName;
        appInfo = loadPackageParam.appInfo;
        classLoader = loadPackageParam.classLoader;
    }
}
