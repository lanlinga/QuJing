package leon.qujing;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.XModuleResources;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class XposedEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    public static XModuleResources res;
    public static ClassLoader classLoader;
    public static XSharedPreferences sPrefs;
    public static String packageName;
    Boolean isFirstApplication;
    String processName;
    ApplicationInfo appInfo;
    public static String StartupAPP = "android";

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        try {
            res = XModuleResources.createInstance(startupParam.modulePath, null);
        } catch (Throwable e) {
            XposedBridge.log("QuJing: initZygote failed - " + e);
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
        }
        catch (Exception e){
            try {
                XposedBridge.log("HttpURLConnection Exception:"+e.getMessage());
                XposedBridge.log("try curl access http");
                java.lang.Process p = Runtime.getRuntime().exec("/system/bin/curl http://127.0.0.1:61000/querytargetapp");
                p.waitFor();
                reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            } catch (Exception e2) {
                XposedBridge.log("curl Exception:"+e2.getMessage());
                XposedBridge.log(e2);
                XposedBridge.log("被注入应用如果没有网络权限，曲境将无法运行");
                return false;
            }
        }
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            result.append(line);
        }
        String TargetAppsStr = result.toString();
        XposedBridge.log("TargetAppsStr:" + TargetAppsStr);
        return TargetAppsStr.contains(XposedEntry.packageName + ";");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        try {
            if(loadPackageParam.packageName.equals(StartupAPP)) {
                try {
                    new QuJingServer(61000);
                    XposedBridge.log("server start at 61000.");

                    if (Build.VERSION.SDK_INT >= 24) {
                        XposedHelpers.findAndHookMethod("android.app.ContextImpl", loadPackageParam.classLoader, "checkMode",int.class, XC_MethodReplacement.returnConstant(null));
                    }
                } catch (Throwable e) {
                    XposedBridge.log("QuJing: Startup failed - " + e);
                }
                return;
            }

            if (loadPackageParam.processName != null && loadPackageParam.processName.contains(":")) return;
            gatherInfo(loadPackageParam);
            
            // 原始的明文流量hook（安全）
            try {
                XposedHelpers.findAndHookMethod("com.android.okhttp.HttpHandler$CleartextURLFilter", XposedEntry.classLoader,"checkURLPermitted", URL.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("HttpHandler$CleartextURLFilter skip, packageName:"+ XposedEntry.packageName);
                        param.setResult(null);
                    }
                });
            } catch (Throwable e) {
                XposedBridge.log("QuJing: Old cleartext hook failed - " + e);
            }
            
            // 为Android 11+尝试额外的明文流量hook（但不影响原始功能）
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    XposedHelpers.findAndHookMethod("com.android.okhttp.internal.http.HttpEngine", XposedEntry.classLoader,
                            "checkCleartextTrafficPermitted", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(null);
                        }
                    });
                    XposedBridge.log("QuJing: New HttpEngine hook for Android 11+");
                } catch (Throwable e) {
                    XposedBridge.log("QuJing: New HttpEngine hook failed - " + e);
                }
                
                // 尝试hook NetworkSecurityPolicy
                try {
                    XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy", null,
                            "isCleartextTrafficPermitted", String.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return true;
                        }
                    });
                    XposedBridge.log("QuJing: NetworkSecurityPolicy per-app hook");
                } catch (Throwable e) {
                    XposedBridge.log("QuJing: NetworkSecurityPolicy per-app hook failed - " + e);
                }
                
                try {
                    XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy", null,
                            "isCleartextTrafficPermitted", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return true;
                        }
                    });
                    XposedBridge.log("QuJing: NetworkSecurityPolicy global hook");
                } catch (Throwable e) {
                    XposedBridge.log("QuJing: NetworkSecurityPolicy global hook failed - " + e);
                }
            }

            disableHooks();
            
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Context context = (Context) param.args[0];
                            classLoader = context.getClassLoader();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    boolean isHook = false;
                                    try {
                                        isHook = isNeedHook();
                                    } catch (IOException e) {
                                        XposedBridge.log(e);
                                    }
                                    XposedBridge.log(XposedEntry.packageName + " isHook: "+isHook);
                                    if (isHook) {
                                        int pid = Process.myPid();
                                        new QuJingServer(pid);
                                        XposedBridge.log("QuJingServer Listening @:"+ pid +" packageName: "+XposedEntry.packageName);
                                    }
                                }
                            }).start();
                        }
                    });
        } catch (Throwable e) {
            XposedBridge.log("QuJing: handleLoadPackage crashed - " + e);
        }
    }

    private void disableHooks(){
        XposedHelpers.findAndHookMethod(ClassLoader.class,"loadClass",String.class,new XC_MethodHook(){
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable{
                if(param.args!=null && param.args[0] != null && param.args[0].toString().startsWith("de.robv.android.xposed.")){
                    //改成一个不存在的类
                    param.args[0]="de.robv.android.xposed.ThTest";
                }
                super.beforeHookedMethod(param);
            }
        });
        XposedHelpers.findAndHookMethod(Class.class,"getDeclaredField",String.class,new XC_MethodHook(){
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable{
                if(param.args!=null && param.args[0] != null && param.args[0].toString().equals("disableHooks")){
                    param.args[0]="disableHook";
                }
                super.beforeHookedMethod(param);
            }
        });
    }

    private void gatherInfo(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        packageName = loadPackageParam.packageName;
        isFirstApplication = loadPackageParam.isFirstApplication;
        processName = loadPackageParam.processName;
        appInfo = loadPackageParam.appInfo;
    }
}
