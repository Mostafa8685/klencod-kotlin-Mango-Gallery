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
import java.util.Calendar
import java.util.Random

/** ودجت صورة: "ذكريات مثل اليوم" من السنوات السابقة، وإلا صورة عشوائية من الأحدث. */
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

        private class Pick(val uri: Uri, val year: Int, val memory: Boolean)

        private fun pickPhoto(ctx: Context): Pick? {
            val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
            val today = Calendar.getInstance()
            val ids = ArrayList<Long>()
            val memIds = ArrayList<Long>()
            val memYears = ArrayList<Int>()
            try {
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    val cal = Calendar.getInstance()
                    var rows = 0
                    while (c.moveToNext() && rows < MAX_ROWS) {
                        rows++
                        val id = c.getLong(0)
                        var t = c.getLong(1)
                        if (t <= 0L) t = c.getLong(2) * 1000L
                        ids.add(id)
                        cal.timeInMillis = t
                        if (cal.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                            cal.get(Calendar.DAY_OF_MONTH) == today.get(Calendar.DAY_OF_MONTH) &&
                            cal.get(Calendar.YEAR) < today.get(Calendar.YEAR)
                        ) {
                            memIds.add(id)
                            memYears.add(cal.get(Calendar.YEAR))
                        }
                    }
                }
            } catch (_: Throwable) {
                return null
            }
            val rnd = Random()
            return if (memIds.isNotEmpty()) {
                val k = rnd.nextInt(memIds.size)
                Pick(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, memIds[k]), memYears[k], true)
            } else if (ids.isNotEmpty()) {
                val id = ids[rnd.nextInt(ids.size)]
                Pick(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), 0, false)
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
            val i = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
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

            if (!hasMediaPermission(ctx)) {
                views.setImageViewBitmap(R.id.widget_image, null)
                views.setTextViewText(R.id.widget_caption, "افتح Mango Gallery لمنح الإذن")
                mgr.updateAppWidget(id, views)
                return
            }
            val pick = pickPhoto(ctx)
            val bmp = if (pick != null) loadThumb(ctx, pick.uri, 480) else null
            if (pick == null || bmp == null) {
                views.setImageViewBitmap(R.id.widget_image, null)
                views.setTextViewText(R.id.widget_caption, "Mango Gallery")
            } else {
                val r = 22f * ctx.resources.displayMetrics.density * (bmp.width / 360f)
                views.setImageViewBitmap(R.id.widget_image, rounded(bmp, r))
                views.setTextViewText(
                    R.id.widget_caption,
                    if (pick.memory) "ذكرى من ${pick.year}" else "Mango Gallery"
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

/** ودجت وصول سريع: الكاميرا • المعرض • الخزنة (تتطلب كلمة السر عند الفتح). */
class MangoQuickWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val camera = PendingIntent.getActivity(
            context, 21,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            flags
        )
        val gallery = PendingIntent.getActivity(
            context, 22,
            Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) },
            flags
        )
        val vault = PendingIntent.getActivity(
            context, 23,
            Intent(context, VaultActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) },
            flags
        )
        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, R.layout.mango_widget_quick)
            v.setOnClickPendingIntent(R.id.quick_camera, camera)
            v.setOnClickPendingIntent(R.id.quick_gallery, gallery)
            v.setOnClickPendingIntent(R.id.quick_vault, vault)
            appWidgetManager.updateAppWidget(id, v)
        }
    }
}
