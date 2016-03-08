package com.jikexueyuan.imageloaderapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.LruCache;

/**
 * Created by Cong Hao on 2015/12/21.
 */
public class MyMemoryCache {

	/**一个变量声明为volatile，就意味着这个变量是随时会被其他线程修改的，因此不能将它cache在线程memory中*/
	private volatile static MyMemoryCache mInstance;

	/**
	 * 	 Android自带缓存机制
	 * 	 内部熟悉有:
	 * 	 	 private int size; //已经存储的大小
			 private int maxSize; //规定的最大存储空间
			 private int putCount;  //put的次数
			 private int createCount;  //create的次数
			 private int evictionCount;  //回收的次数
			 private int hitCount;  //命中的次数
			 private int missCount;  //丢失的次数

	     这里key为缓存图片名称
	 		value为缓存图片的bitmap
	 * */
	private LruCache<String, Bitmap> mLruCache;

	/**4屏缓存用于跟最小缓存作比较*/
	private static final int SCREENS_OF_MEMORY_CACHE = 4;

	private MyMemoryCache(Context context) {
		this.mLruCache = new LruCache<String, Bitmap>(getCacheSize(context, SCREENS_OF_MEMORY_CACHE)) {

			@Override
			protected int sizeOf(String key, Bitmap bitmap) {
				/**重写此方法来衡量每张图片的大小,这里是该位图所占用的内存字节数*/
				return bitmap.getByteCount();
			}
		};
	}

	/**
	 * 应用启动时初始化
	 * @param context
     */
	public static void initialize(Context context) {
		if (mInstance == null) {
			synchronized(MyMemoryCache.class) {
				if(mInstance == null) {
					mInstance = new MyMemoryCache(context.getApplicationContext());
				}
			}
		}
	}
	public static MyMemoryCache getInstance() {
		if (mInstance == null) {
			throw new IllegalStateException(MyMemoryCache.class.getName() + "未初始化, 请先调用initialize(context).)");
		}
		return mInstance;
	}

	/**
	 * 定义LruCache的缓存大小为 当前手机内存的1/8 或 4屏图片的内存大小
	 * @param context
	 * @param intScreens
     * @return
     */
	private int getCacheSize(Context context, int intScreens) {
		final int cacheSizeOfMaxMemory = (int)(Runtime.getRuntime().maxMemory() / 8);
		if(intScreens <= 0 ){
			return cacheSizeOfMaxMemory;
		} else {
			final DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
			final int screenWidth = displayMetrics.widthPixels;
			final int screenHeight = displayMetrics.heightPixels;
			// 4 bytes per pixel
			final int screenBytes = screenWidth * screenHeight * 4;
			final int cacheSizeOfScreens = screenBytes * intScreens;

			//获取系统分配给每个应用程序的最大内存，每个应用系统分配32M
//	        final int memClass = ((ActivityManager)this.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();
//  	    final int cacheSize = 1024 * 1024 * memClass / 8;
			return cacheSizeOfScreens < cacheSizeOfMaxMemory ? cacheSizeOfScreens : cacheSizeOfMaxMemory;
		}
	}


	/**setter and getter*/
	public final Bitmap get(@NonNull String key) {
		if(!TextUtils.isEmpty(key)) {
			return this.mLruCache.get(key);
		} else {
			return null;
		}
	}
	public final Bitmap put(@NonNull String key, @NonNull Bitmap bitmap) {
		if (!TextUtils.isEmpty(key) && !bitmap.isRecycled()) {
			return this.mLruCache.put(key, bitmap);
		} else {
			return null;
		}
	}
	public final Bitmap remove(@NonNull String key) {
		if (!TextUtils.isEmpty(key)) {
			return this.mLruCache.remove(key);
		} else {
			return null;
		}
	}

	/**
	 * key生成策略
	 * @param str
	 * @param width
	 * @param height
     * @return
     */
	public static String getCacheKey(String str, int width, int height) {
		return str + "_" + String.valueOf(width) + "_" + String.valueOf(height);
	}

	/**
	 * 清空缓存
	 */
	public final void evictAll() {
		this.mLruCache.evictAll();
	}
}
