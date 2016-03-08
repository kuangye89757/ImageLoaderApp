package com.jikexueyuan.imageloaderapp.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;
import com.jikexueyuan.imageloaderapp.R;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义图片缓存ImageLoader
 * Created by wangshijie on 2016/3/7.
 */
public class ImageLoader {

    private static final String TAG = "ImageLoader";

    private static final int MSG_POST_RESULT = 1;

    /**
     * CPU数
     */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 核心线程数
     */
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;

    /**
     * 最大容量
     */
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;

    /**
     * 线程闲置超时时长
     */
    private static final long KEEP_ALIVE = 10L;

    private static final int TAG_KEY_URI = R.id.imageloader_uri;

    /**
     * 磁盘缓存容量
     */
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;

    /**
     * 读写缓存容量
     */
    private static final int IO_BUFFER_SIZE = 1024 * 1024 * 8;

    /**
     * 磁盘缓存索引
     */
    private static final int DISK_CACHE_INDEX = 0;

    /**
     * 是否使用了DiskLruCache
     */
    private boolean mIsDiskLruCacheCreated = false;

    /**
     * 上下文
     */
    private Context mContext;

    /**
     * 图片压缩功能类
     */
    private ImageResizer mImageResizer = new ImageResizer();

    /**
     * 内存缓存
     */
    private LruCache<String, Bitmap> mMemoryCache;

    /**
     * 磁盘缓存
     */
    private DiskLruCache mDiskLruCache;

    /**
     * 线程工厂
     */
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    public ImageLoader(Context context) {
        mContext = context.getApplicationContext();

        /**LruCache的典型初始化  总容量为当前进程可用内存的1/8 单位KB*/
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                //计算缓存对象(bitmap)的大小,单位需要同总容量一致
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };


        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if(!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }

        /**若缓存的文件容量大于最小缓存量50M时,通过open方法用于创建自身*/
        if(getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                /**
                 * 参数1:数据的缓存地址
                 * 参数2:一般为1,当版本号变化时会清空所有缓存文件
                 * 参数3:单个节点所应用的数据个数 一般也是1
                 * 参数4:缓存总量 超容量时策略为LRU
                 */
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;//使用了DiskLruCache的标示
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 构造器
     * @param context
     * @return
     */
    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    /**
     * 将bitmap添加到内存缓存
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if(getBitmapFromMemCache(key) == null && !bitmap.isRecycled()){
            mMemoryCache.put(key,bitmap);
        }
    }

    /**
     * 获取内存缓存中的bitmap
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemCache(String key) {
        if(!TextUtils.isEmpty(key)) {
            return mMemoryCache.get(key);
        } else {
            return null;
        }
    }


    public void bindBitmap(final String uri,final ImageView imageView){
        bindBitmap(uri,imageView,0,0);
    }

    /**
     * 先从LruCache中获取bitmap,有则直接使用
     * 否则在线程池中调用loadBitmap去加载图片
     * @param uri
     * @param imageView
     * @param reqWidth
     * @param reqHeight
     */
    public void bindBitmap(final String uri,final ImageView imageView,
                           final int reqWidth,final int reqHeight){
        imageView.setTag(TAG_KEY_URI,uri);//每个imageView绑定一个url作为唯一标示
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap!=null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri,reqWidth,reqHeight);
                if(bitmap!=null){
                    LoaderResult result = new LoaderResult(imageView,uri,bitmap);
                    mMainHandler.obtainMessage(MSG_POST_RESULT,result).sendToTarget();
                }
            }
        };

        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);

    }


    /**
     * 使用线程池
     *      参数1:核心线程数,默认情况下会在线程池一直存活,即使闲置
     *      参数2:所能容纳的最大线程数(核心+非核心),达到这个值,之后的新任务就要排队
     *      参数3:非核心线程闲置是的超时时间,超过则回收
     *      参数4:参数3的单位
     *      参数5:线程池中的任务队列
     *      参数6:线程工厂
     */
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), sThreadFactory
    );


    /**
     * 在主线程中更新UI
     */
    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            imageView.setImageBitmap(result.bitmap);
            String uri = (String) imageView.getTag(TAG_KEY_URI);

            /**
             * 每个imageView绑定一个url作为唯一标示
             * 为了解决View复用所导致列表错位,在设置图片之前坚持url有没有发生改变
             * 若发生变化则不设置
             */
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else {
                Log.i(TAG, "uri has changed");
            }
        }
    };

    /**
     * 首先尝试从内存缓存中读取图片
     * 接着尝试从磁盘缓存中读取图片
     * 最后才从网络中拉取图片
     * @param uri
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap loadBitmap(String uri, int reqWidth,int reqHeight){
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if(bitmap !=null){
            Log.d(TAG,"loadBitmapFromMemCache,url:" + uri);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
            if(bitmap !=null){
                Log.d(TAG,"loadBitmapFromDiskCache,url:" + uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri,reqWidth,reqHeight);
            Log.d(TAG,"loadBitmapFromHttp,url:" + uri);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(bitmap == null && !mIsDiskLruCacheCreated){//若没有使用DiskLruCache则从网络下载
            Log.w(TAG,"DiskLruCache is not created");
            bitmap = downloadBitmapFromUrl(uri);
        }

        return bitmap;
    }

    /**
     * DiskLruCache的缓存写操作是通过Editor完成
     * @param uri
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromHttp(String uri, int reqWidth, int reqHeight) throws IOException {
        if(Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI Thread");
        }
        if(mDiskLruCache == null){
            return null;
        }
        String key = hashKeyFormUrl(uri);//根据url获取生成的key

        /**根据key获取Editor对象,如果这个缓存正在被编辑,edit()返回null*/
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if(editor!=null){
            //根据open的第三个参数获取文件输出流
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if(downloadUrlToStream(uri,outputStream)){//当从网络下载图片时,通过文件输出流写入文件系统
                editor.commit();//提交
            }else {
                editor.abort();//中断
            }
            mDiskLruCache.flush();//刷新
        }
        return loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
    }

    /**
     * 当从网络下载图片时,通过文件输出流写入文件系统
     * @param urlString
     * @param outputStream
     * @return
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);

            int b;
            while ((b =in.read())!= -1){
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG,"downloadUrlToStream.failed" + e);
        }finally {
            if(urlConnection!=null){
                urlConnection.disconnect();
            }
            if(out!=null){
                try {
                    out.flush();
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if(in!=null){
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }


    /**
     * 网络下载生成bitmap
     * @param urlString
     * @return
     */
    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.e(TAG,"downloadBitmapFromUrl.failed" + e);
        }finally {
            if(urlConnection!=null){
                urlConnection.disconnect();
            }
            if(in!=null){
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return bitmap;
    }

    /**
     * 从磁盘缓存文件中读取并生成Bitmap
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {

        //当前操作要在非UI线程中执行
        if(Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG,"load bitmap from UI Thread,it's not recommended!");
        }

        if(mDiskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);//获取key
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);//获取Snapshot即可拿到输入流
        if(snapshot !=null){
            FileInputStream fileInputStream = (FileInputStream) snapshot
                    .getInputStream(DISK_CACHE_INDEX);

            //避免OOM,这里通过文件流得到它对应的文件描述符
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            //根据文件描述符,获取一张缩放后的bitmap
            bitmap = mImageResizer.decodeSampledBitmapFromFileDescripter(fileDescriptor,reqWidth,reqHeight);
            if(bitmap!=null){
                //将该bitmap添加到内存缓存中
                addBitmapToMemoryCache(key,bitmap);
            }
        }
        return bitmap;
    }

    /**
     * 根据url转义的key获取LruCache中的bitmap
     * @param url
     * @return
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;

    }

    /**
     * 将url转成key,由于url中可能有特殊字符,故采用MD5转义
     * @param url
     * @return
     */
    private String hashKeyFormUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < bytes.length; i++){
            String hex = Integer.toHexString(0xFF * bytes[i]);
            if(hex.length() == 1){
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 获取缓存路径
     * @param context
     * @param uniqueName 文件路径名
     * @return
     */
    private File getDiskCacheDir(Context context, String uniqueName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState()
                .equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if(externalStorageAvailable){
            //  SDCard/Android/data/你的应用包名/cache/目录，一般存放临时缓存数据
            cachePath = context.getExternalCacheDir().getPath();
        }else {
            //  /data/data/<application package>/cache
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 获取文件或文件夹可使用的容量
     * @param file
     * @return
     */
    private long getUsableSpace(File file){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){
            return file.getUsableSpace();
        }
        final StatFs stats = new StatFs(file.getPath());
        return (long) stats.getBlockSize() * stats.getAvailableBlocks();
    }

    private static class LoaderResult {
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }

}
