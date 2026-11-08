package com.m3rd.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * ودجت الذكريات واللحظات المميزة (بستايل التطبيقات الكبرى بهوية Mango Gallery بدون إيموجي):
 * يعرض التاريخ العربي الحي + شارة حالة الذكرى + إحصائيات المعرض + صورة متجددة بحواف دائرية أنيقة + زر تحديث فوري.
 */
class MangoPhotoWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.m3rd.app.WIDGET_REFRESH"
        private const val MAX_ROWS = 600

        private fun hasMediaPermission(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= 34 &&
                        ctx.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)
            } else {
                ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }

        private class Pick(
            val uri: Uri,
            val year: Int,
            val memory: Boolean,
            val totalPhotos: Int,
            val bucketName: String
        )

        private fun pickPhoto(ctx: Context): Pick? {
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val today = Calendar.getInstance()
            val ids = ArrayList<Long>()
            val buckets = ArrayList<String>()
            val memIds = ArrayList<Long>()
            val memYears = ArrayList<Int>()
            val memBuckets = ArrayList<String>()
            var totalCount = 0
            try {
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    totalCount = c.count
                    val cal = Calendar.getInstance()
                    var rows = 0
                    while (c.moveToNext() && rows < MAX_ROWS) {
                        rows++
                        val id = c.getLong(0)
                        var t = c.getLong(1)
                        if (t <= 0L) t = c.getLong(2) * 1000L
                        val bName = c.getString(3) ?: "الكاميرا"
                        ids.add(id)
                        buckets.add(bName)
                        cal.timeInMillis = t
                        if (cal.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                            cal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH) &&
                            cal.get(Calendar.YEAR) < today.get(Calendar.YEAR)
                        ) {
                            memIds.add(id)
                            memYears.add(cal.get(Calendar.YEAR))
                            memBuckets.add(bName)
                        }
                    }
                }
            } catch (_: Throwable) {
                return null
            }
            val rnd = Random()
            return if (memIds.isNotEmpty()) {
                val k = rnd.nextInt(memIds.size)
                Pick(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, memIds[k]),
                    memYears[k],
                    true,
                    totalCount,
                    memBuckets[k]
                )
            } else if (ids.isNotEmpty()) {
                val k = rnd.nextInt(ids.size)
                Pick(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ids[k]),
                    0,
                    false,
                    totalCount,
                    buckets[k]
                )
            } else null
        }

        private fun loadThumb(ctx: Context, uri: Uri, px: Int): Bitmap? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        ctx.contentResolver, ContentUris.parseId(uri),
                        MediaStore.Images.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun rounded(src: Bitmap, radiusPx: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radiusPx, radiusPx, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(src, 0f, 0f, p)
            return out
        }

        private fun openAppIntent(ctx: Context, code: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun updateOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.mango_widget_photo)
            val refresh = Intent(ctx, MangoPhotoWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(ctx, 7, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx, 1))

            val dateStr = try {
                SimpleDateFormat("EEEE، d MMMM", Locale("ar")).format(Date())
            } catch (_: Throwable) {
                "اليوم"
            }
            views.setTextViewText(R.id.widget_date_badge, dateStr)

            if (!hasMediaPermission(ctx)) {
                views.setImageViewBitmap(R.id.widget_image, null)
                views.setTextViewText(R.id.widget_caption, "Mango Gallery")
                views.setTextViewText(R.id.widget_subcaption, "اضغط لمنح إذن عرض الذكريات والصور")
                mgr.updateAppWidget(id, views)
                return
            }
            val pick = pickPhoto(ctx)
            val bmp = if (pick != null) loadThumb(ctx, pick.uri, 520) else null
            if (pick == null || bmp == null) {
                views.setImageViewBitmap(R.id.widget_image, null)
                views.setTextViewText(R.id.widget_caption, "Mango Gallery")
                views.setTextViewText(R.id.widget_subcaption, "التقط صوراً جديدة لتظهر ذكرياتك هنا")
            } else {
                val r = 22f * ctx.resources.displayMetrics.density * (bmp.width / 360f)
                views.setImageViewBitmap(R.id.widget_image, rounded(bmp, r))
                views.setTextViewText(
                    R.id.widget_caption,
                    if (pick.memory) "ذكرى مثل هذا اليوم (${pick.year})" else "لقطة مميزة • ${pick.bucketName}"
                )
                views.setTextViewText(
                    R.id.widget_subcaption,
                    "Mango Gallery • ${pick.totalPhotos} صورة في معرضك"
                )
            }
            mgr.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateOne(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MangoPhotoWidgetProvider::class.java))
            onUpdate(context, mgr, ids)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateOne(context, appWidgetManager, appWidgetId)
    }
}

/**
 * ودجت مركز التحكم الذكي (Mango Smart Hub Widget):
 * يعرض إحصائيات المعرض الحية + أزرار وصول فوري للكاميرا، المعرض، والخزنة الخاصة المشفّرة بدون إيموجي.
 */
class MangoQuickWidgetProvider : AppWidgetProvider() {

    private fun countMedia(ctx: Context): Pair<Int, Int> {
        var photos = 0
        var videos = 0
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, null
            )?.use { photos = it.count }
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID), null, null, null
            )?.use { videos = it.count }
        } catch (_: Throwable) {}
        return Pair(photos, videos)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val camera = PendingIntent.getActivity(
            context, 21,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            flags
        )
        val gallery = PendingIntent.getActivity(
            context, 22,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
        val vault = PendingIntent.getActivity(
            context, 23,
            Intent(context, VaultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
        val (pCount, vCount) = countMedia(context)
        val vaultCount = try { PrivateVault.list(context).size } catch (_: Throwable) { 0 }

        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, R.layout.mango_widget_quick)
            v.setTextViewText(
                R.id.quick_stats_header,
                "Mango Gallery • $pCount صورة • $vCount فيديو • الخزنة: $vaultCount"
            )
            v.setOnClickPendingIntent(R.id.quick_camera, camera)
            v.setOnClickPendingIntent(R.id.quick_gallery, gallery)
            v.setOnClickPendingIntent(R.id.quick_vault, vault)
            appWidgetManager.updateAppWidget(id, v)
        }
    }
}
