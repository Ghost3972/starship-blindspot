package com.elliot.blindspot;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ADB Shell 命令执行器
 * 通过连接本地 ADB daemon (127.0.0.1:5555) 执行 shell 命令
 * 
 * ADB 协议说明:
 * 1. 连接到 ADB daemon
 * 2. 发送 CNXN (connect) 消息进行握手
 * 3. 发送 OPEN 消息打开 shell 流
 * 4. 发送 WRTE 消息写入命令
 * 5. 接收 WRTE 消息获取输出
 * 6. 发送 CLSE 关闭流
 */
public class AdbShellExecutor {
    private static final String TAG = "AdbShellExecutor";
    
    // ADB 协议常量
    private static final int A_SYNC = 0x434e5953;  // "SYNC"
    private static final int A_CNXN = 0x4e584e43;  // "CNXN"
    private static final int A_OPEN = 0x4e45504f;  // "OPEN"
    private static final int A_OKAY = 0x59414b4f;  // "OKAY"
    private static final int A_CLSE = 0x45534c43;  // "CLSE"
    private static final int A_WRTE = 0x45545257;  // "WRTE"
    private static final int A_AUTH = 0x48545541;  // "AUTH"
    
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    
    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final int TIMEOUT_MS = 5000;
    
    // 重试配置（STR 唤醒后需要多次尝试）
    private static final int MAX_RETRY_COUNT = 5;
    private static final int RETRY_DELAY_MS = 2000;
    private static final int ADB_TCP_START_COOLDOWN_MS = 30000; // 30秒冷却
    
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static boolean adbTcpStartAttempted = false;
    private static long lastAdbTcpStartTime = 0;
    
    // 连接状态（供外部查询）
    private static volatile boolean lastConnectionSuccessful = false;
    private static volatile String lastConnectionError = null;
    private static volatile long lastCheckTime = 0;
    
    public interface CommandCallback {
        void onSuccess(String output);
        void onError(String error);
    }
    
    /**
     * 连接状态回调
     */
    public interface ConnectionStatusCallback {
        void onResult(boolean connected, String message);
    }
    
    /**
     * 获取最近一次连接状态
     */
    public static boolean isLastConnectionSuccessful() {
        return lastConnectionSuccessful;
    }
    
    /**
     * 获取最近一次连接错误信息
     */
    public static String getLastConnectionError() {
        return lastConnectionError;
    }
    
    /**
     * 异步检查 ADB 连接状态
     */
    public void checkConnectionAsync(ConnectionStatusCallback callback) {
        executor.execute(() -> {
            boolean connected = false;
            String message = null;
            try {
                Socket socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(ADB_HOST, ADB_PORT), 2000);
                socket.close();
                connected = true;
                message = "ADB TCP connected (127.0.0.1:5555)";
                lastConnectionSuccessful = true;
                lastConnectionError = null;
            } catch (Exception e) {
                message = "ADB disconnected: " + e.getMessage();
                lastConnectionSuccessful = false;
                lastConnectionError = e.getMessage();
            }
            lastCheckTime = System.currentTimeMillis();
            
            if (callback != null) {
                final boolean finalConnected = connected;
                final String finalMessage = message;
                callback.onResult(finalConnected, finalMessage);
            }
        });
    }
    
    /**
     * 同步检查 ADB 连接状态（用于启动时快速检测）
     */
    public boolean checkConnectionSync() {
        try {
            Socket socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(ADB_HOST, ADB_PORT), 2000);
            socket.close();
            lastConnectionSuccessful = true;
            lastConnectionError = null;
            return true;
        } catch (Exception e) {
            lastConnectionSuccessful = false;
            lastConnectionError = e.getMessage();
            return false;
        }
    }
    
    /**
     * 异步执行 shell 命令
     */
    public void executeAsync(String command, CommandCallback callback) {
        executor.execute(() -> {
            try {
                String result = execute(command);
                if (callback != null) {
                    callback.onSuccess(result);
                }
            } catch (Exception e) {
                Log.e(TAG, "Command failed: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 同步执行 shell 命令
     */
    public String execute(String command) throws IOException {
        Log.d(TAG, "Executing: " + command);
        
        Socket socket = null;
        try {
            socket = connectWithRetry();
            
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            
            // 1. 发送 CNXN (连接)
            String systemIdentity = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send";
            sendMessage(out, A_CNXN, A_VERSION, MAX_PAYLOAD, systemIdentity.getBytes());
            
            // 2. 等待响应
            AdbMessage response = readMessage(in);
            if (response == null) {
                throw new IOException("No response from ADB daemon");
            }
            
            Log.d(TAG, "Received: " + Integer.toHexString(response.command));
            
            if (response.command == A_AUTH) {
                // 需要认证，这种情况下我们无法继续
                throw new IOException("ADB requires authentication. Please accept the connection on device.");
            }
            
            if (response.command != A_CNXN) {
                throw new IOException("Unexpected response: " + Integer.toHexString(response.command));
            }
            
            // 3. 发送 OPEN (打开 shell)
            int localId = 1;
            String destination = "shell:" + command;
            sendMessage(out, A_OPEN, localId, 0, destination.getBytes());
            
            // 4. 等待 OKAY 或 WRTE
            StringBuilder output = new StringBuilder();
            int remoteId = 0;
            
            while (true) {
                response = readMessage(in);
                if (response == null) {
                    break;
                }
                
                Log.d(TAG, "Received: " + Integer.toHexString(response.command) + 
                        ", arg0=" + response.arg0 + ", arg1=" + response.arg1);
                
                if (response.command == A_OKAY) {
                    remoteId = response.arg0;
                    // 继续读取输出
                } else if (response.command == A_WRTE) {
                    // 收到输出数据
                    if (response.data != null) {
                        output.append(new String(response.data));
                    }
                    // 发送 OKAY 确认
                    sendMessage(out, A_OKAY, localId, response.arg0, null);
                } else if (response.command == A_CLSE) {
                    // 流关闭
                    break;
                }
            }
            
            // 5. 发送 CLSE
            if (remoteId != 0) {
                sendMessage(out, A_CLSE, localId, remoteId, null);
            }
            
            String result = output.toString();
            Log.d(TAG, "Command output: " + result);
            return result;
            
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
    }
    
    private void sendMessage(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24);
        header.order(ByteOrder.LITTLE_ENDIAN);
        
        int dataLength = data != null ? data.length : 0;
        int dataCheck = data != null ? checksum(data) : 0;
        int magic = command ^ 0xffffffff;
        
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(dataLength);
        header.putInt(dataCheck);
        header.putInt(magic);
        
        out.write(header.array());
        if (data != null && data.length > 0) {
            out.write(data);
        }
        out.flush();
        
        Log.d(TAG, "Sent: " + Integer.toHexString(command) + ", dataLen=" + dataLength);
    }
    
    private AdbMessage readMessage(InputStream in) throws IOException {
        byte[] header = new byte[24];
        int bytesRead = 0;
        while (bytesRead < 24) {
            int r = in.read(header, bytesRead, 24 - bytesRead);
            if (r < 0) {
                if (bytesRead == 0) return null;
                throw new IOException("Unexpected end of stream");
            }
            bytesRead += r;
        }
        
        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        
        AdbMessage msg = new AdbMessage();
        msg.command = buf.getInt();
        msg.arg0 = buf.getInt();
        msg.arg1 = buf.getInt();
        int dataLength = buf.getInt();
        int dataCheck = buf.getInt();
        int magic = buf.getInt();
        
        // 验证 magic
        if ((msg.command ^ 0xffffffff) != magic) {
            throw new IOException("Invalid magic");
        }
        
        // 读取数据
        if (dataLength > 0) {
            msg.data = new byte[dataLength];
            bytesRead = 0;
            while (bytesRead < dataLength) {
                int r = in.read(msg.data, bytesRead, dataLength - bytesRead);
                if (r < 0) {
                    throw new IOException("Unexpected end of data");
                }
                bytesRead += r;
            }
        }
        
        return msg;
    }
    
    private int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xff);
        }
        return sum;
    }
    
    private static class AdbMessage {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }
    
    public void shutdown() {
        executor.shutdown();
    }
    
    /**
     * 尝试连接 ADB，如果失败则自动启动 ADB TCP 服务（支持多次重试）
     * STR 唤醒后 ADB TCP 可能需要较长时间才能恢复
     */
    private Socket connectWithRetry() throws IOException {
        IOException lastException = null;
        
        // 多次重试连接
        for (int attempt = 1; attempt <= MAX_RETRY_COUNT; attempt++) {
            try {
                Socket socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(ADB_HOST, ADB_PORT), 3000);
                socket.setSoTimeout(TIMEOUT_MS);
                
                // 连接成功
                if (attempt > 1) {
                    Log.i(TAG, "ADB connection successful on attempt " + attempt);
                }
                lastConnectionSuccessful = true;
                lastConnectionError = null;
                return socket;
                
            } catch (IOException e) {
                lastException = e;
                Log.w(TAG, "ADB connection attempt " + attempt + "/" + MAX_RETRY_COUNT + " failed: " + e.getMessage());
                lastConnectionSuccessful = false;
                lastConnectionError = e.getMessage();
                
                // 第一次失败后尝试启动 ADB TCP 服务
                if (attempt == 1) {
                    long now = System.currentTimeMillis();
                    if (now - lastAdbTcpStartTime > ADB_TCP_START_COOLDOWN_MS) {
                        lastAdbTcpStartTime = now;
                        Log.i(TAG, "Attempting to start ADB TCP service...");
                        startAdbTcpService();
                    }
                }
                
                // 等待后重试（除了最后一次）
                if (attempt < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        
        // 所有重试都失败
        Log.e(TAG, "All " + MAX_RETRY_COUNT + " ADB connection attempts failed");
        throw lastException != null ? lastException : new IOException("ADB connection failed");
    }
    
    /**
     * 异步尝试建立 ADB 连接（带完整重试机制，用于启动时）
     * @param callback 完成后的回调
     */
    public void establishConnectionAsync(ConnectionStatusCallback callback) {
        executor.execute(() -> {
            boolean success = false;
            String message = null;
            
            // 首先尝试启动 ADB TCP
            long now = System.currentTimeMillis();
            if (now - lastAdbTcpStartTime > ADB_TCP_START_COOLDOWN_MS) {
                lastAdbTcpStartTime = now;
                Log.i(TAG, "Proactively starting ADB TCP service for STR recovery...");
                startAdbTcpService();
                
                // 等待 ADB TCP 服务启动
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
            
            // 多次尝试连接
            for (int attempt = 1; attempt <= MAX_RETRY_COUNT; attempt++) {
                try {
                    Socket socket = new Socket();
                    socket.connect(new java.net.InetSocketAddress(ADB_HOST, ADB_PORT), 3000);
                    socket.close();
                    
                    success = true;
                    message = "ADB connected on attempt " + attempt;
                    Log.i(TAG, message);
                    lastConnectionSuccessful = true;
                    lastConnectionError = null;
                    break;
                    
                } catch (Exception e) {
                    message = "Attempt " + attempt + " failed: " + e.getMessage();
                    Log.w(TAG, message);
                    lastConnectionSuccessful = false;
                    lastConnectionError = e.getMessage();
                    
                    // 中途尝试重启 ADB TCP
                    if (attempt == 2) {
                        Log.i(TAG, "Retrying ADB TCP service start...");
                        startAdbTcpService();
                    }
                    
                    if (attempt < MAX_RETRY_COUNT) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
            
            if (callback != null) {
                final boolean finalSuccess = success;
                final String finalMessage = success ? message : "ADB connection failed after " + MAX_RETRY_COUNT + " attempts";
                callback.onResult(finalSuccess, finalMessage);
            }
        });
    }
    
    /**
     * 尝试通过 su 命令启动 ADB TCP 服务
     */
    private boolean startAdbTcpService() {
        try {
            // 方法1: 使用 setprop 启动 ADB TCP
            Process process = Runtime.getRuntime().exec(new String[]{
                "su", "-c", 
                "setprop service.adb.tcp.port 5555 && stop adbd && start adbd"
            });
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Log.i(TAG, "ADB TCP service started via setprop");
                return true;
            }
            
            // 方法2: 如果方法1失败，尝试直接重启 adbd
            process = Runtime.getRuntime().exec(new String[]{
                "su", "-c",
                "stop adbd && setprop service.adb.tcp.port 5555 && start adbd"
            });
            exitCode = process.waitFor();
            if (exitCode == 0) {
                Log.i(TAG, "ADB TCP service started via stop/start");
                return true;
            }
            
            Log.w(TAG, "Failed to start ADB TCP service, exit code: " + exitCode);
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Exception starting ADB TCP service: " + e.getMessage());
            return false;
        }
    }
}
