/*
   Process Lock Utility - Cross-process synchronization using file locks
   
   ✅ Bug修复 #4: 使用文件锁实现跨进程原子操作
   SharedPreferences在跨进程环境下不保证原子性，因此需要使用文件锁

   Copyright 2025 FreeRDP Contributors
   
   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
*/

package com.freerdp.freerdpcore.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * ProcessLock provides cross-process synchronization using file locks.
 * This is more reliable than SharedPreferences for inter-process coordination.
 */
public class ProcessLock {
    private static final String TAG = "ProcessLock";
    private final File lockFile;
    private FileChannel channel;
    private FileLock lock;
    
    /**
     * Create a ProcessLock
     * @param context Application context
     * @param lockName Name of the lock (will be used as filename)
     */
    public ProcessLock(Context context, String lockName) {
        File lockDir = new File(context.getFilesDir(), "locks");
        if (!lockDir.exists()) {
            lockDir.mkdirs();
        }
        this.lockFile = new File(lockDir, lockName + ".lock");
    }
    
    /**
     * Try to acquire the lock (non-blocking)
     * @param timeoutMs Maximum time to wait for lock (milliseconds)
     * @return true if lock acquired, false if timeout or error
     */
    public synchronized boolean tryLock(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // Create file if doesn't exist
                if (!lockFile.exists()) {
                    lockFile.createNewFile();
                }
                
                // Open channel
                RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                channel = raf.getChannel();
                
                // Try to acquire exclusive lock (non-blocking)
                lock = channel.tryLock();
                
                if (lock != null) {
                    Log.d(TAG, "✓ Lock acquired: " + lockFile.getName());
                    return true;
                }
                
                // Close channel if lock failed
                channel.close();
                
                // Wait a bit before retrying
                Thread.sleep(50);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to acquire lock: " + lockFile.getName(), e);
                cleanup();
                return false;
            }
        }
        
        Log.w(TAG, "✗ Lock timeout: " + lockFile.getName() + " (waited " + timeoutMs + "ms)");
        return false;
    }
    
    /**
     * Try to acquire the lock immediately (non-blocking)
     * @return true if lock acquired, false otherwise
     */
    public synchronized boolean tryLock() {
        try {
            // Create file if doesn't exist
            if (!lockFile.exists()) {
                lockFile.createNewFile();
            }
            
            // Open channel
            RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
            channel = raf.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            lock = channel.tryLock();
            
            if (lock != null) {
                Log.d(TAG, "✓ Lock acquired immediately: " + lockFile.getName());
                return true;
            }
            
            // Close channel if lock failed
            channel.close();
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire lock: " + lockFile.getName(), e);
            cleanup();
            return false;
        }
    }
    
    /**
     * Release the lock
     */
    public synchronized void unlock() {
        if (lock != null) {
            try {
                lock.release();
                Log.d(TAG, "✓ Lock released: " + lockFile.getName());
            } catch (Exception e) {
                Log.e(TAG, "Failed to release lock", e);
            } finally {
                lock = null;
            }
        }
        
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to close channel", e);
            } finally {
                channel = null;
            }
        }
    }
    
    /**
     * Check if lock is currently held
     * @return true if lock is held
     */
    public synchronized boolean isLocked() {
        return lock != null && lock.isValid();
    }
    
    /**
     * Clean up resources
     */
    private void cleanup() {
        unlock();
    }
    
    /**
     * Ensure resources are released
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            cleanup();
        } finally {
            super.finalize();
        }
    }
}


