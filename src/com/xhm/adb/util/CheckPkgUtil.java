package com.xhm.adb.util;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * 多渠道包自动验证
 * Created by xhm on 9/11/2017.
 */
public class CheckPkgUtil {

    //渠道配置文件名
    private static final String CHANNEL_CONFIG_NAME = "channel.txt";
    //配置文件名
    private static final String CONFIG_NAME = "config.txt";
    //下载渠道包文件夹名
    private static final String DOWNLOAD_DIR_NAME = "download";

    private static final String LOG_NAME = "log.txt";

    private static final String SDCARD_CHANNEL_PICS_DIR = "/sdcard/channel_pics/";

    private static final String PC_CHANNEL_PIC_DIR = "channel_pics_%s";

    private static final String ADB_SHELL = "adb -s %s shell %s";
    private static final String ADB = "adb -s %s %s";

    FileWriter logWriter;

    private void init(List<String> devices){
        initLogFile();
        for(String device : devices){
            adbShell(device,"svc power stayon true");
            boolean isLocked = isScreenLocked(device);
            if(isLocked){
                delLockScreen(device);
            }
        }
    }

    private void initLogFile(){
        File logFile = new File(LOG_NAME);
        if(logFile.exists() && logFile.length() > 1024 * 1024 * 2){
            logFile.delete();
        }
        try {
            logWriter = new FileWriter(logFile, true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss");
        if(logFile.length() != 0){
            printLog("\r\n\r\n");
        }
        printStepLog("StartTime:" + sdf.format(new Date()));
    }

    private Config readConfig(){
        Config config = new Config();
        try {
            FileReader fr = new FileReader(CONFIG_NAME);
            BufferedReader br = new BufferedReader(fr);
            String line = null;
            printStepLog("Step ReadConfig");
            Map<String, String > map = new HashMap<>();
            while ((line = br.readLine()) != null){
                line = line.trim();
                if(line.length() != 0
                        && !line.startsWith("#")
                        && !line.startsWith("//")
                        && !line.startsWith("\\")
                        && line.contains("=")){
                    String[] kv = line.split("=");
                    map.put(kv[0].trim(), kv[1].trim());
                    printLog(line);
                }
            }
            br.close();
            config.parseConfig(map);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
        return config;
    }

    private List<ChannelPkg> readChannelConfig(List<String> devices, Config config){
        List<ChannelPkg> pkgList = new ArrayList<>(40);
        try {
            FileReader fr = new FileReader(CHANNEL_CONFIG_NAME);
            BufferedReader br = new BufferedReader(fr);
            String line = null;
            ChannelPkg pkg = null;
            printStepLog("Step ReadChannelConfig");
            int index = 0;
            while ((line = br.readLine()) != null){
                if(line.length() != 0 && !line.startsWith("#")
                        && !line.startsWith("//")
                        && !line.startsWith("\\")){
                    if(index == devices.size()){
                        index = 0;
                    }
                    pkg  = new ChannelPkg(config, line.trim(), devices.get(index));
                    index++;
                    pkgList.add(pkg);
                    printLog(pkg.channelName);
                }
            }
            br.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
        
        return pkgList;
    }

    //下载所有渠道包
    private void downloadAllPkgs(List<String> deviceList, List<ChannelPkg> pkgList){
        printStepLog("Step DownloadAllPkgs");
        if(pkgList != null && pkgList.size() != 0){
            long currTime = System.currentTimeMillis();
            File dir = new File(DOWNLOAD_DIR_NAME);
            if(dir.exists()){
                File[] files = dir.listFiles();
                for(File file : files){
                    file.delete();
                }
            }else{
                dir.mkdirs();
            }

            for(ChannelPkg pkg : pkgList){
                File file = new File(dir, pkg.fileName);
                downloadPkg(pkg, pkg.downloadUrl, file);
            }

            int excuTime = (int)(System.currentTimeMillis() - currTime)/1000;
            printLog("下载执行时长: " + timeConver(excuTime));
        }
    }

    private void downloadPkg(ChannelPkg pkg, String fileUrl, File file){
        try {
            keepScreenHightLight(pkg.device);
            URL url = new URL(fileUrl);
            HttpURLConnection httpURLConnection = (HttpURLConnection)url.openConnection();
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setDoInput(true);
            httpURLConnection.connect();
            int code = httpURLConnection.getResponseCode();
            if(code == 200){
                InputStream is = httpURLConnection.getInputStream();
                BufferedInputStream bis = new BufferedInputStream(is);
                String tmpPath = file.getPath() + ".tmp";
                File tmpFile = new File(tmpPath);
                if(tmpFile.exists()){
                    tmpFile.delete();
                }
                FileOutputStream fos = new FileOutputStream(tmpFile);
                BufferedOutputStream bfs = new BufferedOutputStream(fos);

                byte[] buf = new byte[2 * 1024];
                int len;
                while((len = bis.read(buf)) != -1){
                    bfs.write(buf, 0, len);
                }
                bfs.flush();
                bfs.close();
                bis.close();
                tmpFile.renameTo(file);

                printLog("succ download pkg " + pkg.channelName);
            }else{
                printLog("fail download pkg " + pkg.channelName);
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
            printLog("fail download pkg " + pkg.channelName);
        } catch (IOException e){
            e.printStackTrace();
            printLog("fail download pkg " + pkg.channelName);
        }
    }

    private void snapshotChannel(Config config, List<String> deviceList, List<ChannelPkg> pkgList){
        printStepLog("Step SnapshotChannel");
        long startTime = System.currentTimeMillis();

        CountDownLatch cdl = new CountDownLatch(deviceList.size());
        Map<String, List<ChannelPkg>> map = new HashMap<>();
        for(ChannelPkg pkg : pkgList){
            String device = pkg.device;
            if(!map.containsKey(device)){
                map.put(device, new ArrayList<>());
            }
            map.get(device).add(pkg);
        }

        for(String device : deviceList){
            if(map.get(device) != null){
                new Thread(new InstallRunnable(device, map.get(device), config, cdl)).start();
            }
        }

        try {
            cdl.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int excuTime = (int)(System.currentTimeMillis() - startTime)/1000;
        printLog("安装执行时长: " + timeConver(excuTime));
    }

    class InstallRunnable implements Runnable{

        String device;
        List<ChannelPkg> pkgList;
        Config config;
        CountDownLatch cdl;

        InstallRunnable(String device, List<ChannelPkg> list, Config config, CountDownLatch cdl){
            this.device = device;
            this.pkgList = list;
            this.config = config;
            this.cdl = cdl;
        }

        @Override
        public void run() {
            adbShell(device,"rm -rf " + SDCARD_CHANNEL_PICS_DIR);
            adbShell(device,"mkdir " + SDCARD_CHANNEL_PICS_DIR);

            for(ChannelPkg pkg : pkgList){
                File file = new File(pkg.filePath);
                if(!file.exists())continue;

                keepScreenHightLight(device);
                printLog("install pkg " + pkg.channelName + "on device: " + device);
                adb(device, "install -r " + pkg.filePath);
                keepScreenHightLight(device);
                adbShell(device, "am start -n " + config.packageName + "/" + config.activityName);
                sleep(config.sleepTime * 1000);
                adbShell(device,"screencap -p " + SDCARD_CHANNEL_PICS_DIR + pkg.channelName + ".png");
            }

            String picDirNema = String.format(PC_CHANNEL_PIC_DIR, device);
            File dir = new File(picDirNema);
            if(dir.exists()){
                File[] files = dir.listFiles();
                for(File file : files){
                    file.delete();
                }
                dir.delete();
            }
            adb(device, "pull " + SDCARD_CHANNEL_PICS_DIR + " " + picDirNema);

            cdl.countDown();
        }
    }

    //保持屏幕高亮
    private void keepScreenHightLight(String device){
        //模拟点击（10,10）坐标像素点
        adbShell(device,"input tap 10 10");
    }

    //屏幕是否唤醒
    private boolean isScreenAwake(String device){
        String result = adbShell(device,"dumpsys window policy|grep mAwake");
        boolean isScreenAwake = result.contains("true");
        printLog("isScreenAwake: " + isScreenAwake);
        return isScreenAwake;
    }

    ///唤醒屏幕
    private void awakeScreen(String device){
        adbShell(device,"input keyevent 26");
    }

    //屏幕是否被锁
    private boolean isScreenLocked(String device){
        String result = adbShell(device, "dumpsys window policy|grep -E 'isStatusBarKeyguard|mShowingLockscreen'");
        boolean isLocked = result.contains("mShowingLockscreen=true") || result.contains("isStatusBarKeyguard=true");
        printLog("isScreenLocked: " + isLocked);
        return isLocked;
    }

    ///解锁屏幕
    private void delLockScreen(String device){
        adbShell(device, "input swipe 500 700 500 50");
        sleep(1000);
    }

    private void sleep(int millis){
        try {
            Thread.currentThread().sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private String adb(String device, String cmd){
        return execCmd(String.format(ADB, device, cmd));
    }

    private String adbShell(String device, String cmd){
        return execCmd(String.format(ADB_SHELL, device, cmd));
    }

    private String execCmd(String cmd){
        try {
            logToFile("execCmd: " + cmd);
            Process process =  Runtime.getRuntime().exec(cmd);
            InputStream is = process.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String line;
            while((line = br.readLine()) != null){
                line = line.trim();
                if(line.length() != 0){
//                    printLog("result: " + line.trim());
                    logToFile(line);
                    sb.append(line);
                    sb.append("|");
                }
            }
            br.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    private void printStepLog(String str){
        printLog("====================" + str + "====================");
    }

    private void printLog(String str){
        System.out.println(str);
        logToFile(str);
    }
    
    private List<String> getDevices(){
    	String result = execCmd("adb devices");
    	String[] results = result.split("[|]");
    	if(results.length > 1){
    		List<String> deviceList = new ArrayList<>();
    		for(int i = 1; i < results.length; i++){
    			if(results[i].contains("device")){
    				String device = results[i].replace("device", "").trim();
    				printLog("device: " + device);
    				deviceList.add(device);
    			}
    		}
    		return deviceList;
    	}
    	return null;
    }

    private void logToFile(String str){
        if(logWriter != null){
            try {
                logWriter.write(str + "\r\n");
            } catch (IOException e) {
                e.printStackTrace();
                try {
                    logWriter.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                logWriter = null;
            }
        }
    }

    private void close(){
        if(logWriter != null){
            try {
                logWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    class Config{
        private static final String KEY_BASE_URL = "apkBaseUrl";
        private static final String KEY_APK_BASE_NAME = "apkBaseName";
        private static final String KEY_LOAD_FROM_LOCAL = "loadFromLocal";
        private static final String KEY_PACKAGE_NAME = "packageName";
        private static final String KEY_ACTIVITY_NAME = "activityName";
        private static final String KEY_SLEEP_TIME = "sleepTime";
        private static final String KEY_ONLY_DOWNLOAD = "onlyDownload";


        String apkBaseUrl;
        String apkBaseName;
        String packageName;
        String activityName;
        //是否从本地读取apk包，不从远程下载
        boolean loadFromLocal;
        //是否仅下载apk包，不进行安装校验
        boolean onlyDownload;
        int sleepTime;

        private void parseConfig(Map<String, String> map){
            if(map != null && map.size() != 0){
                loadFromLocal = Boolean.valueOf(map.get(KEY_LOAD_FROM_LOCAL));
                onlyDownload = Boolean.valueOf(map.get(KEY_ONLY_DOWNLOAD));
                apkBaseUrl = map.get(KEY_BASE_URL);
                apkBaseName = map.get(KEY_APK_BASE_NAME);
                packageName = map.get(KEY_PACKAGE_NAME);
                activityName = map.get(KEY_ACTIVITY_NAME);
                sleepTime = Integer.parseInt(map.get(KEY_SLEEP_TIME));
            }
        }

    }

    class ChannelPkg{

        //渠道名称
        private String channelName;

        //下载地址
        private String downloadUrl;

        //文件名
        private String fileName;

        //文件路径
        private String filePath;

        //安装的目标设备号
        private String device;

        public ChannelPkg(Config config, String channelName, String device){
            this.channelName = channelName;
            this.fileName = config.apkBaseName + channelName + ".apk";
            this.downloadUrl = config.apkBaseUrl + fileName;
            this.filePath = new File(DOWNLOAD_DIR_NAME, fileName).getPath();
            this.device = device;
        }

    }

    public static void main(String[] args) {
    	long currTime = System.currentTimeMillis();
        CheckPkgUtil util = new CheckPkgUtil();
        List<String> deviceList = util.getDevices();
        if(deviceList == null || deviceList.size() == 0){
        	util.printLog("No device Connected");
        }else{
            util.init(deviceList);
        	Config config = util.readConfig();
        	List<ChannelPkg> pkgList = util.readChannelConfig(deviceList, config);
        	if(!config.loadFromLocal){
        		util.downloadAllPkgs(deviceList, pkgList);
        	}
        	if(!config.onlyDownload){
        		util.snapshotChannel(config, deviceList, pkgList);
        	}
        }
        int excuTime = (int) (System.currentTimeMillis() - currTime) / 1000;
        util.printLog("总共执行时长: " + timeConver(excuTime));
        util.close();
    }

    private static String timeConver(int seconds){
        int hour = 0;
        int minute = 0;
        int second = 0;

        if(seconds >= 60 * 60){
            hour = seconds / (60 * 60);
            seconds = seconds % (60 * 60);
        }

        if(seconds >= 60){
            minute = seconds / 60;
            seconds = seconds % 60;
        }

        second = seconds;

        StringBuilder sb = new StringBuilder();
        if(hour != 0){
            sb.append(hour);
            sb.append("小时");
        }
        if(minute != 0){
            sb.append(minute);
            sb.append("分");
        }
        if(second != 0){
            sb.append(second);
            sb.append("秒");
        }

        return sb.toString();
    }




}
