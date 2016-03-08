Android对单个应用施加的内存限制是16MB
    高效的加载Bitmap,比较常用的缓存策略是LruCache(内存缓存)和DiskLruCache(存储缓存)

    BitmapFactory提供的四类方法:decodeFile,decodeResource,decodeByteArray,decodeStream
        分别用于支持文件,资源文件,输入流以及字节数组加载一个Bitmap


    如何高效地加载Bitmap?
        采用BitmapFactory.Options来加载所需尺寸的图片,通过ImageView来显示,但很多时候并没有原始图片那么大,
      整个加载出来是很浪费的,可以通过采样率缩小后显示

      inSampleSize参数 -- 缩放比例为1/2次方  即等于2时,缩放为1/4;因为宽和高一起缩放
                            比如ImageView的大小是100*100,原始图片为200*200
                                只需将inSampleSize采样率设置为2

      获取采样率:
          将BitmapFactory.Options的inJustDecodeBounds设为true后,
            获取outWidth和outHeight来设置inSampleSize,最后inJustDecodeBounds设为false后再获取Bitmap


    一、LRU -- Least Recently Used,当缓存满时,会淘汰最近最少使用的缓存
        LruCache是Android3.1所提供的一个缓存类,建议使用support-v4兼容包中的
            是一个泛型类,采用一个LinkedHashMap以强引用方式存储外界的缓存对象,通过get和set来获取和设置


    二、DiskLruCache用于实现磁盘缓存,通过将缓存对象写入文件系统从而实现缓存效果
            具体使用 compile 'com.jakewharton:disklrucache:2.0.2'